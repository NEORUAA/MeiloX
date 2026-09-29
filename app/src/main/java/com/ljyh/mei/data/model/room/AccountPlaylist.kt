package com.ljyh.mei.data.model.room

import androidx.room.Embedded
import androidx.room.Entity

@Entity(tableName = "account_playlist", primaryKeys = ["accountId", "playlistId"])
data class AccountPlaylistMembership(
    val accountId: String,
    val playlistId: String,
    val position: Int,
    val isLiked: Boolean,
)

data class AccountPlaylist(
    @Embedded val playlist: Playlist,
    val isLiked: Boolean,
)
