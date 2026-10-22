package com.ljyh.mei.playback

/** The NetEase source context attached to one playback weblog. */
internal data class PlaybackHistorySource(
    val sourceId: Long,
    val source: String,
)

internal fun resolvePlaybackHistorySource(songId: Long): PlaybackHistorySource? =
    songId.takeIf { it > 0L }?.let { PlaybackHistorySource(it, "track") }

/** Device-library media IDs use a negative number while Room retains its local_ identity. */
internal fun playbackHistoryStorageId(mediaId: String, isLocal: Boolean): String {
    val number = mediaId.toLongOrNull()
    return if (isLocal && number != null && number < 0 && number != Long.MIN_VALUE) {
        "local_${-number}"
    } else mediaId
}
