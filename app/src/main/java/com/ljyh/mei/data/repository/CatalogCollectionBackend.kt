package com.ljyh.mei.data.repository

import com.ljyh.mei.data.model.api.BaseResponse
import com.ljyh.mei.data.session.SessionStamp

/** Account-owned collection state, independent of each runtime's response schema. */
interface CatalogCollectionBackend {
    suspend fun albumCollected(id: Long, owner: SessionStamp): Boolean
    suspend fun artistFollowed(id: Long, owner: SessionStamp): Boolean
    suspend fun setArtistFollowed(id: Long, followed: Boolean, owner: SessionStamp): BaseResponse
}
