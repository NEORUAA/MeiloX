package com.ljyh.mei.data.repository

import com.ljyh.mei.data.model.melox.CloudMusicPage
import com.ljyh.mei.data.session.SessionStamp

interface CloudMusicSource {
    suspend fun cloudSongs(session: SessionStamp): CloudMusicPage
    suspend fun deleteCloudSong(session: SessionStamp, id: Long)
    suspend fun uploadCloudSong(session: SessionStamp, uri: String, onProgress: (Long, Long) -> Unit)
}
