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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
) {
    val state by viewModel.state.collectAsState()
    val navController = LocalNavController.current
    val insets = LocalPlayerAwareWindowInsets.current
    val bottomPadding = insets.asPaddingValues().calculateBottomPadding()
    val topPadding = insets.asPaddingValues().calculateTopPadding()
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

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(pageBackground),
        contentPadding = PaddingValues(
            top = topPadding + 12.dp,
            bottom = bottomPadding + 20.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item(key = "podcast-title") {
            Text(
                text = stringResource(R.string.podcasts),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 22.dp),
            )
        }
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
    val bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    val listState = rememberLazyListState()
    val detail = state.detail
    LaunchedEffect(id) { viewModel.load(id) }
    LaunchedEffect(id, listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
        }.distinctUntilChanged().collect { (lastVisibleIndex, totalItemsCount) ->
            if (totalItemsCount > 0 && lastVisibleIndex >= totalItemsCount - 1) {
                viewModel.loadMore()
            }
        }
    }

    val playPrograms: (Long?) -> Unit = playPrograms@ { startProgramId ->
        val current = state.detail ?: return@playPrograms
        val playable = current.programs.filter { it.mainSongId != null }
        val startIndex = startProgramId
            ?.let { programId -> playable.indexOfFirst { it.id == programId } }
            ?.takeIf { it >= 0 }
            ?: 0
        val queueItems = playable.map { program ->
            val song = program.asMediaMetadata().toMediaItem()
            song.mediaId to song
        }
        if (queueItems.isNotEmpty()) {
            playerConnection?.playQueue(
                ListQueue("podcast_${current.podcast.id}", current.podcast.name, queueItems, startIndex),
            )
        }
    }

    IosPinnedListPage(
        title = detail?.podcast?.name.orEmpty(),
        subtitle = detail?.podcast?.host?.nickname,
        bottomPadding = bottomPadding,
        listState = listState,
        showsLargeTitle = false,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        onNavigateBack = navController::navigateUp,
    ) {
        when {
            state.isLoading && detail == null -> item(key = "podcast-detail-loading") {
                Box(Modifier.fillMaxWidth().height(620.dp)) {
                    PlaylistShimmer()
                }
            }

            detail == null -> item(key = "podcast-detail-error") {
                InlineErrorState(state.error) { viewModel.load(id, true) }
            }

            else -> {
                item(key = "podcast-detail-hero") {
                    val programCount = maxOf(
                        detail.totalCount,
                        detail.podcast.programCount,
                        detail.programs.size,
                    )
                    val metadata = listOf(
                        stringResource(R.string.podcast_program_count, programCount),
                        stringResource(R.string.song_wiki_play_count, detail.podcast.playCount.toString()),
                    ).joinToString(" · ")
                    PlaylistHeader(
                        title = detail.podcast.name,
                        cover = detail.podcast.picUrl.orEmpty(),
                        coverList = emptyList(),
                        creator = detail.podcast.host?.nickname.orEmpty(),
                        onPlayAll = { playPrograms(null) },
                        actionIcon = Icons.Default.Add,
                        actionLabel = stringResource(
                            if (detail.podcast.isSubscribed) R.string.podcast_subscribed
                            else R.string.podcast_subscribe,
                        ),
                        count = programCount,
                        playCount = detail.podcast.playCount,
                        subscribeCount = detail.podcast.subscriberCount,
                        isSubscribed = detail.podcast.isSubscribed,
                        onSubscribed = { viewModel.toggleSubscription() },
                        metadata = metadata,
                    )
                }
                state.error?.let { error ->
                    item(key = "podcast-detail-operation-error") {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }

                val hasFooter = detail.hasMore || state.isLoadingMore || state.loadMoreError != null
                itemsIndexed(
                    items = detail.programs,
                    key = { _, program -> "podcast-program-${program.id}" },
                    contentType = { _, _ -> "podcast-program" },
                ) { index, program ->
                    PlaylistSurface(
                        isFirst = index == 0,
                        isLast = index == detail.programs.lastIndex && !hasFooter,
                    ) {
                        Track(
                            track = program.asMediaMetadata(),
                            index = index,
                            onClick = { playPrograms(program.id) },
                            onMoreClick = null,
                        )
                        if (index < detail.programs.lastIndex || hasFooter) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 64.dp),
                                thickness = 0.5.dp,
                                color = LocalGlassColors.current.separator,
                            )
                        }
                    }
                }
                if (hasFooter) {
                    item(key = "podcast-program-pagination") {
                        PlaylistSurface(
                            isFirst = detail.programs.isEmpty(),
                            isLast = true,
                        ) {
                            PodcastPaginationFooter(
                                failureMessage = state.loadMoreError,
                                onLoadMore = viewModel::loadMore,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun PodcastProgram.asMediaMetadata() = MediaMetadata(
    id = mainSongId ?: -id,
    title = name,
    coverUrl = coverUrl.orEmpty(),
    artists = listOf(MediaMetadata.Artist(host?.id ?: 0, host?.nickname ?: radioName)),
    duration = durationMs,
    album = MediaMetadata.Album(radioId, radioName),
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
