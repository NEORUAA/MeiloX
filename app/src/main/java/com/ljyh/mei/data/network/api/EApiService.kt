package com.ljyh.mei.data.network.api

import com.ljyh.mei.data.model.AlbumPhoto
import com.ljyh.mei.data.model.api.BaseResponse
import com.ljyh.mei.data.model.eapi.HomePageResourceShow
import com.ljyh.mei.data.model.api.GetUserPhotoAlbum
import com.ljyh.mei.data.model.api.EApiSubscribePlaylist
import com.ljyh.mei.data.model.weapi.GetHomePageResourceShow
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Tag
import com.ljyh.mei.data.session.SessionStamp


interface EApiService {
    @POST("/eapi/link/page/rcmd/resource/show")
    suspend fun getHomePageResourceShow(@Body body: GetHomePageResourceShow): HomePageResourceShow

    @POST("/eapi/search/pc/complex/page/v3")
    suspend fun search(@Body body: GetUserPhotoAlbum): AlbumPhoto

    @POST("/api/multi/terminal/playlist/subscribe")
    suspend fun subscribePlaylist(@Body body: EApiSubscribePlaylist, @Tag expectedSession: SessionStamp? = null): BaseResponse

    @POST("/api/multi/terminal/playlist/unsubscribe")
    suspend fun unSubscribePlaylist(@Body body: EApiSubscribePlaylist, @Tag expectedSession: SessionStamp? = null): BaseResponse
}
