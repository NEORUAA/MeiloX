package com.ljyh.mei.data.model.api

import com.ljyh.mei.data.model.PlaylistDetail

data class GetRandomPlaylist(
    val playlistId: Long,
    val songId: Long,
    val alg: String = "",
)

data class RandomPlaylistResponse(
    val code: Int = 0,
    val data: RandomPlaylistData? = null,
)

data class RandomPlaylistData(
    val songIds: List<RandomPlaylistSong>? = null,
    val songData: List<PlaylistDetail.Playlist.Track>? = null,
    val privileges: List<RandomPlaylistPrivilege>? = null,
)

data class RandomPlaylistSong(val id: Long = 0, val alg: String? = null)

data class RandomPlaylistPrivilege(
    val id: Long = 0,
    val st: Int? = null,
    val pl: Int? = null,
    val playMaxBrLevel: String? = null,
) {
    val isUnavailable: Boolean
        get() = (st != null && st < 0) ||
            (pl == 0 && (playMaxBrLevel.isNullOrBlank() || playMaxBrLevel == "none"))
}
