package com.ljyh.mei.data.repository

import com.ljyh.mei.data.model.Lyric
import com.ljyh.mei.data.model.Tracks
import com.ljyh.mei.data.model.api.GetIntelligence
import com.ljyh.mei.data.model.api.GetLyric
import com.ljyh.mei.data.model.api.GetLyricV1
import com.ljyh.mei.data.model.api.GetSongDetails
import com.ljyh.mei.data.model.api.Intelligence
import com.ljyh.mei.data.model.qq.u.GetLyricData
import com.ljyh.mei.data.model.qq.u.GetSearchData
import com.ljyh.mei.data.model.qq.u.LyricResult
import com.ljyh.mei.data.model.qq.u.SearchResult
import com.ljyh.mei.data.model.weapi.Like
import com.ljyh.mei.data.model.weapi.Radio
import com.ljyh.mei.data.network.QQMusicUApiService
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.network.safeApiCall
import android.util.Base64
import com.ljyh.mei.data.model.api.CheckSongLike
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import java.util.concurrent.TimeUnit

class PlayerRepository(
    private val qqMusicUApiService: QQMusicUApiService,
    private val apiService: ApiService,
    private val weApiService: WeApiService
) {
    private val likeMutex = Mutex()
    private val likeStateLock = Any()
    private var likeGeneration = 0L
    private val _songLikes = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val songLikes: StateFlow<Map<String, Boolean>> = _songLikes.asStateFlow()
    private val _songLikeGeneration = MutableStateFlow(0L)
    val songLikeGeneration: StateFlow<Long> = _songLikeGeneration.asStateFlow()

    fun resetLikeState() {
        synchronized(likeStateLock) {
            likeGeneration++
            _songLikes.value = emptyMap()
            _songLikeGeneration.value++
        }
    }


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


    suspend fun getLyricV1(id: String): Resource<Lyric> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                apiService.getLyricV1(
                    GetLyricV1(
                        id = id
                    )
                )
            }
        }
    }


    suspend fun toggleLike(id: String, expectedSession: String): Resource<Boolean> {
        if (expectedSession.isBlank()) return Resource.Error("An authenticated session is required")
        val songId = id.toLongOrNull()?.takeIf { it > 0 }
            ?: return Resource.Error("Invalid song ID")
        val key = songId.toString()
        val generation = synchronized(likeStateLock) { likeGeneration }
        return withContext(Dispatchers.IO) {
            likeMutex.withLock {
                if (!isLikeGenerationCurrent(generation)) return@withLock accountChangedLikeResult()
                val current = _songLikes.value[key] ?: when (val status = fetchSongLike(songId, expectedSession)) {
                    is Resource.Success -> {
                        if (!publishSongLike(key, status.data, generation)) {
                            return@withLock accountChangedLikeResult()
                        }
                        status.data
                    }
                    is Resource.Error -> return@withLock status
                    Resource.Loading -> return@withLock Resource.Loading
                }
                if (!isLikeGenerationCurrent(generation)) return@withLock accountChangedLikeResult()
                val result = safeApiCall {
                    val response = apiService.like(Like(trackId = key, like = !current), expectedSession)
                    check(response.code == 200) { "Unable to update song like (${response.code})" }
                    !current
                }
                if (result is Resource.Success && !publishSongLike(key, result.data, generation)) {
                    return@withLock accountChangedLikeResult()
                }
                result
            }
        }
    }

    private val amllClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun getAMLLyric(id: String): Resource<String> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://amlldb.bikonoo.com/ncm-lyrics/$id.ttml"
                val request = Request.Builder().url(url).build()

                val result = amllClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val lyricContent = response.body?.string()
                        if (!lyricContent.isNullOrEmpty() && lyricContent != "歌词不存在") {
                            Resource.Success(lyricContent)
                        } else {
                            Resource.Error("歌词不存在")
                        }
                    } else {
                        if (response.code == 404) {
                            Resource.Error("歌词不存在")
                        } else {
                            Resource.Error("请求失败，错误码: ${response.code}")
                        }
                    }
                }
                result
            } catch (e: IOException) {
                Resource.Error("网络异常，请检查你的网络连接")
            }
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

    suspend fun checkSongLike(id: Long, expectedSession: String? = null): Resource<Boolean> {
        if (id <= 0) return Resource.Error("Invalid song ID")
        val generation = synchronized(likeStateLock) { likeGeneration }
        return withContext(Dispatchers.IO) {
            likeMutex.withLock {
                if (!isLikeGenerationCurrent(generation)) return@withLock accountChangedLikeResult()
                val result = fetchSongLike(id, expectedSession)
                if (result is Resource.Success && !publishSongLike(id.toString(), result.data, generation)) {
                    return@withLock accountChangedLikeResult()
                }
                result
            }
        }
    }

    suspend fun ensureSongLikes(ids: Set<Long>, expectedSession: String): Resource<Unit> =
        loadSongLikes(ids, expectedSession, refresh = false)

    suspend fun refreshSongLikes(ids: Set<Long>, expectedSession: String): Resource<Unit> =
        loadSongLikes(ids, expectedSession, refresh = true)

    private suspend fun loadSongLikes(
        ids: Set<Long>,
        expectedSession: String,
        refresh: Boolean,
    ): Resource<Unit> {
        if (expectedSession.isBlank()) return Resource.Error("An authenticated session is required")
        val generation = synchronized(likeStateLock) { likeGeneration }
        return withContext(Dispatchers.IO) {
            likeMutex.withLock {
                currentCoroutineContext().ensureActive()
                if (!isLikeGenerationCurrent(generation)) return@withLock accountChangedLikeResult()
                val requestedIds = ids.filter { it > 0 && (refresh || it.toString() !in _songLikes.value) }.sorted()
                for (chunk in requestedIds.chunked(100)) {
                    currentCoroutineContext().ensureActive()
                    if (!isLikeGenerationCurrent(generation)) return@withLock accountChangedLikeResult()
                    val result = safeApiCall {
                        val response = apiService.checkSongLike(
                            CheckSongLike(chunk.joinToString(prefix = "[", postfix = "]")),
                            expectedSession,
                        )
                        check(response.code == 200) { "Unable to load song likes (${response.code})" }
                        val likedIds = response.ids.toSet()
                        chunk.associate { it.toString() to (it in likedIds) }
                    }
                    when (result) {
                        is Resource.Success -> {
                            currentCoroutineContext().ensureActive()
                            if (!publishSongLikes(result.data, generation)) return@withLock accountChangedLikeResult()
                        }
                        is Resource.Error -> {
                            currentCoroutineContext().ensureActive()
                            if (refresh) invalidateSongLikes(requestedIds, generation)
                            return@withLock result
                        }
                        Resource.Loading -> return@withLock Resource.Loading
                    }
                }
                Resource.Success(Unit)
            }
        }
    }

    private suspend fun fetchSongLike(id: Long, expectedSession: String?): Resource<Boolean> = safeApiCall {
        val result = apiService.checkSongLike(CheckSongLike("[$id]"), expectedSession)
        check(result.code == 200) { "Unable to load song like (${result.code})" }
        result.ids.contains(id)
    }

    private fun isLikeGenerationCurrent(generation: Long): Boolean =
        synchronized(likeStateLock) { likeGeneration == generation }

    private fun publishSongLike(id: String, liked: Boolean, generation: Long): Boolean =
        synchronized(likeStateLock) {
            if (likeGeneration != generation) return@synchronized false
            _songLikes.value = _songLikes.value + (id to liked)
            true
        }

    private fun publishSongLikes(likes: Map<String, Boolean>, generation: Long): Boolean =
        synchronized(likeStateLock) {
            if (likeGeneration != generation) return@synchronized false
            _songLikes.value = _songLikes.value + likes
            true
        }

    private fun invalidateSongLikes(ids: List<Long>, generation: Long) {
        synchronized(likeStateLock) {
            if (likeGeneration != generation) return
            _songLikes.value = _songLikes.value - ids.map(Long::toString).toSet()
            // Wake list observers even when the affected IDs were already unknown.
            _songLikeGeneration.value++
        }
    }

    private fun accountChangedLikeResult() = Resource.Error("Account changed")

}
