package com.ljyh.mei.ui.screen.search

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.R
import com.ljyh.mei.constants.PodcastsEnabledKey
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.ui.component.player.OverlayState
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import com.ljyh.mei.ui.screen.playlist.component.StandaloneTrackActionOverlay
import com.ljyh.mei.source.api.*
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.source.*
import com.ljyh.mei.ui.screen.main.library.component.groupedLazyItems
import android.net.Uri
import coil3.compose.AsyncImage
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.utils.rememberPreference
import io.github.neoruaa.glasmio.ui.GlasmioSegmentedControl
import io.github.neoruaa.glasmio.ui.GlasmioSearchBar
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow

@OptIn(UnstableApi::class)
@Composable
fun SearchResultScreen(
    query: String,
    type: Int,
    viewModel: SourceSearchViewModel = hiltViewModel(key = "source-search-choice"),
) {
    LaunchedEffect(viewModel) { viewModel.useSearchPreference() }
    val state by viewModel.state.collectAsState()
    val providerId by viewModel.selectedProviderId.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val choices by viewModel.sourceChoices.collectAsState()
    val context = LocalContext.current
    val player = LocalPlayerConnection.current
    val navController = LocalNavController.current
    val bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    val listState = rememberLazyListState()
    val focus = LocalFocusManager.current
    var submittedQuery by rememberSaveable(query, type) { mutableStateOf(query) }
    var submittedType by rememberSaveable(query, type) { mutableIntStateOf(type) }
    val supportedTypes = viewModel.supportedSearchTypes(providerId)
    val selectedType = supportedSourceSearchType(submittedType, supportedTypes)
    var searchQuery by rememberSaveable(query, type, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(query)) }
    var searchActive by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf<OverlayState>(OverlayState.None) }
    val playTracks: (List<MediaMetadata>, Int) -> Unit = { songs, index ->
        player?.playQueue(ListQueue(
            id = "SourceSearch-$providerId-$submittedQuery", title = "搜索: $submittedQuery",
            items = songs.map { song -> song.toMediaItem().let { it.mediaId to it } },
            startIndex = index, position = 0,
        ))
    }
    val contentType = when (selectedType) {
        SearchType.Album -> ContentType.ALBUM
        SearchType.Artist -> ContentType.ARTIST
        SearchType.Playlist -> ContentType.PLAYLIST
        else -> ContentType.TRACK
    }
    val onTrackMore: (MediaMetadata, Rect) -> Unit = { metadata, anchor ->
        overlay = OverlayState.TrackActionMenu(metadata, anchor)
    }

    LaunchedEffect(submittedQuery, providerId, selectedType) {
        if (selectedType != null) viewModel.search(submittedQuery, type = contentType, podcasts = selectedType == SearchType.Podcast)
        else if (selectedType == null) viewModel.search("", force = true)
    }
    LaunchedEffect(submittedQuery) { context.recordSearchHistory(submittedQuery) }
    LaunchedEffect(submittedQuery, selectedType, providerId) { listState.scrollToItem(0) }

    Box(Modifier.fillMaxSize()) {
        MeiloXPinnedListPage(
            title = stringResource(R.string.search_details_title), bottomPadding = bottom, listState = listState,
            topContentPaddingAdjustment = if (com.ljyh.mei.ui.component.utils.rememberDeviceInfo().isLandscape)
                com.ljyh.mei.constants.LandscapeSearchTopAdjustment else 0.dp,
            verticalArrangement = Arrangement.spacedBy(0.dp), onNavigateBack = navController::navigateUp,
            actions = { listOf(sourcePickerHeaderAction(choices, providerId, viewModel::selectProvider)) },
        ) {
            item("search-query") {
                GlasmioSearchBar(
                    query = searchQuery, onQueryChange = { searchQuery = it }, active = searchActive,
                    onActiveChange = { searchActive = it },
                    onClose = { searchQuery = TextFieldValue(submittedQuery); searchActive = false },
                    placeholder = stringResource(R.string.search_bar_search), closeContentDescription = stringResource(R.string.cancel),
                    onSearch = { input ->
                        val keyword = input.trim()
                        if (keyword.isNotEmpty()) {
                            submittedQuery = keyword
                            searchQuery = TextFieldValue(keyword)
                            searchActive = false
                            focus.clearFocus()
                        }
                    }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
            if (supportedTypes.size > 1 && selectedType != null) item("search-tabs") {
                SearchTypeFilterRow(
                    selectedType,
                    onSelect = { submittedType = it.type },
                    availableTypes = supportedTypes,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            if (selectedType == null) item("source-search-unsupported") {
                if (providerId.isBlank()) LoadingView() else GlasmioGroupedList(Modifier.padding(top = 10.dp)) {
                    GlasmioListRow("此音源不支持搜索", showTopSeparator = false, showDisclosure = false)
                }
            }
            else if (selectedType == SearchType.Podcast) {
                groupedLazyItems(state.podcasts, key = { "search-podcast:${it.providerId}:${it.id}" }, contentType = "search-podcast", firstItemTopPadding = 10.dp) { podcast, index ->
                    GlasmioListRow(title = podcast.name,
                        subtitle = listOfNotNull(podcast.host?.nickname, podcast.category).filter(String::isNotBlank).joinToString(" · ").takeIf(String::isNotBlank),
                        showTopSeparator = index > 0, leading = { AsyncImage(podcast.picUrl, null, Modifier.size(44.dp).clip(ContinuousRoundedRectangle(10.dp)), contentScale = ContentScale.Crop) },
                        onClick = { Screen.PodcastDetail.navigate(navController) { addPath(Uri.encode(podcast.id)); addQuery("providerId", Uri.encode(podcast.providerId)) } })
                }
                if (state.loading) item("search-podcast-loading") { LoadingView() }
                state.error?.let { error -> item("search-podcast-error") { ErrorView(sourceSearchErrorText(error)) } }
                if (!state.loading && state.error == null && state.podcasts.isEmpty()) item("search-podcast-empty") {
                    GlasmioGroupedList(Modifier.padding(top = 10.dp)) { EmptyView() }
                }
                if (!state.loading && state.hasMore && state.error == null) item("search-podcast-more") {
                    LaunchedEffect(providerId, state.query, state.podcasts.size) { viewModel.loadMore() }
                    LoadingView()
                }
            }
            else if (selectedType == SearchType.Song) SourceTrackResults(state, providers,
                onPlay = { tracks, index -> playTracks(tracks.map { it.toMediaMetadata() }, index) },
                onLoadMore = viewModel::loadMore, onRetry = { viewModel.search(submittedQuery, force = true) },
                onSongMore = onTrackMore)
            else {
                sourceContentItems(state.items, providers, { navigateSourceContent(navController, it) },
                    { tracks, index -> playTracks(tracks.map { it.toMediaMetadata() }, index) },
                    { track, anchor -> overlay = OverlayState.TrackActionMenu(track.toMediaMetadata(), anchor) }, "typed-search",
                    subtitle = { sourceSearchSubtitle(it) })
                if (!state.loading && !state.hasMore && state.error == null && state.items.isEmpty()) {
                    item("typed-search-empty") { GlasmioGroupedList(Modifier.padding(top = 10.dp)) { EmptyView() } }
                } else {
                    sourceContentStatus(SourceContentState(providerId = providerId, items = state.items,
                        loading = state.loading, hasMore = state.hasMore, nextOffset = state.items.size,
                        nextCursor = state.nextCursor, error = state.error),
                        { viewModel.search(submittedQuery, force = true, type = contentType) }, viewModel::loadMore)
                }
            }
        }
        StandaloneTrackActionOverlay(overlay, onDismiss = { overlay = OverlayState.None }, onUpdateOverlay = { overlay = it })
    }
}

@Composable
private fun sourceSearchSubtitle(item: SourceItem): String = when (item) {
    is SourceItem.AlbumItem -> listOf(
        item.album.artists.joinToString(" / ") { it.name },
        stringResource(R.string.playlist_song_count, item.album.trackCount),
    ).filter(String::isNotBlank).joinToString(" · ")
    is SourceItem.PlaylistItem -> "${item.playlist.trackCount} • ${item.playlist.creatorName}"
    is SourceItem.ArtistItem -> item.artist.aliases.ifEmpty { item.artist.alternateNames }.joinToString(" ")
    is SourceItem.TrackItem -> sourceItemSubtitle(item)
}

@Composable
fun SearchTypeFilterRow(
    selected: SearchType,
    onSelect: (SearchType) -> Unit,
    availableTypes: Set<SearchType> = SearchType.entries.toSet(),
    modifier: Modifier = Modifier,
) {
    val podcastsEnabled by rememberPreference(PodcastsEnabledKey, true)
    val tabItems = SearchType.entries
        .filter { it in availableTypes && it != SearchType.History && (it != SearchType.Podcast || podcastsEnabled) }
        .map { it to stringResource(it.labelRes) }

    GlasmioSegmentedControl(
        items = tabItems,
        selected = selected,
        onSelected = onSelect,
        modifier = modifier.fillMaxWidth(),
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

@Composable
fun EmptyView() {
    GlasmioListRow(
        title = stringResource(R.string.no_search_results),
        showTopSeparator = false,
    )
}
