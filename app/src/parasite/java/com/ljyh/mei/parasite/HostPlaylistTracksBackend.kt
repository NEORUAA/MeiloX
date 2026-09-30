package com.ljyh.mei.parasite

import com.ljyh.mei.data.model.api.ManipulateTrack
import com.ljyh.mei.data.model.api.ManipulateTrackResult
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.repository.PlaylistTracksBackend
import com.ljyh.mei.data.repository.requirePlaylistMutationOwner
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Tag

internal class HostPlaylistTracksBackend @Inject constructor(retrofit: Retrofit, private val sessions: SessionStore) : PlaylistTracksBackend {
    private val api = retrofit.create(HostPlaylistTracksApi::class.java)

    override suspend fun modifySources(op: String, playlistId: Long, sources: List<SongSourceIdentity>, owner: SessionStamp): ManipulateTrackResult {
        sessions.requirePlaylistMutationOwner(owner)
        require(op == "add" || op == "del")
        require(playlistId > 0 && sources.isNotEmpty())
        sources.forEach { it.requireAccount(owner.identity) }
        val trackIds = sources.map { it.songId }.distinct().joinToString(",")
        return api.modify(ManipulateTrack(op, playlistId.toString(), trackIds, if (op == "add") true else null), owner).also {
            currentCoroutineContext().ensureActive()
            sessions.requirePlaylistMutationOwner(owner)
        }
    }
}

internal interface HostPlaylistTracksApi {
    @POST("/api/v1/playlist/manipulate/tracks")
    suspend fun modify(@Body body: ManipulateTrack, @Tag owner: SessionStamp): ManipulateTrackResult
}
