package com.ljyh.mei.ui.screen.playlist

import com.ljyh.mei.data.model.MediaMetadata

data class PlaylistOrderItem(val id: Long, val track: MediaMetadata?)

enum class PlaylistOrderError { LoadFailed, SaveFailed, PlaylistChanged }

data class PlaylistOrderState(
    val playlistId: String,
    val items: List<PlaylistOrderItem> = emptyList(),
    val originalIds: List<Long> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: PlaylistOrderError? = null,
)

internal fun playlistOrderItems(ids: List<Long>, tracks: List<MediaMetadata>): List<PlaylistOrderItem> {
    val byId = tracks.associateBy { it.id }
    return ids.map { PlaylistOrderItem(it, byId[it]) }
}
