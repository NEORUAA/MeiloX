package com.ljyh.mei.parasite

import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.DownloadUrlResponse
import com.ljyh.mei.data.model.api.GetDownloadUrl
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.playback.DownloadSourceBackend
import com.ljyh.mei.playback.resolveOfficialDownloadSources
import javax.inject.Inject
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Tag

internal class HostDownloadSourceBackend @Inject constructor(
    retrofit: Retrofit,
    private val sessions: SessionStore,
) : DownloadSourceBackend {
    private val api = retrofit.create(HostDownloadApi::class.java)
    override val failureTitle: String get() = "获取官方下载授权失败"

    override suspend fun resolve(ids: List<String>, quality: MusicQuality, owner: SessionStamp) =
        resolveOfficialDownloadSources(api, sessions, ids, quality, owner)
}

internal interface HostDownloadApi {
    @Headers("X-Netease-Crypto: eapi")
    @POST("/api/song/enhance/download/url/v1")
    suspend fun getDownloadUrl(@Body body: GetDownloadUrl, @Tag owner: SessionStamp): DownloadUrlResponse
}
