package com.ljyh.mei.ui.screen.playlist

import com.ljyh.mei.data.model.MediaMetadata

data class PlaylistOrderItem<Id : Any>(val id: Id, val track: MediaMetadata?)

enum class PlaylistOrderError { LoadFailed, SaveFailed, PlaylistChanged }

data class PlaylistOrderState<Id : Any>(
    val playlistId: String,
    val items: List<PlaylistOrderItem<Id>> = emptyList(),
    val originalIds: List<Id> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: PlaylistOrderError? = null,
)

internal fun playlistOrderItems(ids: List<Long>, tracks: List<MediaMetadata>): List<PlaylistOrderItem<Long>> {
    val byId = tracks.associateBy { it.id }
    return ids.map { PlaylistOrderItem(it, byId[it]) }
}
