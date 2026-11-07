package com.ljyh.mei.ui.screen.playlist

import androidx.paging.PagingData
import androidx.paging.filter
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.PlaylistDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

internal fun MediaMetadata.matchesPlaylistSearch(query: String): Boolean {
    val keyword = query.trim()
    return title.contains(keyword, ignoreCase = true) ||
        album.title.contains(keyword, ignoreCase = true) ||
        artists.any { artist ->
            artist.name.contains(keyword, ignoreCase = true) ||
                artist.alias.orEmpty().any { it.contains(keyword, ignoreCase = true) }
        }
}

internal fun Flow<PagingData<MediaMetadata>>.excludeRemovedPlaylistTracks(
    removedTrackIds: StateFlow<Set<Long>>,
): Flow<PagingData<MediaMetadata>> = combine(removedTrackIds) { pagingData, removedIds ->
    pagingData.filter { it.id !in removedIds }
}

internal fun PlaylistDetail.Playlist.cleanDetailCover(homeRadarArtwork: String? = null): String {
    // Radar covers can have labels baked into the bitmap; the background is the original artwork.
    val radarArtwork = (backgroundCoverUrl as? String)?.trim()?.takeIf {
        specialType == 100 && name.contains("雷达") &&
            (it.startsWith("https://") || it.startsWith("http://"))
    }
    return com.ljyh.mei.utils.playlistCoverWithoutOverlays(homeRadarArtwork ?: radarArtwork ?: coverImgUrl)
}
