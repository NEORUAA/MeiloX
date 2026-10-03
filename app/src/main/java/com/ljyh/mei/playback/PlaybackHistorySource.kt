package com.ljyh.mei.playback

/** The NetEase source context attached to one playback weblog. */
internal data class PlaybackHistorySource(
    val sourceId: Long,
    val source: String,
)

internal fun resolvePlaybackHistorySource(songId: Long): PlaybackHistorySource? =
    songId.takeIf { it > 0L }?.let { PlaybackHistorySource(it, "track") }
