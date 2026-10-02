package com.ljyh.mei.data.network.api


import com.ljyh.mei.data.model.api.GetFloorComment
import com.ljyh.mei.data.model.weapi.EveryDaySongs
import com.ljyh.mei.data.model.weapi.FloorComment
import com.ljyh.mei.data.model.weapi.HighQualityPlaylist
import com.ljyh.mei.data.model.weapi.HighQualityPlaylistResult
import com.ljyh.mei.data.model.weapi.Radio
import com.ljyh.mei.data.model.weapi.UserSubcount
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Tag
import com.ljyh.mei.data.session.SessionStamp

interface WeApiService {

    // weapi 其实也是api开头的，但是为了拦截器区分，所以使用weapi开头
    @POST("/weapi/v3/discovery/recommend/songs")
    suspend fun getEveryDayRecommendSongs(
        @Body body: Map<String, String>,
        @Tag expectedSession: SessionStamp,
    ): EveryDaySongs

    @POST("/weapi/subcount")
    suspend fun getUserSubcount(
        @Body body: Map<String, String> = mapOf(),
        @Tag expectedSession: SessionStamp? = null,
    ): UserSubcount

    @POST("/api/playlist/highquality/list")
    suspend fun getHighQualityPlaylist(@Body body: HighQualityPlaylist, @Tag expectedSession: SessionStamp): HighQualityPlaylistResult

    @POST("/weapi/v1/radio/get")
    suspend fun getRadio(@Body body: Map<String, String>, @Tag expectedSession: SessionStamp): Radio

    @POST("/weapi/resource/comment/floor/get")
    suspend fun getFloorComment(@Body body: GetFloorComment, @Tag expectedSession: SessionStamp): FloorComment
}
