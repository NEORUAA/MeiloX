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
import com.ljyh.mei.source.netease.ui.rememberNeteaseSearchResults
import com.ljyh.mei.ui.component.player.OverlayState
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import com.ljyh.mei.ui.screen.playlist.component.StandaloneTrackActionOverlay
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
    viewModel: SourceSearchViewModel = hiltViewModel(),
) {
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
    val selectedType = SearchType.entries.find { it.type == submittedType && it in supportedTypes } ?: SearchType.Song
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
    val nativeContent = if (selectedType != SearchType.Song && selectedType in supportedTypes)
        rememberNeteaseSearchResults(submittedQuery, selectedType,
            onSongMore = { track, anchor -> overlay = OverlayState.TrackActionMenu(track, anchor) },
            onSongClick = playTracks)
        else null

    LaunchedEffect(submittedQuery, providerId, selectedType) {
        if (selectedType == SearchType.Song) viewModel.search(submittedQuery)
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
            if (supportedTypes.size > 1) item("search-tabs") {
                SearchTypeFilterRow(
                    selectedType,
                    onSelect = { submittedType = it.type },
                    availableTypes = supportedTypes,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            if (nativeContent != null) nativeContent()
            else SourceTrackResults(state, providers,
                onPlay = { tracks, index -> playTracks(tracks.map { it.toMediaMetadata() }, index) },
                onLoadMore = viewModel::loadMore, onRetry = { viewModel.search(submittedQuery, force = true) },
                onSongMore = { track, anchor -> overlay = OverlayState.TrackActionMenu(track, anchor) })
        }
        StandaloneTrackActionOverlay(overlay, onDismiss = { overlay = OverlayState.None }, onUpdateOverlay = { overlay = it })
    }
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
