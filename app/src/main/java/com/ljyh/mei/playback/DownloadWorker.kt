package com.ljyh.mei.playback

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.data.model.room.DownloadTask
import com.ljyh.mei.data.model.room.DownloadArtifact
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.utils.DownloadManager
import com.ljyh.mei.utils.SongMate
import com.ljyh.mei.utils.StringUtils.specialReplace
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jaudiotagger.audio.AudioFileIO
import timber.log.Timber

open class DownloadWorker internal constructor(
    context: Context, params: WorkerParameters, private val environment: DownloadWorkerEnvironment,
) : CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params, DownloadWorkerEnvironment.production(context))
    companion object {
        const val KEY_SONG_ID = "song_id"
        const val KEY_OWNER_ID = "owner_id"
        const val CHANNEL_ID = "download_channel"
        const val NOTIFICATION_ID = 1001
        private val slots = Semaphore(3)
        private val sharedClient by lazy {
            OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true).build()
        }
        fun getDownloadClient(): OkHttpClient = sharedClient
    }

    private val sessions get() = environment.sessions
    private val songId get() = inputData.getString(KEY_SONG_ID).orEmpty()
    private val accountId get() = inputData.getLong(KEY_OWNER_ID, 0)
    private lateinit var owner: SessionStamp
    private var failureTitle = "下载初始化失败"
    private var notice: DownloadNotificationState.Lease? = null
    private var notificationFence = 0L

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (songId.isBlank() || accountId <= 0) return@withContext Result.failure()
        val db = environment.database
        notificationFence = environment.notifications.fence()
        try {
            val completed = DownloadManager.mutations.withLock {
                environment.publication.recover()
                db.downloadDao().getOwned(songId, id.toString(), accountId)?.status == DownloadStatus.COMPLETED
            }
            if (completed) {
                return@withContext if (db.downloadArtifactDao().all().any {
                    it.requestId == id.toString() && it.phase == DownloadArtifact.PUBLISHED
                }) Result.success() else Result.retry()
            }
            owner = sessions.snapshot()
            sessions.requireDownloadOwner(owner)
            if (owner.identity.userId != accountId) throw SessionChangedException()
            sessions.withDownloadOwner(owner) {
                requireTask(db)
                failureTitle = "无法启动后台下载，请重试"
                showNotification("准备下载...", 0)
                slots.withPermit { processSong(db) }
            }
            Result.success()
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                val status = if (::owner.isInitialized && runCatching { sessions.requireDownloadOwner(owner) }.isSuccess) {
                    DownloadStatus.PENDING
                } else DownloadStatus.FAILED
                if (updateTask(db, status, 0) == 1) {
                    finishNotification(DownloadNotificationState.Outcome.CANCELED, "下载已取消")
                } else if (db.downloadDao().getOwned(songId, id.toString(), accountId)?.status == DownloadStatus.PAUSED) {
                    finishNotification(DownloadNotificationState.Outcome.CANCELED, "下载已暂停")
                }
            }
            throw error
        } catch (error: Exception) {
            Timber.w("Download failed: %s", error.javaClass.simpleName)
            if (updateTask(db, DownloadStatus.FAILED, 0) == 1) {
                finishNotification(DownloadNotificationState.Outcome.FAILED,
                    if (error is SessionChangedException) "账号会话已变化，请重新登录后下载" else failureTitle)
            } else if (db.downloadDao().getOwned(songId, id.toString(), accountId)?.status == DownloadStatus.COMPLETED) {
                return@withContext Result.retry()
            }
            Result.failure()
        } finally {
            environment.notifications.finish(notice, null)
            // WorkManager can stop the service before a canceled coroutine finishes cleanup.
            // The completion ID is distinct from the foreground ID, so either order is safe.
            if (notice != null) {
                environment.notifications.publishCompletion(applicationContext)
            }
        }
    }

    private suspend fun requireTask(db: AppDatabase): DownloadTask {
        currentCoroutineContext().ensureActive()
        sessions.requireDownloadOwner(owner)
        val task = db.downloadDao().getOwned(songId, id.toString(), accountId)
            ?: throw CancellationException("Download request was replaced or deleted")
        task.requireExecutable(id, accountId)
        return task
    }

    private suspend fun processSong(db: AppDatabase) = coroutineScope {
        val task = requireTask(db)
        check(updateTask(db, DownloadStatus.DOWNLOADING, 0) == 1)
        failureTitle = environment.sources.failureTitle
        val source = environment.sources.resolve(
            listOf(task.sourceKey.ifEmpty { songId }), MusicQuality.entries.single { it.text == task.quality }, owner).sources.singleOrNull()
            ?: throw IOException("Download source unavailable")
        requireTask(db)
        val root = task.downloadPath.trim().trim('/').ifBlank { "Music/Mei" }
        require(root.split('/').none { it == ".." || it == "." })
        val relativePath = "$root/${specialReplace(task.playlistName).trim().ifBlank { "未分类" }}"
        val fileName = "${specialReplace("${task.songTitle} - ${task.songArtist}")}.${source.fileType}"
        check(db.downloadDao().updateOwnedFileInfo(songId, id.toString(), accountId, fileName, source.fileType) == 1)
        val tempDir = File(applicationContext.cacheDir, "download").apply { check(isDirectory || mkdirs()) }
        val temp = File(tempDir, "$id.${source.fileType}")
        try {
            failureTitle = "下载文件失败，请重试"
            val lyric = async { environment.lyric(songId, owner) }
            val cover = async { if (task.songCover.isBlank()) null else environment.cover(task.songCover) }
            transferOfficialDownload(environment.client, source, temp, { requireTask(db); Unit }) { progress ->
                check(updateTask(db, DownloadStatus.DOWNLOADING, progress) == 1)
                showNotification("正在下载 (0/1)", progress)
            }
            requireTask(db)
            val lyricText = lyric.await()
            val coverBytes = cover.await()
            try {
                SongMate.writeTagsWithCoverBytes(task.songTitle, task.songArtist, task.songAlbum,
                    coverBytes, temp.absolutePath, lyricText)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { Timber.w("Download tags failed: %s", error.javaClass.simpleName) }
            requireTask(db)
            val duration = runCatching { AudioFileIO.read(temp).audioHeader.trackLength.toLong() * 1000 }.getOrDefault(0)
            failureTitle = "保存下载文件失败，请重试"
            val execution = currentCoroutineContext()
            DownloadManager.mutations.withLock {
                withContext(NonCancellable) {
                    requireTask(db)
                    environment.publication.store(task, temp, fileName, source.fileType, relativePath, duration,
                        requireActive = {
                            execution.ensureActive()
                            sessions.requireDownloadOwner(owner)
                        },
                        withOwner = { commit -> sessions.withCurrent(owner) {
                            execution.ensureActive()
                            if (sessions.recoveryRequired.value) throw SessionChangedException()
                            commit()
                        } },
                    )
                    environment.publication.recover()
                }
            }
            finishNotification(DownloadNotificationState.Outcome.SUCCESS, "全部下载完成")
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                temp.delete()
            }
        }
    }

    private suspend fun updateTask(db: AppDatabase, status: DownloadStatus, progress: Int): Int =
        db.downloadDao().updateOwnedProgress(songId, id.toString(), accountId, status, progress, System.currentTimeMillis())

    private suspend fun showNotification(title: String, progress: Int) {
        environment.notification?.let { it(title, progress, true); return }
        if (notice == null) {
            environment.notifications.createChannel(applicationContext)
            notice = sessions.withCurrent(owner) {
                environment.notifications.begin(applicationContext, id.toString(), accountId, owner.generation)
            }
        }
        val info = sessions.withCurrent(owner) {
            environment.notifications.progress(applicationContext, checkNotNull(notice), title, progress)
        }
        setForeground(info)
    }

    private fun finishNotification(outcome: DownloadNotificationState.Outcome, title: String) {
        val progress = if (outcome == DownloadNotificationState.Outcome.SUCCESS) 100 else 0
        environment.notification?.let { it(title, progress, false); return }
        environment.notifications.finish(notice, outcome, title)
        if (notice == null) environment.notifications.publishPreflightFailure(applicationContext, notificationFence, id.toString(), title)
    }
}

data class SongDownloadInfo(
    val songId: String,
    val songTitle: String,
    val songArtist: List<String>,
    val songAlbum: String,
    val songCover: String,
    val duration: Long,
    val quality: String = "",
    val sourceKey: String = "",
)
