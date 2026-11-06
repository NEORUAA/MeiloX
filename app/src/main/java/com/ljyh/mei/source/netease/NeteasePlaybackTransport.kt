package com.ljyh.mei.source.netease

import com.ljyh.mei.data.model.SongUrl
import com.ljyh.mei.data.model.Tracks
import com.ljyh.mei.data.model.api.GetSongDetails
import com.ljyh.mei.data.model.api.GetSongUrlV1
import com.ljyh.mei.data.network.api.ApiService
import javax.inject.Inject
import javax.inject.Singleton

/** Native transport retained for the player's existing quality probing and recovery policy. */
@Singleton
class NeteasePlaybackTransport @Inject constructor(private val api: ApiService) {
    suspend fun getSongUrlV1(request: GetSongUrlV1): SongUrl = api.getSongUrlV1(request)
    suspend fun getSongDetail(request: GetSongDetails): Tracks = api.getSongDetail(request)
}
