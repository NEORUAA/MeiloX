package com.ljyh.mei.playback

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import com.ljyh.mei.R
import com.ljyh.mei.constants.AutoCacheEnabledKey
import com.ljyh.mei.constants.AutoCachePlaybackThresholdKey
import com.ljyh.mei.constants.AutoCacheQualityKey
import com.ljyh.mei.constants.DownloadPathKey
import com.ljyh.mei.constants.DownloadQuality
import com.ljyh.mei.source.api.*
import com.ljyh.mei.data.model.metadata
import kotlinx.coroutines.CancellationException
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.utils.DownloadManager
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.get
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Counts a queue entry only after actual playback, never from paused restoration or buffering. */
internal class AutomaticCachePlaybackTracker(private val scope: CoroutineScope) {
    private var pending: Job? = null
    private var recorded = false

    fun update(isPlaying: Boolean, recordPlayback: suspend () -> Unit) {
        if (!isPlaying) {
            if (!recorded) {
                pending?.cancel()
                pending = null
            }
            return
        }
        if (recorded || pending != null) return
        pending = scope.launch {
            delay(5_000L)
            recorded = true
            recordPlayback()
        }
    }

    fun reset() {
        pending?.cancel()
        pending = null
        recorded = false
    }
}

@Singleton
class AutomaticCacheController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaUriProvider: MediaUriProvider,
    private val database: AppDatabase,
) {
    suspend fun recordPlayback(mediaItem: MediaItem) {
        val songId = mediaItem.mediaId.takeIf(String::isNotBlank) ?: return
        database.downloadDao().recordPlayback(songId)
        val count = database.downloadDao().playbackCount(songId) ?: return
        if (context.dataStore[AutoCacheEnabledKey] != true) return
        val threshold = (context.dataStore[AutoCachePlaybackThresholdKey] ?: 5)
            .takeIf { it in setOf(3, 5, 10, 20) } ?: 5
        if (count < threshold || hasLocalCopy(songId) || isActive(songId)) return

        val quality = runCatching {
            DownloadQuality.valueOf(context.dataStore[AutoCacheQualityKey] ?: DownloadQuality.EXHIGH.name)
        }.getOrDefault(DownloadQuality.EXHIGH)
        val metadata = mediaItem.mediaMetadata
        val trackRef = mediaItem.metadata?.trackRef ?: TrackRefCodec.decode(songId) ?: return
        val resolution = try {
            mediaUriProvider.resolveDownloadSource(Track(trackRef,
                metadata.title?.toString().orEmpty(),
                mediaItem.metadata?.artists?.map { SourceArtist(it.name, it.ref) }.orEmpty(),
                SourceAlbum(metadata.albumTitle?.toString().orEmpty()),
                mediaItem.metadata?.duration ?: 0L, metadata.artworkUri?.toString().orEmpty()), quality.toMusicQuality().text)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            Timber.w("Automatic cache could not resolve source")
            return
        }
        if (resolution.resource.cachePolicy == CachePolicy.NO_STORE) return
        val source = resolution.resource
        val artists = metadata.extras?.getStringArrayList("artist_list")
            ?.filter(String::isNotBlank)
            .orEmpty()
            .ifEmpty { listOf(context.getString(R.string.unknown_artist)) }
        DownloadManager.enqueue(
            context = context,
            songs = listOf(
                SongDownloadInfo(
                    songId = songId,
                    url = source.uri,
                    songTitle = metadata.title?.toString().orEmpty().ifBlank { context.getString(R.string.unknown_song) },
                    songArtist = artists,
                    songAlbum = metadata.albumTitle?.toString().orEmpty(),
                    songCover = metadata.artworkUri?.toString().orEmpty(),
                    duration = metadata.durationMs ?: 0,
                    fileType = "",
                    quality = quality.toMusicQuality().text,
                    originTrackRef = trackRef,
                    resolution = resolution,
                ),
            ),
            playlistName = context.getString(R.string.automatic_cache),
            playlistId = "automatic_$songId",
            downloadPath = context.dataStore[DownloadPathKey] ?: DownloadManager.getDefaultDownloadPath(),
        )
    }

    private suspend fun isActive(songId: String): Boolean {
        val task = database.downloadDao().getBySongId(songId) ?: return false
        return task.status == com.ljyh.mei.data.model.room.DownloadStatus.PENDING ||
            task.status == com.ljyh.mei.data.model.room.DownloadStatus.DOWNLOADING ||
            task.status == com.ljyh.mei.data.model.room.DownloadStatus.COMPLETED
    }

    private suspend fun hasLocalCopy(songId: String): Boolean {
        val path = database.songDao().getSong(songId).first()?.path ?: return false
        return path.startsWith("content://") || File(path).exists()
    }
}
