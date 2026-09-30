package com.ljyh.mei.data.repository

import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.session.SessionStamp

/** Each runtime validates its own favorite response and reconciliation semantics. */
interface SongFavoritesBackend {
    suspend fun isLiked(source: SongSourceIdentity, owner: SessionStamp): Boolean
    suspend fun setLiked(source: SongSourceIdentity, liked: Boolean, owner: SessionStamp): Boolean

    suspend fun isLiked(id: Long, owner: SessionStamp): Boolean =
        isLiked(SongSourceIdentity(id), owner)

    suspend fun setLiked(id: Long, liked: Boolean, owner: SessionStamp): Boolean =
        setLiked(SongSourceIdentity(id), liked, owner)
}
