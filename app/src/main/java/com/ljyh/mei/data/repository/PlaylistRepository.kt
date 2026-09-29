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
import com.ljyh.mei.parasite.HostSessionStamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal interface AlbumDetailSource {
    suspend fun getAlbumDetail(id: String, session: HostSessionStamp): Resource<AlbumDetail>
    suspend fun getAlbumCollection(id: String, session: HostSessionStamp): Resource<Boolean>
    suspend fun setAlbumCollection(id: String, collected: Boolean, session: HostSessionStamp): Resource<BaseResponse>
    suspend fun getSongUrlV1(ids: List<String>, quality: MusicQuality, session: HostSessionStamp? = null): Resource<SongUrl>
}

internal interface PlaylistPageSource {
    suspend fun getPlaylistDetail(id: String, session: HostSessionStamp? = null): Resource<PlaylistDetail>
    suspend fun getPlaylistTrackDetails(ids: List<String>, session: HostSessionStamp? = null): List<PlaylistDetail.Playlist.Track>
    suspend fun subscribePlaylist(id: String, session: HostSessionStamp? = null): Resource<BaseResponse>
    suspend fun unSubscribePlaylist(id: String, session: HostSessionStamp? = null): Resource<BaseResponse>
}

internal interface PlaylistMutationSource {
    suspend fun manipulateTrack(op: String, pid: String, trackIds: String, session: HostSessionStamp): Resource<ManipulateTrackResult>
    suspend fun createPlaylist(name: String, privacy: Boolean, type: String, session: HostSessionStamp): Resource<CreatePlaylistResult>
    suspend fun deletePlaylist(id: String, session: HostSessionStamp): Resource<BaseMessageResponse>
}

class PlaylistRepository(
    private val apiService: ApiService,
    private val weApiService: WeApiService,
    private val eApiService: EApiService
) : AlbumDetailSource, PlaylistPageSource, PlaylistMutationSource {
    override suspend fun getPlaylistDetail(id: String, session: HostSessionStamp?): Resource<PlaylistDetail> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                apiService.getPlaylistDetail(
                    GetPlaylistDetail(
                        id = id
                    ), session,
                ).also {
                    check(it.code == 200) { "Playlist request failed (${it.code})" }
                    check(it.playlist.Id.toString() == id) { "Official playlist identity mismatch" }
                }
            }
        }
    }

    override suspend fun getPlaylistTrackDetails(ids: List<String>, session: HostSessionStamp?): List<PlaylistDetail.Playlist.Track> =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext emptyList()
            val response = apiService.getSongDetail(GetSongDetails(ids.joinToString(",")), session)
            check(response.code == 200) { "Playlist tracks failed (${response.code})" }
            val byId = response.songs.associateBy { it.id.toString() }
            currentCoroutineContext().ensureActive()
            ids.distinct().mapNotNull(byId::get)
        }

    suspend fun getCompletePlaylistTracks(detail: PlaylistDetail, session: HostSessionStamp? = null): List<MediaMetadata> {
        return withContext(Dispatchers.IO) {
            val playlist = detail.playlist
            val tracksById = playlist.tracks.associateBy { it.id }.toMutableMap()

            playlist.trackIds
                .map { it.id }
                .filterNot(tracksById::containsKey)
                .chunked(200)
                .forEach { ids ->
                    currentCoroutineContext().ensureActive()
                    getPlaylistTrackDetails(ids.map(Long::toString), session)
                        .forEach { track -> tracksById[track.id] = track }
                }
            check(playlist.trackIds.all { it.id in tracksById }) { "Incomplete official playlist tracks" }
            currentCoroutineContext().ensureActive()
            playlist.trackIds
                .distinctBy { it.id }
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

    override suspend fun getSongUrlV1(ids: List<String>, quality: MusicQuality, session: HostSessionStamp?): Resource<SongUrl> {
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
                        ),
                        expectedSession = session,
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


    override suspend fun manipulateTrack(
        op: String,
        pid: String,
        trackIds: String,
        session: HostSessionStamp,
    ): Resource<ManipulateTrackResult> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                check(session.identity.authenticated) { "Official login is required" }
                apiService.manipulateTracks(
                    ManipulateTrack(
                        op = op,
                        pid = pid,
                        trackIds = trackIds,
                        reverse = if (op == "add") true else null,
                    ), session,
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


    override suspend fun createPlaylist(
        name: String,
        privacy: Boolean, // 0 普通歌单, 10 隐私歌单
        type: String,
        session: HostSessionStamp,
    ): Resource<CreatePlaylistResult> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                check(session.identity.authenticated) { "Official login is required" }
                require(name.isNotBlank()) { "Playlist name is required" }
                require(type in setOf("NORMAL", "VIDEO", "SHARED"))
                apiService.createPlaylist(
                    CreatePlaylist(
                        name = name,
                        privacy = if (privacy) "10" else "0",
                        type = type
                    ), session,
                ).also {
                    check(it.code == 200) { "Playlist creation failed (${it.code})" }
                    val id = it.playlist?.id ?: it.id
                    check(id != null && id > 0) { "Missing created playlist identity" }
                    check(it.id == null || it.id == id) { "Created playlist identity mismatch" }
                }
            }
        }
    }

    override suspend fun subscribePlaylist(
        id: String, session: HostSessionStamp?,
    ): Resource<BaseResponse> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                eApiService.subscribePlaylist(
                    EApiSubscribePlaylist(
                        id = id.toLong(),
                    ), session,
                ).also { check(it.code == 200) { "Playlist collection failed (${it.code})" } }
            }
        }
    }

    override suspend fun unSubscribePlaylist(
        id: String, session: HostSessionStamp?,
    ): Resource<BaseResponse> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                eApiService.unSubscribePlaylist(
                    EApiSubscribePlaylist(
                        id = id.toLong(),
                    ), session,
                ).also { check(it.code == 200) { "Playlist collection failed (${it.code})" } }
            }
        }
    }


    override suspend fun setAlbumCollection(id: String, collected: Boolean, session: HostSessionStamp): Resource<BaseResponse> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                check(session.identity.authenticated) { "Official login is required" }
                val body = SubscribePlaylist(id = id)
                val response = if (collected) apiService.subscribeAlbum(body, session)
                    else apiService.unsubscribeAlbum(body, session)
                check(response.code == 200) { "Album collection request failed (${response.code})" }
                response
            }
        }
    }

    override suspend fun deletePlaylist(
        id: String, session: HostSessionStamp,
    ): Resource<BaseMessageResponse> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                check(session.identity.authenticated) { "Official login is required" }
                require(id.toLongOrNull()?.let { it > 0 } == true)
                apiService.deletePlaylist(
                    DeletePlaylist(
                        ids = "[$id]"
                    ), session,
                ).also { check(it.code == 200) { "Playlist deletion failed (${it.code})" } }
            }
        }
    }


    override suspend fun getAlbumDetail(id: String, session: HostSessionStamp): Resource<AlbumDetail> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                apiService.getAlbumDetail(
                    id = id, expectedSession = session,
                ).also { check(it.code == 200) { "Album request failed (${it.code})" } }
            }
        }
    }

    override suspend fun getAlbumCollection(id: String, session: HostSessionStamp): Resource<Boolean> = withContext(Dispatchers.IO) {
        safeApiCall {
            if (!session.identity.authenticated) return@safeApiCall false
            val request = com.google.gson.JsonObject().apply { addProperty("albumId", id) }
            val response = apiService.getAlbumCollection(mapOf("request" to request.toString()), session)
            check(response.code == 200) { "Album collection state failed (${response.code})" }
            val album = checkNotNull(response.data) { "Missing official album collection state" }
            check(album.id.toString() == id) { "Official album identity mismatch" }
            checkNotNull(album.collected) { "Missing official album collection flag" }
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
