package com.ljyh.mei.data.model

class PlaylistOrderChangedException : IllegalStateException("Playlist changed during order editing")

/** An order update may permute IDs, but must never add, omit, or duplicate them. */
internal fun validatePlaylistTrackOrder(
    originalIds: List<Long>,
    orderedIds: List<Long>,
    currentIds: List<Long>,
) {
    require(originalIds.all { it > 0 } && originalIds.distinct().size == originalIds.size) {
        "Playlist IDs are invalid or duplicated"
    }
    require(orderedIds.size == originalIds.size && orderedIds.toSet() == originalIds.toSet()) {
        "Playlist order must preserve every track ID exactly once"
    }
    if (currentIds != originalIds) throw PlaylistOrderChangedException()
}
