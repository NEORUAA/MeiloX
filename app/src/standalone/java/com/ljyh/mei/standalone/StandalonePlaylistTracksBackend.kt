package com.ljyh.mei.standalone

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.ljyh.mei.data.model.api.ManipulateTrackResult
import com.ljyh.mei.data.repository.PlaylistTracksBackend
import com.ljyh.mei.data.session.SessionStamp
import javax.inject.Inject
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Tag

internal class StandalonePlaylistTracksBackend @Inject constructor(retrofit: Retrofit) : PlaylistTracksBackend {
    private val api = retrofit.create(StandalonePlaylistTracksApi::class.java)

    override suspend fun modify(op: String, playlistId: Long, trackIds: List<Long>, owner: SessionStamp): ManipulateTrackResult =
        api.modify(StandaloneTrackMutation(op, playlistId.toString(), Gson().toJson(trackIds.map(Long::toString))), owner)
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
