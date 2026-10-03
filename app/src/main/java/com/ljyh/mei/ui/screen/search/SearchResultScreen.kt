package com.ljyh.mei.ui.screen.search

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

import android.widget.Toast
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import com.ljyh.mei.ui.glass.GlassButton
import com.ljyh.mei.ui.glass.GlassEmphasis
import com.ljyh.mei.ui.glass.GlassSegmentedControl
import com.ljyh.mei.ui.glass.IosGroupedList
import com.ljyh.mei.ui.glass.IosListRow
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.navigation.MeiNavigator
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.main.library.component.groupedLazyItems
import com.ljyh.mei.ui.screen.playlist.component.StandaloneTrackActionOverlay
import com.ljyh.mei.utils.rememberPreference
import com.ljyh.mei.utils.smallImage

@OptIn(UnstableApi::class)
@Composable
fun SearchResultScreen(
    query: String,
    type: Int,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val searchState by viewModel.searchResult.collectAsState()
    val selectedType by viewModel.currentTab.collectAsState()
    val playerConnection = LocalPlayerConnection.current
    val navController = LocalNavController.current
    val bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    val listState = rememberLazyListState()
    var currentOverlay by remember { mutableStateOf<OverlayState>(OverlayState.None) }

    // Keep initialization and the ViewModel's tab/cache semantics unchanged.
    LaunchedEffect(query, type) {
        viewModel.onSearchInit(query, type)
    }
    LaunchedEffect(query, selectedType) {
        listState.scrollToItem(0)
    }

    Box(Modifier.fillMaxSize()) {
        IosPinnedListPage(
            title = query,
            bottomPadding = bottomPadding,
            listState = listState,
            verticalArrangement = Arrangement.spacedBy(0.dp),
            onNavigateBack = navController::navigateUp,
        ) {
            item(key = "search-tabs") {
                Box(Modifier.padding(top = 10.dp)) {
                    SearchTypeFilterRow(
                        selected = selectedType,
                        onSelect = viewModel::onTabChange,
                    )
                }
            }

            when (val result = searchState) {
                is Resource.Loading -> item(key = "search-loading") { LoadingView() }
                is Resource.Error -> item(key = "search-error") { ErrorView(result.message) }
                is Resource.Success -> SearchResultList(
                    data = result.data,
                    type = selectedType,
                    navController = navController,
                    onSongMore = { track, anchor -> currentOverlay = OverlayState.TrackActionMenu(track, anchor) },
                    onSongClick = { songs, index ->
                        playerConnection?.playQueue(
                            ListQueue(
                                id = "SearchQueue-$query",
                                title = "搜索: $query",
                                items = songs.map { song ->
                                    song.id.toString() to song.toMediaData().toMediaItem()
                                },
                                startIndex = index,
                                position = 0,
                            ),
                        )
                    },
                )
            }
        }

        StandaloneTrackActionOverlay(
            overlay = currentOverlay,
            onDismiss = { currentOverlay = OverlayState.None },
            onUpdateOverlay = { currentOverlay = it },
        )
    }
}

@Composable
fun SearchTypeFilterRow(
    selected: SearchType,
    onSelect: (SearchType) -> Unit,
) {
    val podcastsEnabled by rememberPreference(PodcastsEnabledKey, true)
    val tabItems = SearchType.entries
        .filter { it != SearchType.History && (it != SearchType.Podcast || podcastsEnabled) }
        .map { it to stringResource(it.labelRes) }

    GlassSegmentedControl(
        items = tabItems,
        selected = selected,
        onSelected = onSelect,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun LoadingView() {
    Box(
        Modifier.fillMaxWidth().padding(top = 10.dp).padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
fun ErrorView(message: String) {
    Box(
        Modifier.fillMaxWidth().padding(top = 10.dp).padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.load_failed_message, message))
    }
}

fun LazyListScope.SearchResultList(
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
                    IosGroupedList(Modifier.padding(top = 10.dp)) { EmptyView() }
                }
            } else {
                groupedLazyItems(
                    items = songs,
                    key = { "search-song-${it.id}" },
                    contentType = "search-song",
                    firstItemTopPadding = 10.dp,
                ) { song, index ->
                    val media = song.toMediaData()
                    IosListRow(
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
                    IosGroupedList(Modifier.padding(top = 10.dp)) { EmptyView() }
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
                    IosGroupedList(Modifier.padding(top = 10.dp)) { EmptyView() }
                }
            } else {
                groupedLazyItems(
                    items = albums,
                    key = { "search-album-${it.id}" },
                    contentType = "search-album",
                    firstItemTopPadding = 10.dp,
                ) { album, index ->
                    val albumModel = album.toAlbum()
                    IosListRow(
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
                    IosGroupedList(Modifier.padding(top = 10.dp)) { EmptyView() }
                }
            } else {
                groupedLazyItems(
                    items = playlists,
                    key = { "search-playlist-${it.id}" },
                    contentType = "search-playlist",
                    firstItemTopPadding = 10.dp,
                ) { playlist, index ->
                    val playlistModel = playlist.toPlaylist()
                    IosListRow(
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
                    IosGroupedList(Modifier.padding(top = 10.dp)) { EmptyView() }
                }
            } else {
                groupedLazyItems(
                    items = podcasts,
                    key = { "search-podcast-${it.id}" },
                    contentType = "search-podcast",
                    firstItemTopPadding = 10.dp,
                ) { podcast, index ->
                    IosListRow(
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
    val context = LocalContext.current
    IosListRow(
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
        trailing = {
            GlassButton(
                onClick = {
                    Toast.makeText(context, "正在建设中: ${artist.name}", Toast.LENGTH_SHORT).show()
                },
                emphasis = GlassEmphasis.Regular,
            ) {
                Text(stringResource(R.string.artist_follow))
            }
        },
        onClick = onClick,
    )
}

@Composable
fun EmptyView() {
    IosListRow(
        title = stringResource(R.string.no_search_results),
        showTopSeparator = false,
    )
}
