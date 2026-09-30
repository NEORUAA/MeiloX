package com.ljyh.mei.playback

import android.content.Context
import androidx.media3.common.MediaItem
import com.ljyh.mei.data.model.sourceKey
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.R
import com.ljyh.mei.constants.AutoCacheEnabledKey
import com.ljyh.mei.constants.AutoCachePlaybackThresholdKey
import com.ljyh.mei.constants.AutoCacheQualityKey
import com.ljyh.mei.constants.DownloadPathKey
import com.ljyh.mei.constants.DownloadQuality
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.utils.DownloadManager
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.get
import com.ljyh.mei.di.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionStamp

@Singleton
class AutomaticCacheController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val sessions: SessionStore,
) {
    suspend fun recordPlayback(mediaItem: MediaItem, owner: SessionStamp) {
        sessions.requirePlaybackSession(owner)
        val songId = mediaItem.mediaId.takeIf(String::isNotBlank) ?: return
        val sourceKey = mediaItem.sourceKey
        val cloud = sourceKey.takeIf { it.startsWith("meilox-cloud-v1:") }?.let(SongSourceIdentity::fromKey)
        cloud?.requireAccount(owner.identity)
        val countKey = cloud?.key ?: songId
        database.downloadDao().recordPlayback(countKey)
        val count = database.downloadDao().playbackCount(countKey) ?: return
        if (context.dataStore[AutoCacheEnabledKey] != true) return
        val threshold = (context.dataStore[AutoCachePlaybackThresholdKey] ?: 5)
            .takeIf { it in setOf(3, 5, 10, 20) } ?: 5
        if (count < threshold || hasLocalCopy(songId, cloud) || isActive(songId, cloud)) return

        val quality = runCatching {
            DownloadQuality.valueOf(context.dataStore[AutoCacheQualityKey] ?: DownloadQuality.EXHIGH.name)
        }.getOrDefault(DownloadQuality.EXHIGH)

        val metadata = mediaItem.mediaMetadata
        val artists = metadata.extras?.getStringArrayList("artist_list")
            ?.filter(String::isNotBlank)
            .orEmpty()
            .ifEmpty { listOf(context.getString(R.string.unknown_artist)) }
        val downloadPath = context.dataStore[DownloadPathKey] ?: DownloadManager.getDefaultDownloadPath()
        sessions.requirePlaybackSession(owner)
        DownloadManager.enqueue(
            context = context,
            owner = owner,
            songs = listOf(
                SongDownloadInfo(
                    songId = songId,
                    songTitle = metadata.title?.toString().orEmpty().ifBlank { context.getString(R.string.unknown_song) },
                    songArtist = artists,
                    songAlbum = metadata.albumTitle?.toString().orEmpty(),
                    songCover = metadata.artworkUri?.toString().orEmpty(),
                    duration = metadata.durationMs ?: 0,
                    quality = quality.toMusicQuality().text,
                    sourceKey = sourceKey,
                ),
            ),
            playlistName = context.getString(R.string.automatic_cache),
            playlistId = "automatic_$songId",
            downloadPath = downloadPath,
        )
    }

    private suspend fun isActive(songId: String, cloud: SongSourceIdentity? = null): Boolean {
        val task = database.downloadDao().getBySongId(songId) ?: return false
        if (cloud != null && (task.sourceKey != cloud.key || task.ownerId != cloud.accountId)) return false
        return task.status == com.ljyh.mei.data.model.room.DownloadStatus.PENDING ||
            task.status == com.ljyh.mei.data.model.room.DownloadStatus.DOWNLOADING ||
            task.status == com.ljyh.mei.data.model.room.DownloadStatus.COMPLETED
    }

    private suspend fun hasLocalCopy(songId: String, cloud: SongSourceIdentity? = null): Boolean {
        if (cloud != null) {
            val task = database.downloadDao().getBySongId(songId) ?: return false
            if (task.sourceKey != cloud.key || task.ownerId != cloud.accountId ||
                task.status != com.ljyh.mei.data.model.room.DownloadStatus.COMPLETED) return false
        }
        val path = database.songDao().getSong(songId).first()?.path ?: return false
        return path.startsWith("content://") || File(path).exists()
    }
}
