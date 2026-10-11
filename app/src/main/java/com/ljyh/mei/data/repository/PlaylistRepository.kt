package com.ljyh.mei.data.repository

import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.AlbumDetail
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.PlaylistDetail
import com.ljyh.mei.data.model.SongUrl
import com.ljyh.mei.data.model.api.BaseMessageResponse
import com.ljyh.mei.data.model.api.BaseResponse
import com.ljyh.mei.data.model.api.CreatePlaylist
import com.ljyh.mei.data.model.api.CreatePlaylistResult
import com.ljyh.mei.data.model.api.DeletePlaylist
import com.ljyh.mei.data.model.api.EApiSubscribePlaylist
import com.ljyh.mei.data.model.api.GetPlaylistDetail
import com.ljyh.mei.data.model.api.GetSongDetails
import com.ljyh.mei.data.model.api.GetSongUrl
import com.ljyh.mei.data.model.api.GetSongUrlV1
import com.ljyh.mei.data.model.api.ManipulateTrack
import com.ljyh.mei.data.model.api.ManipulateTrackResult
import com.ljyh.mei.data.model.api.SubscribePlaylist
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.model.weapi.EveryDaySongs
import com.ljyh.mei.data.model.weapi.HighQualityPlaylist
import com.ljyh.mei.data.model.weapi.HighQualityPlaylistResult
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.EApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.network.safeApiCall
import com.ljyh.mei.playback.playbackQualityFallbacks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import timber.log.Timber

class PlaylistRepository(
    private val apiService: ApiService,
    private val weApiService: WeApiService,
    private val eApiService: EApiService,
    private val freshCheckToken: suspend () -> String,
    private val subscriptionAccount: () -> String,
    private val onUnsubscribed: suspend (String) -> Unit = {},
    private val subscriptionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val subscriptionLock = Any()
    private val subscriptions = mutableMapOf<SubscriptionKey, SubscriptionEntry>()

    private data class SubscriptionKey(val account: String, val playlistId: String)

    private class SubscriptionEntry {
        var revision = 0L
        var readers = 0
        var pending: SubscriptionWrite? = null
    }

    private class SubscriptionWrite(
        val subscribed: Boolean,
        val result: Deferred<Resource<BaseResponse>>,
    )

    suspend fun getPlaylistDetail(id: String): Resource<PlaylistDetail> {
        val key = SubscriptionKey(subscriptionAccount(), id)
        val entry = synchronized(subscriptionLock) {
            subscriptions.getOrPut(key, ::SubscriptionEntry).also { it.readers++ }
        }
        try {
            while (true) {
                val (pending, revision) = synchronized(subscriptionLock) {
                    entry.pending to entry.revision
                }
                if (pending != null) {
                    pending.result.join()
                    continue
                }
                val response = withContext(ioDispatcher) {
                    safeApiCall {
                        apiService.getPlaylistDetail(GetPlaylistDetail(id = id))
                    }
                }
                // A read started before a write must not publish its old subscription state.
                val current = synchronized(subscriptionLock) {
                    entry.pending == null && entry.revision == revision
                }
                if (subscriptionAccount() != key.account) {
                    return Resource.Error("NetEase account changed during the playlist request")
                }
                if (current) return response
            }
        } finally {
            synchronized(subscriptionLock) {
                entry.readers--
                removeIdleSubscription(key, entry)
            }
        }
    }

    private fun removeIdleSubscription(key: SubscriptionKey, entry: SubscriptionEntry) {
        // Keep the revision alive while a reader can still return an older response.
        if (entry.readers == 0 && entry.pending == null && subscriptions[key] === entry) {
            subscriptions.remove(key)
        }
    }

    private suspend fun changePlaylistSubscription(id: String, subscribed: Boolean): Resource<BaseResponse> {
        val key = SubscriptionKey(subscriptionAccount(), id)
        if (key.account.isBlank()) return Resource.Error("NetEase account is not signed in")
        val operation = synchronized(subscriptionLock) {
            val entry = subscriptions.getOrPut(key, ::SubscriptionEntry)
            val previous = entry.pending
            if (previous?.subscribed == subscribed && !previous.result.isCompleted) {
                previous.result
            } else {
                // Register before the caller's first suspension. Navigation can cancel its
                // await, but accepted writes belong to this singleton repository's scope.
                val result = subscriptionScope.async(start = CoroutineStart.LAZY) {
                    previous?.result?.join()
                    safeApiCall {
                        if (subscribed) {
                            val bodyToken = freshCheckToken()
                            val headerToken = freshCheckToken()
                            check(subscriptionAccount() == key.account) { "NetEase account changed before playlist subscription" }
                            eApiService.subscribePlaylist(
                                EApiSubscribePlaylist(id = id.toLong(), checkToken = bodyToken),
                                antiCheatToken = headerToken,
                            )
                        } else {
                            val headerToken = freshCheckToken()
                            check(subscriptionAccount() == key.account) { "NetEase account changed before playlist subscription" }
                            val response = eApiService.unSubscribePlaylist(
                                EApiSubscribePlaylist(id = id.toLong()),
                                antiCheatToken = headerToken,
                            )
                            if (response.code == 200) {
                                try {
                                    onUnsubscribed(id)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    // A local cleanup failure cannot undo a confirmed server write.
                                    Timber.w("Playlist local cleanup failed: %s", error.javaClass.simpleName)
                                }
                            }
                            response
                        }
                    }
                }
                val write = SubscriptionWrite(subscribed, result)
                entry.pending = write
                entry.revision++
                result.invokeOnCompletion {
                    synchronized(subscriptionLock) {
                        if (entry.pending === write) entry.pending = null
                        removeIdleSubscription(key, entry)
                    }
                }
                result
            }
        }
        operation.start()
        return operation.await()
    }

    suspend fun getCompletePlaylistTracks(detail: PlaylistDetail): List<MediaMetadata> {
        return withContext(Dispatchers.IO) {
            val playlist = detail.playlist
            val tracksById = playlist.tracks.associateBy { it.id }.toMutableMap()

            playlist.trackIds
                .map { it.id }
                .filterNot(tracksById::containsKey)
                .chunked(200)
                .forEach { ids ->
                    apiService.getSongDetail(GetSongDetails(ids.joinToString(",")))
                        .songs
                        .forEach { track -> tracksById[track.id] = track }
                }

            playlist.trackIds
                .mapNotNull { trackId -> tracksById[trackId.id] }
                .map { track -> track.toMediaMetadata() }
        }
    }

    suspend fun getSongUrl(id: String): Resource<SongUrl> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                apiService.getSongUrl(
                    GetSongUrl(
                        ids = "[$id]"
                    )
                )
            }
        }
    }

    suspend fun getSongUrlV1(ids: List<String>, quality: MusicQuality): Resource<SongUrl> {
        return withContext(Dispatchers.IO) {
            val requestedIds = ids.map(String::trim).filter(String::isNotBlank).distinct()
            if (requestedIds.isEmpty()) {
                return@withContext Resource.Success(SongUrl(code = 200, data = emptyList()))
            }

            val fullSourcesById = LinkedHashMap<String, SongUrl.Data>()
            var responseCode = 200
            for (attemptedQuality in playbackQualityFallbacks(quality.text)) {
                val response = try {
                    apiService.getSongUrlV1(
                        GetSongUrlV1(
                            ids = "[${requestedIds.joinToString(",")}]",
                            level = attemptedQuality,
                        )
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // A transport/authentication failure is not a quality fallback signal.
                    return@withContext Resource.Error(error.message ?: "Unable to resolve song URL")
                }
                if (response.code != 200) {
                    return@withContext Resource.Error(
                        "Song URL API returned code ${response.code}"
                    )
                }

                responseCode = response.code
                response.fullSourcesFor(requestedIds.toSet()).forEach { source ->
                    fullSourcesById.putIfAbsent(source.id.toString(), source)
                }
                if (fullSourcesById.size == requestedIds.size) break
            }

            Resource.Success(
                SongUrl(
                    code = if (fullSourcesById.isNotEmpty()) 200 else responseCode,
                    data = fullSourcesById.values.toList(),
                )
            )
        }
    }


    suspend fun manipulateTrack(
        op: String,
        pid: String,
        trackIds: String,
        imme: Boolean = true
    ): Resource<ManipulateTrackResult> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                apiService.manipulateTracks(
                    ManipulateTrack(
                        op = op,
                        pid = pid,
                        trackIds = trackIds,
                        imme = imme
                    )
                )
            }
        }
    }


    suspend fun getEveryDayRecommendSongs(): Resource<EveryDaySongs> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                weApiService.getEveryDayRecommendSongs()
            }
        }
    }


    suspend fun createPlaylist(
        name: String,
        privacy: Boolean, // 0 普通歌单, 10 隐私歌单
        type: String = "NORMAL" // 默认 NORMAL, VIDEO 视频歌单, SHARED 共享歌单
    ): Resource<CreatePlaylistResult> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                apiService.createPlaylist(
                    CreatePlaylist(
                        name = name,
                        privacy = if (privacy) "10" else "0",
                        type = type
                    )
                )
            }
        }
    }

    suspend fun subscribePlaylist(
        id: String
    ): Resource<BaseResponse> = changePlaylistSubscription(id, subscribed = true)

    suspend fun unSubscribePlaylist(
        id: String
    ): Resource<BaseResponse> = changePlaylistSubscription(id, subscribed = false)


    suspend fun subscribeAlbum(id: String): Resource<BaseResponse> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                apiService.subscribeAlbum(
                    SubscribePlaylist(
                        id = id,
                    )
                )
            }
        }
    }


    suspend fun unsubscribeAlbum(id: String): Resource<BaseResponse> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                apiService.unsubscribeAlbum(
                    SubscribePlaylist(
                        id = id,
                    )
                )
            }
        }
    }

    suspend fun deletePlaylist(
        id: String
    ): Resource<BaseMessageResponse> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                apiService.deletePlaylist(
                    DeletePlaylist(
                        ids = "[$id]"
                    )
                )
            }
        }
    }


    suspend fun getAlbumDetail(id: String): Resource<AlbumDetail> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                apiService.getAlbumDetail(
                    id = id
                )
            }
        }
    }

    suspend fun getHighQualityPlaylist(cat:String, limit:Int): Resource<HighQualityPlaylistResult>{
        return withContext(Dispatchers.IO){
            safeApiCall {
                weApiService.getHighQualityPlaylist(
                    HighQualityPlaylist(
                        category = cat,
                        limit = limit
                    )
                )
            }
        }
    }


}
