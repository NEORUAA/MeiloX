package com.ljyh.mei.playback

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.datasource.DataSpec
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.toSourceTrack
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.model.room.SourceType
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.PlaybackResolver
import com.ljyh.mei.source.core.SourceRouter
import com.ljyh.mei.source.core.audioResourceIdentity
import com.ljyh.mei.di.repository.SongRepository
import com.ljyh.mei.di.repository.DownloadRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

class SourceNotFoundException(message: String, cause: Throwable? = null) : SourceException(SourceErrorCode.UNAVAILABLE, message) {
    init { if (cause != null) initCause(cause) }
}

data class LocalPlaybackInfo(
    val mimeType: String? = null,
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
)

internal data class ResolvedMediaSource(
    val uri: Uri,
    val actualQuality: String,
    val cacheKey: String?,
    val sourceIdentity: String? = null,
    val localPlaybackInfo: LocalPlaybackInfo? = null,
    val contentLength: Long? = null,
    val headers: Map<String, String> = emptyMap(),
    val resolution: PlaybackResolution? = null,
    val cachePolicy: CachePolicy = CachePolicy.ALLOW,
    val expiresAtMs: Long? = null,
) {
    override fun toString(): String = "ResolvedMediaSource(quality=$actualQuality, cachePolicy=$cachePolicy)"
}

internal fun resolvedMediaDataSpec(dataSpec: DataSpec, source: ResolvedMediaSource): DataSpec =
    dataSpec.buildUpon().setUri(source.uri).setHttpRequestHeaders(source.headers)
        .setCustomData(source.cachePolicy).setKey(source.cacheKey).build()

@Singleton
class MediaUriProvider @Inject constructor(
    private val playbackResolver: PlaybackResolver,
    private val router: SourceRouter,
    private val songRepository: SongRepository,
    @ApplicationContext private val context: Context,
    private val downloadRepository: DownloadRepository? = null,
    private val sourceUpdatePlaybackGuard: com.ljyh.mei.source.update.SourceUpdatePlaybackGuard = com.ljyh.mei.source.update.SourceUpdatePlaybackGuard(),
) {
    private data class CachedUrl(
        val url: String,
        val expiresAtMs: Long,
        val actualQuality: String,
        val cacheKey: String,
        val sourceIdentity: String,
        val contentLength: Long?,
        val resource: AudioResource,
        val owner: MusicProvider,
        val ref: TrackRef,
        val accountRevision: String?,
    )

    private data class AvailableQualities(
        val qualities: List<MusicQuality>,
        val expiresAtMs: Long,
    )

    private val urlCache = ConcurrentHashMap<String, CachedUrl>()
    private val availableQualityCache = ConcurrentHashMap<String, AvailableQualities>()
    private val resolvedSources = ConcurrentHashMap<String, ResolvedMediaSource>()
    private val trackMetadata = ConcurrentHashMap<TrackRef, Track>()
    private val contextualRefs = ConcurrentHashMap<String, TrackRef>()

    private fun trackRef(mediaId: String): TrackRef? = contextualRefs[mediaId] ?: TrackRefCodec.decode(mediaId)

    internal fun rememberTrack(metadata: com.ljyh.mei.data.model.MediaMetadata, mediaId: String = TrackRefCodec.mediaId(metadata.trackRef)) {
        contextualRefs[mediaId] = metadata.trackRef
        trackMetadata[metadata.trackRef] = metadata.toSourceTrack()
    }
    private val rejectedSources = ConcurrentHashMap<String, MutableSet<String>>()
    private val onlineSourceOverrides = ConcurrentHashMap.newKeySet<String>()
    private val qualityStateVersion = AtomicLong()
    private val accountStateVersion = AtomicLong()
    private val qualityStateLock = Any()

    @Volatile
    internal var onSourceResolved: ((String, ResolvedMediaSource) -> Unit)? = null

    internal fun resolvedMediaSource(mediaId: String): ResolvedMediaSource? =
        resolvedSources[mediaId]?.takeIf { source ->
            source.cacheKey == null ||
                resolvedCacheQuality(mediaId, source.cacheKey) == source.actualQuality
        }

    internal fun rememberCachedSource(mediaId: String, quality: String, cacheKey: String, uri: Uri) {
        val actualQuality = resolvedCacheQuality(mediaId, cacheKey)
            ?.takeIf { it == normalizePlaybackQuality(quality) }
            ?: return
        if (sourceIsRejected(mediaId, cacheKey.substringAfterLast(':'))) return
        rememberResolvedSource(
            mediaId,
            ResolvedMediaSource(
                uri = uri,
                actualQuality = actualQuality,
                cacheKey = cacheKey,
                sourceIdentity = cacheKey.substringAfterLast(':'),
                resolution = cachedPlaybackResolution(mediaId, cacheKey, uri.toString()),
            ),
        )
    }

    internal fun isRejectedCachedSource(mediaId: String, cacheKey: String): Boolean =
        resolvedCacheQuality(mediaId, cacheKey) == null ||
            sourceIsRejected(mediaId, cacheKey.substringAfterLast(':'))

    suspend fun resolveMediaUri(mediaId: String, quality: String): Uri =
        resolveMediaSource(mediaId, quality).uri

    /** Explicit online selections bypass an existing downloaded copy for this session. */
    internal fun preferOnlineSource(mediaId: String) = synchronized(qualityStateLock) {
        onlineSourceOverrides.add(mediaId)
        qualityStateVersion.incrementAndGet()
        resolvedSources.remove(mediaId)
    }

    internal fun preferLocalSource(mediaId: String) = synchronized(qualityStateLock) {
        onlineSourceOverrides.remove(mediaId)
        qualityStateVersion.incrementAndGet()
        resolvedSources.remove(mediaId)
    }

    /** Reads the existing file independently of the selected online playback source. */
    suspend fun getLocalPlaybackInfo(mediaId: String): LocalPlaybackInfo? {
        val song = localSong(mediaId) ?: return null
        val uri = localUri(song) ?: return null
        return readLocalPlaybackInfo(uri)
    }

    /** All local opens publish the same source information as streaming and disk cache hits. */
    internal suspend fun resolveLocalSource(mediaId: String): ResolvedMediaSource? {
        val stateVersion = synchronized(qualityStateLock) { qualityStateVersion.get() }
        val song = localSong(mediaId) ?: return null
        if (song.sourceType != SourceType.LOCAL && onlineSourceOverrides.contains(mediaId)) return null
        val uri = localUri(song) ?: return null
        val info = readLocalPlaybackInfo(uri)
        val task = if (song.sourceType == SourceType.DOWNLOAD) downloadRepository?.getBySongId(song.id) else null
        val origin = task?.originTrackRef?.let(TrackRefCodec::decode) ?: trackRef(mediaId)
        val actual = task?.resolvedTrackRef?.let(TrackRefCodec::decode)
        val provenance = if (origin != null && actual != null && task != null) PlaybackResolution(
            origin, actual, task.actualAudioProviderId, ProviderInstanceId(task.actualProviderInstanceId),
            AudioResource(uri.toString(), task.actualQuality ?: task.quality), emptyList()) else null
        return synchronized(qualityStateLock) {
            ensureCurrentState(stateVersion)
            rememberResolvedSource(
                mediaId,
                ResolvedMediaSource(uri, actualQuality = "", cacheKey = null, localPlaybackInfo = info, resolution = provenance),
            )
        }
    }

    internal suspend fun resolveMediaSource(mediaId: String, quality: String): ResolvedMediaSource {
        resolveLocalSource(mediaId)?.let { return it }
        router.registry.awaitInitialized()
        return sourceUpdatePlaybackGuard.withResolution({ resolutionUpdateSources(trackRef(mediaId)?.providerId) }) {
            resolveOnlineMediaSource(mediaId, quality)
        }
    }

    private fun resolutionUpdateSources(origin: String?): Set<String> =
        router.registry.playable().mapTo(mutableSetOf()) { it.id }.apply { origin?.let(::add) }

    private suspend fun resolveOnlineMediaSource(mediaId: String, quality: String): ResolvedMediaSource {
        val ref = trackRef(mediaId) ?: throw SourceNotFoundException("Unknown content identity")
        if (ref.providerId == ProviderIds.LOCAL) throw SourceNotFoundException("Local file is unavailable")
        router.registry.awaitInitialized()
        val stateVersion = synchronized(qualityStateLock) { qualityStateVersion.get() }
        val track = trackMetadata[ref] ?: localSong(mediaId)?.let { song ->
            Track(ref, song.title, song.artist.map { SourceArtist(it) }, SourceAlbum(song.album),
                song.duration, song.cover)
        } ?: Track(ref, "", emptyList())
        val resolution = try { playbackResolver.resolve(
            track, quality,
            excludedResources = rejectedSources[mediaId]?.mapNotNull {
                runCatching { String(java.util.Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }.getOrNull()
            }?.toSet().orEmpty(),
            originalResolver = { resolveOriginResource(mediaId, ref, quality, stateVersion) },
        ) } catch (error: com.ljyh.mei.source.core.PlaybackResolutionException) {
            throw SourceNotFoundException("No playable source is available", error)
        }
        return synchronized(qualityStateLock) {
            ensureCurrentState(stateVersion)
            val identity = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(audioResourceIdentity(resolution).toByteArray(Charsets.UTF_8))
            if (sourceIsRejected(mediaId, identity)) throw SourceNotFoundException("Playback resource was rejected")
            rememberResolvedSource(mediaId, ResolvedMediaSource(
                uri = resolution.resource.uri.toUri(), actualQuality = resolution.resource.quality,
                cacheKey = if (resolution.resource.cachePolicy == CachePolicy.ALLOW) resolvedPlaybackCacheKey(resolution) else null,
                sourceIdentity = identity, contentLength = resolution.resource.contentLength,
                headers = resolution.resource.headers, resolution = resolution,
                cachePolicy = resolution.resource.cachePolicy, expiresAtMs = resolution.resource.expiresAtMs,
            ))
        }
    }

    /** Downloads refresh credentials in the worker and never persist resource headers. */
    internal suspend fun resolveDownloadSource(track: Track, quality: String): PlaybackResolution {
        router.registry.awaitInitialized()
        return sourceUpdatePlaybackGuard.withResolution({ resolutionUpdateSources(track.ref.providerId) }) {
            val stateVersion = synchronized(qualityStateLock) { accountStateVersion.get() }
            val resolution = playbackResolver.resolve(track, quality, originalResolver = {
                resolveOriginResource(TrackRefCodec.mediaId(track.ref), track.ref, quality, stateVersion, forDownload = true)
            })
            synchronized(qualityStateLock) { ensureCurrentState(stateVersion, forDownload = true) }
            resolution
        }
    }

    private suspend fun resolveOriginResource(
        mediaId: String,
        ref: TrackRef,
        quality: String,
        stateVersion: Long,
        forDownload: Boolean = false,
    ): AudioResource {
        val owner = router.registry.requireProvider(ref.providerId)
        val capability = owner as? PlaybackCapability ?: throw SourceException(SourceErrorCode.UNSUPPORTED)
        if (SourceCapability.PLAYBACK !in owner.capabilities) throw SourceException(SourceErrorCode.UNSUPPORTED)
        val account = (owner as? AccountRevisionCapability)?.accountRevision?.firstOrNull()
        val requestedQuality = normalizePlaybackQuality(quality)
        for (attemptedQuality in playbackQualityFallbacks(requestedQuality)) {
            val cacheKey = "$mediaId:$attemptedQuality"
            val cached = urlCache[cacheKey]
            if (cached != null) {
                if (cached.expiresAtMs > System.currentTimeMillis() && cached.owner === owner &&
                    cached.accountRevision == account && cached.hasSupportedQuality(mediaId) &&
                    (forDownload || !cached.isRejectedSource(mediaId))
                ) {
                    synchronized(qualityStateLock) { ensureCurrentState(stateVersion, forDownload) }
                    return cached.resource
                }
                urlCache.remove(cacheKey, cached)
            }
            val resource = try { capability.resolve(ref, attemptedQuality) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: SourceException) {
                if (error.code == SourceErrorCode.UNAVAILABLE) continue else throw error
            }
            ensureOwner(owner, account)
            val entry = resource.toCachedUrl(ref, owner, account, attemptedQuality) ?: continue
            if (!forDownload && entry.isRejectedSource(mediaId)) continue
            return synchronized(qualityStateLock) {
                ensureCurrentState(stateVersion, forDownload)
                if (!forDownload && entry.isRejectedSource(mediaId)) {
                    throw SourceException(SourceErrorCode.UNAVAILABLE)
                }
                if (resource.cachePolicy == CachePolicy.ALLOW) {
                    urlCache["$mediaId:$requestedQuality"] = entry
                    urlCache[cacheKey] = entry
                    cacheEffectiveSource(mediaId, entry)
                }
                entry.resource
            }
        }
        throw SourceException(SourceErrorCode.UNAVAILABLE, "No playable full source is available")
    }

    private suspend fun ensureOwner(owner: MusicProvider, account: String?) {
        if (router.registry.provider(owner.id) !== owner ||
            (owner as? AccountRevisionCapability)?.accountRevision?.firstOrNull() != account) {
            throw CancellationException("Source changed while resolving playback")
        }
    }

    /** Verifies catalog hints against full URLs for this account and device. */
    suspend fun getAvailableMusicQualities(mediaId: String, supportDolby: Boolean): List<MusicQuality> =
        getAvailableQualities(mediaId, supportDolby, forDownload = false)

    /** Download availability depends on the account's full sources, regardless of local playback. */
    suspend fun getAvailableDownloadQualities(mediaId: String): List<MusicQuality> =
        getAvailableQualities(mediaId, supportDolby = true, forDownload = true)

    private suspend fun getAvailableQualities(
        mediaId: String,
        supportDolby: Boolean,
        forDownload: Boolean,
    ): List<MusicQuality> {
        val stateVersion = synchronized(qualityStateLock) { availabilityStateVersion(forDownload) }
        if (!canResolveOnline(mediaId)) return emptyList()
        val ref = trackRef(mediaId) ?: return emptyList()
        val owner = router.registry.requireProvider(ref.providerId)
        val account = (owner as? AccountRevisionCapability)?.accountRevision?.firstOrNull()
        val availabilityKey = if (forDownload) "download:$mediaId" else "$mediaId:$supportDolby"
        val now = System.currentTimeMillis()
        availableQualityCache[availabilityKey]?.let { cached ->
            if (cached.expiresAtMs > now) return synchronized(qualityStateLock) {
                if (availabilityStateVersion(forDownload) == stateVersion) cached.qualities else emptyList()
            }
            availableQualityCache.remove(availabilityKey, cached)
        }

        val knownSource = resolvedMediaSource(mediaId).takeUnless { forDownload }
            ?.takeIf { it.resolution?.actualAudioProviderId?.let { provider -> provider == ref.providerId } != false }
            ?.takeIf { it.sourceIdentity != null && !sourceIsRejected(mediaId, it.sourceIdentity) }
        val knownQuality = MusicQuality.entries.firstOrNull { it.text == knownSource?.actualQuality }
            ?.takeIf { supportDolby || it != MusicQuality.DOLBY }
        val candidates = try {
            router.availableQualities(ref).mapNotNull { value ->
                MusicQuality.entries.firstOrNull { it.text == normalizePlaybackQuality(value) }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { emptyList() }
            .distinct().filter { supportDolby || it != MusicQuality.DOLBY }
        ensureOwner(owner, account)

        val semaphore = Semaphore(QUALITY_PROBE_CONCURRENCY)
        val verified = coroutineScope {
            candidates.map { quality ->
                async {
                    semaphore.withPermit { verifyAvailableQuality(mediaId, ref, owner, account, quality, stateVersion, forDownload) }
                }
            }.awaitAll().filterNotNull().filter { supportDolby || it != MusicQuality.DOLBY }
        }
        val available = (verified + listOfNotNull(knownQuality)).distinct().sortedBy { it.ordinal }
        return synchronized(qualityStateLock) {
            if (availabilityStateVersion(forDownload) != stateVersion) return@synchronized emptyList()
            availableQualityCache[availabilityKey] = AvailableQualities(
                qualities = available,
                expiresAtMs = System.currentTimeMillis() + AVAILABLE_QUALITY_CACHE_TTL_MS,
            )
            available
        }
    }

    /** Rejects the exact failed bytes, including aliases returned under other requested levels. */
    fun rejectCurrentSource(mediaId: String): Boolean = synchronized(qualityStateLock) {
        val source = resolvedSources[mediaId] ?: return false
        val identity = source.sourceIdentity ?: source.cacheKey?.substringAfterLast(':') ?: return false
        val rejected = rejectedSources.computeIfAbsent(mediaId) { ConcurrentHashMap.newKeySet() }
        if (!rejected.add(identity)) return false
        source.resolution?.resource?.contentId?.let(rejected::add)
        qualityStateVersion.incrementAndGet()
        invalidate(mediaId)
        invalidateAvailableQualities(mediaId)
        true
    }

    /** Explicit quality changes may retry a source previously rejected by the decoder. */
    fun resetRejectedSources() = synchronized(qualityStateLock) {
        qualityStateVersion.incrementAndGet()
        rejectedSources.keys.forEach(::invalidate)
        rejectedSources.clear()
        availableQualityCache.keys
            .filterNot { it.startsWith("download:") }
            .forEach(availableQualityCache::remove)
    }

    /** Account changes must not reuse entitlement, URL, or decoder state from the previous account. */
    internal fun clearQualityState() = synchronized(qualityStateLock) {
        qualityStateVersion.incrementAndGet()
        accountStateVersion.incrementAndGet()
        urlCache.clear()
        availableQualityCache.clear()
        rejectedSources.clear()
        resolvedSources.clear()
        onlineSourceOverrides.clear()
        playbackResolver.invalidate()
    }

    /** Forces the next request for this song to obtain a fresh signed URL and source identity. */
    fun invalidate(mediaId: String) {
        playbackResolver.invalidate()
        val prefix = "${mediaId.trim()}:"
        urlCache.keys
            .filter { it.startsWith(prefix) }
            .forEach(urlCache::remove)
    }

    private suspend fun verifyAvailableQuality(
        mediaId: String,
        ref: TrackRef,
        owner: MusicProvider,
        account: String?,
        quality: MusicQuality,
        stateVersion: Long,
        forDownload: Boolean,
    ): MusicQuality? {
        val cached = urlCache["$mediaId:${quality.text}"]
        if (cached != null && cached.expiresAtMs > System.currentTimeMillis() &&
            cached.owner === owner && cached.accountRevision == account && cached.hasSupportedQuality(mediaId) &&
            cached.actualQuality == quality.text && (forDownload || !cached.isRejectedSource(mediaId))) return quality
        return try {
            val capability = owner as? PlaybackCapability ?: return null
            val resource = capability.resolve(ref, quality.text)
            ensureOwner(owner, account)
            // Quality menus never infer a level from the requested preference.
            if (resource.quality.isBlank() || forDownload && resource.cachePolicy == CachePolicy.NO_STORE) return null
            val entry = resource.toCachedUrl(ref, owner, account, quality.text) ?: return null
            synchronized(qualityStateLock) {
                if (availabilityStateVersion(forDownload) != stateVersion ||
                    (!forDownload && entry.isRejectedSource(mediaId))) return@synchronized null
                if (resource.cachePolicy == CachePolicy.ALLOW) cacheEffectiveSource(mediaId, entry)
                MusicQuality.entries.firstOrNull { it.text == entry.actualQuality }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { null }
    }

    private suspend fun localSong(mediaId: String): Song? {
        val ref = trackRef(mediaId)
        val storageId = ref?.let(TrackRefCodec::storageKey) ?: mediaId
        val legacy = if (ref?.providerId == ProviderIds.LOCAL && mediaId != storageId &&
            mediaId.toLongOrNull()?.let { it < 0 && it != Long.MIN_VALUE } == true
        ) songRepository.getSong(mediaId).firstOrNull()?.takeIf {
            it.providerId == ref.providerId && it.providerTrackId == ref.trackId
        } else null
        if (!legacy?.path.isNullOrBlank()) return legacy
        val exact = songRepository.getSong(storageId).firstOrNull()
            ?.takeUnless { ref?.providerId != ProviderIds.LOCAL && it.sourceType == SourceType.LOCAL }
        return exact?.takeIf { !it.path.isNullOrBlank() } ?: legacy ?: exact
    }

    private suspend fun canResolveOnline(mediaId: String): Boolean =
        trackRef(mediaId)?.let { it.providerId != ProviderIds.LOCAL } == true &&
            localSong(mediaId)?.sourceType != SourceType.LOCAL

    private fun localUri(song: Song): Uri? {
        val path = song.path ?: return null
        val uri = Uri.parse(path)
        if (uri.scheme == "content") return uri
        val file = if (uri.scheme == "file") File(uri.path ?: return null) else File(path)
        return file.takeIf(File::exists)?.let(Uri::fromFile)
    }

    private suspend fun readLocalPlaybackInfo(uri: Uri): LocalPlaybackInfo = withContext(Dispatchers.IO) {
        var mimeType: String? = null
        var bitrate: Int? = null
        var sampleRate: Int? = null
        var extractor: MediaExtractor? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") != true) continue
                mimeType = mime
                if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    bitrate = format.getInteger(MediaFormat.KEY_BIT_RATE).takeIf { it > 0 }
                }
                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).takeIf { it > 0 }
                }
                break
            }
        } catch (error: Exception) {
            Timber.tag("MediaUriProvider").d("Unable to inspect local audio: %s", error.javaClass.simpleName)
        } finally {
            runCatching { extractor?.release() }
        }
        if (bitrate == null || mimeType == null) {
            var retriever: MediaMetadataRetriever? = null
            try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                if (mimeType == null) {
                    mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                }
                if (bitrate == null) {
                    bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                        ?.toIntOrNull()?.takeIf { it > 0 }
                }
            } catch (error: Exception) {
                Timber.tag("MediaUriProvider").d("Unable to read local audio metadata: %s", error.javaClass.simpleName)
            } finally {
                runCatching { retriever?.release() }
            }
        }
        LocalPlaybackInfo(mimeType, bitrate, sampleRate)
    }

    /** A decoder failure rejects aliases of the same bytes at every quality. */
    private fun CachedUrl.isRejectedSource(mediaId: String): Boolean {
        val rejected = rejectedSources[mediaId] ?: return false
        if (sourceIdentity in rejected) return true
        val digest = audioResourceIdentity(owner.instanceId, ref, resource)
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest.toByteArray(Charsets.UTF_8))
        if (encoded !in rejected) return false
        rejected.add(sourceIdentity)
        return true
    }

    private fun sourceIsRejected(mediaId: String, identity: String): Boolean =
        rejectedSources[mediaId]?.contains(identity) == true

    private fun availabilityStateVersion(forDownload: Boolean): Long =
        if (forDownload) accountStateVersion.get() else qualityStateVersion.get()

    private fun ensureCurrentState(stateVersion: Long, forDownload: Boolean = false) {
        if (availabilityStateVersion(forDownload) != stateVersion) {
            throw IOException("Playback quality state changed while resolving a source")
        }
    }

    private fun cacheEffectiveSource(mediaId: String, source: CachedUrl) {
        if (!source.hasSupportedQuality(mediaId)) return
        urlCache["$mediaId:${source.actualQuality}"] = source
    }

    private fun invalidateAvailableQualities(mediaId: String) {
        val prefix = "$mediaId:"
        availableQualityCache.keys.filter { it.startsWith(prefix) }.forEach(availableQualityCache::remove)
    }

    private fun rememberResolvedSource(mediaId: String, source: ResolvedMediaSource): ResolvedMediaSource {
        resolvedSources[mediaId] = source
        onSourceResolved?.invoke(mediaId, source)
        return source
    }

    private fun CachedUrl.hasSupportedQuality(mediaId: String): Boolean =
        isSupportedPlaybackQuality(actualQuality) &&
            resolvedCacheQuality(mediaId, cacheKey) == normalizePlaybackQuality(actualQuality)

    private fun AudioResource.toCachedUrl(
        ref: TrackRef, owner: MusicProvider, account: String?, attemptedQuality: String,
    ): CachedUrl? {
        val actual = effectivePlaybackQuality(quality, attemptedQuality)
        if (!isSupportedPlaybackQuality(actual) || uri.isBlank() ||
            expiresAtMs?.let { it <= System.currentTimeMillis() } == true || contentLength?.let { it <= 0 } == true) return null
        val resource = copy(quality = actual)
        val resolution = PlaybackResolution(ref, ref, owner.id, owner.instanceId, resource, emptyList())
        return CachedUrl(uri, expiresAtMs ?: System.currentTimeMillis() + DEFAULT_URL_CACHE_TTL_MS,
            actual, resolvedPlaybackCacheKey(resolution), contentId ?: audioResourceIdentity(resolution), contentLength,
            resource, owner, ref, account)
    }

    private companion object {
        const val DEFAULT_URL_CACHE_TTL_MS = 5 * 60 * 1_000L
        const val AVAILABLE_QUALITY_CACHE_TTL_MS = 60 * 1_000L
        const val QUALITY_PROBE_CONCURRENCY = 3
    }
}
