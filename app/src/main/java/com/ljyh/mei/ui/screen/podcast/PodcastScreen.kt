package com.ljyh.mei.ui.screen.podcast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.ljyh.mei.ui.screen.playlist.rememberDetailSelection
import com.ljyh.mei.ui.screen.playlist.DetailSelectionToolbar
import com.ljyh.mei.ui.screen.playlist.detailMenuItems
import com.ljyh.mei.ui.screen.playlist.matchesPlaylistSearch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.melox.Podcast
import com.ljyh.mei.data.model.melox.PodcastProgram
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.ui.component.GlobalProfileAvatarButton
import com.ljyh.mei.ui.component.item.Track
import com.ljyh.mei.ui.glass.GlassButton
import com.ljyh.mei.ui.glass.GlassSegmentedControl
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.IosListRow
import com.ljyh.mei.ui.glass.IosTypography
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.main.library.component.groupedLazyItems
import com.ljyh.mei.ui.screen.playlist.component.PlaylistHeader
import com.ljyh.mei.ui.screen.playlist.component.PlaylistShimmer
import com.ljyh.mei.ui.screen.playlist.component.PlaylistSurface
import com.ljyh.mei.utils.rememberPreference
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun PodcastScreen(
    viewModel: PodcastViewModel = hiltViewModel(),
    isNavigationTab: Boolean = false,
) {
    val state by viewModel.state.collectAsState()
    val navController = LocalNavController.current
    val insets = LocalPlayerAwareWindowInsets.current
    val bottomPadding = insets.asPaddingValues().calculateBottomPadding()
    val cookie by rememberPreference(CookieKey, defaultValue = "")
    val isVisitor = cookie.isBlank()
    val listState = rememberLazyListState()
    val colors = LocalGlassColors.current
    val pageBackground = if (state.selectedTab == PodcastTab.Subscriptions || colors.isDark) {
        colors.groupedBackground
    } else {
        Color.White
    }

    LaunchedEffect(state.selectedTab, isVisitor) {
        if (state.selectedTab == PodcastTab.Subscriptions && !isVisitor) {
            viewModel.ensureSubscriptionsLoaded()
        }
    }
    LaunchedEffect(listState, state.selectedTab) {
        if (state.selectedTab != PodcastTab.Subscriptions) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
        }.distinctUntilChanged().collect { (lastVisibleIndex, totalItemsCount) ->
            if (totalItemsCount > 0 && lastVisibleIndex >= totalItemsCount - 1) {
                viewModel.loadMoreSubscriptions()
            }
        }
    }

    IosPinnedListPage(
        title = stringResource(R.string.podcasts),
        bottomPadding = bottomPadding,
        listState = listState,
        horizontalContentPadding = 0.dp,
        largeTitleHorizontalPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        onNavigateBack = if (isNavigationTab) null else ({ navController.navigateUp() }),
        actions = {
            if (isNavigationTab) GlobalProfileAvatarButton()
        },
        backgroundColor = pageBackground,
    ) {
        item(key = "podcast-tabs") {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 22.dp)) {
                GlassSegmentedControl(
                    items = listOf(
                        PodcastTab.Discover to stringResource(R.string.podcast_discover),
                        PodcastTab.Subscriptions to stringResource(R.string.podcast_my_subscriptions),
                    ),
                    selected = state.selectedTab,
                    onSelected = viewModel::selectTab,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (state.selectedTab == PodcastTab.Discover) {
            if (state.isLoading && state.home == null) {
                item(key = "podcast-loading") { InlineLoadingState() }
            } else if (state.error != null && state.home == null) {
                item(key = "podcast-error") { InlineErrorState(state.error, viewModel::refresh) }
            }
            state.home?.categories?.takeIf(List<*>::isNotEmpty)?.let { categories ->
                item {
                    Box(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
                        PodcastCategorySelector(
                            categories = categories,
                            selectedCategoryId = state.selectedCategoryId,
                            onCategorySelected = viewModel::selectCategory,
                        )
                    }
                }
            }
            val visible = state.categoryPodcasts.takeIf { state.selectedCategoryId != null }
                ?: state.home?.personalized.orEmpty()
            item {
                Box(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
                    PodcastSection(
                        title = stringResource(
                            if (state.selectedCategoryId == null) R.string.podcast_for_you else R.string.podcast_category,
                        ),
                        podcasts = visible,
                        onClick = { Screen.PodcastDetail.navigate(navController) { addPath(it.toString()) } },
                    )
                }
            }
            state.home?.featured?.takeIf(List<*>::isNotEmpty)?.let { featured ->
                item {
                    PodcastSection(
                        title = stringResource(R.string.podcast_featured),
                        podcasts = featured,
                        onClick = { Screen.PodcastDetail.navigate(navController) { addPath(it.toString()) } },
                    )
                }
            }
        } else {
            when {
                isVisitor -> item(key = "podcast-subscriptions-sign-in") {
                    PodcastSubscriptionsEmptyState(
                        title = stringResource(R.string.podcast_subscriptions_sign_in),
                        description = stringResource(R.string.podcast_subscriptions_sign_in_description),
                        actionLabel = stringResource(R.string.library_sign_in),
                        onAction = { Screen.NeteaseLogin.navigate(navController) },
                    )
                }
                !state.subscriptionsLoaded && state.subscriptionsError == null -> {
                    item(key = "podcast-subscriptions-loading") { InlineLoadingState() }
                }
                state.subscriptionsError != null && !state.subscriptionsLoaded -> {
                    item(key = "podcast-subscriptions-error") {
                        InlineErrorState(state.subscriptionsError) { viewModel.refresh() }
                    }
                }
                state.subscribedPodcasts.isEmpty() -> item(key = "podcast-subscriptions-empty") {
                    PodcastSubscriptionsEmptyState(
                        title = stringResource(R.string.podcast_empty_subscriptions),
                        description = stringResource(R.string.podcast_empty_subscriptions_description),
                    )
                }
                else -> {
                    groupedLazyItems(
                        items = state.subscribedPodcasts,
                        key = { "subscribed-podcast-${it.id}" },
                        contentType = "subscribed-podcast",
                        horizontalPadding = 16.dp,
                    ) { podcast, index ->
                        IosListRow(
                            title = podcast.name,
                            subtitle = podcast.host?.nickname ?: podcast.category,
                            detail = stringResource(R.string.podcast_program_count, podcast.programCount),
                            showTopSeparator = index > 0,
                            leading = {
                                AsyncImage(
                                    model = podcast.picUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(ContinuousRoundedRectangle(10.dp)),
                                )
                            },
                            onClick = {
                                Screen.PodcastDetail.navigate(navController) { addPath(podcast.id.toString()) }
                            },
                        )
                    }
                    if (state.hasMoreSubscriptions || state.isLoadingMoreSubscriptions ||
                        state.subscriptionsLoadMoreError != null
                    ) {
                        item(key = "podcast-subscriptions-pagination") {
                            PodcastPaginationFooter(
                                failureMessage = state.subscriptionsLoadMoreError,
                                onLoadMore = viewModel::loadMoreSubscriptions,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PodcastCategorySelector(
    categories: List<com.ljyh.mei.data.model.melox.PodcastCategory>,
    selectedCategoryId: Long?,
    onCategorySelected: (Long?) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "podcast-category-recommended") {
            PodcastCategoryTab(
                title = stringResource(R.string.podcast_for_you),
                selected = selectedCategoryId == null,
                onClick = { onCategorySelected(null) },
            )
        }
        items(categories, key = { it.id }) { category ->
            PodcastCategoryTab(
                title = category.name,
                selected = selectedCategoryId == category.id,
                onClick = { onCategorySelected(category.id) },
            )
        }
    }
}

@Composable
private fun PodcastCategoryTab(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = title,
        color = if (selected) Color.White else LocalGlassColors.current.content,
        style = IosTypography.subheadline,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .clip(ContinuousRoundedRectangle(50))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else LocalGlassColors.current.groupedBackground,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 7.dp),
    )
}

@Composable
private fun PodcastSection(
    title: String,
    podcasts: List<Podcast>,
    onClick: (Long) -> Unit,
) {
    val colors = LocalGlassColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = IosTypography.title2,
            fontWeight = FontWeight.Bold,
            color = colors.content,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(podcasts, key = { it.id }) { podcast ->
                PodcastRecommendationCard(podcast, onClick)
            }
        }
    }
}

@Composable
private fun PodcastRecommendationCard(
    podcast: Podcast,
    onClick: (Long) -> Unit,
) {
    Column(
        modifier = Modifier.width(172.dp).clickable { onClick(podcast.id) },
    ) {
        AsyncImage(
            model = podcast.picUrl,
            contentDescription = podcast.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(ContinuousRoundedRectangle(14.dp)),
        )
        Text(
            text = podcast.name,
            style = IosTypography.subheadline,
            fontWeight = FontWeight.Medium,
            color = LocalGlassColors.current.content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        val subtitle = podcast.recommendation?.takeIf(String::isNotBlank)
            ?: podcast.host?.nickname?.takeIf(String::isNotBlank)
        subtitle?.let {
            Text(
                text = it,
                style = IosTypography.caption,
                color = LocalGlassColors.current.secondaryContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun PodcastDetailScreen(
    id: Long,
    viewModel: PodcastDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val downloadViewModel: com.ljyh.mei.ui.screen.playlist.PlaylistViewModel = hiltViewModel()
    val selection = rememberDetailSelection(id)
    val listState = rememberLazyListState()
    val detail = state.detail
    var searchActive by remember(id) { mutableStateOf(false) }
    var query by remember(id) { mutableStateOf("") }
    var searchResults by remember(id) { mutableStateOf<List<MediaMetadata>>(emptyList()) }
    var searching by remember(id) { mutableStateOf(false) }
    var searchError by remember(id) { mutableStateOf<String?>(null) }
    var pendingDownload by remember(id) { mutableStateOf<List<MediaMetadata>?>(null) }
    var preparingDownload by remember(id) { mutableStateOf(false) }
    val downloadPath by rememberPreference(com.ljyh.mei.constants.DownloadPathKey, com.ljyh.mei.utils.DownloadManager.getDefaultDownloadPath())
    val downloadQuality by com.ljyh.mei.utils.rememberEnumPreference(com.ljyh.mei.constants.DownloadQualityKey, com.ljyh.mei.constants.DownloadQuality.EXHIGH)
    LaunchedEffect(id) { viewModel.load(id) }
    LaunchedEffect(id, query, state.isLoading) {
        searchError = null
        if (query.isBlank() || state.isLoading || detail == null) {
            searching = false
            return@LaunchedEffect
        }
        searching = true
        try {
            kotlinx.coroutines.delay(200)
            searchResults = viewModel.allPrograms(id).map { it.asMediaMetadata() }
                .filter { it.matchesPlaylistSearch(query) }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) { searchError = error.message }
        finally { searching = false }
    }
    LaunchedEffect(id, listState, query) {
        if (query.isNotBlank()) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
        }.distinctUntilChanged().collect { (lastVisibleIndex, totalItemsCount) ->
            if (totalItemsCount > 0 && lastVisibleIndex >= totalItemsCount - 1) viewModel.loadMore()
        }
    }
    val tracks = if (query.isBlank()) detail?.programs.orEmpty().map { it.asMediaMetadata() } else searchResults
    val count = maxOf(detail?.totalCount ?: 0, detail?.podcast?.programCount ?: 0, detail?.programs?.size ?: 0)

    fun download(tracks: List<MediaMetadata>, quality: com.ljyh.mei.constants.MusicQuality) {
        scope.launch {
            try {
                val sources = tracks.map { it.id.toString() }.chunked(200).flatMap { ids ->
                    val result = downloadViewModel.resolveSongUrls(ids, quality)
                    if (result is com.ljyh.mei.data.network.Resource.Success) result.data.fullSourcesFor(ids.toSet()) else emptyList()
                }.associateBy { it.id.toString() }
                val songs = tracks.mapNotNull { track ->
                    val source = sources[track.id.toString()] ?: return@mapNotNull null
                    val url = source.url ?: return@mapNotNull null
                    com.ljyh.mei.playback.SongDownloadInfo(
                        songId = track.id.toString(), url = url, songTitle = track.title,
                        songArtist = track.artists.map { it.name }, songAlbum = track.album.title,
                        songCover = track.coverUrl, duration = track.duration,
                        fileType = source.encodeType, quality = source.level,
                    )
                }
                check(songs.isNotEmpty()) { "No downloadable programs" }
                com.ljyh.mei.utils.DownloadManager.enqueue(
                    context = context, songs = songs, playlistName = detail?.podcast?.name.orEmpty(),
                    playlistId = "podcast_$id", downloadPath = downloadPath,
                )
                android.widget.Toast.makeText(context, context.getString(R.string.detail_download_queued, songs.size), android.widget.Toast.LENGTH_SHORT).show()
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { android.widget.Toast.makeText(context, R.string.load_failed, android.widget.Toast.LENGTH_SHORT).show() }
        }
    }
    fun prepareDownload(quality: com.ljyh.mei.constants.MusicQuality? = null, ids: Set<String>? = null) {
        if (preparingDownload) return
        preparingDownload = true
        scope.launch {
            try {
                val selected = viewModel.allPrograms(id).filter { it.mainSongId != null }.map { it.asMediaMetadata() }
                    .filter { ids == null || it.id.toString() in ids }
                if (selected.isEmpty()) {
                    android.widget.Toast.makeText(context, R.string.load_failed, android.widget.Toast.LENGTH_SHORT).show()
                } else if (quality == null) pendingDownload = selected else download(selected, quality)
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { android.widget.Toast.makeText(context, R.string.load_failed, android.widget.Toast.LENGTH_SHORT).show() }
            finally { preparingDownload = false }
        }
    }
    DetailSelectionToolbar(selection,
        onSelectAll = {
            viewModel.allPrograms(id).filter { it.mainSongId != null }.map { it.asMediaMetadata() }
                .filter { it.matchesPlaylistSearch(query) }.map { it.id.toString() }.toSet()
        },
        onDownload = { prepareDownload(ids = selection.ids) },
    )
    val menu = detailMenuItems(
        downloadTitle = stringResource(R.string.detail_podcast_download_all, count),
        subscriptionTitle = stringResource(if (detail?.podcast?.isSubscribed == true) R.string.detail_podcast_unsubscribe else R.string.podcast_subscribe),
        subscribed = detail?.podcast?.isSubscribed == true,
        onDownload = { prepareDownload(it) }, onSelect = selection::start,
        onSubscribe = viewModel::toggleSubscription,
        onRefresh = { selection.finish(); viewModel.load(id, true) },
    )
    fun play(trackId: Long? = null, shuffle: Boolean = false) {
        val playable = detail?.programs.orEmpty().filter { it.mainSongId != null }
        val items = playable.map { it.asMediaMetadata().toMediaItem().let { song -> song.mediaId to song } }
        if (items.isEmpty()) return
        val index = if (shuffle) items.indices.random() else playable.indexOfFirst { it.mainSongId == trackId }.coerceAtLeast(0)
        playerConnection?.playQueue(ListQueue("podcast_$id", detail?.podcast?.name.orEmpty(), items, index), shuffle = shuffle)
    }
    com.ljyh.mei.ui.screen.playlist.CommonSongListScreen(
        uiData = com.ljyh.mei.ui.model.UiPlaylist(
            id = id, title = detail?.podcast?.name.orEmpty(), count = count,
            subscriberCount = detail?.podcast?.subscriberCount ?: 0,
            cover = detail?.podcast?.picUrl.orEmpty(), coverList = emptyList(),
            creatorName = detail?.podcast?.host?.nickname.orEmpty(), tracks = tracks,
            playCount = detail?.podcast?.playCount, isSubscribed = detail?.podcast?.isSubscribed == true,
        ),
        isLoading = (state.isLoading && detail == null) || searching,
        listState = listState,
        headerMetadata = listOf(stringResource(R.string.podcast_program_count, count),
            stringResource(R.string.song_wiki_play_count, (detail?.podcast?.playCount ?: 0).toString())).joinToString(" · "),
        onPlayAll = { play() }, onShufflePlay = { play(shuffle = true) },
        headerActionIcon = Icons.Default.Add,
        headerActionLabel = stringResource(if (detail?.podcast?.isSubscribed == true) R.string.detail_podcast_unsubscribe else R.string.podcast_subscribe),
        onHeaderAction = viewModel::toggleSubscription,
        onTrackClick = { track, _ ->
            if (selection.active) { if (track.id > 0) selection.toggle(track.id.toString()) }
            else if (track.id > 0) {
                // Search may return a program outside the pages loaded for scrolling.
                val playable = if (query.isBlank()) detail?.programs.orEmpty().map { it.asMediaMetadata() } else tracks
                val items = playable.filter { it.id > 0 }.map { it.toMediaItem().let { song -> song.mediaId to song } }
                val index = items.indexOfFirst { it.first == track.id.toString() }.coerceAtLeast(0)
                playerConnection?.playQueue(ListQueue("podcast_$id", detail?.podcast?.name.orEmpty(), items, index))
            }
        },
        onTrackDownload = { track, quality -> if (track.id > 0) download(listOf(track), quality) },
        detailMenu = menu, detailMenuTitle = stringResource(R.string.detail_podcast_menu),
        selectionMode = selection.active, selectedTrackIds = selection.ids, onSelectionDone = selection::finish,
        playlistSearchQuery = query, isPlaylistSearchActive = searchActive,
        onPlaylistSearchQueryChange = { query = it },
        onPlaylistSearchActiveChange = { searchActive = it; if (!it) query = "" },
        onBack = { navController.navigateUp() },
        footer = {
            (searchError ?: state.error)?.let { error ->
                item(key = "podcast-detail-error") { InlineErrorState(error) { viewModel.load(id, true) } }
            }
            if (query.isBlank() && (detail?.hasMore == true || state.isLoadingMore || state.loadMoreError != null)) {
                item(key = "podcast-program-pagination") {
                    PodcastPaginationFooter(state.loadMoreError, viewModel::loadMore)
                }
            }
        },
    )
    pendingDownload?.let { selected ->
        com.ljyh.mei.ui.component.DownloadConfirmDialog(
            currentQuality = downloadQuality, downloadPath = downloadPath,
            onDismiss = { pendingDownload = null },
            onConfirm = { pendingDownload = null; download(selected, downloadQuality.toMusicQuality()) },
            onGoToSettings = { pendingDownload = null; Screen.DownloadSettings.navigate(navController) },
            onGoToDownloadManage = { pendingDownload = null; Screen.DownloadManage.navigate(navController) },
        )
    }
}

private fun PodcastProgram.asMediaMetadata() = MediaMetadata(
    id = mainSongId ?: -id,
    title = name,
    coverUrl = coverUrl.orEmpty(),
    artists = listOf(MediaMetadata.Artist(host?.id ?: 0, host?.nickname ?: radioName)),
    duration = durationMs,
    album = MediaMetadata.Album(radioId, radioName),
    isPodcast = true,
)

@Composable
private fun InlineLoadingState() {
    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun InlineErrorState(message: String?, retry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            message ?: stringResource(R.string.load_failed),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GlassButton(onClick = retry) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
private fun PodcastSubscriptionsEmptyState(
    title: String,
    description: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SfIcon(SfSymbol.Microphone, null, size = 42.dp, tint = LocalGlassColors.current.tertiaryContent)
        Text(
            title,
            style = IosTypography.headline,
            color = LocalGlassColors.current.content,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            description,
            style = IosTypography.subheadline,
            color = LocalGlassColors.current.secondaryContent,
            modifier = Modifier.padding(top = 5.dp),
        )
        if (actionLabel != null && onAction != null) {
            GlassButton(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun PodcastPaginationFooter(
    failureMessage: String?,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (failureMessage != null) {
            Text(
                failureMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GlassButton(onClick = onLoadMore) { Text(stringResource(R.string.retry)) }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(9.dp))
                Text(
                    stringResource(R.string.podcast_loading_more),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
