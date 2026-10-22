package com.ljyh.mei.di.repository

import com.ljyh.mei.data.model.room.Playlist
import com.ljyh.mei.data.model.UserPlaylist
import com.ljyh.mei.data.repository.LibraryAccount
import com.ljyh.mei.data.model.room.PlaylistSongCrossRef
import com.ljyh.mei.di.dao.PlaylistDao
import com.ljyh.mei.di.dao.PlaylistSongCrossRefDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

class LocalPlaylistRepository @Inject constructor(private val playlistDao: PlaylistDao) {
    suspend fun getPlaylist(id: String): Playlist? = playlistDao.getPlaylist(id)
    suspend fun getPlaylistByAuthor(author: String): List<Playlist> = playlistDao.getPlaylistByAuthor(author)
    fun getAllPlaylist(): Flow<List<Playlist>> = playlistDao.getAllPlaylist()
    suspend fun insertPlaylist(playlist: Playlist) = playlistDao.insertPlaylist(playlist)
    suspend fun insertPlaylists(playlists: List<Playlist>) = playlistDao.insertPlaylists(playlists)
    suspend fun mergeRemotePlaylists(playlists: List<Playlist>) = playlistDao.mergeRemotePlaylists(playlists)
    suspend fun deletePlaylistById(id: String) = playlistDao.deletePlaylistById(id)
    suspend fun touchPlaylist(id: String, timestamp: Long) = playlistDao.touchPlaylist(id, timestamp)
}

internal fun UserPlaylist.Playlist.toLocalPlaylist() = Playlist(
    id = id.toString(), title = name, cover = coverImgUrl,
    author = creator.userId.toString(), authorName = creator.nickname,
    authorAvatar = creator.avatarUrl, count = trackCount, playCount = playCount,
)

internal fun observeOwnedPlaylists(
    playlists: Flow<List<Playlist>>,
    accounts: Flow<LibraryAccount?>,
): Flow<List<Playlist>> = combine(playlists, accounts) { rows, account ->
    if (account == null) emptyList() else rows.filter { it.author == account.userId }
}

class PlaylistSongCrossRefRepository @Inject constructor(private val dao: PlaylistSongCrossRefDao) {
    fun getSongIdsByPlaylist(playlistId: String): Flow<List<String>> = dao.getSongIdsByPlaylist(playlistId)
    suspend fun insert(crossRef: PlaylistSongCrossRef) = dao.insert(crossRef)
    suspend fun insertAll(crossRefs: List<PlaylistSongCrossRef>) = dao.insertAll(crossRefs)
    suspend fun deleteByPlaylist(playlistId: String) = dao.deleteByPlaylist(playlistId)
    suspend fun delete(playlistId: String, songId: String) = dao.delete(playlistId, songId)
}
