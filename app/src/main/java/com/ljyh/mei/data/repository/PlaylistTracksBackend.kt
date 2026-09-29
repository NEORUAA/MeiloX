package com.ljyh.mei.data.repository

import com.ljyh.mei.data.model.api.ManipulateTrackResult
import com.ljyh.mei.data.session.SessionStamp

fun interface PlaylistTracksBackend {
    suspend fun modify(op: String, playlistId: Long, trackIds: List<Long>, owner: SessionStamp): ManipulateTrackResult
}
