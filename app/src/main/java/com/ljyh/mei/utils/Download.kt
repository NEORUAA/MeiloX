package com.ljyh.mei.utils

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Constraints
import androidx.work.NetworkType
import com.ljyh.mei.AppContext
import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.data.model.room.DownloadTask
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.di.AppGraph
import com.ljyh.mei.parasite.HostSessionStamp
import com.ljyh.mei.parasite.HostSessionBridge
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
import com.ljyh.mei.playback.AndroidDownloadMediaStore
import com.ljyh.mei.playback.DownloadPublication
import com.ljyh.mei.playback.requireExecutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

object DownloadManager {
    private val managementScope = CoroutineScope(SupervisorJob() + Dispatchers.IO +
        kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
            Timber.w("Download management failed: %s", error.javaClass.simpleName)
        })
    internal val mutations = Mutex()

    fun getDefaultDownloadPath(): String {
        return "Music/Mei"
    }

    private fun queue(context: Context): DownloadQueue {
        val db = AppDatabase.getDatabase(context)
        val manager = try { WorkManager.getInstance(context) }
        catch (error: IllegalStateException) { throw DownloadQueueUnavailableException(error) }
        return DownloadQueue(context, db, AppGraph.component.hostRequests().sessions, manager)
    }

    suspend fun enqueue(
        context: Context, songs: List<SongDownloadInfo>, playlistName: String, owner: HostSessionStamp,
        playlistId: String = "", downloadPath: String = getDefaultDownloadPath(), expectedRequestId: String? = null,
    ) = withContext(Dispatchers.IO) {
        queue(context).enqueue(songs, playlistName, owner, playlistId, downloadPath, expectedRequestId)
    }

    internal fun recover(context: Context) {
        managementScope.launch { queue(context).recover() }
    }

    fun pauseSong(context: Context, songId: String, requestId: String) {
        managementScope.launch { queue(context).pauseSong(songId, requestId) }
    }

    suspend fun resumeSong(context: Context, songId: String, playlistName: String, requestId: String,
        downloadPath: String = getDefaultDownloadPath()) = withContext(Dispatchers.IO) {
        queue(context).resumeSong(songId, playlistName, requestId, downloadPath)
    }

    fun deleteTask(context: Context, songId: String, requestId: String) {
        managementScope.launch { queue(context).deleteTask(songId, requestId) }
    }

    fun deleteAll(context: Context) {
        managementScope.launch { deleteAllAndAwait(context) }
    }

    suspend fun deleteAllAndAwait(context: Context, resetPlaybackCounts: Boolean = false) = withContext(Dispatchers.IO) {
        queue(context).deleteAll(resetPlaybackCounts)
    }

    fun cancelAll(context: Context) {
        managementScope.launch { queue(context).cancelAll() }
    }

    fun isSongDownloaded(songId: String): Boolean {
        val db = AppDatabase.getDatabase(AppContext.instance)
        val song = kotlinx.coroutines.runBlocking { db.songDao().getSong(songId).first() }
        val path = song?.path ?: return false
        if (path.startsWith("content://")) return true
        return File(path).exists()
    }

    suspend fun isSongDownloading(songId: String): Boolean {
        val task = AppDatabase.getDatabase(AppContext.instance).downloadDao().getBySongId(songId)
        return task?.status == DownloadStatus.DOWNLOADING || task?.status == DownloadStatus.PENDING
    }
}

/** The same queue operations can run against a private qualification database and worker. */
internal class DownloadQueue(
    private val context: Context,
    private val db: AppDatabase,
    private val sessions: HostSessionBridge,
    private val manager: WorkManager,
    private val workNamePrefix: String = "download_song_",
    private val request: (DownloadTask) -> androidx.work.OneTimeWorkRequest = ::downloadWorkRequest,
) {
    private val mutations get() = DownloadManager.mutations
    private val publication = DownloadPublication(db, AndroidDownloadMediaStore(context), context.packageName)

    suspend fun enqueue(
        songs: List<SongDownloadInfo>,
        playlistName: String,
        owner: HostSessionStamp,
        playlistId: String = "",
        downloadPath: String = DownloadManager.getDefaultDownloadPath(),
        expectedRequestId: String? = null,
    ) = withContext(Dispatchers.IO) {
        mutations.withLock {
            sessions.requireDownloadOwner(owner)
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
                    manager.enqueueUniqueWork(workNamePrefix + task.songId, ExistingWorkPolicy.REPLACE, request(task)).result.await()
                }
                sessions.requireDownloadOwner(owner)
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    tasks.forEach { task ->
                        manager.cancelWorkById(UUID.fromString(task.requestId)).result.await()
                        db.downloadDao().updateOwnedProgress(task.songId, task.requestId, task.ownerId,
                            DownloadStatus.FAILED, 0, System.currentTimeMillis())
                    }
                }
                throw error
            }
            Timber.tag("DownloadManager").d("Song work enqueued: playlist=$playlistId, songs=${songs.size}")
        }
    }

    suspend fun recover() = withContext(Dispatchers.IO) {
        mutations.withLock {
            publication.recover()
            val owner = sessions.snapshot()
            if (runCatching { sessions.requireDownloadOwner(owner) }.isFailure) return@withLock
            db.downloadDao().getAll().first().filter {
                it.status in setOf(DownloadStatus.PENDING, DownloadStatus.DOWNLOADING)
            }.forEach { task ->
                val id = task.workId()
                if (id == null || task.ownerId != owner.identity.userId) {
                    db.downloadDao().updateOwnedProgress(task.songId, task.requestId, task.ownerId,
                        DownloadStatus.FAILED, 0, System.currentTimeMillis())
                    return@forEach
                }
                task.requireExecutable(id, owner.identity.userId)
                sessions.requireDownloadOwner(owner)
                val work = manager.getWorkInfoById(id).await()
                if (work == null) {
                    // Repair a crash between the Room enqueue commit and WorkManager enqueue.
                    manager.enqueueUniqueWork(workNamePrefix + task.songId,
                        ExistingWorkPolicy.REPLACE, request(task)).result.await()
                } else if (work.state.isFinished) {
                    db.downloadDao().updateOwnedProgress(task.songId, task.requestId, task.ownerId,
                        DownloadStatus.FAILED, 0, System.currentTimeMillis())
                }
            }
        }
    }

    suspend fun pauseSong(songId: String, requestId: String) = withContext(Dispatchers.IO) {
        mutations.withLock {
            val dao = db.downloadDao()
            val task = dao.getBySongId(songId) ?: return@withLock
            if (task.requestId != requestId) return@withLock
            dao.updateOwnedProgress(songId, task.requestId, task.ownerId,
                DownloadStatus.PAUSED, task.progress, System.currentTimeMillis())
            task.workId()?.let { manager.cancelWorkById(it).result.await() }
        }
    }

    suspend fun resumeSong(
        songId: String,
        playlistName: String,
        requestId: String,
        downloadPath: String = DownloadManager.getDefaultDownloadPath()
    ) {
        val task = db.downloadDao().getBySongId(songId) ?: return
        if (task.requestId != requestId || task.status !in setOf(DownloadStatus.PAUSED, DownloadStatus.FAILED)) return
        val owner = sessions.snapshot()
        sessions.requireDownloadOwner(owner)
        if (task.ownerId != owner.identity.userId) throw HostSessionChangedException()
        enqueue(
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

    suspend fun deleteTask(songId: String, requestId: String) = withContext(Dispatchers.IO) {
        mutations.withLock {
            val task = db.downloadDao().getBySongId(songId) ?: return@withLock
            if (task.requestId != requestId) return@withLock
            removeTask(task)
        }
    }

    suspend fun deleteAll(resetPlaybackCounts: Boolean = false) = withContext(Dispatchers.IO) {
        mutations.withLock {
            db.downloadDao().getAll().first().forEach { task ->
                removeTask(task)
            }
            if (resetPlaybackCounts) db.downloadDao().clearPlaybackCounts()
        }
    }

    private suspend fun removeTask(task: DownloadTask) {
        task.workId()?.let { manager.cancelWorkById(it).result.await() }
        val song = db.songDao().getSong(task.songId).first()
            ?.takeIf { it.sourceType == com.ljyh.mei.data.model.room.SourceType.DOWNLOAD }
        val path = song?.path
        if (!path.isNullOrBlank() && db.downloadArtifactDao().byUri(path) == null) {
            // Legacy files have no ownership receipt. Only an explicit user deletion reaches here.
            if (path.startsWith("content://")) {
                context.contentResolver.delete(android.net.Uri.parse(path), null, null)
            } else File(path).let { check(!it.exists() || it.delete()) }
        }
        db.withTransaction {
            if (song != null) db.songDao().updatePath(task.songId, null)
            db.downloadDao().deleteOwned(task.songId, task.requestId, task.ownerId)
        }
        publication.recover()
    }

    suspend fun cancelAll() = withContext(Dispatchers.IO) {
        mutations.withLock {
            val dao = db.downloadDao()
            dao.getAll().first().forEach { task ->
                dao.updateOwnedProgress(task.songId, task.requestId, task.ownerId,
                    DownloadStatus.PAUSED, task.progress, System.currentTimeMillis())
                task.workId()?.let { manager.cancelWorkById(it).result.await() }
            }
        }
    }

    private fun DownloadTask.workId(): UUID? = runCatching {
        UUID.fromString(requestId).takeIf { it.toString() == requestId }
    }.getOrNull()
}

private fun downloadWorkRequest(task: DownloadTask) = OneTimeWorkRequestBuilder<DownloadWorker>()
    .setId(UUID.fromString(task.requestId))
    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
    .addTag("download")
    .addTag("download_song_" + task.songId)
    .setInputData(androidx.work.workDataOf(DownloadWorker.KEY_SONG_ID to task.songId, DownloadWorker.KEY_OWNER_ID to task.ownerId))
    .build()
