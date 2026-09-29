package com.ljyh.mei.data.repository

import com.ljyh.mei.data.model.api.BaseResponse
import com.ljyh.mei.data.session.SessionStamp

/** Runtime-owned routes and security parameters for the shared collection action. */
fun interface PlaylistCollectionBackend {
    suspend fun setCollected(id: Long, collected: Boolean, owner: SessionStamp): BaseResponse
}
