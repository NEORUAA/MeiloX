package com.ljyh.mei.ui.screen.main.library.component

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.data.model.room.DownloadTask
import com.ljyh.mei.data.model.room.Playlist
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.ui.component.GlobalProfileAvatarButton
import com.ljyh.mei.ui.component.player.OverlayState
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
import com.ljyh.mei.ui.screen.cloud.CloudMusicViewModel
import com.ljyh.mei.ui.screen.cloud.CloudMusicUiState
import com.ljyh.mei.ui.screen.history.HistoryViewModel
import com.ljyh.mei.ui.screen.history.HistoryUiState
import com.ljyh.mei.ui.screen.playlist.component.StandaloneTrackActionOverlay
import com.ljyh.mei.ui.screen.podcast.PodcastViewModel
import com.ljyh.mei.ui.screen.podcast.PodcastUiState
import com.ljyh.mei.ui.screen.podcast.PodcastPaginationFooter
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

/** MeloX Library: six user-configurable content pages under the shared collapsing title. */
@Composable
fun LibraryMobileLayout(
    @Suppress("UNUSED_PARAMETER") userPhoto: String,
    selectedPage: LibraryPage,
    onPageSelect: (LibraryPage) -> Unit,
    createdPlaylists: List<Playlist>,
    collectedPlaylists: List<Playlist>,
    albums: List<Album>,
    likedSongs: List<MediaMetadata>,
    likedSongsLoading: Boolean,
    userId: String,
    onPlaylistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
) {
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val podcastViewModel: PodcastViewModel? = if (selectedPage == LibraryPage.Podcasts) hiltViewModel() else null
    val podcastState = podcastViewModel?.state?.collectAsState()?.value
    LaunchedEffect(podcastViewModel) {
        podcastViewModel?.ensureSubscriptionsLoaded()
    }
    val cloudViewModel: CloudMusicViewModel? = if (selectedPage == LibraryPage.Cloud) hiltViewModel() else null
    val cloudState = cloudViewModel?.state?.collectAsState()?.value
    val historyViewModel: HistoryViewModel? = if (selectedPage == LibraryPage.History) hiltViewModel() else null
    val historyState = historyViewModel?.state?.collectAsState()?.value ?: HistoryUiState()
    LaunchedEffect(historyViewModel) {
        historyViewModel?.refresh()
    }
    val downloadTasks = if (selectedPage == LibraryPage.Downloads) {
        val context = LocalContext.current
        val dao = remember(context) { AppDatabase.getDatabase(context).downloadDao() }
        val tasksFlow = remember(dao) { dao.getAll() }
        tasksFlow.collectAsState(initial = emptyList()).value
    } else {
        emptyList()
    }
    val collapseDistance = with(LocalDensity.current) { 56.dp.toPx() }
    LaunchedEffect(listState, selectedPage, podcastViewModel) {
        if (selectedPage != LibraryPage.Podcasts || podcastViewModel == null) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
        }.distinctUntilChanged().collect { (lastVisibleIndex, totalItemsCount) ->
            if (totalItemsCount > 0 && lastVisibleIndex >= totalItemsCount - 1) {
                podcastViewModel.loadMoreSubscriptions()
            }
        }
    }
    val collapseProgress by remember(listState) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / collapseDistance).coerceIn(0f, 1f)
        }
    }
    val pages = LibraryPage.entries
    val title = stringResource(R.string.app_tab_library)
    val likedTitle = stringResource(R.string.app_tab_library_songs)
    val usesGroupedLazyRows = selectedPage == LibraryPage.Podcasts ||
        selectedPage == LibraryPage.Downloads ||
        selectedPage == LibraryPage.Cloud ||
        selectedPage == LibraryPage.History
    val pageSpacing = if (usesGroupedLazyRows) 12.dp else 0.dp
    var currentOverlay by remember { mutableStateOf<OverlayState>(OverlayState.None) }
    var searchQuery by remember { mutableStateOf(TextFieldValue()) }
    var searchActive by remember { mutableStateOf(false) }
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
    val visibleAlbums = remember(albums, query) {
        albums.filterIfSearching(query) { album ->
            album.title.containsQuery(query) || album.artist.any { it.name.containsQuery(query) }
        }
    }

    IosPinnedPage(
        title = title,
        bottomPadding = insets.calculateBottomPadding(),
        collapseProgress = collapseProgress,
        actions = { GlobalProfileAvatarButton() },
    ) { contentPadding ->
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
            item(key = "library-title") {
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
            item(key = "library-pages") {
                GlassSegmentedControl(
                    items = pages.map { it to stringResource(it.titleRes) },
                    selected = selectedPage,
                    onSelected = onPageSelect,
                    modifier = Modifier.fillMaxWidth().padding(bottom = pageSpacing),
                )
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
                                    currentOverlay = OverlayState.TrackActionMenu(song)
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
                    val playlists = visibleCreatedPlaylists + visibleCollectedPlaylists
                    if (playlists.isEmpty() && (!isSearching || visibleAlbums.isEmpty())) {
                        item {
                            EmptyState(
                                stringResource(
                                    if (isSearching) R.string.no_search_results else R.string.library_empty_collected,
                                ),
                                SfSymbol.MusicNoteList,
                            )
                        }
                    } else {
                        items(
                            playlists,
                            key = { "playlist-${it.id}" },
                            contentType = { "playlist" },
                        ) { playlist ->
                            LibraryPlaylistRow(playlist) { onPlaylistClick(playlist.id) }
                        }
                    }
                }

                LibraryPage.Podcasts -> {
                    podcastViewModel?.let { viewModel ->
                        podcastState?.let { state ->
                            libraryPodcastItems(navController, state, viewModel, query)
                        }
                    }
                }

                LibraryPage.Downloads -> libraryDownloadItems(downloadTasks, query)

                LibraryPage.Cloud -> {
                    cloudState?.let { state ->
                        libraryCloudItems(state, playerConnection, query)
                    }
                }

                LibraryPage.History -> libraryHistoryItems(historyState, playerConnection, query)
            }

            if (selectedPage == LibraryPage.Playlists && visibleAlbums.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.library_collected_albums),
                        style = IosTypography.headline,
                        color = LocalGlassColors.current.content,
                    )
                }
                items(
                    visibleAlbums,
                    key = { "album-${it.id}" },
                    contentType = { "album" },
                ) { album ->
                    LibraryMediaRow(
                        album.cover,
                        album.title,
                        album.artist.joinToString(" / ") { it.name },
                        onClick = { onAlbumClick(album.id.toString()) },
                    )
                }
            }
        }
    }

    StandaloneTrackActionOverlay(
        overlay = currentOverlay,
        onDismiss = { currentOverlay = OverlayState.None },
        onUpdateOverlay = { currentOverlay = it },
    )
}

@Composable
private fun LibrarySongRow(
    song: MediaMetadata,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
) {
    LibraryMediaRow(
        image = song.coverUrl,
        title = song.title,
        subtitle = song.artists.joinToString(" / ") { it.name },
        onClick = onClick,
        trailing = {
            Box(
                Modifier.size(44.dp).clip(ContinuousRoundedRectangle(22.dp)).clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = onMoreClick,
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
                color = LocalGlassColors.current.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = IosTypography.caption,
                color = LocalGlassColors.current.secondaryContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke() ?: SfIcon(
            "chevron.forward",
            null,
            size = 12.dp,
            tint = LocalGlassColors.current.tertiaryContent,
        )
    }
}

private fun LazyListScope.libraryPodcastItems(
    navController: com.ljyh.mei.ui.navigation.MeiNavigator,
    state: PodcastUiState,
    viewModel: PodcastViewModel,
    query: String,
) {
    val podcasts = state.subscribedPodcasts.filterIfSearching(query) { podcast ->
        podcast.name.containsQuery(query) ||
            podcast.host?.nickname.containsQuery(query) ||
            podcast.category.containsQuery(query) ||
            podcast.description.containsQuery(query) ||
            podcast.recommendation.containsQuery(query)
    }
    if (!state.subscriptionsLoaded && state.subscriptionsError == null) {
        item(key = "library-podcast-loading") {
            Box(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
    if (podcasts.isNotEmpty()) {
        groupedLazyItems(
            items = podcasts,
            key = { "podcast-${it.id}" },
            contentType = "podcast",
        ) { podcast, index ->
            IosListRow(
                title = podcast.name,
                subtitle = podcast.host?.nickname ?: podcast.category,
                showTopSeparator = index > 0,
                leading = {
                    AsyncImage(
                        podcast.picUrl,
                        null,
                        Modifier.size(44.dp).clip(ContinuousRoundedRectangle(10.dp)),
                        contentScale = ContentScale.Crop,
                    )
                },
                onClick = {
                    Screen.PodcastDetail.navigate(navController) { addPath(podcast.id.toString()) }
                },
            )
        }
        if (query.isEmpty() && (
                state.hasMoreSubscriptions || state.isLoadingMoreSubscriptions ||
                    state.subscriptionsLoadMoreError != null
                )
        ) {
            item(key = "library-podcast-pagination") {
                PodcastPaginationFooter(
                    failureMessage = state.subscriptionsLoadMoreError,
                    onLoadMore = viewModel::loadMoreSubscriptions,
                )
            }
        }
    } else if (!state.isSubscriptionsLoading) {
        item(key = "library-podcast-empty") {
            EmptyState(
                if (query.isNotEmpty()) stringResource(R.string.no_search_results)
                else state.subscriptionsError ?: stringResource(R.string.podcast_empty_subscriptions),
                SfSymbol.Microphone,
            )
        }
    }
}

private fun LazyListScope.libraryDownloadItems(tasks: List<DownloadTask>, query: String) {
    val visibleTasks = tasks.filterIfSearching(query) { task ->
        task.songTitle.containsQuery(query) ||
            task.songArtist.containsQuery(query) ||
            task.songAlbum.containsQuery(query)
    }
    if (visibleTasks.isEmpty()) {
        item(key = "library-download-empty") {
            EmptyState(
                if (query.isNotEmpty()) stringResource(R.string.no_search_results)
                else stringResource(R.string.no_download_tasks, stringResource(R.string.download_filter_all)),
                SfSymbol.Download,
            )
        }
    } else {
        groupedLazyItems(
            items = visibleTasks,
            key = { "download-${it.songId}" },
            contentType = "download-task",
        ) { task, index ->
            val detail = when (task.status) {
                DownloadStatus.DOWNLOADING -> "${task.progress}%"
                DownloadStatus.PAUSED -> stringResource(R.string.download_paused)
                DownloadStatus.COMPLETED -> stringResource(R.string.download_completed)
                DownloadStatus.FAILED -> stringResource(R.string.download_failed)
                DownloadStatus.PENDING -> stringResource(R.string.download_waiting)
            }
            IosListRow(
                title = task.songTitle.ifBlank { task.songId },
                subtitle = task.songArtist,
                detail = detail,
                showTopSeparator = index > 0,
                leading = {
                    AsyncImage(
                        task.songCover.ifBlank { null },
                        null,
                        Modifier.size(44.dp).clip(ContinuousRoundedRectangle(10.dp)),
                        contentScale = ContentScale.Crop,
                    )
                },
            )
        }
    }
}

private fun LazyListScope.libraryCloudItems(
    state: CloudMusicUiState,
    playerConnection: PlayerConnection?,
    query: String,
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
