package com.ljyh.mei.playback

import com.ljyh.mei.source.api.PlaybackResolution
import com.ljyh.mei.source.api.ProviderIds
import com.ljyh.mei.source.api.TrackRef

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

/** Platform reports describe official playback, while local history always retains origin identity. */
internal fun canReportNeteasePlayback(origin: TrackRef, resolution: PlaybackResolution?): Boolean =
    origin.providerId == ProviderIds.NETEASE &&
        (resolution == null || resolution.actualAudioProviderId == ProviderIds.NETEASE &&
            resolution.resolvedTrackRef == origin)
