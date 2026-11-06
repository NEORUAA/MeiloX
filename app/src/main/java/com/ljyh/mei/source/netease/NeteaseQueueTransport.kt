package com.ljyh.mei.source.netease

import com.ljyh.mei.data.model.api.GetRandomPlaylist
import com.ljyh.mei.data.model.api.GetSongDetails
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.WeApiService
import javax.inject.Inject
import javax.inject.Singleton

/** NetEase-only roaming and cloud shuffle operations attached to the native player features. */
@Singleton
class NeteaseQueueTransport @Inject constructor(private val api: ApiService, private val weapi: WeApiService) {
    suspend fun getRandomPlaylist(request: GetRandomPlaylist) = api.getRandomPlaylist(request)
    suspend fun getSongDetail(request: GetSongDetails) = api.getSongDetail(request)
    suspend fun getRadio(body: Map<String, String>) = weapi.getRadio(body)
    suspend fun getPersonalRadio(body: Map<String, String>) = weapi.getPersonalRadio(body)
    suspend fun getPrivateFmModes(body: Map<String, String>) = weapi.getPrivateFmModes(body)
}

/** Listen Together accepts native song identities and retains its existing account session. */
@Singleton
class NeteaseListenTogetherTransport @Inject constructor(private val catalog: NeteaseCatalogTransport) {
    suspend fun getSongDetail(request: GetSongDetails) = catalog.getSongDetail(request)
}
