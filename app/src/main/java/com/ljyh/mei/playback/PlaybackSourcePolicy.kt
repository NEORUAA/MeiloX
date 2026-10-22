package com.ljyh.mei.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.StuckPlayerException
import com.ljyh.mei.constants.MusicQuality
import java.util.Locale

private const val PLAYBACK_CACHE_KEY_VERSION = "meilox-media-v3"

/** Normalizes quality values from preferences, API responses, and enum names. */
internal fun normalizePlaybackQuality(quality: String): String =
    quality.trim().lowercase(Locale.ROOT).ifBlank { MusicQuality.EXHIGH.text }

/** Only advertised player levels are supported; unknown server formats need a fallback. */
internal fun isSupportedPlaybackQuality(quality: String?): Boolean =
    !quality.isNullOrBlank() && MusicQuality.entries.any { it.text == normalizePlaybackQuality(quality) }

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
    MusicQuality.DOLBY.text -> listOf(
        MusicQuality.DOLBY.text,
        MusicQuality.SKY.text,
        MusicQuality.JYEFFECT.text,
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

/** Reads a supported level from this song's current cache-key format without trusting its alias. */
internal fun cachedPlaybackQuality(mediaId: String, cacheKey: String): String? {
    val prefix = playbackCacheKeyPrefix(mediaId)
    if (!cacheKey.startsWith(prefix)) return null
    val source = cacheKey.removePrefix(prefix)
    val quality = source.substringBefore(':', missingDelimiterValue = "")
    if (source.substringAfter(':', missingDelimiterValue = "").isBlank()) return null
    return quality.takeIf(::isSupportedPlaybackQuality)?.let(::normalizePlaybackQuality)
}

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

/** Recovers only Media3's confirmed loading or playback stalls, not generic runtime failures. */
internal fun shouldRecoverStalledPlayback(errorCode: Int, cause: Throwable?): Boolean {
    if (errorCode != PlaybackException.ERROR_CODE_TIMEOUT &&
        errorCode != PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK
    ) return false
    val stuck = generateSequence(cause) { it.cause }
        .take(16)
        .filterIsInstance<StuckPlayerException>()
        .firstOrNull()
        ?: return false
    return when (stuck.stuckType) {
        StuckPlayerException.STUCK_BUFFERING_NOT_LOADING,
        StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS,
        StuckPlayerException.STUCK_PLAYING_NO_PROGRESS -> true
        else -> false
    }
}

/** Retries a different full source when the device cannot decode or render these bytes. */
internal fun shouldTryLowerPlaybackQuality(errorCode: Int): Boolean = when (errorCode) {
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> true
    else -> false
}

/** Keeps automatic source failures bounded until playback has actually made sustained progress. */
internal class PlaybackFailureTracker(
    private val maximumFailures: Int = 5,
    private val healthyPlaybackDurationMs: Long = 2_000L,
) {
    var consecutiveFailures: Int = 0
        private set
    private var progressEntryId: String? = null
    private var progressStartedAtMs = 0L
    private var previousPositionMs = 0L
    private var automaticSkipEntryId: String? = null
    private var currentEntryId: String? = null

    fun recordFailure(): Boolean {
        resetProgress()
        consecutiveFailures++
        return consecutiveFailures >= maximumFailures
    }

    fun reset() {
        consecutiveFailures = 0
        automaticSkipEntryId = null
        resetProgress()
    }

    fun expectAutomaticSkip(entryId: String) {
        automaticSkipEntryId = entryId
    }

    fun onMediaItemTransition(entryId: String?, reason: Int) {
        val entryChanged = entryId != currentEntryId
        currentEntryId = entryId
        val isAutomaticSkip = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK &&
            automaticSkipEntryId != null && automaticSkipEntryId == entryId
        // Metadata hydration can replace the current MediaItem without changing its identity.
        if (entryChanged || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
            automaticSkipEntryId = null
        }
        resetProgress()
        if ((reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && !isAutomaticSkip) ||
            (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && entryChanged)
        ) {
            reset()
        }
    }

    fun resetProgress() {
        progressEntryId = null
    }

    fun recordProgress(entryId: String?, positionMs: Long, realtimeMs: Long, isPlaying: Boolean) {
        if (!isPlaying || entryId == null || consecutiveFailures == 0) {
            resetProgress()
            return
        }
        if (progressEntryId != entryId || positionMs <= previousPositionMs) {
            progressEntryId = entryId
            progressStartedAtMs = realtimeMs
        } else if (realtimeMs - progressStartedAtMs >= healthyPlaybackDurationMs) {
            reset()
        }
        previousPositionMs = positionMs
    }
}
