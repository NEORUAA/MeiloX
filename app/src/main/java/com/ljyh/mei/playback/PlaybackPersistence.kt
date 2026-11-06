package com.ljyh.mei.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.ljyh.mei.constants.PlaybackSnapshotKey
import com.ljyh.mei.constants.RestorePlaybackPositionKey
import com.ljyh.mei.constants.RestorePlaybackStateKey
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.PLACEHOLDER_URI
import com.ljyh.mei.data.model.createPlaceholder
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.playback.queue.PlaylistQueueSource
import com.ljyh.mei.source.api.AlbumRef
import com.ljyh.mei.source.api.ArtistRef
import com.ljyh.mei.source.api.ProviderIds
import com.ljyh.mei.source.api.TrackRef
import com.ljyh.mei.source.api.TrackRefCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

private val Context.playbackProgressStore by preferencesDataStore(name = "playback_progress")
private val ProgressKey = stringPreferencesKey("checkpoint")

/** Stable disk keys; alternates match the verified pre-fix R8 checkpoint mapping. */
internal data class PlaybackCheckpoint(
    @field:SerializedName(value = "queueSavedAtEpochMs", alternate = ["a"])
    val queueSavedAtEpochMs: Long,
    @field:SerializedName(value = "savedAtEpochMs", alternate = ["b"])
    val savedAtEpochMs: Long,
    @field:SerializedName(value = "currentIndex", alternate = ["c"])
    val currentIndex: Int,
    @field:SerializedName(value = "positionMs", alternate = ["d"])
    val positionMs: Long,
    @field:SerializedName(value = "repeatMode", alternate = ["e"])
    val repeatMode: Int,
    @field:SerializedName(value = "shuffleModeEnabled", alternate = ["f"])
    val shuffleModeEnabled: Boolean,
    @field:SerializedName(value = "playWhenReady", alternate = ["g"])
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

internal fun PlaybackSnapshot.withRestorePreferences(preferences: Preferences): PlaybackSnapshot = copy(
    positionMs = if (preferences[RestorePlaybackPositionKey] != false) positionMs.coerceAtLeast(0L) else 0L,
    playWhenReady = playWhenReady && preferences[RestorePlaybackStateKey] != false,
)

data class PlaybackSnapshot(
    val schemaVersion: Int = 3,
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
    val similarFmSourceIds: List<String>? = null,
    val queueAutoplayEnabled: Boolean = false,
    val queueAutoplayStartIndex: Int? = null,
    val sourceContentContext: SourceContentQueueSnapshot? = null,
) {
    val isFmMode: Boolean
        get() = sourceType == SOURCE_PERSONAL_FM || queueAutoplayEnabled

    companion object {
        const val SOURCE_QUEUE = "queue"
        const val SOURCE_PERSONAL_FM = "personal_fm"
    }
}

/** Safe continuation locator. Opaque cursors and account/session values never reach disk. */
data class SourceContentQueueSnapshot(
    @field:SerializedName("providerId") val providerId: String = "",
    @field:SerializedName("instanceId") val instanceId: String = "",
    @field:SerializedName("providerVersion") val providerVersion: String = "",
    @field:SerializedName("contentType") val contentType: String? = null,
    @field:SerializedName("contentId") val contentId: String? = null,
    @field:SerializedName("libraryCollection") val libraryCollection: String? = null,
    @field:SerializedName("loadedCount") val loadedCount: Int = 0,
    @field:SerializedName("nextOffset") val nextOffset: Int = 0,
    @field:SerializedName("pageSize") val pageSize: Int = 30,
    @field:SerializedName("hasMore") val hasMore: Boolean = false,
    @field:SerializedName("knownTotalCount") val knownTotalCount: Int? = null,
    @field:SerializedName("accountFingerprint") val accountFingerprint: String = "",
)

data class PlaybackItemSnapshot(
    val mediaId: String = "",
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
    val trackRef: String? = null,
    val albumRef: AlbumRef? = null,
    val isrc: String? = null,
    val recordingVersion: String? = null,
)

data class PlaybackArtistSnapshot(
    val id: Long = 0L,
    val name: String = "",
    val ref: ArtistRef? = null,
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
        similarFmSourceIds: List<String>? = null,
        queueAutoplayEnabled: Boolean = false,
        queueAutoplayStartEntryId: String? = null,
        sourceContentContext: SourceContentQueueSnapshot? = null,
    ): PlaybackSnapshot {
        val items = cachedItems ?: buildList(player.mediaItemCount) {
            repeat(player.mediaItemCount) { index ->
                add(player.getMediaItemAt(index).toSnapshot())
            }
        }.also { cachedItems = it }
        val currentIndex = player.currentMediaItemIndex
            .takeIf { it in items.indices }
            ?: 0
        val preserveQueueAutoplay = isFmMode && queueAutoplayEnabled
        return PlaybackSnapshot(
            items = items,
            currentIndex = currentIndex,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            repeatMode = player.repeatMode,
            shuffleModeEnabled = player.shuffleModeEnabled,
            playWhenReady = player.playWhenReady,
            queueTitle = queueTitle,
            shuffleOrder = cachedOrder ?: player.playbackOrderIndices(true).also { cachedOrder = it },
            playlistSource = if (!isFmMode || preserveQueueAutoplay) playlistSource else null,
            sourceContentContext = sourceContentContext?.takeUnless { isFmMode },
            similarFmSourceIds = if (isFmMode) similarFmSourceIds else null,
            queueAutoplayEnabled = preserveQueueAutoplay,
            queueAutoplayStartIndex = queueAutoplayStartEntryId?.takeIf { preserveQueueAutoplay }?.let { id ->
                (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).queueEntryId == id }
            },
            sourceType = if (isFmMode && !preserveQueueAutoplay) {
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
                    previous.sourceType == snapshot.sourceType && previous.playlistSource == snapshot.playlistSource &&
                    previous.similarFmSourceIds == snapshot.similarFmSourceIds &&
                    previous.queueAutoplayEnabled == snapshot.queueAutoplayEnabled &&
                    previous.queueAutoplayStartIndex == snapshot.queueAutoplayStartIndex &&
                    previous.sourceContentContext == snapshot.sourceContentContext
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
                ?.takeIf { it.schemaVersion in 1..3 }
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
            PlaybackArtistSnapshot(id = it.id, name = it.name, ref = it.ref)
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
            trackRef = domainMetadata?.trackRef?.let(TrackRefCodec::encode),
            albumRef = domainMetadata?.album?.ref,
            isrc = domainMetadata?.isrc,
            recordingVersion = domainMetadata?.recordingVersion,
        )
    }

    private fun PlaybackItemSnapshot.toMediaItem(): MediaItem {
        if (isPlaceholder) return createPlaceholder(mediaId)

        val resolvedArtists = artists
            .filter { it.name.isNotBlank() }
            .ifEmpty { listOf(PlaybackArtistSnapshot(name = UNKNOWN_ARTIST)) }
        val ref = trackRef?.let { TrackRefCodec.decode(it) }
            ?: TrackRefCodec.decode(mediaId, isLocal, isPodcast)
            ?: TrackRef(ProviderIds.NETEASE, mediaId.ifEmpty { "0" })
        val legacyId = when (ref.providerId) {
            ProviderIds.NETEASE -> ref.trackId.toLongOrNull() ?: 0L
            ProviderIds.LOCAL -> ref.trackId.removePrefix("local_").toLongOrNull()
                ?.takeIf { it > 0L }?.let { -it } ?: 0L
            else -> 0L
        }
        val transportId = if (ref.providerId == ProviderIds.NETEASE && legacyId < 0L) {
            TrackRefCodec.mediaId(ref)
        } else mediaId
        val domainMetadata = MediaMetadata(
            id = legacyId,
            title = title.ifBlank { UNKNOWN_TITLE },
            coverUrl = artworkUri,
            artists = resolvedArtists.map { artist ->
                MediaMetadata.Artist(
                    id = artist.id.takeIf { it != 0L }
                        ?: if (ref.providerId == ProviderIds.NETEASE || ref.providerId == ProviderIds.LOCAL) stableId(artist.name) else 0L,
                    name = artist.name,
                    ref = artist.ref,
                )
            },
            duration = durationMs,
            album = MediaMetadata.Album(
                id = albumId.takeIf { it != 0L }
                    ?: if (ref.providerId == ProviderIds.NETEASE || ref.providerId == ProviderIds.LOCAL) stableId(albumTitle) else 0L,
                title = albumTitle,
                ref = albumRef,
            ),
            explicit = explicit,
            tns = translatedName,
            isPodcast = isPodcast,
            isLocal = isLocal,
            trackRef = ref,
            isrc = isrc,
            recordingVersion = recordingVersion,
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
            .setMediaId(transportId)
            .setUri(transportId)
            .setCustomCacheKey(transportId)
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
