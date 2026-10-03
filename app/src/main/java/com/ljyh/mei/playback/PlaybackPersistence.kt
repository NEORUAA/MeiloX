package com.ljyh.mei.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.gson.Gson
import com.ljyh.mei.constants.PlaybackSnapshotKey
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.PLACEHOLDER_URI
import com.ljyh.mei.data.model.createPlaceholder
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.playback.queue.PlaylistQueueSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

private val Context.playbackProgressStore by preferencesDataStore(name = "playback_progress")
private val ProgressKey = stringPreferencesKey("checkpoint")

internal data class PlaybackCheckpoint(
    val queueSavedAtEpochMs: Long,
    val savedAtEpochMs: Long,
    val currentIndex: Int,
    val positionMs: Long,
    val repeatMode: Int,
    val shuffleModeEnabled: Boolean,
    val playWhenReady: Boolean,
)

internal fun PlaybackSnapshot.withCheckpoint(checkpoint: PlaybackCheckpoint?): PlaybackSnapshot {
    if (checkpoint == null || checkpoint.queueSavedAtEpochMs != savedAtEpochMs ||
        checkpoint.currentIndex !in items.indices || checkpoint.positionMs < 0 ||
        checkpoint.repeatMode !in Player.REPEAT_MODE_OFF..Player.REPEAT_MODE_ALL
    ) return this
    return copy(
        savedAtEpochMs = checkpoint.savedAtEpochMs,
        currentIndex = checkpoint.currentIndex,
        positionMs = checkpoint.positionMs,
        repeatMode = checkpoint.repeatMode,
        shuffleModeEnabled = checkpoint.shuffleModeEnabled,
        playWhenReady = checkpoint.playWhenReady,
    )
}

data class PlaybackSnapshot(
    val schemaVersion: Int = 2,
    val savedAtEpochMs: Long = System.currentTimeMillis(),
    val items: List<PlaybackItemSnapshot> = emptyList(),
    val currentIndex: Int = 0,
    val positionMs: Long = 0L,
    val repeatMode: Int = Player.REPEAT_MODE_ALL,
    val shuffleModeEnabled: Boolean = false,
    val playWhenReady: Boolean = false,
    val queueTitle: String? = null,
    val sourceType: String = SOURCE_QUEUE,
    val shuffleOrder: List<Int>? = null,
    val playlistSource: PlaylistQueueSource? = null,
) {
    val isFmMode: Boolean
        get() = sourceType == SOURCE_PERSONAL_FM

    companion object {
        const val SOURCE_QUEUE = "queue"
        const val SOURCE_PERSONAL_FM = "personal_fm"
    }
}

data class PlaybackItemSnapshot(
    val mediaId: String,
    val title: String = "",
    val artists: List<PlaybackArtistSnapshot> = emptyList(),
    val albumTitle: String = "",
    val albumId: Long = 0L,
    val artworkUri: String = "",
    val durationMs: Long = 0L,
    val explicit: Boolean = false,
    val translatedName: String? = null,
    val isPodcast: Boolean = false,
    val isLocal: Boolean = false,
    val isPlaceholder: Boolean = false,
)

data class PlaybackArtistSnapshot(
    val id: Long = 0L,
    val name: String,
)

@UnstableApi
class PlaybackPersistence(
    private val context: Context,
    private val gson: Gson = Gson(),
) {
    private var cachedItems: List<PlaybackItemSnapshot>? = null
    private var cachedOrder: List<Int>? = null
    private var savedQueue: PlaybackSnapshot? = null
    private var savedCheckpoint: PlaybackCheckpoint? = null
    private val saveMutex = Mutex()

    fun invalidateQueue() {
        cachedItems = null
        cachedOrder = null
    }

    fun capture(
        player: Player,
        queueTitle: String?,
        isFmMode: Boolean,
        playlistSource: PlaylistQueueSource? = null,
    ): PlaybackSnapshot {
        val items = cachedItems ?: buildList(player.mediaItemCount) {
            repeat(player.mediaItemCount) { index ->
                add(player.getMediaItemAt(index).toSnapshot())
            }
        }.also { cachedItems = it }
        val currentIndex = player.currentMediaItemIndex
            .takeIf { it in items.indices }
            ?: 0
        return PlaybackSnapshot(
            items = items,
            currentIndex = currentIndex,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            repeatMode = player.repeatMode,
            shuffleModeEnabled = player.shuffleModeEnabled,
            playWhenReady = player.playWhenReady,
            queueTitle = queueTitle,
            shuffleOrder = cachedOrder ?: player.playbackOrderIndices(true).also { cachedOrder = it },
            playlistSource = if (isFmMode) null else playlistSource,
            sourceType = if (isFmMode) {
                PlaybackSnapshot.SOURCE_PERSONAL_FM
            } else {
                PlaybackSnapshot.SOURCE_QUEUE
            },
        )
    }

    suspend fun save(snapshot: PlaybackSnapshot) {
        withContext(Dispatchers.IO) {
            saveMutex.withLock {
                val previous = savedQueue
                val sameQueue = previous != null && previous.items === snapshot.items &&
                    previous.shuffleOrder === snapshot.shuffleOrder && previous.queueTitle == snapshot.queueTitle &&
                    previous.sourceType == snapshot.sourceType && previous.playlistSource == snapshot.playlistSource
                if (!sameQueue) {
                    val queue = snapshot.copy(savedAtEpochMs = maxOf(snapshot.savedAtEpochMs, (previous?.savedAtEpochMs ?: 0L) + 1))
                    val encoded = gson.toJson(queue)
                    context.dataStore.edit { it[PlaybackSnapshotKey] = encoded }
                    savedQueue = queue
                }
                val checkpoint = PlaybackCheckpoint(
                    queueSavedAtEpochMs = checkNotNull(savedQueue).savedAtEpochMs,
                    savedAtEpochMs = snapshot.savedAtEpochMs,
                    currentIndex = snapshot.currentIndex,
                    positionMs = snapshot.positionMs,
                    repeatMode = snapshot.repeatMode,
                    shuffleModeEnabled = snapshot.shuffleModeEnabled,
                    playWhenReady = snapshot.playWhenReady,
                )
                val lastCheckpoint = savedCheckpoint
                if (lastCheckpoint != null && checkpoint.copy(savedAtEpochMs = lastCheckpoint.savedAtEpochMs) == lastCheckpoint) {
                    return@withLock
                }
                val encoded = gson.toJson(checkpoint)
                context.playbackProgressStore.edit { it[ProgressKey] = encoded }
                savedCheckpoint = checkpoint
            }
        }
    }

    suspend fun load(): PlaybackSnapshot? {
        val encoded = context.dataStore.data.first()[PlaybackSnapshotKey] ?: return null
        return runCatching {
            gson.fromJson(encoded, PlaybackSnapshot::class.java)
                ?.takeIf { it.schemaVersion in 1..2 }
                ?.let { snapshot ->
                    val checkpoint = runCatching {
                        context.playbackProgressStore.data.first()[ProgressKey]
                            ?.let { gson.fromJson(it, PlaybackCheckpoint::class.java) }
                    }.getOrNull()
                    snapshot.withCheckpoint(checkpoint)
                }
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Discarding an invalid playback snapshot")
        }.getOrNull()
    }

    fun restoreItems(snapshot: PlaybackSnapshot): List<MediaItem> =
        snapshot.items.map { it.toMediaItem() }

    private fun MediaItem.toSnapshot(): PlaybackItemSnapshot {
        val domainMetadata = metadata
        val displayMetadata = mediaMetadata
        val placeholder = localConfiguration?.uri?.toString() == PLACEHOLDER_URI
        val artistNames = domainMetadata?.artists?.map {
            PlaybackArtistSnapshot(id = it.id, name = it.name)
        }.orEmpty().ifEmpty {
            displayMetadata.extras?.getStringArrayList("artist_list")
                ?.filter { it.isNotBlank() }
                ?.map { PlaybackArtistSnapshot(name = it) }
                .orEmpty()
                .ifEmpty {
                    splitArtists(displayMetadata.artist?.toString())
                        .map { PlaybackArtistSnapshot(name = it) }
                }
        }
        return PlaybackItemSnapshot(
            mediaId = mediaId,
            title = domainMetadata?.title ?: displayMetadata.title?.toString().orEmpty(),
            artists = artistNames,
            albumTitle = domainMetadata?.album?.title
                ?: displayMetadata.albumTitle?.toString().orEmpty(),
            albumId = domainMetadata?.album?.id ?: 0L,
            artworkUri = domainMetadata?.coverUrl
                ?: displayMetadata.artworkUri?.toString().orEmpty(),
            durationMs = domainMetadata?.duration
                ?: displayMetadata.durationMs
                ?: displayMetadata.extras?.getLong("duration")
                ?: 0L,
            explicit = domainMetadata?.explicit ?: false,
            translatedName = domainMetadata?.tns,
            isPodcast = domainMetadata?.isPodcast ?: false,
            isLocal = domainMetadata?.isLocal ?: false,
            isPlaceholder = placeholder,
        )
    }

    private fun PlaybackItemSnapshot.toMediaItem(): MediaItem {
        if (isPlaceholder) return createPlaceholder(mediaId)

        val resolvedArtists = artists
            .filter { it.name.isNotBlank() }
            .ifEmpty { listOf(PlaybackArtistSnapshot(name = UNKNOWN_ARTIST)) }
        val domainMetadata = MediaMetadata(
            id = stableId(mediaId),
            title = title.ifBlank { UNKNOWN_TITLE },
            coverUrl = artworkUri,
            artists = resolvedArtists.map { artist ->
                MediaMetadata.Artist(
                    id = artist.id.takeIf { it != 0L } ?: stableId(artist.name),
                    name = artist.name,
                )
            },
            duration = durationMs,
            album = MediaMetadata.Album(
                id = albumId.takeIf { it != 0L } ?: stableId(albumTitle),
                title = albumTitle,
            ),
            explicit = explicit,
            tns = translatedName,
            isPodcast = isPodcast,
            isLocal = isLocal,
        )
        val displayMetadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(domainMetadata.title)
            .setSubtitle(resolvedArtists.joinToString { it.name })
            .setArtist(resolvedArtists.joinToString { it.name })
            .setAlbumTitle(albumTitle)
            .setMediaType(MEDIA_TYPE_MUSIC)
            .setExtras(Bundle().apply {
                putLong("duration", durationMs)
                putStringArrayList("artist_list", ArrayList(resolvedArtists.map { it.name }))
            })
            .apply {
                artworkUri.takeIf { it.isNotBlank() }?.let { setArtworkUri(Uri.parse(it)) }
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(mediaId)
            .setCustomCacheKey(mediaId)
            .setTag(domainMetadata)
            .setMediaMetadata(displayMetadata)
            .build()
    }

    private fun stableId(value: String): Long =
        value.toLongOrNull() ?: value.hashCode().toUInt().toLong()

    private fun splitArtists(value: String?): List<String> = value
        ?.split(ARTIST_SEPARATOR)
        ?.map(String::trim)
        ?.filter(String::isNotBlank)
        .orEmpty()

    private companion object {
        const val TAG = "PlaybackPersistence"
        const val UNKNOWN_TITLE = "未知标题"
        const val UNKNOWN_ARTIST = "未知歌手"
        val ARTIST_SEPARATOR = Regex("\\s*[/,&、]\\s*")
    }
}
