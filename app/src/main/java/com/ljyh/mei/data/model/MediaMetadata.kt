package com.ljyh.mei.data.model

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.compose.runtime.Immutable
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.api.ArtistSong
import com.ljyh.mei.data.model.melox.PodcastProgram
import com.ljyh.mei.data.model.weapi.EveryDaySongs
import androidx.core.net.toUri
import com.ljyh.mei.utils.netease.NeteaseUtils.getResourceLink
import com.ljyh.mei.source.api.AlbumRef
import com.ljyh.mei.source.api.ArtistRef
import com.ljyh.mei.source.api.ProviderIds
import com.ljyh.mei.source.api.TrackRef
import com.ljyh.mei.source.api.TrackRefCodec
import com.ljyh.mei.source.api.Track
import com.ljyh.mei.source.api.SourceAlbum
import com.ljyh.mei.source.api.SourceArtist


@Immutable
data class MediaMetadata(
    val id: Long,
    val title: String,
    val coverUrl: String,
    val artists: List<Artist>,
    val duration: Long,
    val album: Album,
    val explicit: Boolean = false,
    val tns:String?=null,
    val isPodcast: Boolean = false,
    val isLocal: Boolean = false,
    val trackRef: TrackRef = TrackRefCodec.decode(id.toString(), isLocal, isPodcast)
        ?: TrackRef(ProviderIds.NETEASE, id.toString()),
    val isrc: String? = null,
    val recordingVersion: String? = null,
) {
    data class Artist(
        val id: Long,
        val name: String,
        val picUrl: String? = null,
        val alias: List<String>? = null,
        val ref: ArtistRef? = null,
    )

    data class Album(
        val id: Long,
        val title: String,
        val ref: AlbumRef? = null,
    )

    fun toSongEntity() = SongEntity(
        id = id,
        title = title,
        duration = duration,
        albumId = album.id,
        albumName = album.title,
        artistId = artists.firstOrNull()?.id ?: 0,
        artistName = artists.firstOrNull()?.name ?: "",
        coverUrl = coverUrl,
        trackRef = trackRef,
    )
}

const val PLACEHOLDER_URI = "https://placeholder.media"

fun PodcastProgram.toMediaMetadata() = MediaMetadata(
    id = mainSongId ?: -id,
    title = name,
    coverUrl = coverUrl.orEmpty(),
    artists = listOf(MediaMetadata.Artist(host?.id ?: 0, host?.nickname ?: radioName)),
    duration = durationMs,
    album = MediaMetadata.Album(radioId, radioName),
    isPodcast = true,
)

fun PlaylistDetail.Playlist.Track.toMediaMetadata() = MediaMetadata(
    id = id,
    title = name,
    coverUrl=al.picUrl,
    artists = ar.map {
        MediaMetadata.Artist(
            id = it.Id,
            name = it.name?:"",
            alias = it.alias
        )
    },
    duration = dt,
    album = MediaMetadata.Album(
        id = al.Id,
        title = al.name?:"",
    ),
    tns= tns?.firstOrNull()
)



fun AlbumDetail.Song.toMediaMetadata() = MediaMetadata(
    id = id,
    title = name,
    coverUrl= getResourceLink(al.pic.toString()),
    artists = ar.map {
        MediaMetadata.Artist(
            id = it.id,
            name = it.name,
        )
    },
    duration = dt,
    album = MediaMetadata.Album(
        id = al.id,
        title = al.name
    )
)



fun EveryDaySongs.Data.DailySong.toMediaMetadata() = MediaMetadata(
    id = id,
    title = name,
    coverUrl=al.picUrl,
    artists = ar.map {
        MediaMetadata.Artist(
            id = it.id,
            name = it.name,
            alias = it.alias
        )
    },
    duration = dt,
    album = MediaMetadata.Album(
        id = al.id,
        title = al.name
    ),
    tns= tns?.firstOrNull()
)




fun ArtistSong.HotSong.toMediaMetadata() = MediaMetadata(
    id = id,
    title = name,
    coverUrl= getResourceLink(al.pic.toString()),
    artists = ar.map {
        MediaMetadata.Artist(
            id = it.id,
            name = it.name,
            alias = it.alia
        )
    },
    duration = dt,
    album = MediaMetadata.Album(
        id = al.id,
        title = al.name
    )
)


// For Intelligence mode - converts Intelligence.Data.songInfo to MediaMetadata
fun com.ljyh.mei.data.model.api.Data.toMediaMetadata() = MediaMetadata(
    id = songInfo.id,
    title = songInfo.name,
    coverUrl = songInfo.al.picUrl,
    artists = songInfo.ar.map {
        MediaMetadata.Artist(id = it.id.toLong(), name = it.name, alias = it.tns)
    },
    duration = songInfo.dt.toLong(),
    album = MediaMetadata.Album(id = songInfo.al.id.toLong(), title = songInfo.al.name),
    tns = songInfo.tns?.firstOrNull()
)

// For FM mode - converts Radio.Data to MediaMetadata
fun com.ljyh.mei.data.model.weapi.Data.toMediaMetadata() = MediaMetadata(
    id = id,
    title = name,
    coverUrl = album.picUrl,
    artists = artists.map {
        MediaMetadata.Artist(id = it.id.toLong(), name = it.name, alias = it.alias)
    },
    duration = duration.toLong(),
    album = MediaMetadata.Album(id = album.id.toLong(), title = album.name),
    tns = transNames?.firstOrNull()
)




@OptIn(UnstableApi::class)
    fun PlaylistDetail.Playlist.Track.toMediaItem() =MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(id.toString()) // 占位
    .setCustomCacheKey(id.toString())
    .setTag(toMediaMetadata())
    .setMediaMetadata(
        androidx.media3.common.MediaMetadata.Builder()
            .setTitle(name)
            .setSubtitle(ar.joinToString { it.name?:"" })
            .setArtist(ar.joinToString { it.name?:"" })
            .setAlbumTitle(al.name)
            .setMediaType(MEDIA_TYPE_MUSIC)
            .setArtworkUri(al.picUrl.toUri())
            .setExtras(Bundle().apply {
                putLong("duration", this@toMediaItem.dt.toLong())
                putStringArrayList("artist_list", ArrayList(ar.map { it.name ?: "" }))
            })
            .build()
    )
    .build()

@OptIn(UnstableApi::class)
fun createPlaceholder(id: String): MediaItem {
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(PLACEHOLDER_URI) // 防止 NPE
        .setCustomCacheKey(id)   // 确保 ResolvingDataSource 能拿到 ID
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle("加载中...")
                .setArtist("")
                .build()
        )
        .build()
}


@OptIn(UnstableApi::class)
fun MediaMetadata.toMediaItem() = MediaItem.Builder()
    .setMediaId(TrackRefCodec.mediaId(trackRef))
    .setUri(TrackRefCodec.mediaId(trackRef))
    .setCustomCacheKey(TrackRefCodec.mediaId(trackRef))
    .setTag(this)
    .setMediaMetadata(
        androidx.media3.common.MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(artists.joinToString { it.name })
            .setArtist(artists.joinToString { it.name })
            .setAlbumTitle(album.title)
            .setMediaType(MEDIA_TYPE_MUSIC)
            .setArtworkUri(coverUrl.toUri())
            .setExtras(Bundle().apply {
                putLong("duration", duration)
                putStringArrayList("artist_list", ArrayList(artists.map { it.name }))
            })
            .build()
    )
    .build()



val MediaItem.metadata: MediaMetadata?
    get() = localConfiguration?.tag as? MediaMetadata

fun Track.toMediaMetadata() = MediaMetadata(
    id = if (ref.providerId == ProviderIds.NETEASE) ref.trackId.toLongOrNull() ?: 0L else 0L,
    title = title,
    coverUrl = artworkUrl,
    artists = artists.map {
        MediaMetadata.Artist(
            id = if (it.ref?.providerId == ProviderIds.NETEASE) it.ref.artistId.toLongOrNull() ?: 0L else 0L,
            name = it.name,
            ref = it.ref,
        )
    },
    duration = durationMs,
    album = MediaMetadata.Album(
        id = if (album.ref?.providerId == ProviderIds.NETEASE) album.ref.albumId.toLongOrNull() ?: 0L else 0L,
        title = album.title,
        ref = album.ref,
    ),
    explicit = explicit,
    isLocal = ref.providerId == ProviderIds.LOCAL,
    trackRef = ref,
    isrc = isrc,
    recordingVersion = version,
)

fun MediaMetadata.toSourceTrack() = Track(
    ref = trackRef,
    title = title,
    artists = artists.map { SourceArtist(it.name, it.ref) },
    album = SourceAlbum(album.title, album.ref),
    durationMs = duration,
    artworkUrl = coverUrl,
    explicit = explicit,
    isrc = isrc,
    version = recordingVersion,
)

val MediaMetadata.supportsNeteaseSongActions: Boolean
    get() = trackRef.providerId == ProviderIds.NETEASE && !isLocal && !isPodcast &&
        trackRef.trackId.toLongOrNull()?.let { it > 0L } == true

val MediaMetadata.mediaId: String get() = TrackRefCodec.mediaId(trackRef)


fun com.ljyh.mei.data.model.room.Song.toMediaMetadata(): MediaMetadata {
    val ref = TrackRef(providerId, providerTrackId.ifEmpty { id })
    val songId = when (ref.providerId) {
        ProviderIds.LOCAL -> ref.trackId.removePrefix("local_").toLongOrNull()
            ?.takeIf { it > 0L }?.let { -it } ?: 0L
        ProviderIds.NETEASE -> ref.trackId.toLongOrNull() ?: 0L
        else -> 0L
    }
    return MediaMetadata(
        id = songId,
        title = title,
        coverUrl = cover,
        artists = artist.map { name ->
            MediaMetadata.Artist(
                id = if (ref.providerId == ProviderIds.NETEASE || ref.providerId == ProviderIds.LOCAL)
                    name.hashCode().toLong().let { if (it < 0) -it else it } else 0L,
                name = name,
            )
        },
        duration = duration,
        album = MediaMetadata.Album(
            id = if (ref.providerId == ProviderIds.NETEASE || ref.providerId == ProviderIds.LOCAL)
                album.hashCode().toLong().let { if (it < 0) -it else it } else 0L,
            title = album,
        ),
        isLocal = sourceType == com.ljyh.mei.data.model.room.SourceType.LOCAL,
        trackRef = ref,
    )
}

data class SongEntity(
    val id: Long,
    val title: String,
    val duration: Long,
    val albumId: Long,
    val albumName: String,
    val artistId: Long ,
    val artistName: String,
    val coverUrl: String,
    val prepare: Boolean = false,
    val trackRef: TrackRef = TrackRefCodec.decode(id.toString())
        ?: TrackRef(ProviderIds.NETEASE, id.toString()),
){
    @OptIn(UnstableApi::class)
    fun toMediaItem() = MediaItem.Builder()
        .setMediaId(TrackRefCodec.mediaId(trackRef))
        .setUri(TrackRefCodec.mediaId(trackRef))
        .setCustomCacheKey(TrackRefCodec.mediaId(trackRef))
        .setTag(this)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(artistName)
                .setArtist(artistName)
                .setAlbumTitle(albumName)
                .setMediaType(MEDIA_TYPE_MUSIC)
                .setArtworkUri(Uri.parse(coverUrl))
                .build()
        )
        .build()
}
