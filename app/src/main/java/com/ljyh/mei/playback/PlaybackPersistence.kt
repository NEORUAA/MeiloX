package com.ljyh.mei.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.datastore.preferences.core.edit
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
import kotlinx.coroutines.flow.first
import timber.log.Timber

data class PlaybackSnapshot(
    val schemaVersion: Int = 1,
    val savedAtEpochMs: Long = System.currentTimeMillis(),
    val items: List<PlaybackItemSnapshot> = emptyList(),
    val currentIndex: Int = 0,
    val positionMs: Long = 0L,
    val repeatMode: Int = Player.REPEAT_MODE_ALL,
    val shuffleModeEnabled: Boolean = false,
    val playWhenReady: Boolean = false,
    val queueTitle: String? = null,
    val sourceType: String = SOURCE_QUEUE,
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
    fun capture(
        player: Player,
        queueTitle: String?,
        isFmMode: Boolean,
    ): PlaybackSnapshot {
        val items = buildList(player.mediaItemCount) {
            repeat(player.mediaItemCount) { index ->
                add(player.getMediaItemAt(index).toSnapshot())
            }
        }
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
            sourceType = if (isFmMode) {
                PlaybackSnapshot.SOURCE_PERSONAL_FM
            } else {
                PlaybackSnapshot.SOURCE_QUEUE
            },
        )
    }

    suspend fun save(snapshot: PlaybackSnapshot) {
        context.dataStore.edit { preferences ->
            preferences[PlaybackSnapshotKey] = gson.toJson(snapshot)
        }
    }

    suspend fun load(): PlaybackSnapshot? {
        val encoded = context.dataStore.data.first()[PlaybackSnapshotKey] ?: return null
        return runCatching {
            gson.fromJson(encoded, PlaybackSnapshot::class.java)
                ?.takeIf { it.schemaVersion == 1 }
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
