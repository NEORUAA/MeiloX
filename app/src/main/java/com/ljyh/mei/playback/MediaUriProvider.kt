package com.ljyh.mei.playback

import android.net.Uri
import androidx.core.net.toUri
import com.ljyh.mei.data.model.api.GetSongUrlV1
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.di.repository.SongRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

class SourceNotFoundException(message: String) : IOException(message)

internal data class ResolvedMediaSource(
    val uri: Uri,
    val actualQuality: String,
    val cacheKey: String?,
)

@Singleton
class MediaUriProvider @Inject constructor(
    private val apiService: ApiService,
    private val songRepository: SongRepository,
) {
    private data class CachedUrl(
        val url: String,
        val expiresAtMs: Long,
        val actualQuality: String,
        val cacheKey: String,
    )

    private val urlCache = ConcurrentHashMap<String, CachedUrl>()

    suspend fun resolveMediaUri(mediaId: String, quality: String): Uri =
        resolveMediaSource(mediaId, quality).uri

    internal suspend fun resolveMediaSource(mediaId: String, quality: String): ResolvedMediaSource {
        val requestedQuality = normalizePlaybackQuality(quality)
        val localPath = songRepository.getSong(mediaId).firstOrNull()?.path
            ?: songRepository.getSong("local_$mediaId").firstOrNull()?.path
        if (localPath != null) {
            if (localPath.startsWith("content://")) {
                return ResolvedMediaSource(Uri.parse(localPath), requestedQuality, cacheKey = null)
            }
            val file = File(localPath)
            if (file.exists()) {
                return ResolvedMediaSource(Uri.fromFile(file), requestedQuality, cacheKey = null)
            }
        }

        val attemptedQualities = mutableListOf<String>()
        for (attemptedQuality in playbackQualityFallbacks(requestedQuality)) {
            val cacheKey = "$mediaId:$attemptedQuality"
            val now = System.currentTimeMillis()
            val cached = urlCache[cacheKey]
            if (cached != null) {
                if (cached.expiresAtMs > now) {
                    return ResolvedMediaSource(
                        uri = cached.url.toUri(),
                        actualQuality = cached.actualQuality,
                        cacheKey = cached.cacheKey,
                    )
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
                val url = fullSource.url ?: continue
                val actualQuality = effectivePlaybackQuality(
                    sourceQuality = fullSource.level,
                    attemptedQuality = attemptedQuality,
                )
                val playbackCacheKey = playbackCacheKey(
                    mediaId = mediaId,
                    quality = actualQuality,
                    sourceMd5 = fullSource.md5,
                    sourceSize = fullSource.size.toLong(),
                )
                val cacheEntry = CachedUrl(
                    url = url,
                    expiresAtMs = expiresAt(
                        expiSeconds = fullSource.expi,
                        nowMs = now,
                    ),
                    actualQuality = actualQuality,
                    cacheKey = playbackCacheKey,
                )
                // Cache the requested, attempted, and effective levels while retaining the
                // effective level so disk bytes are never mislabeled as a higher quality.
                urlCache["$mediaId:$requestedQuality"] = cacheEntry
                urlCache[cacheKey] = cacheEntry
                urlCache["$mediaId:$actualQuality"] = cacheEntry
                return ResolvedMediaSource(
                    uri = url.toUri(),
                    actualQuality = actualQuality,
                    cacheKey = playbackCacheKey,
                )
            }
        }

        val message = "No playable full source for $mediaId at quality $requestedQuality"
        throw SourceNotFoundException(message)
    }

    /** Forces the next request for this song to obtain a fresh signed URL and source identity. */
    fun invalidate(mediaId: String) {
        val prefix = "${mediaId.trim()}:"
        urlCache.keys
            .filter { it.startsWith(prefix) }
            .forEach(urlCache::remove)
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
    }
}
