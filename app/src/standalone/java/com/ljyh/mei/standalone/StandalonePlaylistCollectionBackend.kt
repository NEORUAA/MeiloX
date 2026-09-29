package com.ljyh.mei.standalone

import com.ljyh.mei.constants.checkToken
import com.ljyh.mei.data.model.api.BaseResponse
import com.ljyh.mei.data.model.api.EApiSubscribePlaylist
import com.ljyh.mei.data.repository.PlaylistCollectionBackend
import com.ljyh.mei.data.session.SessionStamp
import javax.inject.Inject
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Tag

internal class StandalonePlaylistCollectionBackend @Inject constructor(retrofit: Retrofit) : PlaylistCollectionBackend {
    private val api = retrofit.create(StandalonePlaylistCollectionApi::class.java)

    override suspend fun setCollected(id: Long, collected: Boolean, owner: SessionStamp): BaseResponse =
        if (collected) api.subscribe(EApiSubscribePlaylist(id, checkToken), owner)
        else api.unsubscribe(EApiSubscribePlaylist(id), owner)
}

internal interface StandalonePlaylistCollectionApi {
    @Headers("X-Netease-Crypto: eapi", "X-Netease-Check-Token: true")
    @POST("/api/playlist/subscribe")
    suspend fun subscribe(@Body body: EApiSubscribePlaylist, @Tag owner: SessionStamp): BaseResponse

    @Headers("X-Netease-Crypto: eapi", "X-Netease-Check-Token: true")
    @POST("/api/playlist/unsubscribe")
    suspend fun unsubscribe(@Body body: EApiSubscribePlaylist, @Tag owner: SessionStamp): BaseResponse
}
