package com.ljyh.mei.data.repository

import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.AlbumDetail
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.PlaylistDetail
import com.ljyh.mei.data.model.DownloadSources
import com.ljyh.mei.data.model.api.BaseMessageResponse
import com.ljyh.mei.data.model.api.BaseResponse
import com.ljyh.mei.data.model.api.CreatePlaylist
import com.ljyh.mei.data.model.api.CreatePlaylistResult
import com.ljyh.mei.data.model.api.DeletePlaylist
import com.ljyh.mei.data.model.api.GetPlaylistDetail
import com.ljyh.mei.data.model.api.GetSongDetails
import com.ljyh.mei.data.model.api.ManipulateTrackResult
import com.ljyh.mei.data.model.api.SubscribePlaylist
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.model.weapi.EveryDaySongs
import com.ljyh.mei.data.model.weapi.HighQualityPlaylist
import com.ljyh.mei.data.model.weapi.HighQualityPlaylistResult
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.network.safeApiCall
import com.ljyh.mei.playback.DownloadSourceBackend
import com.ljyh.mei.playback.requireDownloadOwner
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal interface AlbumDetailSource {
    suspend fun getAlbumDetail(id: String, session: SessionStamp): Resource<AlbumDetail>
    suspend fun getAlbumCollection(id: String, session: SessionStamp): Resource<Boolean>
    suspend fun setAlbumCollection(id: String, collected: Boolean, session: SessionStamp): Resource<BaseResponse>
    suspend fun getDownloadSources(ids: List<String>, quality: MusicQuality, session: SessionStamp): Resource<DownloadSources>
}

internal interface PlaylistPageSource {
    suspend fun getEveryDayRecommendSongs(session: SessionStamp): Resource<EveryDaySongs>
    suspend fun getPlaylistDetail(id: String, session: SessionStamp? = null): Resource<PlaylistDetail>
    suspend fun getPlaylistTrackDetails(ids: List<String>, session: SessionStamp? = null): List<PlaylistDetail.Playlist.Track>
    suspend fun subscribePlaylist(id: String, session: SessionStamp? = null): Resource<BaseResponse>
    suspend fun unSubscribePlaylist(id: String, session: SessionStamp? = null): Resource<BaseResponse>
}

internal interface PlaylistMutationSource {
    suspend fun manipulateTrack(op: String, pid: String, trackIds: String, session: SessionStamp): Resource<ManipulateTrackResult>
    suspend fun createPlaylist(name: String, privacy: Boolean, type: String, session: SessionStamp): Resource<CreatePlaylistResult>
    suspend fun deletePlaylist(id: String, session: SessionStamp): Resource<BaseMessageResponse>
}

class PlaylistRepository(
    private val apiService: ApiService,
    private val weApiService: WeApiService,
    private val collections: PlaylistCollectionBackend,
    private val sessions: SessionStore,
    private val catalogCollections: CatalogCollectionBackend,
    private val playlistTracks: PlaylistTracksBackend,
    private val downloadSources: DownloadSourceBackend,
) : AlbumDetailSource, PlaylistPageSource, PlaylistMutationSource {
    override suspend fun getPlaylistDetail(id: String, session: SessionStamp?): Resource<PlaylistDetail> {
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

    override suspend fun getPlaylistTrackDetails(ids: List<String>, session: SessionStamp?): List<PlaylistDetail.Playlist.Track> =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext emptyList()
            val response = apiService.getSongDetail(GetSongDetails(ids.joinToString(",")), session)
            check(response.code == 200) { "Playlist tracks failed (${response.code})" }
            val byId = response.songs.associateBy { it.id.toString() }
            currentCoroutineContext().ensureActive()
            ids.distinct().mapNotNull(byId::get)
        }

    suspend fun getCompletePlaylistTracks(detail: PlaylistDetail, session: SessionStamp? = null): List<MediaMetadata> {
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

    override suspend fun getDownloadSources(ids: List<String>, quality: MusicQuality, session: SessionStamp): Resource<DownloadSources> {
        return withContext(Dispatchers.IO) {
            try {
                sessions.requireDownloadOwner(session)
                Resource.Success(downloadSources.resolve(ids, quality, session).also {
                    currentCoroutineContext().ensureActive()
                    sessions.requireDownloadOwner(session)
                })
            } catch (error: CancellationException) { throw error }
            catch (error: SessionChangedException) { throw error }
            catch (_: Exception) { Resource.Error("Unable to resolve download sources") }
        }
    }


    override suspend fun manipulateTrack(
        op: String,
        pid: String,
        trackIds: String,
        session: SessionStamp,
    ): Resource<ManipulateTrackResult> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                sessions.requireCurrent(session)
                check(session.identity.authenticated && !session.identity.anonymous) { "Sign-in required" }
                require(op == "add" || op == "del")
                val playlistId = requireNotNull(pid.toLongOrNull()?.takeIf { it > 0 }) { "Invalid playlist identity" }
                val ids = trackIds.split(",").map { raw ->
                    requireNotNull(raw.trim().toLongOrNull()?.takeIf { it > 0 }) { "Invalid track identity" }
                }.distinct()
                playlistTracks.modify(op, playlistId, ids, session).also {
                    currentCoroutineContext().ensureActive()
                    sessions.requireCurrent(session)
                }
            }
        }
    }


    override suspend fun getEveryDayRecommendSongs(session: SessionStamp): Resource<EveryDaySongs> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                check(session.identity.authenticated) { "Official login is required" }
                weApiService.getEveryDayRecommendSongs(
                    mapOf("ispush" to "false", "limit" to "30", "trialMode" to "1"), session,
                ).also { response ->
                    check(response.code == 200) { "Daily recommendations failed (${response.code})" }
                    val data: EveryDaySongs.Data? = response.data
                    val songs: List<EveryDaySongs.Data.DailySong>? = data?.dailySongs
                    checkNotNull(songs) { "Missing daily recommendations" }
                    songs.forEach { song ->
                        check(song.id > 0) { "Invalid recommended song identity" }
                        song.toMediaMetadata()
                    }
                }
            }
        }
    }


    override suspend fun createPlaylist(
        name: String,
        privacy: Boolean, // 0 普通歌单, 10 隐私歌单
        type: String,
        session: SessionStamp,
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
        id: String, session: SessionStamp?,
    ): Resource<BaseResponse> = setPlaylistCollection(id, true, session)

    override suspend fun unSubscribePlaylist(
        id: String, session: SessionStamp?,
    ): Resource<BaseResponse> = setPlaylistCollection(id, false, session)

    private suspend fun setPlaylistCollection(id: String, collected: Boolean, session: SessionStamp?): Resource<BaseResponse> =
        withContext(Dispatchers.IO) {
            safeApiCall {
                val owner = session ?: sessions.snapshot()
                val playlistId = requireNotNull(id.toLongOrNull()?.takeIf { it > 0 }) { "Invalid playlist identity" }
                sessions.requireCurrent(owner)
                check(owner.identity.authenticated && !owner.identity.anonymous) { "Sign-in required" }
                collections.setCollected(playlistId, collected, owner).also {
                    currentCoroutineContext().ensureActive()
                    sessions.requireCurrent(owner)
                    check(it.code == 200) { "Playlist collection failed (${it.code})" }
                }
            }
        }


    override suspend fun setAlbumCollection(id: String, collected: Boolean, session: SessionStamp): Resource<BaseResponse> {
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
        id: String, session: SessionStamp,
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


    override suspend fun getAlbumDetail(id: String, session: SessionStamp): Resource<AlbumDetail> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                apiService.getAlbumDetail(
                    id = id, expectedSession = session,
                ).also { check(it.code == 200) { "Album request failed (${it.code})" } }
            }
        }
    }

    override suspend fun getAlbumCollection(id: String, session: SessionStamp): Resource<Boolean> = withContext(Dispatchers.IO) {
        safeApiCall {
            val albumId = requireNotNull(id.toLongOrNull()?.takeIf { it > 0 }) { "Invalid album identity" }
            sessions.requireCurrent(session)
            if (!session.identity.authenticated || session.identity.anonymous) return@safeApiCall false
            catalogCollections.albumCollected(albumId, session).also {
                currentCoroutineContext().ensureActive()
                sessions.requireCurrent(session)
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
