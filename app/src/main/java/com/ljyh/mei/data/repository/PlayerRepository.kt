package com.ljyh.mei.data.repository

import com.ljyh.mei.data.model.Lyric
import com.ljyh.mei.data.model.Tracks
import com.ljyh.mei.data.model.api.GetIntelligence
import com.ljyh.mei.data.model.api.GetLyric
import com.ljyh.mei.data.model.api.GetSongDetails
import com.ljyh.mei.data.model.api.Intelligence
import com.ljyh.mei.data.model.qq.u.GetLyricData
import com.ljyh.mei.data.model.qq.u.GetSearchData
import com.ljyh.mei.data.model.qq.u.LyricResult
import com.ljyh.mei.data.model.qq.u.SearchResult
import com.ljyh.mei.data.model.weapi.Radio
import com.ljyh.mei.data.network.QQMusicUApiService
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.network.safeApiCall
import android.util.Base64
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionStamp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal interface PlayerLikeSource {
    suspend fun checkSongLike(id: Long, owner: SessionStamp): Resource<Boolean>
    suspend fun like(id: Long, liked: Boolean, owner: SessionStamp): Resource<Boolean>
}

class PlayerRepository(
    private val qqMusicUApiService: QQMusicUApiService,
    private val apiService: ApiService,
    private val weApiService: WeApiService,
    private val sessions: SessionStore,
    private val favorites: SongFavoritesBackend,
    private val lyrics: SongLyricBackend = SongLyricBackend(apiService, sessions),
    private val amllClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) : PlayerLikeSource {

    suspend fun searchNew(keyword: String): Resource<SearchResult> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                qqMusicUApiService.search(
                    GetSearchData(
                        comm = GetSearchData.Comm(),
                        req = GetSearchData.Req(param = GetSearchData.Req.Param(query = keyword))
                    )
                )
            }
        }
    }

    private fun b64encode(str: String): String {
        return Base64.encodeToString(str.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    suspend fun getLyricNew(
        title: String,
        album: String,
        artist: String,
        duration: Long,
        id: Long
    ): Resource<LyricResult> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                qqMusicUApiService.getLyric(
                    GetLyricData(
                        comm = GetLyricData.Comm(),
                        getPlayLyricInfo = GetLyricData.GetPlayLyricInfo(
                            param = GetLyricData.GetPlayLyricInfo.GetLyric(
                                singerName = b64encode(artist),
                                songName = b64encode(title),
                                albumName = b64encode(album),
                                interval = duration,
                                songID = id
                            )
                        )
                    )
                )
            }
        }
    }

    suspend fun getLyricLrc(
        title: String,
        album: String,
        artist: String,
        duration: Long,
        id: Long
    ): Resource<LyricResult> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                qqMusicUApiService.getLyric(
                    GetLyricData(
                        comm = GetLyricData.Comm(),
                        getPlayLyricInfo = GetLyricData.GetPlayLyricInfo(
                            param = GetLyricData.GetPlayLyricInfo.GetLyric(
                                singerName = b64encode(artist),
                                songName = b64encode(title),
                                albumName = b64encode(album),
                                interval = duration,
                                songID = id,
                                qrc = 0,
                                qrcT = 0
                            )
                        )
                    )
                )
            }
        }
    }

    suspend fun getLyric(id: String): Resource<Lyric> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                apiService.getLyric(
                    GetLyric(
                        id = id
                    )
                )
            }
        }
    }


    suspend fun getLyricV1(id: String, owner: SessionStamp = sessions.snapshot()): Resource<Lyric> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                lyrics.lyrics(id, owner)
            }
        }
    }


    override suspend fun like(id: Long, liked: Boolean, owner: SessionStamp): Resource<Boolean> =
        withContext(Dispatchers.IO) {
            safeApiCall {
                requireLikeOwner(id, owner)
                favorites.setLiked(id, liked, owner).also {
                    currentCoroutineContext().ensureActive()
                    sessions.requireCurrent(owner)
                }
            }
        }

    suspend fun getAMLLyric(id: String): Resource<String> {
        return suspendCancellableCoroutine { continuation ->
            val url = "https://amlldb.bikonoo.com/ncm-lyrics/$id.ttml"
            val request = Request.Builder().url(url).build()
            val call = amllClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(Resource.Error("网络异常，请检查你的网络连接"))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            if (response.isSuccessful) {
                                val lyricContent = response.body?.string()
                                if (!lyricContent.isNullOrEmpty() && lyricContent != "歌词不存在") {
                                    Resource.Success(lyricContent)
                                } else {
                                    Resource.Error("歌词不存在")
                                }
                            } else if (response.code == 404) {
                                Resource.Error("歌词不存在")
                            } else {
                                Resource.Error("请求失败，错误码: ${response.code}")
                            }
                        }
                    } catch (_: IOException) {
                        Resource.Error("网络异常，请检查你的网络连接")
                    }
                    continuation.resume(result)
                }
            })
        }
    }

    suspend fun getRadio(): Resource<Radio>{
        return withContext(Dispatchers.IO){
            safeApiCall {
                weApiService.getRadio()
            }
        }
    }


    suspend fun getIntelligenceList(id: String, playlistId: String, startSongId:String): Resource<Intelligence>{
        return withContext(Dispatchers.IO){
            safeApiCall {
                apiService.getIntelligenceList(
                    GetIntelligence(
                        songId = id,
                        playlistId = playlistId,
                        startMusicId = startSongId
                    )
                )
            }
        }
    }

    suspend fun getSongDetail(id: String): Resource<Tracks>{
        return withContext(Dispatchers.IO){
            safeApiCall {
                apiService.getSongDetail(
                    GetSongDetails(id)
                )
            }
        }
    }

    override suspend fun checkSongLike(id: Long, owner: SessionStamp): Resource<Boolean> =
        withContext(Dispatchers.IO) { safeApiCall { readLike(id, owner) } }

    private suspend fun readLike(id: Long, owner: SessionStamp): Boolean {
        requireLikeOwner(id, owner)
        return favorites.isLiked(id, owner).also {
            currentCoroutineContext().ensureActive()
            sessions.requireCurrent(owner)
        }
    }

    private fun requireLikeOwner(id: Long, owner: SessionStamp) {
        require(id > 0)
        check(owner.identity.authenticated && !owner.identity.anonymous && owner.identity.userId > 0) { "Sign-in required" }
        sessions.requireCurrent(owner)
    }
}
