package com.ljyh.mei.di.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ljyh.mei.data.model.room.DownloadArtifact
import com.ljyh.mei.data.model.room.DownloadTask
import com.ljyh.mei.data.model.room.Song

/** Synchronous operations keep the short Room commit inside the session publication lock. */
@Dao
interface DownloadArtifactDao {
    @Insert fun insert(artifact: DownloadArtifact)
    @Update fun update(artifact: DownloadArtifact): Int
    @Query("SELECT * FROM download_artifact") fun all(): List<DownloadArtifact>
    @Query("SELECT * FROM download_artifact WHERE uri = :uri") fun byUri(uri: String): DownloadArtifact?
    @Query("DELETE FROM download_artifact WHERE requestId = :requestId") fun delete(requestId: String)
    @Query("SELECT EXISTS(SELECT 1 FROM song WHERE path = :uri)") fun isReferenced(uri: String): Boolean
    @Query("SELECT * FROM download_task WHERE songId = :songId AND requestId = :requestId AND ownerId = :ownerId")
    fun task(songId: String, requestId: String, ownerId: Long): DownloadTask?
    @Query("UPDATE download_task SET status = 'COMPLETED', progress = 100, updatedAt = :time WHERE songId = :songId AND requestId = :requestId AND ownerId = :ownerId AND status IN ('PENDING', 'DOWNLOADING')")
    fun complete(songId: String, requestId: String, ownerId: Long, time: Long): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertSong(song: Song)
}
