package com.ljyh.mei.playback

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import java.util.UUID

private const val QUEUE_ENTRY_ID = "mei.queueEntryId"

internal val MediaItem.queueEntryId: String?
    get() = mediaMetadata.extras?.getString(QUEUE_ENTRY_ID)

internal fun MediaItem.withQueueEntryId(id: String = UUID.randomUUID().toString()): MediaItem =
    buildUpon().setMediaMetadata(
        mediaMetadata.buildUpon().setExtras(
            Bundle(mediaMetadata.extras ?: Bundle()).apply { putString(QUEUE_ENTRY_ID, id) },
        ).build(),
    ).build()

internal fun Player.playbackOrderIndices(shuffle: Boolean = shuffleModeEnabled): List<Int> {
    val timeline = currentTimeline
    val result = ArrayList<Int>(timeline.windowCount)
    var index = timeline.getFirstWindowIndex(shuffle)
    while (index != C.INDEX_UNSET && result.size < timeline.windowCount) {
        result.add(index)
        index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
    }
    return result
}

internal fun List<Int?>.isPlaybackPermutation(size: Int): Boolean =
    this.size == size && toSet().size == size && all { it != null && it in 0 until size }
