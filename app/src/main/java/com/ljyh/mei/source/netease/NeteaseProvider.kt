package com.ljyh.mei.source.netease

import com.ljyh.mei.data.model.PlaylistDetail
import com.ljyh.mei.data.model.availableMusicQualities
import com.ljyh.mei.data.model.api.GetLyricV1
import com.ljyh.mei.data.model.api.GetSearch
import com.ljyh.mei.data.model.api.GetSongDetails
import com.ljyh.mei.data.model.api.GetSongUrlV1
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.playback.effectivePlaybackQuality
import com.ljyh.mei.playback.isSupportedPlaybackQuality
import com.ljyh.mei.playback.playbackQualityFallbacks
import com.ljyh.mei.source.api.AlbumRef
import com.ljyh.mei.source.api.ArtistRef
import com.ljyh.mei.source.api.AudioResource
import com.ljyh.mei.source.api.BatchCatalogCapability
import com.ljyh.mei.source.api.CatalogCapability
import com.ljyh.mei.source.api.FeatureContribution
import com.ljyh.mei.source.api.LyricsCapability
import com.ljyh.mei.source.api.MusicProvider
import com.ljyh.mei.source.api.PageRequest
import com.ljyh.mei.source.api.PlaybackCapability
import com.ljyh.mei.source.api.ProviderIds
import com.ljyh.mei.source.api.QualityCapability
import com.ljyh.mei.source.api.SourceAlbum
import com.ljyh.mei.source.api.SourceArtist
import com.ljyh.mei.source.api.SourceCapability
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.api.SourceLyrics
import com.ljyh.mei.source.api.SourcePage
import com.ljyh.mei.source.api.Track
import com.ljyh.mei.source.api.TrackRef
import com.ljyh.mei.utils.netease.NeteaseUtils.getResourceLink
import java.io.IOException
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/** The built-in provider owns NetEase's native catalog, playback and feature contributions. */
@Singleton
class NeteaseProvider @Inject constructor(
    private val catalog: NeteaseCatalogTransport,
    private val playback: NeteasePlaybackTransport,
    private val api: ApiService,
) : MusicProvider, CatalogCapability, BatchCatalogCapability, PlaybackCapability, LyricsCapability,
    QualityCapability, FeatureContribution {
    override val id = ProviderIds.NETEASE
    override val displayName = "网易云音乐"
    override val isBuiltIn = true
    override val capabilities = setOf(
        SourceCapability.SEARCH, SourceCapability.TRACK, SourceCapability.PLAYBACK,
        SourceCapability.LYRICS, SourceCapability.ALBUM, SourceCapability.ARTIST,
        SourceCapability.PLAYLIST, SourceCapability.ACCOUNT, SourceCapability.LIBRARY,
        SourceCapability.RECOMMENDATIONS, SourceCapability.PODCAST, SourceCapability.SOCIAL,
    )

    override val featureRoutes = setOf(
        "home", "find_music", "everyday", "podcasts", "podcast", "cloud_music",
        "account/login", "account/web_login", "account/pc_qr_login", "account/home",
        "account/musician", "account/security", "account/security/bindings",
        "account/security/devices", "account/security/authorizations", "account/security/web",
        "account/connections", "account/listening_rank", "account/listening_stats",
        "private_messages", "official_messages", "private_message", "message_contacts",
        "listen_together", "song_recognition", "comment", "song_wiki",
        "album", "artist", "artist-info", "artist-songs", "artist-albums", "playlist",
    )

    override suspend fun search(query: String, page: PageRequest): SourcePage<Track> = nativeCall {
        val response = catalog.search(GetSearch(query, type = 1, limit = page.limit, offset = page.offset))
        requireSuccess(response.code)
        val songs = response.result.songs.orEmpty()
        SourcePage(songs.map { song ->
            Track(
                ref = TrackRef(id, song.id.toString()),
                title = song.name,
                artists = song.artists.map { SourceArtist(it.name, ArtistRef(id, it.id.toString())) },
                album = SourceAlbum(song.album.name, AlbumRef(id, song.album.id.toString())),
                durationMs = song.duration,
                artworkUrl = getResourceLink(song.album.picId.toString(), "jpg"),
            )
        }, hasMore = songs.size == page.limit)
    }

    override suspend fun getTrack(ref: TrackRef): Track? = nativeCall {
        requireTrack(ref)
        val response = catalog.getSongDetail(GetSongDetails(ref.trackId))
        requireSuccess(response.code)
        response.songs.firstOrNull { it.id.toString() == ref.trackId }?.toSourceTrack()
    }

    override suspend fun getTracks(refs: List<TrackRef>): List<Track> = nativeCall {
        refs.forEach(::requireTrack)
        if (refs.isEmpty()) return@nativeCall emptyList()
        val tracks = refs.distinct().chunked(500).flatMap { batch ->
            val response = catalog.getSongDetail(GetSongDetails(batch.joinToString(",") { it.trackId }))
            requireSuccess(response.code)
            response.songs.map { it.toSourceTrack() }
        }.associateBy(Track::ref)
        refs.mapNotNull(tracks::get)
    }

    override suspend fun availableQualities(ref: TrackRef): List<String> = nativeCall {
        requireTrack(ref)
        val response = catalog.getSongDetail(GetSongDetails(ref.trackId))
        requireSuccess(response.code)
        val track = response.songs.firstOrNull { it.id.toString() == ref.trackId } ?: return@nativeCall emptyList()
        val privilege = response.privileges.orEmpty().firstOrNull { it.id.toString() == ref.trackId }
        // Catalog levels are hints. Advertise only full sources returned for this account.
        track.availableMusicQualities(privilege).mapNotNull { requested ->
            val urlResponse = playback.getSongUrlV1(GetSongUrlV1("[${ref.trackId}]", requested.text))
            requireSuccess(urlResponse.code)
            val source = urlResponse.fullSourceFor(ref.trackId) ?: return@mapNotNull null
            effectivePlaybackQuality(source.level, requested.text).takeIf(::isSupportedPlaybackQuality)
        }.distinct()
    }

    override suspend fun resolve(ref: TrackRef, requestedQuality: String): AudioResource = nativeCall {
        requireTrack(ref)
        for (quality in playbackQualityFallbacks(requestedQuality)) {
            val response = playback.getSongUrlV1(GetSongUrlV1("[${ref.trackId}]", quality))
            requireSuccess(response.code)
            val source = response.fullSourceFor(ref.trackId) ?: continue
            val actualQuality = effectivePlaybackQuality(source.level, quality)
            if (!isSupportedPlaybackQuality(actualQuality)) continue
            val url = source.url ?: continue
            val uri = runCatching { URI(url) }.getOrNull() ?: continue
            if (uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank() || uri.userInfo != null) continue
            val ttlMs = (source.expi?.takeIf { it > 0 }?.toLong()?.times(1_000) ?: 300_000L)
                .minus(30_000L).coerceAtLeast(1_000L)
            return@nativeCall AudioResource(
                uri = url,
                quality = actualQuality,
                expiresAtMs = System.currentTimeMillis() + ttlMs,
                contentLength = source.size.toLong().takeIf { it > 0 },
                contentId = source.md5?.takeIf(String::isNotBlank),
                mimeType = when (source.type?.lowercase()) {
                    "flac" -> "audio/flac"
                    "mp3" -> "audio/mpeg"
                    "aac", "m4a" -> "audio/mp4"
                    else -> null
                },
            )
        }
        throw SourceException(SourceErrorCode.UNAVAILABLE)
    }

    override suspend fun getLyrics(ref: TrackRef): SourceLyrics? = nativeCall {
        requireTrack(ref)
        val response = api.getLyricV1(GetLyricV1(ref.trackId))
        requireSuccess(response.code)
        response.lrc?.lyric?.takeIf(String::isNotBlank)?.let { SourceLyrics(it, response.tlyric?.lyric) }
    }

    private fun requireTrack(ref: TrackRef) {
        if (ref.providerId != id || ref.trackId.toLongOrNull()?.let { it > 0 } != true) {
            throw SourceException(SourceErrorCode.NOT_FOUND)
        }
    }

    private fun PlaylistDetail.Playlist.Track.toSourceTrack() = Track(
        ref = TrackRef(this@NeteaseProvider.id, id.toString()),
        title = name,
        artists = ar.map { SourceArtist(it.name.orEmpty(), ArtistRef(this@NeteaseProvider.id, it.Id.toString())) },
        album = SourceAlbum(al.name.orEmpty(), AlbumRef(this@NeteaseProvider.id, al.Id.toString())),
        durationMs = dt,
        artworkUrl = al.picUrl,
    )
}

internal fun requireSuccess(code: Int) {
    if (code == 200) return
    throw SourceException(when (code) {
        301, 401, 403 -> SourceErrorCode.AUTH_REQUIRED
        404 -> SourceErrorCode.NOT_FOUND
        else -> SourceErrorCode.UNAVAILABLE
    })
}

internal suspend fun <T> nativeCall(block: suspend () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: SourceException) {
    throw error
} catch (error: HttpException) {
    throw SourceException(when (error.code()) {
        401, 403 -> SourceErrorCode.AUTH_EXPIRED
        404 -> SourceErrorCode.NOT_FOUND
        else -> SourceErrorCode.NETWORK
    })
} catch (error: IOException) {
    throw SourceException(SourceErrorCode.NETWORK)
} catch (error: Exception) {
    throw SourceException(SourceErrorCode.INVALID_RESPONSE)
}
