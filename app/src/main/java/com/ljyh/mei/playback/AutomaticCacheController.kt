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
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.PlaylistRepository
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.utils.DownloadManager
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.get
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutomaticCacheController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: PlaylistRepository,
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
        val result = repository.getSongUrlV1(listOf(songId), quality.toMusicQuality())
        val source = (result as? Resource.Success)?.data?.fullSourceFor(songId)
        if (source?.url == null) {
            Timber.w("Automatic cache could not resolve source for %s", songId)
            return
        }

        val metadata = mediaItem.mediaMetadata
        val artists = metadata.extras?.getStringArrayList("artist_list")
            ?.filter(String::isNotBlank)
            .orEmpty()
            .ifEmpty { listOf(context.getString(R.string.unknown_artist)) }
        DownloadManager.enqueue(
            context = context,
            songs = listOf(
                SongDownloadInfo(
                    songId = songId,
                    url = source.url,
                    songTitle = metadata.title?.toString().orEmpty().ifBlank { context.getString(R.string.unknown_song) },
                    songArtist = artists,
                    songAlbum = metadata.albumTitle?.toString().orEmpty(),
                    songCover = metadata.artworkUri?.toString().orEmpty(),
                    duration = metadata.durationMs ?: 0,
                    fileType = source.encodeType,
                    quality = source.level,
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
