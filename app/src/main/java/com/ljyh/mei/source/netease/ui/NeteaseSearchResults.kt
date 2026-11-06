package com.ljyh.mei.source.netease.ui

import com.ljyh.mei.ui.screen.search.SearchType
import com.ljyh.mei.ui.screen.search.EmptyView
import com.ljyh.mei.ui.screen.search.ErrorView
import com.ljyh.mei.ui.screen.search.LoadingView
import androidx.compose.runtime.DisposableEffect

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

import androidx.annotation.OptIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.TextFieldValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.constants.PodcastsEnabledKey
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.api.SearchResult
import com.ljyh.mei.data.model.api.toAlbum
import com.ljyh.mei.data.model.api.toMediaData
import com.ljyh.mei.data.model.api.toPlaylist
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.ui.component.player.OverlayState
import io.github.neoruaa.glasmio.ui.GlasmioSegmentedControl
import io.github.neoruaa.glasmio.ui.GlasmioSearchBar
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import io.github.neoruaa.glasmio.icons.SfIcon
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.navigation.MeiNavigator
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.main.library.component.groupedLazyItems
import com.ljyh.mei.ui.screen.playlist.component.StandaloneTrackActionOverlay
import com.ljyh.mei.utils.rememberPreference
import com.ljyh.mei.utils.smallImage

@Composable
fun rememberNeteaseSearchResults(
    query: String,
    type: SearchType,
    onSongMore: (MediaMetadata, Rect) -> Unit,
    onSongClick: (List<MediaMetadata>, Int) -> Unit,
    viewModel: NeteaseSearchViewModel = hiltViewModel(),
): LazyListScope.() -> Unit {
    val state by viewModel.searchResult.collectAsState()
    val navigator = LocalNavController.current
    LaunchedEffect(query, type) {
        viewModel.onSearchInit(query, type.type)
        viewModel.onTabChange(type)
    }
    DisposableEffect(viewModel) { onDispose { viewModel.cancelRequests() } }
    return {
        when (val result = state) {
            Resource.Loading -> item("search-loading") { LoadingView() }
            is Resource.Error -> item("search-error") { ErrorView(result.message) }
            is Resource.Success -> NeteaseSearchResultList(result.data, type, navigator, onSongMore) { songs, index ->
                onSongClick(songs.map { it.toMediaData() }, index)
            }
        }
    }
}

fun LazyListScope.NeteaseSearchResultList(
    data: SearchResult,
    type: SearchType,
    navController: MeiNavigator,
    onSongMore: (MediaMetadata, Rect) -> Unit,
    onSongClick: (List<SearchResult.Result.Song>, Int) -> Unit,
) {
    when (type) {
        SearchType.Song -> {
            val songs = data.result.songs.orEmpty()
            if (songs.isEmpty()) {
                item(key = "search-empty-${type.name}") {
                    GlasmioGroupedList(Modifier.padding(top = 10.dp)) { EmptyView() }
                }
            } else {
                groupedLazyItems(
                    items = songs,
                    key = { "search-song-${it.id}" },
                    contentType = "search-song",
                    firstItemTopPadding = 10.dp,
                ) { song, index ->
                    val media = song.toMediaData()
                    GlasmioListRow(
                        title = media.title,
                        subtitle = media.artists
                            .map { it.name.trim() }
                            .filter(String::isNotBlank)
                            .joinToString(" / ")
                            .takeIf(String::isNotBlank),
                        showTopSeparator = index > 0,
                        leading = {
                            AsyncImage(
                                model = media.coverUrl.smallImage(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(44.dp).clip(ContinuousRoundedRectangle(10.dp)),
                            )
                        },
                        trailing = {
                            var menuAnchor by remember { mutableStateOf(Rect.Zero) }
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .onGloballyPositioned { menuAnchor = it.boundsInWindow() }
                                    .clip(ContinuousRoundedRectangle(22.dp))
                                    .clickable(
                                        interactionSource = null,
                                        indication = null,
                                        onClick = { onSongMore(media, menuAnchor) },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                SfIcon(
                                    "ellipsis",
                                    stringResource(R.string.more_actions_title),
                                    size = 18.dp,
                                )
                            }
                        },
                        onClick = { onSongClick(songs, index) },
                    )
                }
            }
        }

        SearchType.Artist -> {
            val artists = data.result.artists.orEmpty()
            if (artists.isEmpty()) {
                item(key = "search-empty-${type.name}") {
                    GlasmioGroupedList(Modifier.padding(top = 10.dp)) { EmptyView() }
                }
            } else {
                groupedLazyItems(
                    items = artists,
                    key = { "search-artist-${it.id}" },
                    contentType = "search-artist",
                    firstItemTopPadding = 10.dp,
                ) { artist, index ->
                    ArtistSearchRow(
                        artist = artist,
                        showTopSeparator = index > 0,
                        onClick = {
                            Screen.Artist.navigate(navController) { addPath(artist.id.toString()) }
                        },
                    )
                }
            }
        }

        SearchType.Album -> {
            val albums = data.result.albums.orEmpty()
            if (albums.isEmpty()) {
                item(key = "search-empty-${type.name}") {
                    GlasmioGroupedList(Modifier.padding(top = 10.dp)) { EmptyView() }
                }
            } else {
                groupedLazyItems(
                    items = albums,
                    key = { "search-album-${it.id}" },
                    contentType = "search-album",
                    firstItemTopPadding = 10.dp,
                ) { album, index ->
                    val albumModel = album.toAlbum()
                    GlasmioListRow(
                        title = albumModel.title,
                        subtitle = listOf(
                            albumModel.artist.joinToString(" / ") { it.name },
                            stringResource(R.string.playlist_song_count, albumModel.size),
                        ).filter(String::isNotBlank).joinToString(" · "),
                        showTopSeparator = index > 0,
                        leading = {
                            AsyncImage(
                                model = albumModel.cover.smallImage(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(44.dp).clip(ContinuousRoundedRectangle(10.dp)),
                            )
                        },
                        onClick = {
                            Screen.Album.navigate(navController) { addPath(album.id.toString()) }
                        },
                    )
                }
            }
        }

        SearchType.Playlist -> {
            val playlists = data.result.playlists.orEmpty()
            if (playlists.isEmpty()) {
                item(key = "search-empty-${type.name}") {
                    GlasmioGroupedList(Modifier.padding(top = 10.dp)) { EmptyView() }
                }
            } else {
                groupedLazyItems(
                    items = playlists,
                    key = { "search-playlist-${it.id}" },
                    contentType = "search-playlist",
                    firstItemTopPadding = 10.dp,
                ) { playlist, index ->
                    val playlistModel = playlist.toPlaylist()
                    GlasmioListRow(
                        title = playlistModel.title,
                        subtitle = "${playlistModel.count} • ${playlistModel.authorName}",
                        showTopSeparator = index > 0,
                        leading = {
                            AsyncImage(
                                model = playlistModel.cover.smallImage(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(44.dp).clip(ContinuousRoundedRectangle(10.dp)),
                            )
                        },
                        onClick = {
                            Screen.PlayList.navigate(navController) { addPath(playlist.id.toString()) }
                        },
                    )
                }
            }
        }

        SearchType.Podcast -> {
            val podcasts = data.result.podcasts.orEmpty()
            if (podcasts.isEmpty()) {
                item(key = "search-empty-${type.name}") {
                    GlasmioGroupedList(Modifier.padding(top = 10.dp)) { EmptyView() }
                }
            } else {
                groupedLazyItems(
                    items = podcasts,
                    key = { "search-podcast-${it.id}" },
                    contentType = "search-podcast",
                    firstItemTopPadding = 10.dp,
                ) { podcast, index ->
                    GlasmioListRow(
                        title = podcast.name,
                        subtitle = listOfNotNull(podcast.host?.nickname, podcast.category)
                            .filter(String::isNotBlank)
                            .joinToString(" · ")
                            .takeIf(String::isNotBlank),
                        showTopSeparator = index > 0,
                        leading = {
                            AsyncImage(
                                model = podcast.picUrl?.smallImage(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(44.dp).clip(ContinuousRoundedRectangle(10.dp)),
                            )
                        },
                        onClick = {
                            Screen.PodcastDetail.navigate(navController) { addPath(podcast.id.toString()) }
                        },
                    )
                }
            }
        }

        SearchType.History -> Unit
    }
}

@Composable
private fun ArtistSearchRow(
    artist: SearchResult.Result.Artist,
    showTopSeparator: Boolean,
    onClick: () -> Unit,
) {
    GlasmioListRow(
        title = artist.name,
        subtitle = artist.alias.joinToString(" ").takeIf(String::isNotBlank),
        showTopSeparator = showTopSeparator,
        leading = {
            AsyncImage(
                model = artist.picUrl?.smallImage(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(CircleShape),
            )
        },
        onClick = onClick,
    )
}
