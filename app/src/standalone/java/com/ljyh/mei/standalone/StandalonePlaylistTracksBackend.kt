package com.ljyh.mei.standalone

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
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

internal class StandalonePlaylistTracksBackend @Inject constructor(retrofit: Retrofit, private val sessions: SessionStore) : PlaylistTracksBackend {
    private val api = retrofit.create(StandalonePlaylistTracksApi::class.java)

    override suspend fun modifySources(op: String, playlistId: Long, sources: List<SongSourceIdentity>, owner: SessionStamp): ManipulateTrackResult {
        sessions.requirePlaylistMutationOwner(owner)
        require(op == "add" || op == "del")
        require(playlistId > 0 && sources.isNotEmpty())
        sources.forEach { it.requireAccount(owner.identity) }
        val trackIds = Gson().toJson(sources.map { it.songId.toString() }.distinct())
        return api.modify(StandaloneTrackMutation(op, playlistId.toString(), trackIds), owner).also {
            currentCoroutineContext().ensureActive()
            sessions.requirePlaylistMutationOwner(owner)
        }
    }
}

internal interface StandalonePlaylistTracksApi {
    @POST("/api/playlist/manipulate/tracks")
    suspend fun modify(@Body body: StandaloneTrackMutation, @Tag owner: SessionStamp): ManipulateTrackResult
}

internal data class StandaloneTrackMutation(
    @SerializedName("op") val op: String,
    @SerializedName("pid") val pid: String,
    @SerializedName("trackIds") val trackIds: String,
    @SerializedName("imme") val imme: Boolean = true,
)
