package com.ljyh.mei.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.model.sourceKey

/** Existing string primary keys can retain cloud affinity without migrating user rows. */
@OptIn(UnstableApi::class)
internal fun MediaItem.localHistoryStorageIdOrNull(): String? {
    if (metadata?.isPodcast == true) return null
    if (metadata?.isLocal == true) {
        require(metadata?.source == null)
        return mediaId
    }
    return SongSourceIdentity.cloudFromKeyOrNull(sourceKey)?.key ?: mediaId
}

@OptIn(UnstableApi::class)
internal fun MediaItem.toLocalHistorySong(storageId: String, stored: Song?, updatedAt: Long): Song {
    require(storageId == localHistoryStorageIdOrNull() && (stored == null || stored.id == storageId))
    val platform = mediaMetadata
    val artists = platform.extras?.getStringArrayList("artist_list")?.filter(String::isNotBlank)
        ?.takeIf(List<String>::isNotEmpty)
        ?: platform.artist?.toString()?.split(Regex("\\s*[/,&、]\\s*"))?.map(String::trim)
            ?.filter(String::isNotBlank)?.takeIf(List<String>::isNotEmpty)
        ?: listOf("\u672a\u77e5\u6b4c\u624b")
    val title = platform.title?.toString().orEmpty().ifBlank { stored?.title ?: "\u672a\u77e5\u6807\u9898" }
    val album = platform.albumTitle?.toString().orEmpty().ifBlank { stored?.album ?: "\u672a\u77e5\u4e13\u8f91" }
    val cover = platform.artworkUri?.toString().orEmpty().ifBlank { stored?.cover.orEmpty() }
    val cloud = SongSourceIdentity.cloudFromKeyOrNull(storageId) != null
    // Only new canonical cloud rows store milliseconds; legacy Room units stay unchanged.
    val duration = if (cloud) metadata?.duration?.takeIf { it > 0 } ?: platform.durationMs?.takeIf { it > 0 }
        else platform.durationMs?.takeIf { it > 0 }
    return stored?.copy(title = title, artist = artists, album = album, cover = cover,
        duration = duration ?: stored.duration, updatedAt = updatedAt)
        ?: Song(storageId, title, artists, album, cover, duration ?: platform.durationMs ?: 0)
}
