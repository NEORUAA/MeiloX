package com.ljyh.mei.di.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: Playlist)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylists(playlists: List<Playlist>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissingPlaylists(playlists: List<Playlist>): List<Long>

    @Query("""UPDATE playlist SET title = :title, cover = :cover, author = :author,
        authorName = :authorName, authorAvatar = :authorAvatar, count = :count,
        playCount = :playCount WHERE id = :id""")
    suspend fun updateRemoteMetadata(
        id: String, title: String, cover: String, author: String,
        authorName: String, authorAvatar: String, count: Int, playCount: Long,
    )

    /** Refresh remote fields without overwriting local usage recorded during a fetch. */
    @Transaction
    suspend fun mergeRemotePlaylists(playlists: List<Playlist>) {
        val inserted = insertMissingPlaylists(playlists)
        playlists.forEachIndexed { index, playlist ->
            if (inserted[index] == -1L) {
                updateRemoteMetadata(
                    playlist.id, playlist.title, playlist.cover, playlist.author,
                    playlist.authorName, playlist.authorAvatar, playlist.count, playlist.playCount,
                )
            }
        }
    }

    @Query("DELETE FROM playlist where id=:id")
    suspend fun deletePlaylistById(id: String)

    @Query("UPDATE playlist SET lastPlayTime = :timestamp, localPlayCount = localPlayCount + 1 WHERE id = :id")
    suspend fun touchPlaylist(id: String, timestamp: Long)
}
