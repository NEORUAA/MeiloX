package com.ljyh.mei.parasite

import com.ljyh.mei.data.model.api.BaseResponse
import com.ljyh.mei.data.model.api.EApiSubscribePlaylist
import com.ljyh.mei.data.repository.PlaylistCollectionBackend
import com.ljyh.mei.data.session.SessionStamp
import javax.inject.Inject
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Tag

internal class HostPlaylistCollectionBackend @Inject constructor(retrofit: Retrofit) : PlaylistCollectionBackend {
    private val api = retrofit.create(HostPlaylistCollectionApi::class.java)

    override suspend fun setCollected(id: Long, collected: Boolean, owner: SessionStamp): BaseResponse =
        if (collected) api.subscribe(EApiSubscribePlaylist(id), owner)
        else api.unsubscribe(EApiSubscribePlaylist(id), owner)
}

internal interface HostPlaylistCollectionApi {
    @POST("/api/multi/terminal/playlist/subscribe")
    suspend fun subscribe(@Body body: EApiSubscribePlaylist, @Tag owner: SessionStamp): BaseResponse

    @POST("/api/multi/terminal/playlist/unsubscribe")
    suspend fun unsubscribe(@Body body: EApiSubscribePlaylist, @Tag owner: SessionStamp): BaseResponse
}
