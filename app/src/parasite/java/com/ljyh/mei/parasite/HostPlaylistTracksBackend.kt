package com.ljyh.mei.parasite

import com.ljyh.mei.data.model.api.ManipulateTrack
import com.ljyh.mei.data.model.api.ManipulateTrackResult
import com.ljyh.mei.data.repository.PlaylistTracksBackend
import com.ljyh.mei.data.session.SessionStamp
import javax.inject.Inject
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Tag

internal class HostPlaylistTracksBackend @Inject constructor(retrofit: Retrofit) : PlaylistTracksBackend {
    private val api = retrofit.create(HostPlaylistTracksApi::class.java)

    override suspend fun modify(op: String, playlistId: Long, trackIds: List<Long>, owner: SessionStamp): ManipulateTrackResult =
        api.modify(ManipulateTrack(op, playlistId.toString(), trackIds.joinToString(","), if (op == "add") true else null), owner)
}

internal interface HostPlaylistTracksApi {
    @POST("/api/v1/playlist/manipulate/tracks")
    suspend fun modify(@Body body: ManipulateTrack, @Tag owner: SessionStamp): ManipulateTrackResult
}
