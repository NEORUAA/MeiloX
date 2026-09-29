package com.ljyh.mei.di.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.ljyh.mei.data.model.room.AccountPlaylist
import com.ljyh.mei.data.model.room.AccountPlaylistMembership
import com.ljyh.mei.data.model.room.Playlist
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlist where id=:id")
    suspend fun getPlaylist(id: String): Playlist?

    @Query("SELECT * FROM playlist where author=:author")
    suspend fun getPlaylistByAuthor(author: String): List<Playlist>

    @Query("SELECT * FROM playlist")
    fun getAllPlaylist(): Flow<List<Playlist>>

    @Query("""
        SELECT playlist.*, COALESCE(account_playlist.isLiked, 0) AS isLiked
        FROM playlist LEFT JOIN account_playlist
        ON playlist.id = account_playlist.playlistId AND account_playlist.accountId = :accountId
        WHERE account_playlist.accountId = :accountId OR playlist.type != 'NETEAST'
        ORDER BY account_playlist.position, playlist.createdAt
    """)
    fun getAccountPlaylists(accountId: String): Flow<List<AccountPlaylist>>

    @Query("DELETE FROM account_playlist WHERE accountId = :accountId")
    suspend fun clearAccountMemberships(accountId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccountMemberships(memberships: List<AccountPlaylistMembership>)

    @Transaction
    suspend fun replaceAccountPlaylists(
        accountId: String,
        entries: List<AccountPlaylist>,
        validate: () -> Unit,
    ) {
        validate()
        val merged = entries.map { entry ->
            val existing = getPlaylist(entry.playlist.id)
            entry.playlist.copy(
                createdAt = existing?.createdAt ?: entry.playlist.createdAt,
                lastPlayTime = existing?.lastPlayTime ?: 0L,
                localPlayCount = existing?.localPlayCount ?: 0,
            )
        }
        insertPlaylists(merged)
        clearAccountMemberships(accountId)
        insertAccountMemberships(entries.mapIndexed { index, entry ->
            AccountPlaylistMembership(accountId, entry.playlist.id, index, entry.isLiked)
        })
        validate()
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: Playlist)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylists(playlists: List<Playlist>)

    @Query("DELETE FROM playlist where id=:id")
    suspend fun deletePlaylistById(id: String)

    @Query("UPDATE playlist SET lastPlayTime = :timestamp, localPlayCount = localPlayCount + 1 WHERE id = :id")
    suspend fun touchPlaylist(id: String, timestamp: Long)

    @Query("""
        UPDATE playlist SET lastPlayTime = :timestamp, localPlayCount = localPlayCount + 1
        WHERE id = :id AND EXISTS (
            SELECT 1 FROM account_playlist WHERE accountId = :accountId AND playlistId = :id
        )
    """)
    suspend fun updateAccountPlaylistAccess(accountId: String, id: String, timestamp: Long)

    @Transaction
    suspend fun touchAccountPlaylist(accountId: String, id: String, timestamp: Long, validate: () -> Unit) {
        validate()
        updateAccountPlaylistAccess(accountId, id, timestamp)
        validate()
    }
}
