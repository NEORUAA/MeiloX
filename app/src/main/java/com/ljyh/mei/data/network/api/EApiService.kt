package com.ljyh.mei.data.network.api

import com.ljyh.mei.data.model.AlbumPhoto
import com.ljyh.mei.data.model.api.BaseResponse
import com.ljyh.mei.data.model.eapi.HomePageResourceShow
import com.ljyh.mei.data.model.api.GetUserPhotoAlbum
import com.ljyh.mei.data.model.api.EApiSubscribePlaylist
import com.ljyh.mei.data.model.weapi.GetHomePageResourceShow
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST


interface EApiService {
    @POST("/eapi/link/page/rcmd/resource/show")
    suspend fun getHomePageResourceShow(@Body body: GetHomePageResourceShow): HomePageResourceShow

    @POST("/eapi/search/pc/complex/page/v3")
    suspend fun search(@Body body: GetUserPhotoAlbum): AlbumPhoto

    @Headers("X-Netease-Crypto: eapi", "X-Netease-Check-Token: true")
    @POST("/api/playlist/subscribe")
    suspend fun subscribePlaylist(
        @Body body: EApiSubscribePlaylist,
        @Header("X-Netease-Anti-Cheat-Token") antiCheatToken: String,
    ): BaseResponse

    @Headers("X-Netease-Crypto: eapi", "X-Netease-Check-Token: true")
    @POST("/api/playlist/unsubscribe")
    suspend fun unSubscribePlaylist(
        @Body body: EApiSubscribePlaylist,
        @Header("X-Netease-Anti-Cheat-Token") antiCheatToken: String,
    ): BaseResponse
}
