package com.ljyh.mei.source.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ljyh.mei.source.api.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow

/** Screens bind once; later browse-source changes cannot retarget an open conversation or edit. */
@Singleton
class SourceFeatureClient @Inject constructor(
    private val router: SourceRouter,
    private val executor: SourceFeatureExecutor,
    private val preferences: SourcePreferences,
    private val platform: com.ljyh.mei.source.platform.SourcePlatformEffects,
) {
    suspend fun current(scopeId: String, handler: SourceHostEffectHandler = platform): BoundSourceFeatures =
        bind(preferences.settings.first().preferredBrowseProvider, scopeId, handler)

    suspend fun bind(providerId: String, scopeId: String,
        handler: SourceHostEffectHandler = platform): BoundSourceFeatures {
        require(scopeId.isNotBlank() && scopeId.length <= 200)
        router.registry.awaitInitialized()
        val owner = router.registry.requireProvider(providerId)
        if (owner !is SourceFeatureCapability || SourceCapability.FEATURES !in owner.capabilities) {
            throw SourceException(SourceErrorCode.UNSUPPORTED)
        }
        val revision = (owner as? AccountRevisionCapability)?.accountRevision?.first()
        if (router.registry.provider(providerId) !== owner) throw CancellationException("Source changed while opening a feature")
        return BoundSourceFeatures(router, executor, owner, revision, scopeId, handler)
    }
}

/** An immutable account/version key for host-owned data caches, never the mutable screen binding. */
internal class SourceFeatureSessionIdentity(
    private val owner: MusicProvider,
    private val version: String,
    private val revision: String?,
) {
    fun sameSession(other: SourceFeatureSessionIdentity): Boolean =
        owner === other.owner && version == other.version && revision == other.revision
}

class BoundSourceFeatures internal constructor(
    private val router: SourceRouter,
    private val executor: SourceFeatureExecutor,
    private val owner: MusicProvider,
    @Volatile private var revision: String?,
    val scopeId: String,
    private val handler: SourceHostEffectHandler,
) {
    @Volatile private var pendingSessionTransition: Pair<String?, String?>? = null
    val providerId: String get() = owner.id
    val instanceId: ProviderInstanceId get() = owner.instanceId
    private val version = owner.version

    internal fun sessionIdentity(): SourceFeatureSessionIdentity = SourceFeatureSessionIdentity(owner, version, revision)

    internal fun sameSession(other: BoundSourceFeatures): Boolean =
        owner === other.owner && version == other.version && revision == other.revision

    fun supports(feature: String, action: String): Boolean =
        action in (owner as? SourceFeatureCapability)?.features?.get(feature).orEmpty()

    suspend fun webLogin() {
        val result = invoke("login", "webLogin", allowSessionChange = true)
        val authenticated = result.takeIf { it.isJsonObject }?.asJsonObject?.get("authenticated")
        if (authenticated == null || !authenticated.isJsonPrimitive ||
            !authenticated.asJsonPrimitive.isBoolean || !authenticated.asBoolean) {
            throw SourceException(SourceErrorCode.INVALID_RESPONSE)
        }
    }

    suspend fun logout() {
        val result = invoke("login", "logout", allowSessionChange = true)
        if (!result.isJsonPrimitive || !result.asJsonPrimitive.isBoolean || !result.asBoolean) {
            throw SourceException(SourceErrorCode.INVALID_RESPONSE)
        }
    }

    suspend fun invoke(feature: String, action: String, parameters: JsonObject = JsonObject(),
        allowSessionChange: Boolean = false): JsonElement {
        ensureCurrent()
        var ownTransition: Pair<String?, String?>? = null
        val result = try {
            executor.execute(providerId, feature, action, parameters, scopeId, handler, allowSessionChange,
                onSessionCommitted = if (allowSessionChange) { next ->
                    ensureProvider()
                    ownTransition = revision to next
                    pendingSessionTransition = ownTransition
                    revision = next
                } else null,
                expectedProvider = owner, expectedAccountRevision = revision)
        } finally {
            // The provider publishes its durable revision before this invocation returns.
            if (ownTransition != null && pendingSessionTransition === ownTransition) pendingSessionTransition = null
        }
        currentCoroutineContext().ensureActive()
        ensureCurrent()
        if (result.isJsonObject) {
            val response = result.asJsonObject
            when (response.get("status")?.takeIf { it.isJsonPrimitive }?.asString) {
                "cancelled" -> throw CancellationException("Source action was cancelled")
                "failed" -> throw SourceException(response.get("code")?.asString?.let {
                    runCatching { SourceErrorCode.valueOf(it) }.getOrNull()
                } ?: SourceErrorCode.UNAVAILABLE)
            }
        }
        return result
    }

    suspend fun artwork(url: String?): String? {
        ensureCurrent()
        if (url.isNullOrBlank()) return null
        val capability = owner as? SourceFeatureContentCapability ?: throw SourceException(SourceErrorCode.UNSUPPORTED)
        return capability.featureArtwork(url).also { ensureCurrent() }
    }

    suspend fun track(value: JsonObject): Track {
        ensureCurrent()
        val capability = owner as? SourceFeatureContentCapability ?: throw SourceException(SourceErrorCode.UNSUPPORTED)
        return capability.parseFeatureTrack(value).also { ensureCurrent() }
    }

    suspend fun completeTrackIds(playlistId: String, collection: LibraryCollection?): SourceTrackIndex? {
        ensureCurrent()
        if (SourceCapability.TRACK !in owner.capabilities) return null
        val index = (owner as? CatalogIndexCapability)?.completeTrackIds(playlistId, collection)
        ensureCurrent()
        return index?.takeIf { it.ids.size == it.ids.toHashSet().size }
    }

    suspend fun requireTrue(feature: String, action: String, parameters: JsonObject = JsonObject()) {
        val result = invoke(feature, action, parameters)
        if (!result.isJsonPrimitive || !result.asJsonPrimitive.isBoolean || !result.asBoolean) {
            throw SourceException(SourceErrorCode.INVALID_RESPONSE)
        }
    }

    suspend fun requireStatus(feature: String, action: String, expected: String, parameters: JsonObject = JsonObject()) {
        val result = invoke(feature, action, parameters)
        if (!result.isJsonObject || result.asJsonObject.get("status")?.asString != expected) {
            throw SourceException(SourceErrorCode.INVALID_RESPONSE)
        }
    }

    private fun ensureProvider() {
        if (router.registry.provider(providerId) !== owner || owner.version != version) {
            throw CancellationException("Source changed while displaying a feature")
        }
    }

    suspend fun ensureCurrent() {
        currentCoroutineContext().ensureActive()
        ensureProvider()
        if ((owner as? AccountRevisionCapability)?.accountRevision?.first() != revision) {
            throw CancellationException("Source account changed while displaying a feature")
        }
    }

    /** A bound screen can stop recording, polling or sending as soon as its owner disappears. */
    suspend fun awaitInvalidation(): Nothing {
        val account = (owner as? AccountRevisionCapability)?.accountRevision ?: flowOf(null)
        combine(router.registry.revision, account) { _, current ->
            val latest = if (account is StateFlow<String?>) account.value else current
            val expected = revision
            val transition = pendingSessionTransition
            val changingOwnSession = transition != null && transition.second == expected && latest == transition.first
            router.registry.provider(providerId) !== owner || owner.version != version || latest != expected && !changingOwnSession
        }.first { it }
        throw CancellationException("Source feature session was invalidated")
    }

    override fun toString(): String = "BoundSourceFeatures(<redacted>)"
}
