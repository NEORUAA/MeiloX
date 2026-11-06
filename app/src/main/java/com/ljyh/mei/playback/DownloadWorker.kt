package com.ljyh.mei.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ljyh.mei.MainActivity
import com.ljyh.mei.R
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.script.ScriptAudioDataSource
import com.ljyh.mei.source.script.ScriptPluginManager
import androidx.media3.datasource.DataSpec
import androidx.media3.common.C
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.model.room.SourceType
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.utils.ImageUtils
import com.ljyh.mei.utils.AudioFileFormat
import com.ljyh.mei.utils.detectAudioFileFormat
import com.ljyh.mei.utils.localAudioPublicationMutex
import com.ljyh.mei.utils.LyricFetcher
import com.ljyh.mei.utils.SongMate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jaudiotagger.audio.AudioFileIO
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface DownloadSourceEntryPoint {
    fun mediaUriProvider(): MediaUriProvider
    fun sourceRouter(): com.ljyh.mei.source.core.SourceRouter
    fun sourcePlugins(): ScriptPluginManager
}

class DownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_SONG_IDS = "song_ids_json"
        const val KEY_PLAYLIST_NAME = "playlist_name"
        const val KEY_DOWNLOAD_PATH = "download_path"
        const val CHANNEL_ID = "download_channel"
        const val NOTIFICATION_ID = 1001
        private const val CONCURRENCY = 3

        @Volatile
        private var sharedClient: OkHttpClient? = null

        fun getDownloadClient(): OkHttpClient {
            return sharedClient ?: synchronized(this) {
                sharedClient ?: OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(300, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build().also { sharedClient = it }
            }
        }

        fun createNotificationChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音乐下载",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "歌曲下载进度通知"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private val okHttpClient = getDownloadClient()
    private val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val failedCount = java.util.concurrent.atomic.AtomicInteger(0)

    override suspend fun doWork(): Result {
        Timber.d("DownloadWorker started, runAttemptCount=$runAttemptCount")

        val songIdsJson = inputData.getString(KEY_SONG_IDS) ?: run {
            Timber.e("KEY_SONG_IDS not found in inputData")
            return Result.failure()
        }
        val playlistName = inputData.getString(KEY_PLAYLIST_NAME) ?: "未分类"
        val downloadPath = inputData.getString(KEY_DOWNLOAD_PATH)
            ?: "Music/Mei"

        val songIds: List<String> = try {
            Gson().fromJson(songIdsJson, object : TypeToken<List<String>>() {}.type)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse songIds")
            return Result.failure()
        }

        if (songIds.isEmpty()) return Result.success()

        Timber.d("DownloadWorker will process ${songIds.size} songs, path=$downloadPath")
        createNotificationChannel(applicationContext)
        val db = AppDatabase.getDatabase(applicationContext)
        val totalCount = songIds.size

        val sanitizedPlaylistName = DownloadFileNames.playlistName(playlistName)
        val configuredRoot = downloadPath.trim().trim('/').ifBlank { "Music/Mei" }
        val relativePath = "$configuredRoot/$sanitizedPlaylistName"
        val tempDir = File(applicationContext.cacheDir, "download")
        if (!tempDir.exists()) tempDir.mkdirs()

        val ownedTasks = songIds.mapNotNull { songId -> db.downloadDao().getBySongId(songId)?.takeIf {
            it.requestId == id.toString() || (it.requestId.isBlank() && it.status in activeDownloadStates)
        } }
        if (ownedTasks.isEmpty()) return Result.success()
        if (ownedTasks.any { it.originTrackRef != null }) {
            try {
                // A cold-start worker can run before the application's asynchronous source load.
                EntryPointAccessors.fromApplication(applicationContext, DownloadSourceEntryPoint::class.java)
                    .sourcePlugins().loadInstalled()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.w("Download source initialization unavailable (%s)", error.javaClass.simpleName)
                return Result.retry()
            }
        }
        showNotification("准备下载...", 0)

        val semaphore = Semaphore(CONCURRENCY)
        supervisorScope {
            val jobs = songIds.map { songId ->
                async(Dispatchers.IO) {
                    if (isStopped) return@async

                    semaphore.withPermit {
                        if (isStopped) return@withPermit

                        processSong(songId, db, tempDir, relativePath)
                    }

                    currentCoroutineContext().ensureActive()
                    val owned = db.downloadDao().getBySongId(songId)
                    if (owned?.requestId != id.toString() || owned.status == DownloadStatus.PAUSED) return@async
                    val done = completedCount.get() + failedCount.get()
                    showNotification("正在下载 ($done/$totalCount)", done * 100 / totalCount)
                }
            }

            jobs.awaitAll()
        }

        if (isStopped) {
            showNotification("下载已取消", 0, ongoing = false)
            return Result.failure()
        }

        currentCoroutineContext().ensureActive()
        if (songIds.none { db.downloadDao().getBySongId(it)?.requestId == id.toString() }) return Result.success()
        val done = completedCount.get()
        val failed = failedCount.get()
        val statusText = if (failed > 0) "完成 $done, 失败 $failed" else "全部下载完成"
        showNotification(statusText, 100, ongoing = false)

        return Result.success()
    }

    private suspend fun processSong(
        songId: String,
        db: AppDatabase,
        tempDir: File,
        relativePath: String,
    ) = coroutineScope {
        val dao = db.downloadDao()
        dao.claimLegacyRequest(songId, id.toString())
        var task = dao.getBySongId(songId) ?: return@coroutineScope
        if (task.requestId != id.toString() || task.status !in activeDownloadStates) return@coroutineScope
        if (task.url.isBlank() && task.originTrackRef == null) {
            updateTask(db, songId, DownloadStatus.FAILED, 0)
            failedCount.incrementAndGet()
            return@coroutineScope
        }
        val existingSong = db.songDao().getSong(songId).first()
        val existingPath = existingSong?.path
        if (existingPath != null) {
            val valid = runCatching {
                if (existingPath.startsWith("content://")) {
                    applicationContext.contentResolver.openInputStream(existingPath.toUri())?.use { true } ?: false
                } else File(existingPath).isFile
            }.getOrDefault(false)
            if (valid) {
                if (!existingPath.startsWith("content://")) {
                    val tags = SongMate.checkTags(existingPath)
                    if (tags != null && (!tags.isBasicComplete || !tags.hasLyric)) {
                        try {
                            val lyric = if (!tags.hasLyric) downloadLyric(task, songId) else null
                            currentCoroutineContext().ensureActive()
                            SongMate.writeTags(task.songTitle, task.songArtist, task.songAlbum,
                                task.songCover, existingPath, lyric)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { Timber.w(error, "Could not repair existing download tags") }
                    }
                }
                updateTask(db, songId, DownloadStatus.COMPLETED, 100)
                completedCount.incrementAndGet()
                return@coroutineScope
            }
        }
        val resolution = task.originTrackRef?.let { encoded ->
            try {
                val ref = TrackRefCodec.decode(encoded) ?: throw SourceException(SourceErrorCode.INVALID_RESPONSE)
                val resolver = EntryPointAccessors.fromApplication(applicationContext, DownloadSourceEntryPoint::class.java)
                    .mediaUriProvider()
                resolver.resolveDownloadSource(Track(ref, task.songTitle,
                    task.songArtist.split('/').filter(String::isNotBlank).map { SourceArtist(it) },
                    SourceAlbum(task.songAlbum), durationMs = existingSong?.duration ?: 0, artworkUrl = task.songCover), task.quality.ifBlank { "exhigh" })
                    .also { check(it.resource.cachePolicy != CachePolicy.NO_STORE) { "This source does not allow downloads" } }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                Timber.w("Download source unavailable (%s)", error.javaClass.simpleName)
                updateTask(db, songId, DownloadStatus.FAILED, 0)
                failedCount.incrementAndGet()
                return@coroutineScope
            }
        }
        if (resolution != null) {
            db.withTransaction {
                val owned = dao.getBySongId(songId)
                if (owned?.requestId != id.toString() || owned.status !in activeDownloadStates) {
                    throw CancellationException("Download task was retired")
                }
                task = owned.copy(
                    resolvedTrackRef = TrackRefCodec.encode(resolution.resolvedTrackRef),
                    actualAudioProviderId = resolution.actualAudioProviderId,
                    actualProviderInstanceId = resolution.instanceId.value,
                    actualQuality = resolution.resource.quality,
                )
                dao.insert(task)
            }
        }
        val audioUrl = resolution?.resource?.uri ?: task.url
        val audioHeaders = resolution?.resource?.headers.orEmpty()
        updateTask(db, songId, DownloadStatus.DOWNLOADING, 0)
        val tempFile = try {
            createDownloadTempFile(tempDir, id.toString(), "download")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            Timber.e(error, "Could not create download temporary file")
            updateTask(db, songId, DownloadStatus.FAILED, 0)
            failedCount.incrementAndGet()
            return@coroutineScope
        }
        try {
            val onProgress: (Int) -> Unit = { progress ->
                launch { updateTask(db, songId, DownloadStatus.DOWNLOADING, progress) }
            }
            if (ScriptAudioDataSource.accepts(audioUrl.toUri())) {
                downloadScriptAudio(applicationContext, audioUrl, tempFile, onProgress)
            } else downloadAudioFile(okHttpClient, audioUrl, tempFile, audioHeaders, onProgress)
            currentCoroutineContext().ensureActive()
            val format = checkNotNull(detectAudioFileFormat(tempFile)) {
                "Unsupported or malformed downloaded audio"
            }
            val fileName = "${DownloadFileNames.songBaseName(task.songTitle, task.songArtist)}.${format.extension}"
            val lyric = async { downloadLyric(task, songId) }
            val cover = async { if (task.songCover.isNotBlank()) ImageUtils.downloadImageBytes(task.songCover) else null }
            try {
                SongMate.writeTagsWithCoverBytes(task.songTitle, task.songArtist, task.songAlbum,
                    cover.await(), tempFile.absolutePath, lyric.await())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w(error, "Could not write optional download tags")
            }
            currentCoroutineContext().ensureActive()
            check(detectAudioFileFormat(tempFile) == format) { "Audio container changed while writing tags" }
            val duration = runCatching { AudioFileIO.readAs(tempFile, format.extension).audioHeader.trackLength.toLong() * 1_000L }
                .getOrDefault(0L)
            localAudioPublicationMutex.withLock {
                var published: Uri? = null
                try {
                    published = publishDownloadedAudio(applicationContext, tempFile, fileName, format.extension, relativePath)
                    val uri = checkNotNull(published) { "Could not publish downloaded audio" }
                    if (saveDownloadedSong(db, songId, task, uri, duration, relativePath,
                            format, fileName, tempFile.length())) {
                        published = null
                        completedCount.incrementAndGet()
                    }
                } finally {
                    published?.let { uri -> withContext(NonCancellable) {
                        // Keep publication cleanup inside the scanner's reconciliation lock.
                        // Cancellation may arrive after Room commits but before it returns.
                        if (db.songDao().getSong(songId).first()?.path != uri.toString()) {
                            applicationContext.contentResolver.delete(uri, null, null)
                        }
                    } }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.e(error, "Download failed for ${task.songTitle}")
            failedCount.incrementAndGet()
            updateTask(db, songId, DownloadStatus.FAILED, 0)
        } finally {
            tempFile.delete()
        }
    }

    private suspend fun downloadLyric(task: com.ljyh.mei.data.model.room.DownloadTask, songId: String): String? {
        val ref = task.originTrackRef?.let(TrackRefCodec::decode) ?: TrackRefCodec.decode(songId)
        if (ref == null || ref.providerId == ProviderIds.NETEASE) return LyricFetcher.fetchBestLyric(ref?.trackId ?: songId)
        return try {
            EntryPointAccessors.fromApplication(applicationContext, DownloadSourceEntryPoint::class.java)
                .sourceRouter().lyrics(ref)?.text
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { null }
    }

    private suspend fun saveDownloadedSong(
        db: AppDatabase,
        songId: String,
        task: com.ljyh.mei.data.model.room.DownloadTask,
        uri: Uri,
        durationMs: Long,
        relativePath: String,
        format: AudioFileFormat,
        fileName: String,
        fileSize: Long,
    ): Boolean = db.withTransaction {
        currentCoroutineContext().ensureActive()
        val current = db.downloadDao().getBySongId(songId)
        if (current?.requestId != id.toString() || current.status !in activeDownloadStates) return@withTransaction false
        val existing = db.songDao().getById(songId)
        val downloaded = Song(
            id = songId,
            title = task.songTitle,
            artist = task.songArtist
                .split(Regex("[/、,;]"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .ifEmpty { listOf(task.songArtist.trim()) },
            album = task.songAlbum,
            cover = task.songCover,
            duration = durationMs,
            path = uri.toString(),
            sourceType = SourceType.DOWNLOAD,
            fileFormat = format.extension,
            fileSize = fileSize,
            folderPath = relativePath,
            addedAt = existing?.addedAt ?: System.currentTimeMillis(),
            providerId = task.originTrackRef?.let(TrackRefCodec::decode)?.providerId ?: existing?.providerId ?: ProviderIds.NETEASE,
            providerTrackId = task.originTrackRef?.let(TrackRefCodec::decode)?.trackId ?: existing?.providerTrackId ?: songId,
        )
        if (existing == null) db.songDao().insertSong(downloaded)
        else db.songDao().updateSong(downloaded)
        db.downloadDao().updateFileInfo(songId, task.url, fileName, format.extension)
        check(db.downloadDao().updateOwnedProgress(songId, id.toString(), DownloadStatus.COMPLETED,
            100, System.currentTimeMillis()) == 1)
        true
    }

    private suspend fun updateTask(
        db: AppDatabase,
        songId: String,
        status: DownloadStatus,
        progress: Int,
    ) {
        currentCoroutineContext().ensureActive()
        if (db.downloadDao().updateOwnedProgress(songId, id.toString(), status,
                progress.coerceAtMost(if (status == DownloadStatus.COMPLETED) 100 else 99),
                System.currentTimeMillis()) == 0) {
            throw CancellationException("Download task was retired")
        }
    }

    private fun showNotification(title: String, progress: Int, ongoing: Boolean = progress < 100) {
        try {
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                applicationContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText("Mei 音乐下载")
                .setSmallIcon(R.drawable.baseline_download_24)
                .setOngoing(ongoing)
                .setProgress(100, progress, !ongoing)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Timber.w(e, "Notification permission not granted")
        }
    }
}

internal fun createDownloadTempFile(directory: File, requestId: String, suffix: String): File =
    File.createTempFile("audio-$requestId-", ".$suffix", directory)

internal suspend fun publishDownloadedAudio(
    context: Context,
    srcFile: File,
    displayName: String,
    fileType: String,
    relativePath: String
): Uri? {
    val mimeType = AudioFileFormat.entries.firstOrNull { it.extension == fileType.lowercase() }
        ?.mimeType ?: "audio/mpeg"
    val contentValues = ContentValues().apply {
        put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Audio.Media.RELATIVE_PATH, "$relativePath/")
        put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
        put(MediaStore.Audio.Media.IS_PENDING, 1)
    }
    val uri = context.contentResolver.insert(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues
    ) ?: return null

    try {
        checkNotNull(context.contentResolver.openOutputStream(uri)).use { os ->
            srcFile.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val size = input.read(buffer)
                    if (size < 0) break
                    os.write(buffer, 0, size)
                }
            }
        }
        contentValues.clear()
        contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
        currentCoroutineContext().ensureActive()
        check(context.contentResolver.update(uri, contentValues, null, null) == 1) {
            "Downloaded audio publication was rejected"
        }
        return uri
    } catch (cancelled: CancellationException) {
        context.contentResolver.delete(uri, null, null)
        throw cancelled
    } catch (e: Exception) {
        Timber.e(e, "Failed to write to MediaStore")
        context.contentResolver.delete(uri, null, null)
        return null
    }
}


private val activeDownloadStates = setOf(DownloadStatus.PENDING, DownloadStatus.DOWNLOADING)

/** Cancellation closes the call during connect, headers and response-body consumption. */
internal suspend fun <T> readDownloadResponse(
    client: OkHttpClient,
    request: Request,
    consume: (Response) -> T,
): T = withContext(Dispatchers.IO) {
    suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        try {
            if (!continuation.isActive) throw CancellationException("Download canceled")
            val value = call.execute().use(consume)
            continuation.resume(value)
        } catch (error: Throwable) { continuation.resumeWithException(error) }
    }
}

internal suspend fun downloadAudioFile(
    client: OkHttpClient,
    url: String,
    file: File,
    headers: Map<String, String> = emptyMap(),
    onProgress: (Int) -> Unit,
) = withContext(Dispatchers.IO) {
    val owner = currentCoroutineContext()
    try {
        readDownloadResponse(client, Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()) { response ->
            check(response.isSuccessful) { "Audio download failed (${response.code})" }
            val body = response.body
            val total = body.contentLength()
            var received = 0L
            var lastProgress = -1
            file.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        owner.ensureActive()
                        val size = input.read(buffer)
                        if (size < 0) break
                        output.write(buffer, 0, size)
                        received += size
                        if (total > 0) {
                            val progress = (received * 100 / total).coerceAtMost(99).toInt()
                            if (progress != lastProgress) { lastProgress = progress; onProgress(progress) }
                        }
                    }
                }
            }
            check(received > 0 && (total < 0 || received == total)) { "Incomplete audio download" }
        }
    } catch (error: Throwable) { file.delete(); throw error }
}

/** Reads through the same permission-enforcing transport used by Media3. */
internal suspend fun downloadScriptAudio(
    context: Context,
    resource: String,
    file: File,
    onProgress: (Int) -> Unit,
) = withContext(Dispatchers.IO) {
    val source = ScriptAudioDataSource.Factory(context).createDataSource()
    try {
        val total = source.open(DataSpec.Builder().setUri(resource).build())
        var received = 0L
        file.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = source.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                if (count == 0) continue
                output.write(buffer, 0, count)
                received += count
                if (total > 0) onProgress((received * 100 / total).coerceAtMost(99).toInt())
            }
        }
        check(received > 0 && (total < 0 || received == total)) { "Incomplete audio download" }
    } catch (error: Throwable) { file.delete(); throw error
    } finally { source.close() }
}

data class SongDownloadInfo(
    val songId: String,
    val url: String?,
    val songTitle: String,
    val songArtist: List<String>,
    val songAlbum: String,
    val songCover: String,
    val duration: Long,
    val fileType: String = "",
    val quality: String = "",
    val lyric: String = "",
    val originTrackRef: TrackRef? = null,
    val resolution: PlaybackResolution? = null,
)
