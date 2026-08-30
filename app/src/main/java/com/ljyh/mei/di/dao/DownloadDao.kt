package com.ljyh.mei.di.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.data.model.room.DownloadTask
import com.ljyh.mei.data.model.room.Song
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query(
        """
        INSERT INTO playback_count(songId, playCount, lastPlayedAt)
        VALUES(:songId, 1, :time)
        ON CONFLICT(songId) DO UPDATE SET
            playCount = playCount + 1,
            lastPlayedAt = :time
        """,
    )
    suspend fun recordPlayback(songId: String, time: Long = System.currentTimeMillis())

    @Query("SELECT playCount FROM playback_count WHERE songId = :songId")
    suspend fun playbackCount(songId: String): Int?

    @Query("DELETE FROM playback_count")
    suspend fun clearPlaybackCounts()

    @Query("SELECT * FROM download_task WHERE songId = :songId")
    suspend fun getBySongId(songId: String): DownloadTask?

    @Query("SELECT * FROM download_task ORDER BY createdAt DESC")
    fun getAll(): Flow<List<DownloadTask>>

    @Query(
        """
        SELECT song.* FROM song
        INNER JOIN download_task ON song.id = download_task.songId
        WHERE download_task.status = 'COMPLETED' AND song.path IS NOT NULL AND song.path != ''
        """,
    )
    fun getPlayableSongs(): Flow<List<Song>>

    @Query("SELECT COUNT(*) FROM download_task WHERE status = 'DOWNLOADING' OR status = 'PENDING'")
    fun activeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: DownloadTask)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<DownloadTask>)

    @Query("UPDATE download_task SET status = :status, progress = :progress, updatedAt = :time WHERE songId = :songId")
    suspend fun updateProgress(songId: String, status: DownloadStatus, progress: Int, time: Long)

    @Query("UPDATE download_task SET url = :url, fileName = :fileName, fileType = :fileType WHERE songId = :songId")
    suspend fun updateFileInfo(songId: String, url: String, fileName: String, fileType: String)

    @Query("UPDATE download_task SET status = :status, updatedAt = :time WHERE songId = :songId")
    suspend fun updateStatus(songId: String, status: DownloadStatus, time: Long = System.currentTimeMillis())

    @Query("DELETE FROM download_task WHERE songId = :songId")
    suspend fun delete(songId: String)

    @Query("DELETE FROM download_task")
    suspend fun deleteAll()

    @Query("UPDATE download_task SET status = 'FAILED' WHERE status = 'DOWNLOADING'")
    suspend fun markAllDownloadingAsFailed()
}
