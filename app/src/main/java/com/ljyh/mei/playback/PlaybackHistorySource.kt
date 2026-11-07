package com.ljyh.mei.playback

import com.ljyh.mei.source.api.PlaybackResolution
import com.ljyh.mei.source.api.TrackRef

/** Device-library media IDs use a negative number while Room retains its local_ identity. */
internal fun playbackHistoryStorageId(mediaId: String, isLocal: Boolean): String {
    val number = mediaId.toLongOrNull()
    return if (isLocal && number != null && number < 0 && number != Long.MIN_VALUE) {
        "local_${-number}"
    } else mediaId
}

/** Reports belong to the original content owner and never to a cross-source audio substitute. */
internal fun canReportProviderPlayback(origin: TrackRef, resolution: PlaybackResolution?): Boolean =
    resolution == null || resolution.actualAudioProviderId == origin.providerId && resolution.resolvedTrackRef == origin
