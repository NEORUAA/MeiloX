package com.ljyh.mei.data.repository

import com.ljyh.mei.data.session.SessionStamp

/** Each runtime validates its own favorite response and reconciliation semantics. */
interface SongFavoritesBackend {
    suspend fun isLiked(id: Long, owner: SessionStamp): Boolean
    suspend fun setLiked(id: Long, liked: Boolean, owner: SessionStamp): Boolean
}
