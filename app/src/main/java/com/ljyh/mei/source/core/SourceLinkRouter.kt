package com.ljyh.mei.source.core

import com.ljyh.mei.source.api.*
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class OwnedSourceLink internal constructor(internal val owner: MusicProvider, val target: SourceLinkTarget, internal val accountRevision: String?) {
    val providerId get() = owner.id
    override fun toString() = "OwnedSourceLink(<redacted>)"
}

/** Only a matching declared owner receives the URL, never the rest of a private clipboard. */
@Singleton
class SourceLinkRouter @Inject constructor(private val registry: ProviderRegistry, private val preferences: SourcePreferences) {
    suspend fun resolve(text: String): OwnedSourceLink? {
        registry.awaitInitialized()
        val preferred = preferences.snapshot().preferredBrowseProvider
        for (url in sourceLinkUrls(text)) {
            val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: continue
            val owners = registry.providers.value.filter {
                it.status == SourceStatus.READY && SourceCapability.LINKS in it.capabilities &&
                    it is SourceLinkCapability && host in it.linkHosts
            }
            // An ambiguous host is handled only by the source the user explicitly selected.
            val owner = owners.firstOrNull { it.id == preferred } ?: owners.singleOrNull() ?: continue
            val revision = (owner as? AccountRevisionCapability)?.accountRevision?.first()
            val target = try { (owner as SourceLinkCapability).resolveLink(url) }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: SourceException) { null }
            if (target != null) {
                val result = OwnedSourceLink(owner, target, revision)
                if (isCurrent(result)) return result
            }
        }
        return null
    }

    suspend fun isCurrent(link: OwnedSourceLink): Boolean = registry.provider(link.providerId) === link.owner &&
        (link.owner as? AccountRevisionCapability)?.accountRevision?.first() == link.accountRevision
}

internal fun sourceLinkUrls(text: String): List<String> = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
    .findAll(text.take(16_384)).map { it.value.trimEnd('。', '，', '；', '）', '】', '”', '」') }
    .filter { it.length <= 8192 }.distinct().take(5).toList()
