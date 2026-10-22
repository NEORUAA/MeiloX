package com.ljyh.mei.ui.screen.local

import android.content.ContentUris
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import androidx.room.withTransaction
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.model.room.SourceType
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.utils.localAudioPublicationMutex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.File

data class LocalScanState(
    val isScanning: Boolean = false,
    val scanned: Int = 0,
    val total: Int = 0,
    val added: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    val error: String? = null,
    val message: String? = null,
) {
    val progress: Float get() = if (total > 0) scanned.toFloat() / total else 0f
}

internal class LocalLibraryScanner(private val context: Context, private val database: AppDatabase) {
    suspend fun scan(onProgress: (LocalScanState) -> Unit): LocalScanState = withContext(Dispatchers.IO) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATA,
        )
        var state = LocalScanState(isScanning = true)
        onProgress(state)
        val cursor = context.contentResolver.query(
            collection, projection,
            "${MediaStore.Audio.Media.IS_PENDING} = 0 AND ${MediaStore.Audio.Media.SIZE} > 0 AND ${MediaStore.Audio.Media.DURATION} > 0",
            null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        ) ?: error("无法读取设备音乐库")
        cursor.use { rows ->
            state = state.copy(total = rows.count)
            onProgress(state)
            while (rows.moveToNext()) {
                currentCoroutineContext().ensureActive()
                fun text(column: String) = rows.getString(rows.getColumnIndexOrThrow(column)).orEmpty()
                fun number(column: String) = rows.getLong(rows.getColumnIndexOrThrow(column))
                try {
                    val mediaId = number(MediaStore.Audio.Media._ID)
                    val uri = ContentUris.withAppendedId(collection, mediaId)
                    val path = uri.toString()
                    val legacyPath = text(MediaStore.Audio.Media.DATA)
                    val localId = "local_$mediaId"
                    val known = database.songDao().getByPath(path)
                        ?: legacyPath.takeIf(String::isNotBlank)?.let { database.songDao().getByPath(it) }
                        ?: database.songDao().getById(localId)
                    val displayName = text(MediaStore.Audio.Media.DISPLAY_NAME)
                    val fileFormat = displayName.substringAfterLast('.', "").lowercase().takeIf(String::isNotBlank)
                    val albumId = number(MediaStore.Audio.Media.ALBUM_ID)
                    var scanned = Song(
                        id = localId,
                        title = text(MediaStore.Audio.Media.TITLE).ifBlank { displayName.substringBeforeLast('.') },
                        artist = text(MediaStore.Audio.Media.ARTIST).takeUnless { it.isBlank() || it == "<unknown>" }
                            ?.let(::listOf).orEmpty(),
                        album = text(MediaStore.Audio.Media.ALBUM).takeUnless { it == "<unknown>" }.orEmpty(),
                        cover = if (albumId > 0) "content://media/external/audio/albumart/$albumId" else "",
                        duration = number(MediaStore.Audio.Media.DURATION),
                        path = path,
                        sourceType = SourceType.LOCAL,
                        fileSize = number(MediaStore.Audio.Media.SIZE),
                        fileFormat = fileFormat,
                        folderPath = legacyPath.substringBeforeLast('/', "").takeIf(String::isNotBlank),
                    )
                    scanned = readMetadata(scanned, uri, readArtwork = known?.cover.isNullOrBlank())
                    currentCoroutineContext().ensureActive()
                    localAudioPublicationMutex.withLock {
                        val available = context.contentResolver.query(
                            uri, arrayOf(MediaStore.Audio.Media._ID),
                            "${MediaStore.Audio.Media.IS_PENDING} = 0", null, null,
                        )?.use { it.moveToFirst() } == true
                        if (!available) {
                            state = state.copy(skipped = state.skipped + 1)
                        } else database.withTransaction {
                            val current = database.songDao().getByPath(path)
                                ?: legacyPath.takeIf(String::isNotBlank)?.let { database.songDao().getByPath(it) }
                                ?: database.songDao().getById(localId)
                            // Reconcile after publication commits, retaining history and selected tags.
                            val merged = mergeScannedSong(current, scanned)
                            if (current == null) database.songDao().insertSong(merged)
                            else database.songDao().updateSong(merged)
                            state = if (current == null) state.copy(added = state.added + 1)
                            else state.copy(updated = state.updated + 1)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    state = state.copy(skipped = state.skipped + 1)
                }
                state = state.copy(scanned = state.scanned + 1)
                onProgress(state)
            }
        }
        state.copy(isScanning = false, message = "扫描完成，新增 ${state.added} 首歌曲").also(onProgress)
    }

    private fun readMetadata(song: Song, uri: android.net.Uri, readArtwork: Boolean): Song {
        var result = song
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun metadata(key: Int) = retriever.extractMetadata(key)?.trim()?.takeIf(String::isNotBlank)
            val cover = (if (readArtwork) retriever.embeddedPicture else null)?.let { bytes ->
                val directory = File(context.filesDir, "local-artwork").apply { mkdirs() }
                File(directory, "${song.id}.jpg").apply { writeBytes(bytes) }.toURI().toString()
            } ?: song.cover
            result = song.copy(
                title = metadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: song.title,
                artist = metadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let(::listOf) ?: song.artist,
                album = metadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: song.album,
                cover = cover,
                duration = metadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
                    ?: song.duration,
                bitrate = metadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.takeIf { it > 0 },
            )
        } catch (_: Exception) {
            // Keep MediaStore facts when an optional tag parser rejects the format.
        } finally {
            runCatching { retriever.release() }
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") != true) continue
                fun positiveInteger(key: String): Int? = if (format.containsKey(key)) {
                    format.getInteger(key).takeIf { it > 0 }
                } else null
                result = result.copy(
                    bitrate = result.bitrate ?: positiveInteger(MediaFormat.KEY_BIT_RATE),
                    sampleRate = positiveInteger(MediaFormat.KEY_SAMPLE_RATE),
                )
                break
            }
        } catch (_: Exception) {
            // Technical information is optional; the file remains available for playback.
        } finally {
            runCatching { extractor.release() }
        }
        return result
    }
}
