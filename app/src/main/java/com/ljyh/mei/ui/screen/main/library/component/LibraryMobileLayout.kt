package com.ljyh.mei.ui.screen.main.library.component

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

import android.text.format.DateUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.constants.PodcastsEnabledKey
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.room.Playlist
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.ui.component.GlobalProfileAvatarButton
import com.ljyh.mei.ui.component.player.OverlayState
import com.ljyh.mei.ui.component.player.PlayerViewModel
import com.ljyh.mei.ui.glass.GlassSearchBar
import com.ljyh.mei.ui.glass.GlassSegmentedControl
import com.ljyh.mei.ui.glass.IosGroupedList
import com.ljyh.mei.ui.glass.IosListRow
import com.ljyh.mei.ui.glass.IosPinnedPage
import com.ljyh.mei.ui.glass.IosTypography
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.LocalGroupedListBackgroundAlpha
import com.ljyh.mei.ui.glass.LocalGroupedListIconColor
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.model.Album
import com.ljyh.mei.ui.navigation.LibraryPage
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.main.library.LibraryCollectionState
import com.ljyh.mei.ui.screen.main.library.LibraryPlaylistFilter
import com.ljyh.mei.ui.screen.cloud.CloudMusicViewModel
import com.ljyh.mei.ui.screen.cloud.CloudMusicUiState
import com.ljyh.mei.ui.screen.cloud.CloudMusicPaginationFooter
import com.ljyh.mei.ui.screen.history.HistoryViewModel
import com.ljyh.mei.ui.screen.history.HistoryUiState
import com.ljyh.mei.ui.screen.playlist.PlaylistViewModel
import com.ljyh.mei.ui.screen.playlist.component.StandaloneTrackActionOverlay
import com.ljyh.mei.ui.screen.podcast.PodcastViewModel
import com.ljyh.mei.ui.screen.podcast.PodcastUiState
import com.ljyh.mei.ui.screen.podcast.PodcastTab
import com.ljyh.mei.utils.rememberPreference
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Adds rows directly to the parent lazy list while retaining the visual treatment of
 * [IosGroupedList]. The original component owns a ColumnScope, so putting a large dynamic list
 * inside it eagerly composes every row. Each row gets the corresponding edge shape here and can
 * therefore be composed and disposed independently by the parent LazyColumn.
 */
internal fun <T> LazyListScope.groupedLazyItems(
    items: List<T>,
    key: ((T) -> Any)? = null,
    contentType: Any? = "grouped-row",
    firstItemTopPadding: Dp = 0.dp,
    horizontalPadding: Dp = 0.dp,
    itemContent: @Composable (item: T, index: Int) -> Unit,
) {
    itemsIndexed(
        items = items,
        key = key?.let { itemKey -> { _, item -> itemKey(item) } },
        contentType = { _, _ -> contentType },
    ) { index, item ->
        GroupedLazyListRow(
            index = index,
            itemCount = items.size,
            modifier = Modifier.padding(
                start = horizontalPadding,
                top = if (index == 0) firstItemTopPadding else 0.dp,
                end = horizontalPadding,
            ),
        ) {
            itemContent(item, index)
        }
    }
}

@Composable
private fun GroupedLazyListRow(
    index: Int,
    itemCount: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalGlassColors.current
    val background = colors.elevatedBackground.copy(
        alpha = LocalGroupedListBackgroundAlpha.current.coerceIn(0f, 1f),
    )
    val shape = when {
        itemCount == 1 -> ContinuousRoundedRectangle(26.dp)
        index == 0 -> ContinuousRoundedRectangle(topStart = 26.dp, topEnd = 26.dp)
        index == itemCount - 1 -> ContinuousRoundedRectangle(bottomStart = 26.dp, bottomEnd = 26.dp)
        else -> RectangleShape
    }
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background, shape),
    ) {
        CompositionLocalProvider(
            LocalGroupedListIconColor provides colors.accent,
            LocalContentColor provides colors.content,
        ) {
            content()
        }
    }
}

/** Library categories share the same collapsing title and search behavior. */
@Composable
fun LibraryMobileLayout(
    @Suppress("UNUSED_PARAMETER") userPhoto: String,
    isNavigationTab: Boolean,
    selectedPage: LibraryPage,
    onPageSelect: (LibraryPage) -> Unit,
    createdPlaylists: List<Playlist>,
    collectedPlaylists: List<Playlist>,
    playlistState: LibraryCollectionState<Playlist>,
    albumState: LibraryCollectionState<Album>,
    onRefreshPlaylists: () -> Unit,
    onLoadMorePlaylists: () -> Unit,
    onRefreshAlbums: () -> Unit,
    onLoadMoreAlbums: () -> Unit,
    likedSongs: List<MediaMetadata>,
    likedSongsLoading: Boolean,
    likedSongsError: String?,
    onRetryLikedSongs: () -> Unit,
    userId: String,
    onPlaylistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    isCategoryPage: Boolean = false,
    mergeAlbumPodcasts: Boolean = false,
) {
    val pages = LibraryPage.entries.filter { !mergeAlbumPodcasts || it != LibraryPage.Podcasts }
    val listState = rememberLazyListState()
    val tabPosition = remember {
        Animatable(pages.indexOf(selectedPage).coerceAtLeast(0).toFloat(), 0.001f)
    }
    if (selectedPage == LibraryPage.Downloads) {
        LibraryDownloadsPage(
            isNavigationTab = isNavigationTab,
            isCategoryPage = isCategoryPage,
            pages = pages,
            onPageSelect = onPageSelect,
            listState = listState,
            tabPosition = tabPosition,
        )
        return
    }
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current
    val focusManager = LocalFocusManager.current
    val playlistViewModel: PlaylistViewModel = hiltViewModel()
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val showsAlbums = selectedPage == LibraryPage.Albums
    val showsPodcasts = selectedPage == LibraryPage.Podcasts || (mergeAlbumPodcasts && showsAlbums)
    val podcastsEnabled by rememberPreference(PodcastsEnabledKey, true)
    val podcastViewModel: PodcastViewModel? = if (showsPodcasts && podcastsEnabled && userId.isNotBlank()) {
        hiltViewModel(key = "library-podcasts:$userId")
    } else null
    val podcastState = podcastViewModel?.state?.collectAsState()?.value
    LaunchedEffect(podcastViewModel) {
        podcastViewModel?.selectTab(PodcastTab.Subscriptions)
        podcastViewModel?.ensureSubscriptionsLoaded()
    }
    val cloudViewModel: CloudMusicViewModel? = if (selectedPage == LibraryPage.Cloud) hiltViewModel() else null
    val cloudState = cloudViewModel?.state?.collectAsState()?.value
    val historyViewModel: HistoryViewModel? = if (selectedPage == LibraryPage.History) hiltViewModel() else null
    val historyState = historyViewModel?.state?.collectAsState()?.value ?: HistoryUiState()
    LaunchedEffect(historyViewModel) {
        historyViewModel?.refresh()
    }
    val collapseDistance = with(LocalDensity.current) { 56.dp.toPx() }
    LaunchedEffect(listState, selectedPage, podcastViewModel) {
        if (!showsPodcasts || podcastViewModel == null) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
        }.distinctUntilChanged().collect { (lastVisibleIndex, totalItemsCount) ->
            if (totalItemsCount > 0 && lastVisibleIndex >= totalItemsCount - 1) {
                if (podcastViewModel.state.value.subscriptionsLoadMoreError == null) {
                    podcastViewModel.loadMoreSubscriptions()
                }
            }
        }
    }
    val collapseProgress by remember(listState) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / collapseDistance).coerceIn(0f, 1f)
        }
    }
    val title = stringResource(if (isCategoryPage) selectedPage.titleRes else R.string.app_tab_library)
    val likedTitle = stringResource(R.string.app_tab_library_songs)
    val usesGroupedLazyRows = selectedPage == LibraryPage.Cloud ||
        selectedPage == LibraryPage.History
    val pageSpacing = if (usesGroupedLazyRows) 12.dp else 0.dp
    var currentOverlay by remember { mutableStateOf<OverlayState>(OverlayState.None) }
    var searchQuery by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    val query = searchQuery.text.trim()
    val isSearching = query.isNotEmpty()
    val visibleLikedSongs = remember(likedSongs, query) {
        likedSongs.filterIfSearching(query) { song ->
            song.title.containsQuery(query) ||
                song.album.title.containsQuery(query) ||
                song.tns.containsQuery(query) ||
                song.artists.any { artist ->
                    artist.name.containsQuery(query) || artist.alias.orEmpty().any { it.containsQuery(query) }
                }
        }
    }
    val visibleCreatedPlaylists = remember(createdPlaylists, query) {
        createdPlaylists.filterIfSearching(query) { it.matchesQuery(query) }
    }
    val visibleCollectedPlaylists = remember(collectedPlaylists, query) {
        collectedPlaylists.filterIfSearching(query) { it.matchesQuery(query) }
    }
    val visibleAlbums = remember(albumState.items, query) {
        albumState.items.filterIfSearching(query) { album ->
            album.title.containsQuery(query) || album.artist.any { it.name.containsQuery(query) }
        }
    }

    var playlistFilter by rememberSaveable { mutableStateOf(LibraryPlaylistFilter.All) }
    val visiblePlaylists = when (playlistFilter) {
        LibraryPlaylistFilter.All -> visibleCreatedPlaylists + visibleCollectedPlaylists
        LibraryPlaylistFilter.Created -> visibleCreatedPlaylists
        LibraryPlaylistFilter.Subscribed -> visibleCollectedPlaylists
    }
    val collectionLoading = if (selectedPage == LibraryPage.Playlists) {
        playlistState.isLoading
    } else {
        (showsAlbums && albumState.isLoading) || (showsPodcasts && podcastState?.isSubscriptionsLoading == true)
    }
    var isPullRefreshing by remember(selectedPage, mergeAlbumPodcasts) { mutableStateOf(false) }
    LaunchedEffect(collectionLoading) {
        if (!collectionLoading) isPullRefreshing = false
    }

    IosPinnedPage(
        title = title,
        bottomPadding = insets.calculateBottomPadding(),
        collapseProgress = collapseProgress,
        onNavigateBack = if (isNavigationTab) null else ({ navController.navigateUp() }),
        actions = {
            if (isNavigationTab) GlobalProfileAvatarButton()
        },
    ) { contentPadding ->
        LibraryCollectionRefresh(
            enabled = showsAlbums || showsPodcasts || selectedPage == LibraryPage.Playlists,
            isRefreshing = isPullRefreshing && collectionLoading,
            topPadding = contentPadding.calculateTopPadding(),
            onRefresh = {
                isPullRefreshing = true
                if (selectedPage == LibraryPage.Playlists) onRefreshPlaylists()
                if (showsAlbums) onRefreshAlbums()
                if (showsPodcasts) {
                    podcastViewModel?.selectTab(PodcastTab.Subscriptions)
                    podcastViewModel?.refresh()
                }
            },
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val albumColumns = (maxWidth / 180.dp).toInt().coerceAtLeast(2)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = contentPadding.calculateTopPadding(),
                        end = 16.dp,
                        bottom = contentPadding.calculateBottomPadding(),
                    ),
                    verticalArrangement = if (usesGroupedLazyRows) Arrangement.spacedBy(0.dp) else Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "ios-large-title:$title") {
                        Text(
                            title,
                            style = IosTypography.largeTitle,
                            color = LocalGlassColors.current.content,
                            modifier = Modifier
                                .offset(y = (-10).dp)
                                .padding(vertical = 6.dp)
                                .padding(bottom = pageSpacing),
                        )
                    }
                    item(key = "library-search") {
                        GlassSearchBar(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            active = searchActive,
                            onActiveChange = { searchActive = it },
                            onClose = {
                                searchQuery = TextFieldValue()
                                searchActive = false
                            },
                            placeholder = stringResource(R.string.search_bar_search),
                            closeContentDescription = stringResource(R.string.cancel),
                            onSearch = { focusManager.clearFocus() },
                            modifier = Modifier.fillMaxWidth().padding(bottom = pageSpacing),
                        )
                    }
                    if (!isCategoryPage) {
                        item(key = "library-pages") {
                            GlassSegmentedControl(
                                items = pages.map { it to stringResource(it.titleRes) },
                                selected = selectedPage,
                                onSelected = onPageSelect,
                                positionAnimation = tabPosition,
                                modifier = Modifier.fillMaxWidth().padding(bottom = pageSpacing),
                            )
                        }
                    }

                    when (selectedPage) {
                        LibraryPage.Songs -> {
                            if (likedSongs.isEmpty() && likedSongsLoading) {
                                item(key = "liked-loading") {
                                    Box(
                                        Modifier.fillMaxWidth().height(120.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                }
                            } else if (likedSongsError != null && likedSongs.isEmpty()) {
                                item(key = "liked-error") { CollectionFooter(false, true, onRetryLikedSongs) }
                            } else if (visibleLikedSongs.isNotEmpty()) {
                                item(key = "liked-actions") {
                                    IosGroupedList {
                                        IosListRow(
                                            title = stringResource(R.string.library_play_all),
                                            systemName = "play.fill",
                                            showTopSeparator = false,
                                            onClick = {
                                                playerConnection?.playQueue(
                                                    ListQueue(
                                                        id = "library-liked",
                                                        title = likedTitle,
                                                        items = visibleLikedSongs.map { it.id.toString() to it.toMediaItem() },
                                                    ),
                                                )
                                            },
                                        )
                                        IosListRow(
                                            title = stringResource(R.string.library_heart_mode),
                                            systemName = "heart.circle.fill",
                                            onClick = {
                                                playerConnection?.fmStart(visibleLikedSongs.randomOrNull()?.id?.toString())
                                            },
                                        )
                                    }
                                }
                                items(
                                    visibleLikedSongs,
                                    key = { "liked-${it.id}" },
                                    contentType = { "liked-song" },
                                ) { song ->
                                    LibrarySongRow(
                                        song = song,
                                        onClick = {
                                            val index = visibleLikedSongs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                                            playerConnection?.playQueue(
                                                ListQueue(
                                                    id = "library-liked",
                                                    title = likedTitle,
                                                    items = visibleLikedSongs.map { it.id.toString() to it.toMediaItem() },
                                                    startIndex = index,
                                                ),
                                            )
                                        },
                                        onMoreClick = {
                                            currentOverlay = OverlayState.TrackActionMenu(song, it)
                                        },
                                    )
                                }
                            } else {
                                item {
                                    EmptyState(
                                        stringResource(
                                            if (isSearching) R.string.no_search_results else R.string.library_empty_songs,
                                        ),
                                        SfSymbol.MusicNote,
                                    )
                                }
                            }
                        }

                        LibraryPage.Playlists -> {
                            item(key = "playlist-filter") {
                                GlassSegmentedControl(
                                    items = listOf(
                                        LibraryPlaylistFilter.All to stringResource(R.string.library_filter_all),
                                        LibraryPlaylistFilter.Created to stringResource(R.string.library_filter_created),
                                        LibraryPlaylistFilter.Subscribed to stringResource(R.string.library_filter_subscribed),
                                    ),
                                    selected = playlistFilter,
                                    onSelected = { playlistFilter = it },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (!isSearching) {
                                item(key = "playlist-rank") {
                                    IosGroupedList {
                                        IosListRow(
                                            title = stringResource(R.string.account_listening_rank),
                                            systemName = "chart.bar.xaxis",
                                            showTopSeparator = false,
                                            onClick = {
                                                Screen.AccountListeningRank.navigate(navController) { addPath(userId) }
                                            },
                                        )
                                    }
                                }
                            }
                            if (visiblePlaylists.isEmpty() && !playlistState.isLoading && playlistState.error == null && !playlistState.hasMore) {
                                item(key = "playlist-empty") {
                                    EmptyState(
                                        stringResource(
                                            when {
                                                isSearching -> R.string.no_search_results
                                                playlistFilter == LibraryPlaylistFilter.Created -> R.string.library_empty_created_playlists
                                                playlistFilter == LibraryPlaylistFilter.Subscribed -> R.string.library_empty_subscribed_playlists
                                                else -> R.string.library_empty_collected
                                            },
                                        ),
                                        SfSymbol.MusicNoteList,
                                    )
                                }
                            }
                            items(visiblePlaylists, key = { "playlist-${it.id}" }, contentType = { "playlist" }) { playlist ->
                                LibraryPlaylistRow(playlist) { onPlaylistClick(playlist.id) }
                            }
                            if (playlistState.isLoading || playlistState.isLoadingMore || playlistState.error != null || playlistState.hasMore) {
                                item(key = "playlist-pagination") {
                                    CollectionFooter(
                                        loading = playlistState.isLoading || playlistState.isLoadingMore,
                                        failed = playlistState.error != null,
                                        onClick = if (playlistState.items.isEmpty() || playlistState.retryFromStart) onRefreshPlaylists else onLoadMorePlaylists,
                                    )
                                }
                            }
                        }

                        LibraryPage.Albums, LibraryPage.Podcasts -> {
                            if (showsAlbums) {
                                if (mergeAlbumPodcasts) {
                                    item(key = "library-albums-heading") {
                                        LibraryCollectionHeading(stringResource(R.string.library_page_albums))
                                    }
                                }
                                if (visibleAlbums.isEmpty() && !albumState.isLoading && albumState.error == null && !albumState.hasMore) {
                                    item(key = "album-empty") {
                                        EmptyState(
                                            stringResource(if (isSearching) R.string.no_search_results else R.string.library_empty_albums),
                                            SfSymbol.MusicNoteSquareStackFilled,
                                        )
                                    }
                                }
                                items(visibleAlbums.chunked(albumColumns), key = { "albums-${it.first().id}" }) { row ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        row.forEach { album ->
                                            LibraryGridItem(
                                                title = album.title,
                                                subtitle = album.artist.joinToString(" / ") { it.name },
                                                cover = album.cover,
                                                onClick = { onAlbumClick(album.id.toString()) },
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                        repeat(albumColumns - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                                if (albumState.isLoading || albumState.isLoadingMore || albumState.error != null || albumState.hasMore) {
                                    item(key = "album-pagination") {
                                        CollectionFooter(
                                            loading = albumState.isLoading || albumState.isLoadingMore,
                                            failed = albumState.error != null,
                                            onClick = if (albumState.items.isEmpty() || albumState.retryFromStart) onRefreshAlbums else onLoadMoreAlbums,
                                        )
                                    }
                                }
                            }
                            if (showsPodcasts && podcastsEnabled) {
                                if (mergeAlbumPodcasts) {
                                    item(key = "library-podcasts-heading") {
                                        LibraryCollectionHeading(stringResource(R.string.library_page_podcasts))
                                    }
                                }
                                podcastViewModel?.let { viewModel ->
                                    podcastState?.let { state ->
                                        libraryPodcastItems(navController, state, viewModel, query, albumColumns)
                                    }
                                }
                            }
                        }

                        LibraryPage.Downloads -> Unit

                        LibraryPage.Cloud -> {
                            cloudState?.let { state ->
                                libraryCloudItems(state, playerConnection, query) { cloudViewModel?.loadMore() }
                            }
                        }

                        LibraryPage.History -> libraryHistoryItems(historyState, playerConnection, query)
                    }

                }
            }
        }
    }

    StandaloneTrackActionOverlay(
        overlay = currentOverlay,
        onDismiss = { currentOverlay = OverlayState.None },
        onUpdateOverlay = { currentOverlay = it },
        playlistViewModel = playlistViewModel,
        playerViewModel = playerViewModel,
    )

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryCollectionRefresh(
    enabled: Boolean,
    isRefreshing: Boolean,
    topPadding: Dp,
    onRefresh: () -> Unit,
    content: @Composable () -> Unit,
) {
    val state = rememberPullToRefreshState()
    Box(
        modifier = Modifier.fillMaxSize().pullToRefresh(
            isRefreshing = enabled && isRefreshing,
            enabled = enabled,
            onRefresh = onRefresh,
            state = state,
        ),
    ) {
        content()
        if (enabled) {
            PullToRefreshDefaults.Indicator(
                isRefreshing = isRefreshing,
                state = state,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = topPadding),
            )
        }
    }
}

@Composable
private fun LibraryCollectionHeading(title: String) {
    Text(
        title,
        style = IosTypography.title2,
        color = LocalGlassColors.current.content,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}

@Composable
private fun CollectionFooter(loading: Boolean, failed: Boolean, onClick: () -> Unit) {
    if (loading) {
        Box(Modifier.fillMaxWidth().height(88.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = LocalGlassColors.current.accent)
        }
    } else {
        IosGroupedList {
            IosListRow(
                title = stringResource(if (failed) R.string.retry else R.string.library_load_more),
                subtitle = if (failed) stringResource(R.string.library_collection_load_failed) else null,
                systemName = if (failed) "arrow.clockwise" else "chevron.down",
                showTopSeparator = false,
                onClick = onClick,
            )
        }
    }
}

@Composable
private fun LibrarySongRow(
    song: MediaMetadata,
    onClick: () -> Unit,
    onMoreClick: (Rect) -> Unit,
) {
    var menuAnchor by remember { mutableStateOf(Rect.Zero) }
    LibraryMediaRow(
        image = song.coverUrl,
        title = song.title,
        subtitle = song.artists.joinToString(" / ") { it.name },
        onClick = onClick,
        trailing = {
            Box(
                Modifier.size(44.dp).onGloballyPositioned { menuAnchor = it.boundsInWindow() }
                    .clip(ContinuousRoundedRectangle(22.dp)).clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = { onMoreClick(menuAnchor) },
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
    )
}

@Composable
private fun LibraryPlaylistRow(playlist: Playlist, onClick: () -> Unit) {
    LibraryMediaRow(
        playlist.cover,
        playlist.title,
        stringResource(R.string.playlist_song_count, playlist.count),
        onClick,
    )
}

@Composable
private fun LibraryMediaRow(
    image: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalGlassColors.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            image,
            null,
            Modifier.size(54.dp).clip(ContinuousRoundedRectangle(9.dp)),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = IosTypography.body,
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = IosTypography.caption,
                color = colors.secondaryContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.let { trailingContent ->
            CompositionLocalProvider(LocalGroupedListIconColor provides colors.content) {
                trailingContent()
            }
        } ?: SfIcon("chevron.forward", null, size = 12.dp, tint = colors.separator)
    }
}

private fun LazyListScope.libraryPodcastItems(
    navController: com.ljyh.mei.ui.navigation.MeiNavigator,
    state: PodcastUiState,
    viewModel: PodcastViewModel,
    query: String,
    columns: Int,
) {
    val podcasts = state.subscribedPodcasts.filterIfSearching(query) { podcast ->
        podcast.name.containsQuery(query) ||
            podcast.host?.nickname.containsQuery(query) ||
            podcast.category.containsQuery(query) ||
            podcast.description.containsQuery(query) ||
            podcast.recommendation.containsQuery(query)
    }
    items(podcasts.chunked(columns), key = { "podcasts-${it.first().id}" }) { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            row.forEach { podcast ->
                LibraryGridItem(
                    title = podcast.name,
                    subtitle = podcast.host?.nickname ?: podcast.category.orEmpty(),
                    cover = podcast.picUrl.orEmpty(),
                    onClick = {
                        Screen.PodcastDetail.navigate(navController) { addPath(podcast.id.toString()) }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    val loading = state.isSubscriptionsLoading || state.isLoadingMoreSubscriptions ||
        (!state.subscriptionsLoaded && state.subscriptionsError == null)
    val failed = state.subscriptionsError != null || state.subscriptionsLoadMoreError != null
    if (loading || failed || state.hasMoreSubscriptions) {
        item(key = "library-podcast-pagination") {
            CollectionFooter(
                loading = loading,
                failed = failed,
                onClick = if (!state.subscriptionsLoaded || state.subscriptionsError != null) {
                    viewModel::refresh
                } else viewModel::loadMoreSubscriptions,
            )
        }
    } else if (podcasts.isEmpty()) {
        item(key = "library-podcast-empty") {
            EmptyState(
                stringResource(if (query.isNotEmpty()) R.string.no_search_results else R.string.podcast_empty_subscriptions),
                SfSymbol.Microphone,
            )
        }
    }
}

private fun LazyListScope.libraryCloudItems(
    state: CloudMusicUiState,
    playerConnection: PlayerConnection?,
    query: String,
    onLoadMore: () -> Unit,
) {
    val songs = state.page?.songs.orEmpty().filterIfSearching(query) { song ->
        song.name.containsQuery(query) ||
            song.artist.containsQuery(query) ||
            song.album.containsQuery(query)
    }
    when {
        state.isLoading && state.page == null -> item(key = "library-cloud-loading") {
            Box(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        songs.isEmpty() -> item(key = "library-cloud-empty") {
            EmptyState(
                if (query.isNotEmpty()) stringResource(R.string.no_search_results)
                else state.error ?: stringResource(R.string.library_empty_songs),
                SfSymbol.Cloud,
            )
        }
        else -> groupedLazyItems(
            items = songs,
            key = { "cloud-${it.id}" },
            contentType = "cloud-song",
        ) { song, index ->
            IosListRow(
                title = song.name,
                subtitle = listOf(song.artist, song.album).filter(String::isNotBlank).joinToString(" · "),
                showTopSeparator = index > 0,
                leading = {
                    AsyncImage(
                        song.coverUrl,
                        null,
                        Modifier.size(44.dp).clip(ContinuousRoundedRectangle(10.dp)),
                        contentScale = ContentScale.Crop,
                    )
                },
                onClick = {
                    val queue = songs.map { item ->
                        val mediaItem = MediaMetadata(
                            id = item.id,
                            title = item.name,
                            coverUrl = item.coverUrl.orEmpty(),
                            artists = listOf(MediaMetadata.Artist(item.artist.hashCode().toLong(), item.artist)),
                            duration = item.durationMs,
                            album = MediaMetadata.Album(item.album.hashCode().toLong(), item.album),
                        ).toMediaItem()
                        mediaItem.mediaId to mediaItem
                    }
                    playerConnection?.playQueue(ListQueue("library-cloud", "Cloud", queue, index))
                },
            )
        }
    }
    if (state.page?.hasMore == true || state.isLoadingMore || state.loadMoreError != null) {
        item(key = "library-cloud-pagination") {
            CloudMusicPaginationFooter(state, onLoadMore)
        }
    }
}

private fun LazyListScope.libraryHistoryItems(
    state: HistoryUiState,
    playerConnection: PlayerConnection?,
    query: String,
) {
    val visibleHistory = state.items.filterIfSearching(query) { item ->
        item.song.title.containsQuery(query) ||
            item.song.album.title.containsQuery(query) ||
            item.song.artists.any { it.name.containsQuery(query) }
    }
    if (state.isRefreshing && state.items.isEmpty()) {
        item(key = "library-history-loading") {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    } else if (visibleHistory.isEmpty()) {
        item(key = "library-history-empty") {
            EmptyState(
                text = when {
                    query.isNotEmpty() -> stringResource(R.string.no_search_results)
                    state.error != null -> stringResource(R.string.load_failed_message, state.error)
                    else -> stringResource(R.string.no_listening_history)
                },
                symbol = SfSymbol.Clock,
                description = if (query.isEmpty() && state.error == null) {
                    stringResource(R.string.no_listening_history_description)
                } else {
                    null
                },
            )
        }
    } else {
        groupedLazyItems(
            items = visibleHistory,
            key = { it.key },
            contentType = "history-item",
        ) { item, index ->
            IosListRow(
                title = item.song.title,
                subtitle = item.song.artists.joinToString(" / ") { it.name },
                detail = item.playedAt?.let { DateUtils.getRelativeTimeSpanString(it).toString() },
                showTopSeparator = index > 0,
                leading = {
                    AsyncImage(
                        item.song.coverUrl,
                        null,
                        Modifier.size(44.dp).clip(ContinuousRoundedRectangle(10.dp)),
                        contentScale = ContentScale.Crop,
                    )
                },
                onClick = {
                    playerConnection?.playQueue(
                        ListQueue(
                            id = "library-history",
                            title = "History",
                            items = visibleHistory.map { it.song.id.toString() to null },
                            startIndex = index,
                        ),
                    )
                },
            )
        }
    }
}

private inline fun <T> List<T>.filterIfSearching(
    query: String,
    predicate: (T) -> Boolean,
): List<T> = if (query.isEmpty()) this else filter(predicate)

private fun String?.containsQuery(query: String): Boolean =
    this?.contains(query, ignoreCase = true) == true

private fun Playlist.matchesQuery(query: String): Boolean =
    title.containsQuery(query) ||
        authorName.containsQuery(query) ||
        description.containsQuery(query)

@Composable
private fun EmptyState(text: String, symbol: SfSymbol, description: String? = null) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SfIcon(symbol, null, size = 38.dp, tint = LocalGlassColors.current.tertiaryContent)
        Text(
            text,
            style = IosTypography.subheadline,
            color = LocalGlassColors.current.secondaryContent,
            modifier = Modifier.padding(top = 10.dp),
        )
        description?.let {
            Text(
                text = it,
                style = IosTypography.caption,
                color = LocalGlassColors.current.tertiaryContent,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
