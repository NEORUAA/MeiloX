package com.ljyh.mei.source.core

import com.ljyh.mei.source.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

fun audioResourceIdentity(instanceId: ProviderInstanceId, ref: TrackRef, resource: AudioResource): String {
    val fields = listOf(instanceId.value, ref.providerId, ref.trackId, resource.quality, resource.contentId ?: resource.uri, resource.contentLength?.toString().orEmpty())
    val encoded = fields.joinToString("") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
fun audioResourceIdentity(resolution: PlaybackResolution): String = audioResourceIdentity(resolution.instanceId, resolution.resolvedTrackRef, resolution.resource)

data class PendingSourceMatch(val origin: TrackRef, val instanceId: ProviderInstanceId, val candidates: List<MatchCandidate>, val originTitle: String = "")
class PlaybackResolutionException(val attempts: List<ResolutionAttempt>) : SourceException(attempts.lastOrNull()?.error ?: SourceErrorCode.UNAVAILABLE)

@Singleton
class PlaybackResolver(
    private val registry: ProviderRegistry,
    private val settingsProvider: suspend () -> SourceSettings,
    private val attemptTimeoutMs: Long = 12_000,
) {
    @Inject constructor(registry: ProviderRegistry, preferences: SourcePreferences) : this(registry, { preferences.snapshot() })

    private val matcher = TrackMatcher()
    private val gates = Array(64) { Mutex() }
    private val stateVersion = AtomicLong()
    private val publicationLock = Any()
    private val concurrency = Semaphore(3)
    private data class Mapping(val expires: Long, val track: Track?)
    private data class CachedResolution(val expires: Long, val resolution: PlaybackResolution)
    private val mappings = ConcurrentHashMap<String, Mapping>()
    private val resolved = ConcurrentHashMap<String, CachedResolution>()
    private val mutablePending = MutableStateFlow<PendingSourceMatch?>(null)
    val pendingMatch: StateFlow<PendingSourceMatch?> = mutablePending
    private val mutableLastResolution = MutableStateFlow<PlaybackResolution?>(null)
    val lastResolution: StateFlow<PlaybackResolution?> = mutableLastResolution
    private val mutableAttempts = MutableStateFlow<List<ResolutionAttempt>>(emptyList())
    val lastAttempts: StateFlow<List<ResolutionAttempt>> = mutableAttempts

    fun invalidate() = synchronized(publicationLock) {
        stateVersion.incrementAndGet()
        mappings.clear()
        resolved.clear()
        mutablePending.value = null
        mutableLastResolution.value = null
        mutableAttempts.value = emptyList()
    }
    fun dismissPendingMatch() { mutablePending.value = null }

    suspend fun resolve(
        track: Track,
        quality: String,
        excludedResources: Set<String> = emptySet(),
        originalResolver: (suspend () -> AudioResource)? = null,
    ): PlaybackResolution {
        registry.awaitInitialized()
        val version = stateVersion.get()
        val settings = settingsProvider()
        val generation = registry.providers.value.joinToString { "${it.instanceId.value}:${it.version}:${it.status}" }
        val requestKey = listOf(version.toString(), TrackRefCodec.encode(track.ref), quality, settings.toString(), generation, excludedResources.sorted().joinToString()).joinToString("|")
        val key = MessageDigest.getInstance("SHA-256").digest(requestKey.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return gates[key.take(2).toInt(16) % gates.size].withLock {
            ensureCurrent(version)
            if (originalResolver == null) resolved[key]?.takeIf { it.expires > System.currentTimeMillis() }?.resolution?.let { return@withLock it }
            val resolution = try { concurrency.withPermit { perform(track, quality, settings, excludedResources, originalResolver, version) } }
                catch (error: PlaybackResolutionException) {
                    synchronized(publicationLock) { ensureCurrent(version); mutableAttempts.value = error.attempts }
                    throw error
                }
            currentCoroutineContext().ensureActive()
            synchronized(publicationLock) {
                ensureCurrent(version)
                val expires = minOf(System.currentTimeMillis() + 2_000, resolution.resource.expiresAtMs ?: Long.MAX_VALUE)
                resolved[key] = CachedResolution(expires, resolution)
                if (resolved.size > 128) resolved.entries.sortedBy { it.value.expires }.take(resolved.size - 128).forEach { resolved.remove(it.key, it.value) }
                mutableLastResolution.value = resolution
                mutableAttempts.value = resolution.attempts
            }
            resolution
        }
    }

    private suspend fun perform(track: Track, quality: String, settings: SourceSettings, excluded: Set<String>, originalResolver: (suspend () -> AudioResource)?, version: Long): PlaybackResolution {
        val attempts = mutableListOf<ResolutionAttempt>()
        val original = registry.provider(track.ref.providerId)
        val mayFallback = settings.autoFallbackEnabled && TrackRefCodec.encode(track.ref) !in settings.disabledTrackFallbacks
        val fallbacks = if (mayFallback) settings.fallbackOrder.distinct().mapNotNull { registry.instance(ProviderInstanceId(it)) }
            .filter { it.status == SourceStatus.READY && it is PlaybackCapability && SourceCapability.PLAYBACK in it.capabilities && it.instanceId != original?.instanceId } else emptyList()
        val providers = if (settings.preferOriginalProvider) listOfNotNull(original) + fallbacks else fallbacks + listOfNotNull(original)
        if (original == null) attempts += ResolutionAttempt(ProviderInstanceId(track.ref.providerId), track.ref, SourceErrorCode.MISSING_PROVIDER)
        for (provider in providers.distinctBy { it.instanceId }) {
            ensureCurrent(version)
            var candidate: Track? = null
            try {
                return withTimeout(attemptTimeoutMs) {
                    val manualKey = TrackRefCodec.encode(track.ref) + "|" + provider.id
                    val matchOrigin = if (provider !== original && manualKey !in settings.manualMatches && (track.title.isBlank() || track.durationMs <= 0 || track.artists.isEmpty())) {
                        (original as? CatalogCapability)?.getTrack(track.ref) ?: track
                    } else track
                    candidate = if (provider === original) track else match(matchOrigin, provider, settings, version)
                    val target = candidate ?: throw SourceException(SourceErrorCode.NOT_FOUND)
                    if (target.ref.providerId != provider.id) throw SourceException(SourceErrorCode.INVALID_RESPONSE)
                    val resource = if (provider === original && originalResolver != null) originalResolver()
                    else (provider as? PlaybackCapability ?: throw SourceException(SourceErrorCode.UNSUPPORTED)).resolve(target.ref, quality)
                    validateResource(provider, resource)
                    if (audioResourceIdentity(provider.instanceId, target.ref, resource) in excluded) throw SourceException(SourceErrorCode.UNAVAILABLE)
                    attempts += ResolutionAttempt(provider.instanceId, target.ref)
                    PlaybackResolution(track.ref, target.ref, provider.id, provider.instanceId, resource, attempts.toList())
                }
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(version)
                attempts += ResolutionAttempt(provider.instanceId, candidate?.ref, SourceErrorCode.TIMEOUT)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(version)
                attempts += ResolutionAttempt(provider.instanceId, candidate?.ref, (error as? SourceException)?.code ?: SourceErrorCode.NETWORK)
            }
        }
        throw PlaybackResolutionException(attempts)
    }

    private suspend fun match(origin: Track, provider: MusicProvider, settings: SourceSettings, version: Long): Track? {
        val catalog = provider as? CatalogCapability ?: throw SourceException(SourceErrorCode.UNSUPPORTED)
        val manualKey = TrackRefCodec.encode(origin.ref) + "|" + provider.id
        settings.manualMatches[manualKey]?.let { encoded ->
            TrackRefCodec.decode(encoded)?.takeIf { it.providerId == provider.id }?.let { target ->
                return catalog.getTrack(target)?.also { if (it.ref != target) throw SourceException(SourceErrorCode.INVALID_RESPONSE) }
            }
        }
        val key = listOf(version.toString(), TrackRefCodec.encode(origin.ref), origin.title, origin.artists.toString(), origin.album.toString(), origin.durationMs.toString(), origin.isrc.orEmpty(), origin.version.orEmpty(), origin.explicit.toString(), provider.instanceId.value, provider.version)
            .joinToString("") { "${it.length}:$it" }
        val now = System.currentTimeMillis()
        mappings[key]?.takeIf { it.expires > now }?.let { return it.track }
        val candidates = catalog.search(origin.title + " " + origin.artists.joinToString(" ") { it.name }, PageRequest(limit = 20)).items
        val ranked = matcher.rank(origin, candidates.filter { it.ref.providerId == provider.id })
        val match = matcher.automatic(ranked)?.track
        synchronized(publicationLock) {
            ensureCurrent(version)
            if (match == null && ranked.isNotEmpty()) mutablePending.value = PendingSourceMatch(origin.ref, provider.instanceId, ranked.take(5), origin.title)
            mappings[key] = Mapping(now + if (match == null) 120_000 else 3_600_000, match)
            if (mappings.size > 256) mappings.entries.sortedBy { it.value.expires }.take(mappings.size - 256).forEach { mappings.remove(it.key, it.value) }
        }
        return match
    }

    private fun ensureCurrent(version: Long) {
        if (stateVersion.get() != version) throw CancellationException("Source resolution state changed")
    }

    private fun validateResource(provider: MusicProvider, resource: AudioResource) {
        val uri = runCatching { URI(resource.uri) }.getOrNull() ?: throw SourceException(SourceErrorCode.INVALID_RESPONSE)
        val mediated = provider is ResourceValidationCapability && provider.acceptsResource(resource)
        val allowed = if (provider.isBuiltIn) setOf("https", "http", "file", "content") else setOf("https")
        if ((!mediated && uri.scheme !in allowed) || resource.quality.isBlank() ||
            resource.expiresAtMs?.let { it <= System.currentTimeMillis() } == true ||
            resource.contentLength?.let { it <= 0 } == true ||
            resource.headers.any { (key, value) -> key.contains('\n') || key.contains('\r') || value.contains('\n') || value.contains('\r') }
        ) throw SourceException(SourceErrorCode.INVALID_RESPONSE)
    }
}
