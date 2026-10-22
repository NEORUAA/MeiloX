package com.ljyh.mei.playback

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.SongUrl
import com.ljyh.mei.data.model.api.GetSongDetails
import com.ljyh.mei.data.model.api.GetSongUrlV1
import com.ljyh.mei.data.model.availableMusicQualities
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.model.room.SourceType
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.di.repository.SongRepository
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

class SourceNotFoundException(message: String) : IOException(message)

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
)

@Singleton
class MediaUriProvider @Inject constructor(
    private val apiService: ApiService,
    private val songRepository: SongRepository,
    @ApplicationContext private val context: Context,
) {
    private data class CachedUrl(
        val url: String,
        val expiresAtMs: Long,
        val actualQuality: String,
        val cacheKey: String,
        val sourceIdentity: String,
        val contentLength: Long?,
    )

    private data class AvailableQualities(
        val qualities: List<MusicQuality>,
        val expiresAtMs: Long,
    )

    private val urlCache = ConcurrentHashMap<String, CachedUrl>()
    private val availableQualityCache = ConcurrentHashMap<String, AvailableQualities>()
    private val resolvedSources = ConcurrentHashMap<String, ResolvedMediaSource>()
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
                (isSupportedPlaybackQuality(source.actualQuality) &&
                    cachedPlaybackQuality(mediaId, source.cacheKey) == normalizePlaybackQuality(source.actualQuality))
        }

    internal fun rememberCachedSource(mediaId: String, quality: String, cacheKey: String, uri: Uri) {
        val actualQuality = cachedPlaybackQuality(mediaId, cacheKey)
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
            ),
        )
    }

    internal fun isRejectedCachedSource(mediaId: String, cacheKey: String): Boolean =
        cachedPlaybackQuality(mediaId, cacheKey) == null ||
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
        return synchronized(qualityStateLock) {
            ensureCurrentState(stateVersion)
            rememberResolvedSource(
                mediaId,
                ResolvedMediaSource(uri, actualQuality = "", cacheKey = null, localPlaybackInfo = info),
            )
        }
    }

    internal suspend fun resolveMediaSource(mediaId: String, quality: String): ResolvedMediaSource {
        val stateVersion = synchronized(qualityStateLock) { qualityStateVersion.get() }
        val requestedQuality = normalizePlaybackQuality(quality)
        resolveLocalSource(mediaId)?.let { return it }
        if (!canResolveOnline(mediaId)) {
            throw SourceNotFoundException("No local source for $mediaId")
        }

        val attemptedQualities = mutableListOf<String>()
        for (attemptedQuality in playbackQualityFallbacks(requestedQuality)) {
            val cacheKey = "$mediaId:$attemptedQuality"
            val now = System.currentTimeMillis()
            val cached = urlCache[cacheKey]
            if (cached != null) {
                if (cached.expiresAtMs > now && cached.hasSupportedQuality(mediaId) &&
                    !sourceIsRejected(mediaId, cached.sourceIdentity)
                ) {
                    return synchronized(qualityStateLock) {
                        ensureCurrentState(stateVersion)
                        rememberResolvedSource(mediaId, cached.toResolvedSource())
                    }
                }
                urlCache.remove(cacheKey, cached)
            }

            attemptedQualities += attemptedQuality
            val response = try {
                apiService.getSongUrlV1(
                    GetSongUrlV1(ids = "[$mediaId]", level = attemptedQuality)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IOException("Network error resolving URL for $mediaId", e)
            }
            if (response.code != 200) {
                throw IOException("Song URL API returned code ${response.code} for $mediaId")
            }
            val source = response.data.firstOrNull { it.id.toString() == mediaId }
            val fullSource = response.fullSourceFor(mediaId)
            logSourceAttempt(
                mediaId = mediaId,
                requestedQuality = requestedQuality,
                attemptedQualities = attemptedQualities,
                source = source,
                responseCode = response.code,
            )

            if (fullSource != null) {
                val cacheEntry = fullSource.toCachedUrl(mediaId, attemptedQuality, now) ?: continue
                if (sourceIsRejected(mediaId, cacheEntry.sourceIdentity)) continue
                // Cache the requested, attempted, and effective levels while retaining the
                // effective level so disk bytes are never mislabeled as a higher quality.
                return synchronized(qualityStateLock) {
                    ensureCurrentState(stateVersion)
                    if (sourceIsRejected(mediaId, cacheEntry.sourceIdentity)) {
                        throw IOException("Playback source was rejected while resolving $mediaId")
                    }
                    urlCache["$mediaId:$requestedQuality"] = cacheEntry
                    urlCache[cacheKey] = cacheEntry
                    cacheEffectiveSource(mediaId, cacheEntry)
                    rememberResolvedSource(mediaId, cacheEntry.toResolvedSource())
                }
            }
        }

        val message = "No playable full source for $mediaId at quality $requestedQuality"
        throw SourceNotFoundException(message)
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
        val availabilityKey = if (forDownload) "download:$mediaId" else "$mediaId:$supportDolby"
        val now = System.currentTimeMillis()
        availableQualityCache[availabilityKey]?.let { cached ->
            if (cached.expiresAtMs > now) return synchronized(qualityStateLock) {
                if (availabilityStateVersion(forDownload) == stateVersion) cached.qualities else emptyList()
            }
            availableQualityCache.remove(availabilityKey, cached)
        }

        val knownSource = resolvedMediaSource(mediaId).takeUnless { forDownload }
            ?.takeIf { it.sourceIdentity != null && !sourceIsRejected(mediaId, it.sourceIdentity) }
        val knownQuality = MusicQuality.entries.firstOrNull { it.text == knownSource?.actualQuality }
            ?.takeIf { supportDolby || it != MusicQuality.DOLBY }
        val candidates = try {
            val detail = apiService.getSongDetail(GetSongDetails(mediaId))
            val track = detail.songs.firstOrNull { it.id.toString() == mediaId }
            val privilege = detail.privileges.firstOrNull { it.id.toString() == mediaId }
            if (detail.code == 200) track?.availableMusicQualities(privilege).orEmpty() else emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag("MediaUriProvider").d(e, "Unable to obtain quality catalog for %s", mediaId)
            emptyList()
        }.filter { supportDolby || it != MusicQuality.DOLBY }

        val semaphore = Semaphore(QUALITY_PROBE_CONCURRENCY)
        val verified = coroutineScope {
            candidates.map { quality ->
                async {
                    semaphore.withPermit { verifyAvailableQuality(mediaId, quality, stateVersion, forDownload) }
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
    }

    /** Forces the next request for this song to obtain a fresh signed URL and source identity. */
    fun invalidate(mediaId: String) {
        val prefix = "${mediaId.trim()}:"
        urlCache.keys
            .filter { it.startsWith(prefix) }
            .forEach(urlCache::remove)
    }

    private suspend fun verifyAvailableQuality(
        mediaId: String,
        quality: MusicQuality,
        stateVersion: Long,
        forDownload: Boolean,
    ): MusicQuality? {
        val now = System.currentTimeMillis()
        val cached = urlCache["$mediaId:${quality.text}"]
        if (cached != null && cached.expiresAtMs > now &&
            cached.hasSupportedQuality(mediaId) &&
            cached.actualQuality == quality.text &&
            (forDownload || !sourceIsRejected(mediaId, cached.sourceIdentity))
        ) {
            return quality
        }
        return try {
            val response = apiService.getSongUrlV1(GetSongUrlV1(ids = "[$mediaId]", level = quality.text))
            if (availabilityStateVersion(forDownload) != stateVersion) return null
            val source = response.fullSourceFor(mediaId) ?: return null
            if (source.level.isNullOrBlank()) return null
            // A downgrade proves the returned level is available, rather than the requested
            // level. Only cache its actual level so probes cannot poison higher-quality aliases.
            val cacheEntry = source.toCachedUrl(mediaId, quality.text, now) ?: return null
            synchronized(qualityStateLock) {
                if (availabilityStateVersion(forDownload) != stateVersion ||
                    (!forDownload && sourceIsRejected(mediaId, cacheEntry.sourceIdentity))
                ) return@synchronized null
                cacheEffectiveSource(mediaId, cacheEntry)
                MusicQuality.entries.firstOrNull { it.text == cacheEntry.actualQuality }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag("MediaUriProvider").d(e, "Quality unavailable id=%s level=%s", mediaId, quality.text)
            null
        }
    }

    private suspend fun localSong(mediaId: String): Song? {
        val exact = songRepository.getSong(mediaId).firstOrNull()
        if (!exact?.path.isNullOrBlank()) return exact
        val localId = mediaId.toLongOrNull()?.takeIf { it < 0 && it != Long.MIN_VALUE }?.let { -it }
            ?: return exact
        val prefixed = songRepository.getSong("local_$localId").firstOrNull()
        return prefixed?.takeIf { !it.path.isNullOrBlank() } ?: exact ?: prefixed
    }

    private suspend fun canResolveOnline(mediaId: String): Boolean =
        !mediaId.startsWith("local_") && mediaId.toLongOrNull()?.let { it > 0L } == true &&
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

    private fun sourceIsRejected(mediaId: String, identity: String): Boolean =
        rejectedSources[mediaId]?.contains(identity) == true

    private fun availabilityStateVersion(forDownload: Boolean): Long =
        if (forDownload) accountStateVersion.get() else qualityStateVersion.get()

    private fun ensureCurrentState(stateVersion: Long) {
        if (qualityStateVersion.get() != stateVersion) {
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

    private fun CachedUrl.toResolvedSource(): ResolvedMediaSource = ResolvedMediaSource(
        uri = url.toUri(),
        actualQuality = actualQuality,
        cacheKey = cacheKey,
        sourceIdentity = sourceIdentity,
        contentLength = contentLength,
    )

    private fun CachedUrl.hasSupportedQuality(mediaId: String): Boolean =
        isSupportedPlaybackQuality(actualQuality) &&
            cachedPlaybackQuality(mediaId, cacheKey) == normalizePlaybackQuality(actualQuality)

    private fun SongUrl.Data.toCachedUrl(mediaId: String, attemptedQuality: String, now: Long): CachedUrl? {
        val sourceUrl = url ?: return null
        val actualQuality = effectivePlaybackQuality(level, attemptedQuality)
        if (!isSupportedPlaybackQuality(actualQuality)) return null
        return CachedUrl(
            url = sourceUrl,
            expiresAtMs = expiresAt(expi, now),
            actualQuality = actualQuality,
            cacheKey = playbackCacheKey(mediaId, actualQuality, md5, size.toLong()),
            sourceIdentity = playbackSourceIdentity(md5, size.toLong()),
            contentLength = size.toLong().takeIf { it > 0 },
        )
    }

    private fun logSourceAttempt(
        mediaId: String,
        requestedQuality: String,
        attemptedQualities: List<String>,
        source: com.ljyh.mei.data.model.SongUrl.Data?,
        responseCode: Int,
    ) {
        Timber.tag("MediaUriProvider").d(
            "source attempt id=%s requested=%s attempted=%s actual=%s time=%s br=%s size=%s " +
                "payed=%s trial=%s code=%s",
            mediaId,
            requestedQuality,
            attemptedQualities.joinToString(","),
            source?.level,
            source?.time,
            source?.br,
            source?.size,
            source?.payed,
            source?.freeTrialInfo != null,
            source?.code ?: responseCode,
        )
    }

    private fun expiresAt(expiSeconds: Int?, nowMs: Long): Long {
        val ttlMs = expiSeconds
            ?.takeIf { it > 0 }
            ?.toLong()
            ?.times(1_000L)
            ?: DEFAULT_URL_CACHE_TTL_MS
        return nowMs + (ttlMs - URL_EXPIRY_SAFETY_MS).coerceAtLeast(MIN_URL_CACHE_TTL_MS)
    }

    private companion object {
        const val DEFAULT_URL_CACHE_TTL_MS = 5 * 60 * 1_000L
        const val URL_EXPIRY_SAFETY_MS = 30 * 1_000L
        const val MIN_URL_CACHE_TTL_MS = 1_000L
        const val AVAILABLE_QUALITY_CACHE_TTL_MS = 60 * 1_000L
        const val QUALITY_PROBE_CONCURRENCY = 3
    }
}
