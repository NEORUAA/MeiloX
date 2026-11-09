package com.ljyh.mei.source.script

import android.content.Context
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.ProviderRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Encrypted URL descriptors let existing file URIs load lazily after a process restart. */
@Singleton
class SourceArtworkStore @Inject constructor(
    @ApplicationContext context: Context,
    private val registry: ProviderRegistry,
) {
    private val root = File(context.cacheDir, "source-artwork")
    private val secrets = PluginSecretStore(context)
    private val flights = mutableMapOf<String, Flight>()
    private val writes = java.util.concurrent.atomic.AtomicInteger()
    private val records = object : LinkedHashMap<String, Pair<String, String>>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String>>?) = size > 4096
    }

    internal suspend fun register(source: ScriptMusicProvider, url: String, identity: String): String = withContext(Dispatchers.IO) {
        source.validateArtworkUrl(url)
        val file = ScriptNetworkPolicy.artworkFile(url, File(root, source.manifest.id))
        val record = SourceArtworkDescriptor(url, identity, source.version)
        val recordIdentity = identity to source.version
        val key = "${source.manifest.id}:${file.name}"
        singleFlight(key) {
            source.ensureArtworkCurrent(identity)
            val cachedIdentity = synchronized(records) { records[key] }
            if (cachedIdentity != recordIdentity || !secrets.hasArtwork(source.manifest.id, file.name)) {
                val kind = "artwork-${file.name}"
                val stored = runCatching { secrets.read(source.manifest.id, kind) }.getOrDefault(emptyMap())
                if (stored != record.values()) {
                    secrets.write(source.manifest.id, kind, record.values())
                    if (writes.getAndIncrement() % 64 == 0) secrets.pruneArtwork()
                }
                synchronized(records) { records[key] = recordIdentity }
            }
            source.ensureArtworkCurrent(identity)
            ScriptNetworkPolicy.artworkUri(file)
        }
    }

    fun owns(value: String): Boolean = location(value) != null

    suspend fun needsMetadataRefresh(value: String): Boolean = withContext(Dispatchers.IO) {
        val (id, file) = location(value) ?: return@withContext false
        if (file.isFile) return@withContext false
        registry.awaitInitialized()
        val source = registry.instance(ProviderInstanceId("script:$id:default")) as? ScriptMusicProvider
            ?: return@withContext false
        if (registry.provider(source.id) !== source) return@withContext false
        val identity = source.artworkAccountIdentity()
        val record = descriptor(id, file)
        if (record != null && record.account != identity) return@withContext false
        if (registry.provider(source.id) !== source || source.artworkAccountIdentity() != identity) return@withContext false
        !file.isFile && record?.sourceVersion != source.version
    }

    internal suspend fun acceptOwned(source: ScriptMusicProvider, value: String): String = withContext(Dispatchers.IO) {
        val (id, file) = location(value) ?: unavailable()
        if (id != source.manifest.id) denied()
        if (file.isFile) {
            source.ensureArtworkCurrent(source.artworkAccountIdentity())
            return@withContext ScriptNetworkPolicy.artworkUri(file)
        }
        val descriptor = descriptor(id, file) ?: unavailable()
        source.ensureArtworkCurrent(descriptor.account)
        source.validateArtworkUrl(descriptor.url)
        ScriptNetworkPolicy.artworkUri(file)
    }

    suspend fun resolve(value: String): String = withContext(Dispatchers.IO) {
        val (id, file) = location(value) ?: unavailable()
        singleFlight("$id:${file.name}") {
            // Downloaded art belongs to retained host metadata, like existing queue and library covers.
            if (file.isFile) {
                file.setLastModified(System.currentTimeMillis())
                return@singleFlight ScriptNetworkPolicy.artworkUri(file)
            }
            registry.awaitInitialized()
            val source = registry.instance(ProviderInstanceId("script:$id:default")) as? ScriptMusicProvider ?: unavailable()
            if (registry.provider(source.id) !== source) unavailable()
            val descriptor = descriptor(id, file) ?: unavailable()
            source.ensureArtworkCurrent(descriptor.account)
            val revision = source.accountRevision.value
            coroutineScope {
                val fetch = async(start = CoroutineStart.LAZY) {
                    source.downloadArtwork(descriptor.url, descriptor.account)
                }
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    combine(registry.revision, source.accountRevision) { _, account ->
                        registry.provider(source.id) !== source || account != revision
                    }.collect { invalid -> if (invalid) fetch.cancel(CancellationException("Source artwork owner changed")) }
                }
                try {
                    source.ensureArtworkCurrent(descriptor.account)
                    fetch.start()
                    fetch.await()
                } finally {
                    withContext(NonCancellable) { watcher.cancelAndJoin(); fetch.cancelAndJoin() }
                }
            }
        }
    }

    private fun descriptor(id: String, file: File): SourceArtworkDescriptor? = runCatching {
        val value = SourceArtworkDescriptor.parse(secrets.read(id, "artwork-${file.name}"))
        if (ScriptNetworkPolicy.artworkFile(value.url, File(root, id)).name != file.name) return@runCatching null
        value
    }.getOrNull()

    private fun location(value: String): Pair<String, File>? = sourceArtworkLocation(root, value)?.let { (id, checked) ->
        // Canonical paths enforce ownership; the configured root preserves Android's stable URI alias.
        id to File(File(root, id), "artwork/${checked.name}")
    }

    private suspend fun <T> singleFlight(key: String, block: suspend () -> T): T {
        val flight = synchronized(flights) { flights.getOrPut(key) { Flight() }.also { it.users++ } }
        try { return flight.mutex.withLock { currentCoroutineContext().ensureActive(); block() } }
        finally { synchronized(flights) { if (--flight.users == 0) flights.remove(key) } }
    }
    private class Flight(val mutex: Mutex = Mutex(), var users: Int = 0)
    private fun unavailable(): Nothing = throw SourceException(SourceErrorCode.MISSING_PROVIDER)
    private fun denied(): Nothing = throw SourceException(SourceErrorCode.PERMISSION_DENIED)
}

internal class SourceArtworkDescriptor(val url: String, val account: String, val sourceVersion: String? = null) {
    fun values() = buildMap {
        put("url", url)
        put("account", account)
        sourceVersion?.let { put("sourceVersion", it) }
    }
    override fun toString() = "SourceArtworkDescriptor(<redacted>)"
    companion object {
        fun parse(values: Map<String, String>): SourceArtworkDescriptor {
            require(values.keys == setOf("url", "account") || values.keys == setOf("url", "account", "sourceVersion"))
            val url = values.getValue("url").also { require(it.length in 1..8192) }
            val account = values.getValue("account").also { require(it.length in 1..256) }
            val sourceVersion = values["sourceVersion"]?.also { require(it.isNotBlank() && it.length <= 80) }
            return SourceArtworkDescriptor(url, account, sourceVersion)
        }
    }
}

internal fun sourceArtworkLocation(root: File, value: String): Pair<String, File>? = runCatching {
    val uri = URI(value)
    val file = if (uri.scheme == "file") {
        require(uri.rawAuthority.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null)
        File(uri)
    } else {
        require(uri.scheme == null && value.startsWith('/'))
        File(value)
    }.canonicalFile
    val relative = root.canonicalFile.toPath().relativize(file.toPath()).map { it.toString() }
    require(relative.size == 3 && relative[0].matches(Regex("[a-z][a-z0-9_.-]{2,79}")) &&
        relative[1] == "artwork" && relative[2].matches(Regex("[a-f0-9]{64}")))
    relative[0] to file
}.getOrNull()
