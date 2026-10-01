package com.ljyh.mei.data.repository

import com.ljyh.mei.data.model.melox.ListenTogetherCommand
import com.ljyh.mei.data.model.melox.ListenTogetherPlaybackSnapshot
import com.ljyh.mei.data.model.melox.ListenTogetherRoom
import com.ljyh.mei.data.model.melox.ListenTogetherStatus
import com.ljyh.mei.data.session.SessionStamp

interface ListenTogetherSource {
    suspend fun listenTogetherStatus(session: SessionStamp): ListenTogetherStatus
    suspend fun createListenTogetherRoom(session: SessionStamp): ListenTogetherRoom
    suspend fun checkListenTogetherRoom(session: SessionStamp, roomId: String): Pair<Boolean, String?>
    suspend fun acceptListenTogetherRoom(session: SessionStamp, roomId: String, inviterId: String): ListenTogetherRoom
    suspend fun reportListenTogetherCommand(session: SessionStamp, roomId: String, command: ListenTogetherCommand,
        progressMs: Long, isPlaying: Boolean, formerSongId: Long?, targetSongId: Long, clientSequence: Long)
    suspend fun listenTogetherPlayback(session: SessionStamp, roomId: String): ListenTogetherPlaybackSnapshot
    suspend fun reportListenTogetherPlaylist(session: SessionStamp, roomId: String, version: Long,
        displaySongIds: List<Long>, randomSongIds: List<Long>)
    suspend fun sendListenTogetherHeartbeat(session: SessionStamp, roomId: String, songId: Long,
        isPlaying: Boolean, progressMs: Long): Int?
    suspend fun endListenTogetherRoom(session: SessionStamp, roomId: String)
}
