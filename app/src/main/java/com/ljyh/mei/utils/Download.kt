package com.ljyh.mei.utils

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ljyh.mei.AppContext
import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.data.model.room.DownloadTask
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.di.AppGraph
import com.ljyh.mei.parasite.HostSessionStamp
import com.ljyh.mei.parasite.HostSessionChangedException
import com.ljyh.mei.playback.requireDownloadOwner
import com.ljyh.mei.constants.MusicQuality
import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.ljyh.mei.playback.DownloadWorker
import com.ljyh.mei.playback.SongDownloadInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

object DownloadManager {
    private const val SONG_WORK_NAME_PREFIX = "download_song_"
    private val managementScope = CoroutineScope(SupervisorJob() + Dispatchers.IO +
        kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
            Timber.w("Download management failed: %s", error.javaClass.simpleName)
        })
    internal val mutations = Mutex()

    fun getDefaultDownloadPath(): String {
        return "Music/Mei"
    }

    suspend fun enqueue(
        context: Context,
        songs: List<SongDownloadInfo>,
        playlistName: String,
        owner: HostSessionStamp,
        playlistId: String = "",
        downloadPath: String = getDefaultDownloadPath(),
        expectedRequestId: String? = null,
    ) = withContext(Dispatchers.IO) {
        mutations.withLock {
            val sessions = AppGraph.component.hostRequests().sessions
            sessions.requireDownloadOwner(owner)
            val wm = WorkManager.getInstance(context)
            val db = AppDatabase.getDatabase(context)
            val tasks = songs.distinctBy { it.songId }.map { info ->
                require(info.songId.toLongOrNull()?.takeIf { it > 0 }?.toString() == info.songId)
                require(MusicQuality.entries.any { it.text == info.quality })
                DownloadTask(
                    songId = info.songId,
                    requestId = UUID.randomUUID().toString(),
                    ownerId = owner.identity.userId,
                    playlistName = playlistName,
                    downloadPath = downloadPath,
                    fileName = "",
                    status = DownloadStatus.PENDING,
                    progress = 0,
                    songTitle = info.songTitle,
                    songArtist = info.songArtist.joinToString("/"),
                    songAlbum = info.songAlbum,
                    songCover = info.songCover,
                    quality = info.quality,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            }

            db.withTransaction {
                sessions.requireDownloadOwner(owner)
                if (expectedRequestId != null) {
                    check(tasks.size == 1 && db.downloadDao().getBySongId(tasks.single().songId)?.requestId == expectedRequestId) {
                        "Download request was replaced"
                    }
                }
                db.downloadDao().insertAll(tasks)
                sessions.requireDownloadOwner(owner)
            }
            try {
                tasks.forEach { task ->
                    sessions.requireDownloadOwner(owner)
                    val uniqueWorkName = SONG_WORK_NAME_PREFIX + task.songId
                    val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
                        .setId(UUID.fromString(task.requestId))
                        .addTag("download")
                        .addTag(uniqueWorkName)
                        .setInputData(
                            androidx.work.Data.Builder()
                                .putString(DownloadWorker.KEY_SONG_ID, task.songId)
                                .putLong(DownloadWorker.KEY_OWNER_ID, task.ownerId)
                                .build()
                        )
                        .build()
                    wm.enqueueUniqueWork(uniqueWorkName, ExistingWorkPolicy.REPLACE, workRequest).result.await()
                }
                sessions.requireDownloadOwner(owner)
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    tasks.forEach { task ->
                        wm.cancelWorkById(UUID.fromString(task.requestId)).result.await()
                        db.downloadDao().updateOwnedProgress(task.songId, task.requestId, task.ownerId,
                            DownloadStatus.FAILED, 0, System.currentTimeMillis())
                    }
                }
                throw error
            }
            Timber.tag("DownloadManager").d("Song work enqueued: playlist=$playlistId, songs=${songs.size}")
        }
    }

    fun pauseSong(context: Context, songId: String, requestId: String) {
        managementScope.launch {
            mutations.withLock {
                val dao = AppDatabase.getDatabase(context).downloadDao()
                val task = dao.getBySongId(songId) ?: return@withLock
                if (task.requestId != requestId) return@withLock
                dao.updateOwnedProgress(songId, task.requestId, task.ownerId,
                    DownloadStatus.PAUSED, task.progress, System.currentTimeMillis())
                task.workId()?.let { WorkManager.getInstance(context).cancelWorkById(it).result.await() }
            }
        }
    }

    suspend fun resumeSong(
        context: Context,
        songId: String,
        playlistName: String,
        requestId: String,
        downloadPath: String = getDefaultDownloadPath()
    ) {
        val db = AppDatabase.getDatabase(context)
        val task = db.downloadDao().getBySongId(songId) ?: return
        if (task.requestId != requestId) return
        val owner = AppGraph.component.hostRequests().sessions.snapshot()
        AppGraph.component.hostRequests().sessions.requireDownloadOwner(owner)
        if (task.ownerId != owner.identity.userId) throw HostSessionChangedException()
        enqueue(
            context = context,
            songs = listOf(
                SongDownloadInfo(
                    songId = task.songId,
                    songTitle = task.songTitle,
                    songArtist = task.songArtist.split("/").map { it.trim() }.filter { it.isNotBlank() },
                    songAlbum = task.songAlbum,
                    songCover = task.songCover,
                    duration = 0,
                    quality = task.quality,
                )
            ),
            owner = owner,
            playlistName = task.playlistName.ifBlank { playlistName },
            playlistId = "resume_${System.currentTimeMillis()}",
            downloadPath = task.downloadPath.ifBlank { downloadPath },
            expectedRequestId = task.requestId,
        )
    }

    fun deleteTask(context: Context, songId: String, requestId: String) {
        managementScope.launch {
            mutations.withLock {
                val db = AppDatabase.getDatabase(context)
                val task = db.downloadDao().getBySongId(songId) ?: return@withLock
                if (task.requestId != requestId) return@withLock
                db.downloadDao().deleteOwned(songId, task.requestId, task.ownerId)
                task.workId()?.let { WorkManager.getInstance(context).cancelWorkById(it).result.await() }
                val song = db.songDao().getSong(songId).first()
                song?.takeIf { it.sourceType == com.ljyh.mei.data.model.room.SourceType.DOWNLOAD }?.path?.let { path ->
                    runCatching {
                        if (path.startsWith("content://")) {
                            context.contentResolver.delete(android.net.Uri.parse(path), null, null)
                        } else {
                            File(path).takeIf(File::exists)?.delete()
                        }
                    }.onFailure { Timber.w(it, "Unable to remove downloaded file for %s", songId) }
                    db.songDao().updatePath(songId, null)
                }
            }
        }
    }

    fun deleteAll(context: Context) {
        managementScope.launch {
            mutations.withLock {
                val db = AppDatabase.getDatabase(context)
                db.downloadDao().getAll().first().forEach { task ->
                    db.downloadDao().deleteOwned(task.songId, task.requestId, task.ownerId)
                    task.workId()?.let { WorkManager.getInstance(context).cancelWorkById(it).result.await() }
                    val song = db.songDao().getSong(task.songId).first()
                    song?.takeIf { it.sourceType == com.ljyh.mei.data.model.room.SourceType.DOWNLOAD }?.path?.let { path ->
                        runCatching {
                            if (path.startsWith("content://")) {
                                context.contentResolver.delete(android.net.Uri.parse(path), null, null)
                            } else {
                                File(path).takeIf(File::exists)?.delete()
                            }
                        }.onFailure { Timber.w(it, "Unable to remove downloaded file for %s", task.songId) }
                        db.songDao().updatePath(task.songId, null)
                    }
                }
            }
        }
    }

    fun cancelAll(context: Context) {
        managementScope.launch {
            mutations.withLock {
                val dao = AppDatabase.getDatabase(context).downloadDao()
                dao.getAll().first().forEach { task ->
                    dao.updateOwnedProgress(task.songId, task.requestId, task.ownerId,
                        DownloadStatus.PAUSED, task.progress, System.currentTimeMillis())
                    task.workId()?.let { WorkManager.getInstance(context).cancelWorkById(it).result.await() }
                }
            }
        }
    }

    private fun DownloadTask.workId(): UUID? = runCatching {
        UUID.fromString(requestId).takeIf { it.toString() == requestId }
    }.getOrNull()

    fun isSongDownloaded(songId: String): Boolean {
        val db = AppDatabase.getDatabase(AppContext.instance)
        val song = kotlinx.coroutines.runBlocking { db.songDao().getSong(songId).first() }
        val path = song?.path ?: return false
        if (path.startsWith("content://")) return true
        return File(path).exists()
    }

    suspend fun isSongDownloading(songId: String): Boolean {
        val db = AppDatabase.getDatabase(AppContext.instance)
        val task = db.downloadDao().getBySongId(songId)
        return task?.status == DownloadStatus.DOWNLOADING || task?.status == DownloadStatus.PENDING
    }
}
