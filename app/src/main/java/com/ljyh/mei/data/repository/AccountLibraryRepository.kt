package com.ljyh.mei.data.repository

import com.ljyh.mei.data.model.AlbumPhoto
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.UserAlbumList
import com.ljyh.mei.data.model.room.AccountPlaylist
import com.ljyh.mei.data.model.room.Playlist
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.safeApiCall
import com.ljyh.mei.di.repository.LocalPlaylistRepository
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionStamp
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow

internal interface AccountLibrarySource {
    val collectionChanges: Flow<SessionStamp>
    fun playlists(accountId: String): Flow<List<AccountPlaylist>>
    suspend fun sync(stamp: SessionStamp): Resource<Unit>
    suspend fun albums(): Resource<UserAlbumList>
    suspend fun photos(accountId: String): Resource<AlbumPhoto>
    suspend fun likedSongs(playlistId: String, stamp: SessionStamp): Resource<List<MediaMetadata>>
}

@Singleton
class AccountLibraryRepository @Inject constructor(
    private val users: UserRepository,
    private val local: LocalPlaylistRepository,
    private val remote: PlaylistRepository,
    private val sessions: SessionStore,
) : AccountLibrarySource {
    private val changedCollections = MutableSharedFlow<SessionStamp>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val collectionChanges = changedCollections.asSharedFlow()

    fun invalidateCollections(stamp: SessionStamp) {
        sessions.withCurrent(stamp) { changedCollections.tryEmit(stamp) }
    }

    override fun playlists(accountId: String) = local.getAccountPlaylists(accountId)

    override suspend fun sync(stamp: SessionStamp): Resource<Unit> = safeApiCall {
        check(stamp.identity.authenticated)
        sessions.requireCurrent(stamp)
        val accountId = stamp.identity.userId.toString()
        val response = users.getAllUserPlaylists(accountId, stamp) { sessions.requireCurrent(stamp) }
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

    override suspend fun likedSongs(playlistId: String, stamp: SessionStamp): Resource<List<MediaMetadata>> = safeApiCall {
        check(stamp.identity.authenticated) { "Official login is required" }
        sessions.requireCurrent(stamp)
        val accountId = stamp.identity.userId.toString()
        check(local.getAccountPlaylists(accountId).first().any {
            it.isLiked && it.playlist.id == playlistId && it.playlist.author == accountId
        }) { "Liked playlist does not belong to this account" }
        sessions.requireCurrent(stamp)
        val songs = when (val response = remote.getPlaylistDetail(playlistId, stamp)) {
            is Resource.Success -> {
                check(response.data.playlist.creator.userId == stamp.identity.userId) { "Liked playlist owner changed" }
                sessions.requireCurrent(stamp)
                remote.getCompletePlaylistTracks(response.data, stamp)
            }
            is Resource.Error -> throw java.io.IOException(response.message)
            Resource.Loading -> error("Unexpected pending liked playlist response")
        }
        currentCoroutineContext().ensureActive()
        sessions.requireCurrent(stamp)
        songs
    }
}
