package com.ljyh.mei.playback


import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.common.C

/** Preloads only the next automatic playback entry, for up to five seconds. */
@UnstableApi
class MusicPreloadStrategy : TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {

    var nextPlayingIndex: Int = C.INDEX_UNSET

    override fun getTargetPreloadStatus(rankingData: Int): DefaultPreloadManager.PreloadStatus {
        if (rankingData != C.INDEX_UNSET && rankingData == nextPlayingIndex) {
            return DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(
                0,
                5000L
            )
        }
        return DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
    }
}
