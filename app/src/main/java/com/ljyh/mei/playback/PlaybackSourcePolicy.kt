package com.ljyh.mei.playback

import androidx.media3.common.PlaybackException
import com.ljyh.mei.constants.MusicQuality
import java.util.Locale

private const val PLAYBACK_CACHE_KEY_VERSION = "meilox-media-v3"

/** Normalizes quality values from preferences, API responses, and enum names. */
internal fun normalizePlaybackQuality(quality: String): String =
    quality.trim().lowercase(Locale.ROOT).ifBlank { MusicQuality.EXHIGH.text }

/** Uses the server-reported level when available, otherwise the attempted level. */
internal fun effectivePlaybackQuality(sourceQuality: String?, attemptedQuality: String): String =
    sourceQuality
        ?.takeIf(String::isNotBlank)
        ?.let(::normalizePlaybackQuality)
        ?: normalizePlaybackQuality(attemptedQuality)

/**
 * Returns the requested quality followed by the progressively lower qualities
 * supported by the NetEase player endpoint.
 */
internal fun playbackQualityFallbacks(requestedQuality: String): List<String> = when (
    normalizePlaybackQuality(requestedQuality)
) {
    MusicQuality.STANDARD.text -> listOf(MusicQuality.STANDARD.text)
    MusicQuality.EXHIGH.text -> listOf(
        MusicQuality.EXHIGH.text,
        MusicQuality.STANDARD.text,
    )
    MusicQuality.LOSSLESS.text -> listOf(
        MusicQuality.LOSSLESS.text,
        MusicQuality.EXHIGH.text,
        MusicQuality.STANDARD.text,
    )
    MusicQuality.HIRES.text -> listOf(
        MusicQuality.HIRES.text,
        MusicQuality.LOSSLESS.text,
        MusicQuality.EXHIGH.text,
        MusicQuality.STANDARD.text,
    )
    MusicQuality.JYEFFECT.text -> listOf(
        MusicQuality.JYEFFECT.text,
        MusicQuality.LOSSLESS.text,
        MusicQuality.EXHIGH.text,
        MusicQuality.STANDARD.text,
    )
    MusicQuality.SKY.text -> listOf(
        MusicQuality.SKY.text,
        MusicQuality.JYEFFECT.text,
        MusicQuality.LOSSLESS.text,
        MusicQuality.EXHIGH.text,
        MusicQuality.STANDARD.text,
    )
    MusicQuality.JYMASTER.text -> listOf(
        MusicQuality.JYMASTER.text,
        MusicQuality.HIRES.text,
        MusicQuality.LOSSLESS.text,
        MusicQuality.EXHIGH.text,
        MusicQuality.STANDARD.text,
    )
    else -> listOf(
        MusicQuality.EXHIGH.text,
        MusicQuality.STANDARD.text,
    )
}

/** Identifies the exact media bytes returned for a logical quality. */
internal fun playbackSourceIdentity(sourceMd5: String?, sourceSize: Long?): String =
    sourceMd5
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf(String::isNotEmpty)
        ?: "size-${sourceSize?.takeIf { it > 0L } ?: 0L}"

/** Prefix shared by every source revision cached for one song and quality. */
internal fun playbackCacheKeyPrefix(mediaId: String, quality: String): String =
    "$PLAYBACK_CACHE_KEY_VERSION:${mediaId.trim()}:${normalizePlaybackQuality(quality)}:"

/** Prefix shared by every playback cache entry for one song. */
internal fun playbackCacheKeyPrefix(mediaId: String): String =
    "$PLAYBACK_CACHE_KEY_VERSION:${mediaId.trim()}:"

/**
 * Builds a disk cache key for the exact server-returned source.
 *
 * Quality alone is insufficient because NetEase may replace the underlying file while keeping
 * the same logical level. Mixing an old partial cache span with that new file can make a later
 * range request start beyond the new resource boundary.
 */
internal fun playbackCacheKey(
    mediaId: String,
    quality: String,
    sourceMd5: String?,
    sourceSize: Long?,
): String = playbackCacheKeyPrefix(mediaId, quality) +
    playbackSourceIdentity(sourceMd5, sourceSize)

/** Returns whether the current source should be invalidated and retried in place. */
internal fun shouldRefreshPlaybackSource(errorCode: Int): Boolean =
    errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
