package com.ljyh.mei.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.data.model.sourceKey

/** The timer distinguishes private files even when their UI entry IDs are equal. */
@OptIn(UnstableApi::class)
internal val MediaItem.playbackHistoryKey: String?
    get() = if (metadata?.isLocal == true || metadata?.isPodcast == true) mediaId else
        runCatching { SongSourceIdentity.fromKey(sourceKey).key }.getOrNull()

@OptIn(UnstableApi::class)
internal fun MediaItem.playbackHistorySourceIdentityOrNull(): SongSourceIdentity? {
    val metadata = metadata ?: return null
    if (metadata.isLocal || metadata.isPodcast) return null
    return runCatching { SongSourceIdentity.fromKey(sourceKey) }.getOrNull()
}
