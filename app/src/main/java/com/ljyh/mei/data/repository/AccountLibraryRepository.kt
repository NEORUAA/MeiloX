package com.ljyh.mei.data.repository

import com.ljyh.mei.data.model.AlbumPhoto
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.UserAlbumList
import com.ljyh.mei.data.model.room.AccountPlaylist
import com.ljyh.mei.data.model.room.Playlist
import com.ljyh.mei.data.model.toMiniPlaylistDetail
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.safeApiCall
import com.ljyh.mei.di.repository.LocalPlaylistRepository
import com.ljyh.mei.parasite.HostSessionBridge
import com.ljyh.mei.parasite.HostSessionStamp
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow

internal interface AccountLibrarySource {
    val albumChanges: Flow<HostSessionStamp>
    fun playlists(accountId: String): Flow<List<AccountPlaylist>>
    suspend fun sync(stamp: HostSessionStamp): Resource<Unit>
    suspend fun albums(): Resource<UserAlbumList>
    suspend fun photos(accountId: String): Resource<AlbumPhoto>
    suspend fun likedSongs(playlistId: String): Resource<List<MediaMetadata>>
}

@Singleton
class AccountLibraryRepository @Inject constructor(
    private val users: UserRepository,
    private val local: LocalPlaylistRepository,
    private val remote: PlaylistRepository,
    private val sessions: HostSessionBridge,
) : AccountLibrarySource {
    private val changedAlbums = MutableSharedFlow<HostSessionStamp>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val albumChanges = changedAlbums.asSharedFlow()

    fun invalidateAlbums(stamp: HostSessionStamp) {
        sessions.withCurrent(stamp) { changedAlbums.tryEmit(stamp) }
    }

    override fun playlists(accountId: String) = local.getAccountPlaylists(accountId)

    override suspend fun sync(stamp: HostSessionStamp): Resource<Unit> = safeApiCall {
        check(stamp.identity.authenticated)
        sessions.requireCurrent(stamp)
        val accountId = stamp.identity.userId.toString()
        val response = users.getAllUserPlaylists(accountId) { sessions.requireCurrent(stamp) }
        val playlists = when (response) {
            is Resource.Success -> response.data.playlist
            is Resource.Error -> throw java.io.IOException(response.message)
            Resource.Loading -> error("Unexpected pending playlist response")
        }
        val entries = playlists.map { item ->
            AccountPlaylist(
                playlist = Playlist(
                    id = item.id.toString(), title = item.name, cover = item.coverImgUrl,
                    author = item.creator.userId.toString(), authorName = item.creator.nickname,
                    authorAvatar = item.creator.avatarUrl, count = item.trackCount, playCount = item.playCount,
                ),
                isLiked = item.specialType == 5 && item.creator.userId == stamp.identity.userId,
            )
        }
        val coroutine = currentCoroutineContext()
        local.replaceAccountPlaylists(accountId, entries) {
            coroutine.ensureActive()
            sessions.requireCurrent(stamp)
        }
        coroutine.ensureActive()
        sessions.requireCurrent(stamp)
    }

    override suspend fun albums(): Resource<UserAlbumList> {
        val stamp = sessions.snapshot()
        return users.getAlbumList(stamp) { sessions.requireCurrent(stamp) }
    }
    override suspend fun photos(accountId: String) = users.getPhotoAlbum(accountId)

    override suspend fun likedSongs(playlistId: String): Resource<List<MediaMetadata>> = safeApiCall {
        when (val response = remote.getPlaylistDetail(playlistId)) {
            is Resource.Success -> try {
                remote.getCompletePlaylistTracks(response.data)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                response.data.toMiniPlaylistDetail().tracks
            }
            is Resource.Error -> throw java.io.IOException(response.message)
            Resource.Loading -> error("Unexpected pending liked playlist response")
        }
    }
}
