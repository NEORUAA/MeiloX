package com.ljyh.mei.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ljyh.mei.MainActivity
import com.ljyh.mei.R
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.data.model.room.DownloadTask
import com.ljyh.mei.data.model.room.DownloadArtifact
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.di.AppGraph
import com.ljyh.mei.parasite.HostSessionChangedException
import com.ljyh.mei.parasite.HostSessionStamp
import com.ljyh.mei.utils.DownloadManager
import com.ljyh.mei.utils.ImageUtils
import com.ljyh.mei.utils.LyricFetcher
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

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
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
        fun createNotificationChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, "音乐下载", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "歌曲下载进度通知" }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private val sessions get() = AppGraph.component.hostRequests().sessions
    private val songId get() = inputData.getString(KEY_SONG_ID).orEmpty()
    private val accountId get() = inputData.getLong(KEY_OWNER_ID, 0)
    private lateinit var owner: HostSessionStamp
    private var failureTitle = "下载初始化失败"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (songId.isBlank() || accountId <= 0) return@withContext Result.failure()
        val db = AppDatabase.getDatabase(applicationContext)
        try {
            val completed = DownloadManager.mutations.withLock {
                publication(db).recover()
                db.downloadDao().getOwned(songId, id.toString(), accountId)?.status == DownloadStatus.COMPLETED
            }
            if (completed) {
                return@withContext if (db.downloadArtifactDao().all().any {
                    it.requestId == id.toString() && it.phase == DownloadArtifact.PUBLISHED
                }) Result.success() else Result.retry()
            }
            owner = sessions.snapshot()
            sessions.requireDownloadOwner(owner)
            if (owner.identity.userId != accountId) throw HostSessionChangedException()
            sessions.withDownloadOwner(owner) {
                requireTask(db)
                slots.withPermit { processSong(db) }
            }
            Result.success()
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                val status = if (::owner.isInitialized && runCatching { sessions.requireDownloadOwner(owner) }.isSuccess) {
                    DownloadStatus.PENDING
                } else DownloadStatus.FAILED
                if (updateTask(db, status, 0) == 1) showNotification("下载已取消", 0, ongoing = false)
            }
            throw error
        } catch (error: Exception) {
            Timber.w("Download failed: %s", error.javaClass.simpleName)
            if (updateTask(db, DownloadStatus.FAILED, 0) == 1) {
                showNotification(if (error is HostSessionChangedException) "账号会话已变化，请重新登录后下载" else failureTitle, 0, ongoing = false)
            } else if (db.downloadDao().getOwned(songId, id.toString(), accountId)?.status == DownloadStatus.COMPLETED) {
                return@withContext Result.retry()
            }
            Result.failure()
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
        createNotificationChannel(applicationContext)
        check(updateTask(db, DownloadStatus.DOWNLOADING, 0) == 1)
        showNotification("准备下载...", 0)
        failureTitle = "获取官方下载授权失败"
        val source = resolveOfficialDownloadSources(AppGraph.component.apiService(), sessions,
            listOf(songId), MusicQuality.entries.single { it.text == task.quality }, owner).sources.singleOrNull()
            ?: throw IOException("Official download permission denied")
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
            val lyric = async { LyricFetcher.fetchBestLyric(songId, owner) }
            val cover = async { if (task.songCover.isBlank()) null else ImageUtils.downloadImageBytes(task.songCover) }
            transferOfficialDownload(getDownloadClient(), source, temp, { requireTask(db); Unit }) { progress ->
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
                    publication(db).store(task, temp, fileName, source.fileType, relativePath, duration,
                        requireActive = {
                            execution.ensureActive()
                            sessions.requireDownloadOwner(owner)
                        },
                        withOwner = { commit -> sessions.withCurrent(owner) {
                            execution.ensureActive()
                            if (sessions.recoveryRequired.value) throw HostSessionChangedException()
                            commit()
                        } },
                    )
                    publication(db).recover()
                }
            }
            showNotification("全部下载完成", 100, ongoing = false)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                temp.delete()
            }
        }
    }

    private fun publication(db: AppDatabase) = DownloadPublication(db, AndroidDownloadMediaStore(applicationContext), applicationContext.packageName)

    private suspend fun updateTask(db: AppDatabase, status: DownloadStatus, progress: Int): Int =
        db.downloadDao().updateOwnedProgress(songId, id.toString(), accountId, status, progress, System.currentTimeMillis())

    private fun showNotification(title: String, progress: Int, ongoing: Boolean = progress < 100) {
        try {
            val intent = com.ljyh.mei.parasite.HostAppComponentHooks.route(
                Intent(applicationContext, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP })
            val pendingIntent = PendingIntent.getActivity(applicationContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setContentTitle(title).setContentText("Mei 音乐下载")
                .setSmallIcon(R.drawable.baseline_download_24).setOngoing(ongoing)
                .setProgress(100, progress, false).setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW).build()
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
        } catch (error: SecurityException) {
            Timber.w("Download notification permission unavailable")
        }
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
)
