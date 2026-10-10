package com.ljyh.mei.playback

import android.net.Uri
import androidx.core.net.toUri
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.SongUrl
import com.ljyh.mei.data.model.api.GetSongDetails
import com.ljyh.mei.data.model.api.GetSongUrlV1
import com.ljyh.mei.data.model.availableMusicQualities
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.di.repository.SongRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

class SourceNotFoundException(message: String) : IOException(message)

internal data class ResolvedMediaSource(
    val uri: Uri,
    val actualQuality: String,
    val cacheKey: String?,
    val sourceIdentity: String? = null,
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
        val sourceIdentity: String,
    )

    private data class AvailableQualities(
        val qualities: List<MusicQuality>,
        val expiresAtMs: Long,
    )

    private val urlCache = ConcurrentHashMap<String, CachedUrl>()
    private val availableQualityCache = ConcurrentHashMap<String, AvailableQualities>()
    private val resolvedSources = ConcurrentHashMap<String, ResolvedMediaSource>()
    private val rejectedSources = ConcurrentHashMap<String, MutableSet<String>>()
    private val qualityStateVersion = AtomicLong()
    private val accountStateVersion = AtomicLong()
    private val qualityStateLock = Any()

    @Volatile
    internal var onSourceResolved: ((String, ResolvedMediaSource) -> Unit)? = null

    internal fun resolvedMediaSource(mediaId: String): ResolvedMediaSource? =
        resolvedSources[mediaId]

    internal fun rememberCachedSource(mediaId: String, quality: String, cacheKey: String, uri: Uri) {
        rememberResolvedSource(
            mediaId,
            ResolvedMediaSource(
                uri = uri,
                actualQuality = normalizePlaybackQuality(quality),
                cacheKey = cacheKey,
                sourceIdentity = cacheKey.substringAfterLast(':'),
            ),
        )
    }

    internal fun isRejectedCachedSource(mediaId: String, cacheKey: String): Boolean =
        sourceIsRejected(mediaId, cacheKey.substringAfterLast(':'))

    suspend fun resolveMediaUri(mediaId: String, quality: String): Uri =
        resolveMediaSource(mediaId, quality).uri

    internal suspend fun resolveMediaSource(mediaId: String, quality: String): ResolvedMediaSource {
        val stateVersion = synchronized(qualityStateLock) { qualityStateVersion.get() }
        val requestedQuality = normalizePlaybackQuality(quality)
        localUri(mediaId)?.let { uri ->
            return synchronized(qualityStateLock) {
                ensureCurrentState(stateVersion)
                rememberResolvedSource(
                    mediaId,
                    ResolvedMediaSource(uri, requestedQuality, cacheKey = null),
                )
            }
        }

        val attemptedQualities = mutableListOf<String>()
        for (attemptedQuality in playbackQualityFallbacks(requestedQuality)) {
            val cacheKey = "$mediaId:$attemptedQuality"
            val now = System.currentTimeMillis()
            val cached = urlCache[cacheKey]
            if (cached != null) {
                if (cached.expiresAtMs > now && !sourceIsRejected(mediaId, cached.sourceIdentity)) {
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
        if (!forDownload && localUri(mediaId) != null) return emptyList()
        val availabilityKey = if (forDownload) "download:$mediaId" else "$mediaId:$supportDolby"
        val now = System.currentTimeMillis()
        availableQualityCache[availabilityKey]?.let { cached ->
            if (cached.expiresAtMs > now) return synchronized(qualityStateLock) {
                if (availabilityStateVersion(forDownload) == stateVersion) cached.qualities else emptyList()
            }
            availableQualityCache.remove(availabilityKey, cached)
        }

        val knownSource = resolvedSources[mediaId].takeUnless { forDownload }
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

    private suspend fun localUri(mediaId: String): Uri? {
        val localPath = songRepository.getSong(mediaId).firstOrNull()?.path
            ?: songRepository.getSong("local_$mediaId").firstOrNull()?.path
            ?: return null
        if (localPath.startsWith("content://")) return Uri.parse(localPath)
        val file = File(localPath)
        return file.takeIf(File::exists)?.let(Uri::fromFile)
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
    )

    private fun SongUrl.Data.toCachedUrl(mediaId: String, attemptedQuality: String, now: Long): CachedUrl? {
        val sourceUrl = url ?: return null
        val actualQuality = effectivePlaybackQuality(level, attemptedQuality)
        return CachedUrl(
            url = sourceUrl,
            expiresAtMs = expiresAt(expi, now),
            actualQuality = actualQuality,
            cacheKey = playbackCacheKey(mediaId, actualQuality, md5, size.toLong()),
            sourceIdentity = playbackSourceIdentity(md5, size.toLong()),
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
