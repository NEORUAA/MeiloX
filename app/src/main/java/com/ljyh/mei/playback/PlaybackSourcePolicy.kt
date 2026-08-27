package com.ljyh.mei.playback

import com.ljyh.mei.constants.MusicQuality
import java.util.Locale

private const val PLAYBACK_CACHE_KEY_VERSION = "meilox-media-v2"

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

/**
 * Builds the disk cache key for a song at a particular effective quality.
 * The version prefix deliberately isolates entries written by older builds.
 */
internal fun playbackCacheKey(mediaId: String, quality: String): String =
    "$PLAYBACK_CACHE_KEY_VERSION:${mediaId.trim()}:${normalizePlaybackQuality(quality)}"
