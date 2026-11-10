package com.ljyh.mei.ui.screen.source

import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ljyh.mei.source.api.AccountRevisionCapability
import com.ljyh.mei.source.api.AccountCapability
import com.ljyh.mei.source.api.SourceCapability
import com.ljyh.mei.source.api.SourceStatus
import com.ljyh.mei.ui.screen.cloud.CloudMusicViewModel
import com.ljyh.mei.ui.screen.history.HistoryViewModel
import com.ljyh.mei.ui.screen.history.HistoryUiState
import com.ljyh.mei.ui.screen.podcast.PodcastViewModel
import com.ljyh.mei.ui.screen.podcast.PodcastTab
import com.ljyh.mei.ui.screen.main.library.component.LibraryCollectionHeading
import com.ljyh.mei.ui.screen.main.library.component.LibraryCollectionRefresh
import com.ljyh.mei.ui.screen.main.library.component.EmptyState
import com.ljyh.mei.ui.screen.main.library.component.libraryPodcastItems
import com.ljyh.mei.ui.screen.main.library.component.libraryCloudItems
import com.ljyh.mei.ui.screen.main.library.component.libraryHistoryItems
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.supportsNeteaseSongActions
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.source.api.SourceFeedCard
import com.ljyh.mei.source.api.SourceFeedTarget
import com.ljyh.mei.source.api.SourceFeatureCapability
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.screen.NavigationBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.animation.core.Animatable
import com.kyant.capsule.ContinuousRoundedRectangle
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.ljyh.mei.R
import com.ljyh.mei.constants.LibraryStyle
import com.ljyh.mei.constants.LibraryStyleKey
import com.ljyh.mei.constants.LibraryDefaultPageKey
import com.ljyh.mei.constants.DownloadsEnabledKey
import com.ljyh.mei.constants.PodcastsEnabledKey
import com.ljyh.mei.constants.CloudMusicEnabledKey
import com.ljyh.mei.source.api.FeatureContribution
import com.ljyh.mei.constants.ListeningHistoryEnabledKey
import com.ljyh.mei.constants.LandscapeSearchTopAdjustment
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.source.api.ContentType
import com.ljyh.mei.ui.navigation.LibraryPage
import com.ljyh.mei.ui.navigation.MeiloXLoadingPage
import com.ljyh.mei.ui.navigation.MeiloXPinnedPage
import com.ljyh.mei.ui.navigation.registerMeiloXNavigationViewport
import com.ljyh.mei.ui.screen.main.library.component.LibraryDownloadsPage
import com.ljyh.mei.ui.screen.main.library.component.LibraryGridItem
import com.ljyh.mei.ui.component.item.LandscapeMediaRow
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.toEnum
import com.ljyh.mei.utils.DateUtils.getGreeting
import com.ljyh.mei.utils.TimeUtils.formatDuration
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.ui.GlasmioSearchBar
import io.github.neoruaa.glasmio.ui.GlasmioSegmentedControl
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioHeaderScrollEdge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import com.ljyh.mei.constants.HomeRowHorizontalPadding
import com.ljyh.mei.constants.HomeSectionSpacing
import com.ljyh.mei.constants.HomeSectionTitlePadding
import com.ljyh.mei.constants.HomeSongNextPagePeek
import com.ljyh.mei.constants.PortraitRecommendCardHeight
import com.ljyh.mei.constants.PortraitRecommendCardWidth
import com.ljyh.mei.constants.RecommendCardHeight
import com.ljyh.mei.constants.RecommendCardWidth
import com.ljyh.mei.constants.homeCardSpacing
import com.ljyh.mei.constants.homePlaylistCardSize
import com.ljyh.mei.constants.songListCoverSize
import com.ljyh.mei.source.api.ContentRef
import com.ljyh.mei.source.api.FeedKind
import com.ljyh.mei.source.api.LibraryCapability
import com.ljyh.mei.source.api.LibraryCollection
import com.ljyh.mei.source.api.PlaylistEditAction
import com.ljyh.mei.source.api.PlaylistEditCapability
import com.ljyh.mei.source.api.ProviderIds
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceFeedLayout
import com.ljyh.mei.source.api.SourceFeedSection
import com.ljyh.mei.source.api.SourceItem
import com.ljyh.mei.source.api.Track
import com.ljyh.mei.source.api.type
import com.ljyh.mei.ui.component.globalProfileHeaderAction
import com.ljyh.mei.ui.component.home.CardExtInfo
import com.ljyh.mei.ui.component.home.PlaylistCard
import com.ljyh.mei.ui.component.home.RecommendCard
import com.ljyh.mei.ui.component.home.HomeRecommendationRow
import com.ljyh.mei.ui.component.home.rememberHomeRowFlingBehavior
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.testTag
import com.ljyh.mei.ui.component.playlist.rememberFavoriteTrackIds
import com.ljyh.mei.ui.component.playlist.songListDividerColor
import com.ljyh.mei.ui.component.playlist.FavoriteTrackStar
import com.ljyh.mei.source.api.TrackRefCodec
import com.ljyh.mei.ui.component.item.HomeSongListRow
import com.ljyh.mei.ui.component.item.ThreeRowSongList
import com.ljyh.mei.ui.component.utils.rememberDeviceInfo
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import com.ljyh.mei.ui.navigation.meiloxHeaderAction
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.search.browsableProviders
import com.ljyh.mei.ui.screen.search.libraryProviders
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.core.swipeBackExcluded
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioCard
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioHeaderCollapse
import com.ljyh.mei.ui.screen.main.home.HomeShimmer
import io.github.neoruaa.glasmio.scaffold.pullToRefreshRebound

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceFeedScreen(
    title: String,
    kind: FeedKind,
    initialCategory: String? = null,
    isNavigationTab: Boolean = true,
    providerId: String? = null,
    viewModel: SourceContentViewModel = hiltViewModel(key = "source-feed:${kind.name}:${providerId.orEmpty()}"),
) {
    val glassColors = LocalGlassColors.current
    val context = LocalContext.current
    val player = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsState()
    val preferredId by viewModel.selectedProviderId.collectAsState()
    val selectedId = providerId ?: preferredId
    val providers by viewModel.providers.collectAsState()
    var categoryId by rememberSaveable(kind, selectedId) { mutableStateOf(initialCategory) }
    var selection by remember(selectedId) { mutableStateOf<Pair<Track, Rect>?>(null) }
    val listState = rememberSaveable(kind, selectedId, categoryId, saver = LazyListState.Saver) { LazyListState() }
    val nav = LocalNavController.current
    val bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    val play = rememberSourceQueuePlayer("source-feed:$selectedId:${kind.name}", title)
    val choices = browsableProviders(providers)
    val categories = state.categories.takeIf { state.providerId == selectedId }.orEmpty()
    var loadingCard by remember(selectedId, categoryId) { mutableStateOf<String?>(null) }
    var cardJob by remember { mutableStateOf<Job?>(null) }
    var actionGeneration by remember { mutableStateOf(0L) }
    DisposableEffect(selectedId, state.providerVersion, state.accountRevision, categoryId) {
        onDispose { actionGeneration++; cardJob?.cancel(); cardJob = null; loadingCard = null }
    }
    fun openCard(card: SourceFeedCard) {
        if (state.providerId != selectedId || loadingCard == card.id) return
        val generation = ++actionGeneration
        cardJob?.cancel()
        loadingCard = null
        when (val target = card.target) {
            is SourceFeedTarget.Content -> navigateSourceContent(nav, target.ref)
            is SourceFeedTarget.Route -> nav.navigate(NavigationBuilder(target.route).apply {
                addQuery("providerId", Uri.encode(selectedId))
                target.parameters.forEach { (key, value) -> addQuery(key, Uri.encode(value)) }
            }.build())
            is SourceFeedTarget.Radio -> {
                if (!target.similar) player?.fmStart(target.seedIds.firstOrNull(), selectedId)
                else cardJob = scope.launch {
                    loadingCard = card.id
                    try { check(player?.similarFmStart(target.seedIds, selectedId) { isActive } == true) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { Toast.makeText(context, R.string.load_failed, Toast.LENGTH_SHORT).show() }
                    finally { if (generation == actionGeneration) loadingCard = null }
                }
            }
            is SourceFeedTarget.Queue -> cardJob = scope.launch {
                loadingCard = card.id
                try {
                    val (tracks, episode) = viewModel.feedQueue(card)
                    val items = tracks.map { track -> track.toMediaMetadata().copy(isPodcast = episode).toMediaItem().let { it.mediaId to it } }
                    player?.playQueue(ListQueue(id = "source-home:$selectedId:${card.id}", title = card.title, items = items, startIndex = 0))
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { Toast.makeText(context, R.string.load_failed, Toast.LENGTH_SHORT).show()
                } finally { if (generation == actionGeneration) loadingCard = null }
            }
        }
    }
    SourceBrowseScrollRequests(listState)
    LaunchedEffect(kind, categoryId, providerId) { viewModel.open(SourceContentRequest.Feed(kind, categoryId, providerId)) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val device = rememberDeviceInfo()
        val discoverColumns = if (device.isTablet) ((maxWidth - 20.dp) / 176.dp).toInt().coerceAtLeast(1) else 2
        val refreshing = state.providerId == selectedId && state.refreshing
        val refreshState = rememberPullToRefreshState()
        registerMeiloXNavigationViewport(listState)
        MeiloXPinnedPage(
            title = title,
            onNavigateBack = if (isNavigationTab) null else ({ nav.navigateUp() }),
            bottomPadding = bottom,
            collapseProgress = if (device.isLandscape) 1f else rememberGlasmioHeaderCollapse(listState),
            topBarBlurProgress = rememberGlasmioHeaderScrollEdge(listState),
            backgroundColor = if (glassColors.isDark) glassColors.groupedBackground else Color.White,
            actions = {
                listOfNotNull(
                    if (providerId == null) sourceOptionsHeaderAction("source-feed-provider", "music.note.list", "切换音源", selectedId,
                        choices.map { it.id to it.displayName }, viewModel::selectProvider) else null,
                    if (kind != FeedKind.DISCOVER && categories.isNotEmpty()) sourceOptionsHeaderAction(
                        "source-feed-category", "line.3.horizontal.decrease", "内容分类", categoryId ?: categories.first().id,
                        categories.map { it.id to it.title },
                        { categoryId = it.takeIf(String::isNotEmpty) },
                    ) else null,
                    if (isNavigationTab) globalProfileHeaderAction() else null,
                )
            },
        ) { padding ->
            PullToRefreshBox(isRefreshing = refreshing, state = refreshState, onRefresh = {
                if (state.providerId == selectedId && !state.loading && !state.refreshing) viewModel.refresh()
            }) {
                Box(Modifier.fillMaxSize().pullToRefreshRebound(refreshState, refreshing)) {
                    if (kind == FeedKind.HOME && (state.providerId != selectedId || state.loading && state.sections.isEmpty())) {
                        HomeShimmer(PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + HomeSectionSpacing))
                    } else LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = padding.calculateTopPadding() - if (kind == FeedKind.HOME) 4.dp else 0.dp,
                            bottom = padding.calculateBottomPadding() + if (kind == FeedKind.HOME) HomeSectionSpacing else 0.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(if (kind == FeedKind.DISCOVER) 16.dp else HomeSectionSpacing),
                    ) {
                        if (!device.isLandscape) item("source-feed-title") {
                            Text(title, style = IosTypography.largeTitle, color = glassColors.content,
                                modifier = if (kind == FeedKind.HOME) Modifier.padding(horizontal = 16.dp)
                                    else Modifier.offset(y = (-10).dp).padding(horizontal = 16.dp, vertical = 6.dp))
                        }
                        if (kind == FeedKind.DISCOVER && categories.isNotEmpty()) item("source-feed-categories") {
                            LazyRow(Modifier.fillMaxWidth().swipeBackExcluded(), contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(categories, key = { it.id }) { category ->
                                    val selected = category.id == (categoryId ?: categories.first().id)
                                    Text(category.title, style = IosTypography.subheadline,
                                        color = if (selected) Color.White else glassColors.content,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        modifier = Modifier.clip(ContinuousRoundedRectangle(50))
                                            .background(if (selected) MaterialTheme.colorScheme.primary else glassColors.groupedBackground)
                                            .clickable { categoryId = category.id.takeIf(String::isNotEmpty) }
                                            .padding(horizontal = 15.dp, vertical = 7.dp))
                                }
                            }
                        }
                        if (state.providerId == selectedId) {
                            if (kind == FeedKind.DISCOVER) {
                                sourceDiscoverItems(state.sections, discoverColumns, !device.isTablet, { navigateSourceContent(nav, it) }, play,
                                    { track, anchor -> selection = track to anchor }, ::openCard, loadingCard)
                            } else state.sections.forEachIndexed { index, section ->
                                item(key = "source-feed:${state.providerId}:${section.id}") {
                                    SourceFeedSectionContent(if (index == 0 && section.layout == SourceFeedLayout.HERO) section.copy(title = getGreeting()) else section,
                                        onOpen = { navigateSourceContent(nav, it) }, onPlay = play,
                                        onMore = { track, anchor -> selection = track to anchor }, onAction = ::openCard, loadingCard = loadingCard)
                                }
                            }
                            sourceBrowseStatus(state, state.sections.sumOf { if (it.cards.isNotEmpty()) it.cards.size else it.items.size },
                                emptyText = if (kind == FeedKind.HOME) "暂无推荐内容" else "暂无发现内容",
                                horizontalPadding = HomeRowHorizontalPadding,
                                onRetry = viewModel::refresh, onLoadMore = viewModel::loadMore,
                                onConfigure = { openSourceConfiguration(viewModel.pluginId(), nav) },
                            )
                        } else item("source-feed-switching") { SourceBrowseLoading() }
                    }
                }
            }
        }
    }
    SourceTrackActions(selection, { selection = null }, viewModel)
}

/** Uses the native home-feed card and song-row primitives for every provider. */
@Composable
internal fun SourceFeedSectionContent(
    section: SourceFeedSection,
    onOpen: (ContentRef) -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onMore: (Track, Rect) -> Unit,
    onAction: (SourceFeedCard) -> Unit = {},
    loadingCard: String? = null,
) {
    val landscape = rememberDeviceInfo().isLandscape
    val spacing = homeCardSpacing(landscape)
    val tracks = remember(section.items) { section.items.filterIsInstance<SourceItem.TrackItem>().map { it.track } }
    Column(Modifier.fillMaxWidth()) {
        if (section.title.isNotBlank()) Text(
            section.title,
            modifier = Modifier.padding(horizontal = HomeRowHorizontalPadding, vertical = HomeSectionTitlePadding),
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (section.layout == SourceFeedLayout.HERO && section.cards.isNotEmpty()) {
            HomeRecommendationRow(section.cards.map { it.id }) { index ->
                val card = section.cards[index]
                RecommendCard(cover = card.artworkUrl, title = card.title,
                    extInfo = CardExtInfo(icon = card.iconUrl.takeIf(String::isNotEmpty), text = card.subtitle),
                    cardWidth = if (landscape) RecommendCardWidth else PortraitRecommendCardWidth,
                    cardHeight = if (landscape) RecommendCardHeight else PortraitRecommendCardHeight,
                    isLoading = loadingCard == card.id, onClick = { onAction(card) })
            }
        } else if (section.layout == SourceFeedLayout.HERO) {
            HomeRecommendationRow(section.items.map { "${it.ref.providerId}:${it.ref.type.wireName}:${it.ref.contentId}" }) { index ->
                val value = section.items[index]
                val subtitle = when (value) {
                    is SourceItem.TrackItem -> value.track.artists.joinToString(" / ") { it.name }
                    is SourceItem.PlaylistItem -> value.playlist.creatorName
                    is SourceItem.AlbumItem -> value.album.artists.joinToString(" / ") { it.name }
                    is SourceItem.ArtistItem -> ""
                }
                RecommendCard(cover = value.artworkUrl, title = value.title, extInfo = CardExtInfo(text = subtitle),
                    showPlay = value is SourceItem.TrackItem,
                    cardWidth = if (landscape) RecommendCardWidth else PortraitRecommendCardWidth,
                    cardHeight = if (landscape) RecommendCardHeight else PortraitRecommendCardHeight,
                    onClick = { if (value is SourceItem.TrackItem) onPlay(tracks, tracks.indexOf(value.track)) else onOpen(value.ref) })
            }
        } else if (section.layout == SourceFeedLayout.TRACKS && tracks.isNotEmpty()) {
            val favoriteIds = rememberFavoriteTrackIds(remember(tracks) { tracks.map { it.toMediaMetadata() } })
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val requestedWidth = homePlaylistCardSize(landscape) * 2 + spacing
                val columnWidth = if (landscape) minOf(requestedWidth,
                    (maxWidth - HomeRowHorizontalPadding * 2 - HomeSongNextPagePeek).coerceAtLeast(220.dp)) else requestedWidth
                ThreeRowSongList(
                    itemCount = tracks.size,
                    columnWidth = columnWidth,
                    columnSpacing = spacing,
                    contentPadding = PaddingValues(horizontal = HomeRowHorizontalPadding),
                    coverSize = songListCoverSize(landscape),
                    modifier = Modifier.swipeBackExcluded(),
                ) { index ->
                    val track = tracks[index]
                    Box {
                        if (TrackRefCodec.mediaId(track.ref) in favoriteIds) FavoriteTrackStar(
                            Modifier.align(Alignment.CenterStart).offset(x = (-14).dp).testTag("HomeSongFavorite:${track.ref.trackId}"),
                            tint = LocalGlassColors.current.secondaryContent)
                        HomeSongListRow(
                            coverUrl = track.artworkUrl,
                            title = track.title,
                            subtitle = track.artists.joinToString(" / ") { it.name },
                            coverSize = songListCoverSize(landscape),
                            onClick = { onPlay(tracks, index) },
                            moreModifier = Modifier.testTag("HomeSongMore:${track.ref.trackId}"),
                            onMore = { onMore(track, it) },
                        )
                    }
                }
            }
        } else {
            val rowState = rememberLazyListState()
            LazyRow(
                state = rowState,
                flingBehavior = rememberHomeRowFlingBehavior(rowState),
                modifier = Modifier.fillMaxWidth().swipeBackExcluded(),
                contentPadding = PaddingValues(horizontal = HomeRowHorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                if (section.cards.isNotEmpty()) items(section.cards, key = { it.id }) { card ->
                    PlaylistCard(id = card.id, title = card.title, coverImg = card.artworkUrl,
                        subTitle = card.coverCaptions.takeIf { it.isNotEmpty() }, extInfo = card.detail,
                        imageSize = false, cardSize = homePlaylistCardSize(landscape),
                        titleMaxLines = card.titleMaxLines, secondaryTitle = card.subtitle.takeIf(String::isNotBlank),
                        onClick = { onAction(card) })
                } else items(section.items, key = { "${it.ref.providerId}:${it.ref.type.wireName}:${it.ref.contentId}" }) { value ->
                    val subtitle = when (value) {
                        is SourceItem.TrackItem -> value.track.artists.joinToString(" / ") { it.name }
                        is SourceItem.PlaylistItem -> value.playlist.creatorName
                        is SourceItem.AlbumItem -> value.album.artists.joinToString(" / ") { it.name }
                        is SourceItem.ArtistItem -> ""
                    }
                    val open = {
                        if (value is SourceItem.TrackItem) onPlay(tracks, tracks.indexOfFirst { it.ref == value.ref }.coerceAtLeast(0))
                        else onOpen(value.ref)
                    }
                    PlaylistCard(
                        id = "${value.ref.providerId}:${value.ref.contentId}",
                        title = value.title,
                        coverImg = value.artworkUrl,
                        cardSize = homePlaylistCardSize(landscape),
                        secondaryTitle = subtitle.takeIf(String::isNotBlank),
                        imageSize = false,
                        onClick = open,
                    )
                }
            }
        }
    }
}

private fun LazyListScope.sourceDiscoverItems(
    sections: List<SourceFeedSection>, columns: Int, featured: Boolean,
    onOpen: (ContentRef) -> Unit, onPlay: (List<Track>, Int) -> Unit, onMore: (Track, Rect) -> Unit,
    onAction: (SourceFeedCard) -> Unit, loadingCard: String?,
) {
    sections.forEachIndexed { sectionIndex, section ->
        if (section.cards.isNotEmpty()) {
            item("source-discover-cards:${section.id}") { SourceFeedSectionContent(section, onOpen, onPlay, onMore, onAction, loadingCard) }
        } else if (section.layout == SourceFeedLayout.TRACKS) {
            item("source-discover-tracks:${section.id}") { SourceFeedSectionContent(section, onOpen, onPlay, onMore) }
        } else {
            val hero = section.items.firstOrNull()?.takeIf { featured && sectionIndex == 0 && it is SourceItem.PlaylistItem }
            if (sections.size > 1 && section.title.isNotBlank()) item("source-discover-heading:${section.id}") {
                Text(section.title, style = IosTypography.title2, modifier = Modifier.padding(horizontal = 16.dp))
            }
            hero?.let { value -> item("source-discover-featured:${value.ref}") {
                Box(Modifier.padding(horizontal = 16.dp)) { SourceDiscoverCard(value, true) { onOpen(value.ref) } }
            } }
            val entries = if (hero == null) section.items else section.items.drop(1)
            items(entries.chunked(columns), key = { row -> "source-discover-grid:${section.id}:${row.first().ref}" }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { value -> Box(Modifier.weight(1f)) { SourceDiscoverCard(value, false) { onOpen(value.ref) } } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun SourceDiscoverCard(item: SourceItem, featured: Boolean, onClick: () -> Unit) {
    if (featured) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.12f).clip(ContinuousRoundedRectangle(22.dp)).clickable(onClick = onClick)) {
            AsyncImage(item.artworkUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .72f)), startY = 280f)))
            SfIcon("music.note", null, Modifier.align(Alignment.TopStart).padding(18.dp), tint = Color.White, size = 38.dp, weight = FontWeight.Bold)
            Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                val playlist = (item as? SourceItem.PlaylistItem)?.playlist
                Text(playlist?.subtitle.orEmpty().ifBlank { stringResource(R.string.app_tab_explore) },
                    style = IosTypography.caption, color = Color.White.copy(alpha = .82f))
                Text(item.title, style = IosTypography.title2, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                playlist?.playCount?.let { count ->
                    Text("▶  ${sourcePlaylistPlayCount(count)}", style = IosTypography.subheadline,
                        color = Color.White.copy(alpha = .82f), modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    } else Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(ContinuousRoundedRectangle(16.dp))) {
            AsyncImage(item.artworkUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            (item as? SourceItem.PlaylistItem)?.playlist?.playCount?.let { count ->
                Row(Modifier.align(Alignment.TopEnd).padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.42f), ContinuousRoundedRectangle(50))
                    .padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    SfIcon("play.fill", null, tint = Color.White, size = 11.dp)
                    Spacer(Modifier.width(3.dp))
                    Text(sourcePlaylistPlayCount(count), style = IosTypography.caption, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(item.title, style = IosTypography.subheadline, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}


private fun sourcePlaylistPlayCount(count: Long): String = when {
    count >= 100_000_000L -> String.format(java.util.Locale.getDefault(), "%.1f亿", count / 100_000_000.0)
    count >= 10_000L -> String.format(java.util.Locale.getDefault(), "%.1f万", count / 10_000.0)
    else -> count.toString()
}

internal fun sourceLibraryPages(collections: Set<LibraryCollection>, downloads: Boolean): List<LibraryPage> = buildList {
    if (LibraryCollection.LIKED_TRACKS in collections) add(LibraryPage.Songs)
    if (LibraryCollection.CREATED_PLAYLISTS in collections || LibraryCollection.SAVED_PLAYLISTS in collections) add(LibraryPage.Playlists)
    if (LibraryCollection.SAVED_ALBUMS in collections) add(LibraryPage.Albums)
    if (downloads) add(LibraryPage.Downloads)
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun sourceLibrarySearchQueue(id: String, title: String, tracks: List<Track>, index: Int): ListQueue? {
    if (index !in tracks.indices) return null
    return ListQueue(id, title, tracks.map { track ->
        val item = track.toMediaMetadata().toMediaItem()
        item.mediaId to item
    }, startIndex = index)
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SourceLibraryScreen(
    isNavigationTab: Boolean = true,
    initialCollection: LibraryCollection? = null,
    category: LibraryPage? = null,
    viewModel: SourceContentViewModel = hiltViewModel(key = "source-library:${category?.name ?: initialCollection?.name ?: "main"}"),
    createdViewModel: SourceContentViewModel = hiltViewModel(key = "source-library-created:${category?.name ?: "main"}"),
) {
    val context = LocalContext.current
    val preferences by context.dataStore.data.collectAsState(initial = null)
    if (preferences == null) { MeiloXLoadingPage(stringResource(category?.titleRes ?: R.string.app_tab_library)); return }
    val style = preferences?.get(LibraryStyleKey).toEnum(LibraryStyle.Default)
    val state by viewModel.state.collectAsState()
    val createdState by createdViewModel.state.collectAsState()
    val selectedId by viewModel.selectedProviderId.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val provider = providers.firstOrNull { it.id == selectedId }
    val available = (provider as? LibraryCapability)?.collections.orEmpty()
    val corePages = sourceLibraryPages(available, preferences?.get(DownloadsEnabledKey) != false)
    val featureRoutes = (provider as? FeatureContribution)?.featureRoutes.orEmpty()
    val hasPodcasts = Screen.Podcasts.route in featureRoutes && preferences?.get(PodcastsEnabledKey) != false
    val hasCloud = Screen.CloudMusic.route in featureRoutes && preferences?.get(CloudMusicEnabledKey) != false
    val hasHistory = preferences?.get(ListeningHistoryEnabledKey) != false
    val sourceFeatures = (provider as? SourceFeatureCapability)?.features.orEmpty()
    val hasRadio = "personalRadio" in sourceFeatures["discovery"].orEmpty()
    val hasListeningRank = "userPlayRecords" in sourceFeatures["stats"].orEmpty() &&
        "accountDetail" in sourceFeatures["account"].orEmpty()
    val player = LocalPlayerConnection.current
    val overviewPages = LibraryPage.entries.filter { it in corePages ||
        it == LibraryPage.Albums && style == LibraryStyle.Default && hasPodcasts ||
        it == LibraryPage.Podcasts && hasPodcasts || it == LibraryPage.Cloud && hasCloud || it == LibraryPage.History && hasHistory }
    val pages = overviewPages.filter { it != LibraryPage.Podcasts }
    val savedDefault = LibraryPage.entries.firstOrNull { it.name == preferences?.get(LibraryDefaultPageKey) }
    val defaultPage = if (style == LibraryStyle.Default && savedDefault == LibraryPage.Podcasts) LibraryPage.Albums else savedDefault
    val initialPage = category ?: when (initialCollection) {
        LibraryCollection.CREATED_PLAYLISTS, LibraryCollection.SAVED_PLAYLISTS -> LibraryPage.Playlists
        LibraryCollection.SAVED_ALBUMS -> LibraryPage.Albums
        else -> defaultPage?.takeIf { it in pages } ?: pages.firstOrNull() ?: LibraryPage.Songs
    }
    var selectedPage by rememberSaveable(selectedId, category, initialCollection) { mutableStateOf(initialPage) }
    val page = category ?: selectedPage.takeIf { it in pages } ?: pages.firstOrNull() ?: LibraryPage.Songs
    var showArtists by rememberSaveable(selectedId) { mutableStateOf(initialCollection == LibraryCollection.FOLLOWED_ARTISTS) }
    var playlistFilter by rememberSaveable(selectedId) { mutableStateOf("all") }
    val collection = when {
        showArtists -> LibraryCollection.FOLLOWED_ARTISTS
        page == LibraryPage.Playlists -> if (LibraryCollection.SAVED_PLAYLISTS in available) LibraryCollection.SAVED_PLAYLISTS else LibraryCollection.CREATED_PLAYLISTS
        page == LibraryPage.Albums -> LibraryCollection.SAVED_ALBUMS
        else -> LibraryCollection.LIKED_TRACKS
    }
    val needsCreated = page == LibraryPage.Playlists && collection != LibraryCollection.CREATED_PLAYLISTS && LibraryCollection.CREATED_PLAYLISTS in available
    val listState = rememberSaveable(selectedId, page, showArtists, saver = LazyListState.Saver) { LazyListState() }
    val nav = LocalNavController.current
    fun openPage(value: LibraryPage) {
        if (style == LibraryStyle.Default && category == null) selectedPage = value
        else Screen.LibraryCategory.navigate(nav) { addPath(value.name) }
    }
    val bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    val choices = libraryProviders(providers)
    val landscape = rememberDeviceInfo().isLandscape
    val colors = LocalGlassColors.current
    val tabPosition = remember { Animatable(pages.indexOf(page).coerceAtLeast(0).toFloat(), 0.001f) }
    val title = if (showArtists) "关注歌手" else stringResource(if (category == null) R.string.app_tab_library else page.titleRes)
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var selection by remember(selectedId, collection) { mutableStateOf<Pair<Track, Rect>?>(null) }
    val focus = LocalFocusManager.current
    val play = rememberSourceQueuePlayer("source-library:$selectedId:${collection.name}", libraryCollectionTitle(collection),
        viewModel = viewModel, queueRequest = SourceContentRequest.Library(collection, selectedId))
    fun playVisible(tracks: List<Track>, index: Int) {
        if (query.text.isNotBlank()) {
            sourceLibrarySearchQueue("source-library-search:$selectedId:${collection.name}", libraryCollectionTitle(collection), tracks, index)
                ?.let { player?.playQueue(it) }
        } else play(tracks, index)
    }
    SourceBrowseScrollRequests(listState)
    val categoryOverview = style == LibraryStyle.AppleMusic && category == null && initialCollection == null && !showArtists
    val hasCoreCollection = collection in available && page !in setOf(LibraryPage.Cloud, LibraryPage.History, LibraryPage.Downloads)
    LaunchedEffect(collection, page, selectedId, categoryOverview, hasCoreCollection) {
        if (!categoryOverview && hasCoreCollection) viewModel.open(SourceContentRequest.Library(collection))
        if (!categoryOverview && needsCreated) createdViewModel.open(SourceContentRequest.Library(LibraryCollection.CREATED_PLAYLISTS))
    }
    var appliedDefault by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(preferences, selectedId, provider) {
        if (preferences == null || selectedId.isBlank() || provider == null || appliedDefault || category != null || initialCollection != null) return@LaunchedEffect
        appliedDefault = true
        if (defaultPage != null && defaultPage in overviewPages) {
            if (style == LibraryStyle.AppleMusic) Screen.LibraryCategory.navigate(nav) { addPath(defaultPage!!.name) }
            else openPage(defaultPage!!)
        } else if (defaultPage == LibraryPage.History && preferences?.get(ListeningHistoryEnabledKey) != false) Screen.History.navigate(nav)
    }
    if (categoryOverview) {
        MeiloXPinnedListPage(title = stringResource(R.string.app_tab_library), bottomPadding = bottom,
            horizontalContentPadding = 6.dp, largeTitleHorizontalPadding = 14.dp,
            verticalArrangement = Arrangement.spacedBy(0.dp), backgroundColor = if (colors.isDark) colors.groupedBackground else Color.White,
            onNavigateBack = if (isNavigationTab) null else ({ nav.navigateUp() }), actions = { listOfNotNull(
                sourceOptionsHeaderAction("source-library-provider", "music.note.list", "切换音源", selectedId, choices.map { it.id to it.displayName }, viewModel::selectProvider),
                if (isNavigationTab) globalProfileHeaderAction() else null) }) {
            overviewPages.forEachIndexed { index, pageItem -> item("source-library-category:${pageItem.name}") {
                GlasmioListRow(title = stringResource(pageItem.titleRes), leading = { SfIcon(pageItem.symbol, null, tint = colors.accent) },
                    showTopSeparator = index > 0, onClick = { Screen.LibraryCategory.navigate(nav) { addPath(pageItem.name) } })
            } }
            if (LibraryCollection.FOLLOWED_ARTISTS in available) item("source-library-artists") {
                GlasmioListRow(title = "关注歌手", systemName = "person.2", onClick = { showArtists = true })
            }
        }
        return
    }
    if (page == LibraryPage.Downloads && !showArtists) {
        LibraryDownloadsPage(isNavigationTab, category != null, pages, ::openPage, listState, tabPosition)
        return
    }
    val revisionFlow = (provider as? AccountRevisionCapability)?.accountRevision
    val accountRevision = androidx.compose.runtime.key(provider) {
        revisionFlow?.collectAsState(initial = (revisionFlow as? kotlinx.coroutines.flow.StateFlow<String?>)?.value)?.value
    }
    val requiresSignIn = provider?.status == SourceStatus.NEEDS_CONFIGURATION ||
        listOfNotNull(state.takeIf { hasCoreCollection }, createdState.takeIf { needsCreated }).any { loaded ->
            loaded.providerId == selectedId && loaded.providerVersion == provider?.version &&
                (revisionFlow == null || loaded.accountRevision == accountRevision) && !loaded.loading &&
                (loaded.error == SourceErrorCode.AUTH_REQUIRED || loaded.error == SourceErrorCode.AUTH_EXPIRED ||
                    loaded.error == null && SourceCapability.ACCOUNT in provider?.capabilities.orEmpty() && loaded.account == null)
        }
    if (requiresSignIn) {
        MeiloXPinnedListPage(title = title, bottomPadding = bottom,
            onNavigateBack = if (showArtists) ({ showArtists = false }) else if (isNavigationTab) null else ({ nav.navigateUp() }),
            actions = { listOfNotNull(
                sourceOptionsHeaderAction("source-library-provider", "music.note.list", "切换音源", selectedId,
                    choices.map { it.id to it.displayName }, viewModel::selectProvider),
                if (isNavigationTab) globalProfileHeaderAction() else null) }) {
            if (category == null) item("signed-out-downloads") {
                GlasmioGroupedList {
                    GlasmioListRow(title = stringResource(R.string.app_tab_library_downloads),
                        leading = { SfIcon(LibraryPage.Downloads.symbol, null, tint = colors.accent) },
                        showTopSeparator = false,
                        onClick = { Screen.LibraryCategory.navigate(nav) { addPath(LibraryPage.Downloads.name) } })
                }
            }
            item("source-library-sign-in") {
                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    GlasmioCard(modifier = Modifier.padding(24.dp), onClick = {
                        if (provider is AccountCapability && provider.status == SourceStatus.READY && SourceCapability.ACCOUNT in provider.capabilities) {
                            Screen.SourceAccount.navigate(nav) { addPath(Uri.encode(selectedId)) }
                        } else openSourceConfiguration(viewModel.pluginId(selectedId), nav)
                    }) {
                        Column(Modifier.padding(horizontal = 28.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            SfIcon("person.crop.circle", null, size = 42.dp)
                            Text(stringResource(R.string.library_sign_in), modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                }
            }
        }
        return
    }
    val featureOwner = "$selectedId:${System.identityHashCode(provider)}:${provider?.version.orEmpty()}:${accountRevision.orEmpty()}"
    val online = remember(context) { dagger.hilt.android.EntryPointAccessors.fromApplication(context, SourceLibraryOnlineEntryPoint::class.java) }
    val mergePodcasts = style == LibraryStyle.Default && category == null && page == LibraryPage.Albums && hasPodcasts && !showArtists
    val podcastViewModel: PodcastViewModel? = if (mergePodcasts) sourceLibraryFeatureViewModel("library-podcasts:$featureOwner") {
        PodcastViewModel(online.repository().screen("library-podcasts", selectedId), initialTab = PodcastTab.Subscriptions)
    } else null
    val podcastState = podcastViewModel?.state?.collectAsState()?.value
    LaunchedEffect(podcastViewModel) {
        podcastViewModel?.selectTab(PodcastTab.Subscriptions)
        podcastViewModel?.ensureSubscriptionsLoaded()
    }
    val cloudViewModel: CloudMusicViewModel? = if (page == LibraryPage.Cloud && !showArtists) sourceLibraryFeatureViewModel("library-cloud:$featureOwner") {
        CloudMusicViewModel(online.repository().screen("library-cloud", selectedId))
    } else null
    val cloudState = cloudViewModel?.state?.collectAsState()?.value
    val historyViewModel: HistoryViewModel? = if (page == LibraryPage.History && !showArtists) sourceLibraryFeatureViewModel("library-history:$featureOwner") {
        HistoryViewModel(online.history(), online.repository().screen("library-history", selectedId))
    } else null
    val historyState = historyViewModel?.state?.collectAsState()?.value ?: HistoryUiState()
    LaunchedEffect(listState, podcastViewModel) {
        if (podcastViewModel == null) return@LaunchedEffect
        snapshotFlow { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to listState.layoutInfo.totalItemsCount }
            .distinctUntilChanged().collect { (last, count) ->
                if (count > 0 && last >= count - 1 && podcastViewModel.state.value.subscriptionsLoadMoreError == null)
                    podcastViewModel.loadMoreSubscriptions()
            }
    }
    val ownState = if (!hasCoreCollection) SourceContentState(providerId = selectedId)
        else state.takeIf { it.providerId == selectedId } ?: SourceContentState(providerId = selectedId, loading = true)
    val playingLikedTrack = if (selectedId == ProviderIds.NETEASE && hasCoreCollection &&
        page == LibraryPage.Songs && !showArtists) {
        player?.mediaMetadata?.collectAsState()?.value?.takeIf { it.supportsNeteaseSongActions }?.trackRef
    } else null
    val extra = createdState.takeIf { needsCreated && it.providerId == selectedId }
    val displayed = if (page == LibraryPage.Playlists && !showArtists) when (playlistFilter) {
        "created" -> if (collection == LibraryCollection.CREATED_PLAYLISTS) ownState.items else extra?.items.orEmpty()
        "saved" -> if (collection == LibraryCollection.SAVED_PLAYLISTS) ownState.items else emptyList()
        else -> (extra?.items.orEmpty() + ownState.items).distinctBy { it.ref }
    } else ownState.items
    val filtered = displayed.filter { item -> query.text.isBlank() || item.title.contains(query.text.trim(), true) || when (item) {
        is SourceItem.TrackItem -> item.track.artists.any { it.name.contains(query.text.trim(), true) } || item.track.album.title.contains(query.text.trim(), true)
        is SourceItem.PlaylistItem -> item.playlist.creatorName.contains(query.text.trim(), true)
        is SourceItem.AlbumItem -> item.album.artists.any { it.name.contains(query.text.trim(), true) }
        is SourceItem.ArtistItem -> item.artist.name.contains(query.text.trim(), true)
    } }
    val combined = ownState.copy(items = displayed, loading = ownState.loading || extra?.loading == true,
        hasMore = ownState.hasMore || extra?.hasMore == true, error = ownState.error ?: extra?.error)
    fun refresh() {
        when (page) {
            LibraryPage.Cloud -> cloudViewModel?.refresh()
            LibraryPage.History -> historyViewModel?.refresh()
            else -> {
                if (hasCoreCollection) viewModel.refresh()
                if (needsCreated) createdViewModel.refresh()
                if (mergePodcasts) { podcastViewModel?.selectTab(PodcastTab.Subscriptions); podcastViewModel?.refresh() }
            }
        }
    }
    fun more() { if (ownState.hasMore) viewModel.loadMore(); if (extra?.hasMore == true) createdViewModel.loadMore() }
    LaunchedEffect(query.text, ownState.nextCursor, extra?.nextCursor, combined.loading) {
        if (query.text.isNotBlank() && !combined.loading && combined.error == null && combined.hasMore) { delay(250); more() }
    }
    val collapseDistance = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
    val collapse by remember(listState, collapseDistance) { derivedStateOf { if (listState.firstVisibleItemIndex > 0) 1f else (listState.firstVisibleItemScrollOffset / collapseDistance).coerceIn(0f, 1f) } }
    val collectionLoading = combined.loading || ownState.refreshing || extra?.refreshing == true || podcastState?.isSubscriptionsLoading == true ||
        podcastState?.isLoadingMoreSubscriptions == true || cloudState?.isLoading == true ||
        cloudState?.isLoadingMore == true || historyState.isRefreshing
    var pullRefreshing by remember(page, featureOwner) { mutableStateOf(false) }
    LaunchedEffect(collectionLoading) { if (!collectionLoading) pullRefreshing = false }
    val emptyText = if (query.text.isNotBlank()) stringResource(R.string.no_search_results) else libraryCollectionEmpty(collection, playlistFilter)
    val rankAccount = ownState.account ?: extra?.account
    registerMeiloXNavigationViewport(listState)
    MeiloXPinnedPage(title = title, bottomPadding = bottom, collapseProgress = collapse,
        topBarBlurProgress = rememberGlasmioHeaderScrollEdge(listState),
        onNavigateBack = if (showArtists) ({ showArtists = false }) else if (isNavigationTab) null else ({ nav.navigateUp() }),
        actions = { listOfNotNull(
            sourceOptionsHeaderAction("source-library-provider", "music.note.list", "切换音源", selectedId, choices.map { it.id to it.displayName }, viewModel::selectProvider),
            if (LibraryCollection.FOLLOWED_ARTISTS in available && !showArtists) sourceOptionsHeaderAction("source-library-more", "line.3.horizontal.decrease", "媒体库分类", "", listOf("artists" to "关注歌手"), { showArtists = true }) else null,
            if (page == LibraryPage.Playlists && PlaylistEditAction.CREATE in (provider as? PlaylistEditCapability)?.playlistEditActions.orEmpty()) meiloxHeaderAction("source-library-create", "新建歌单", { navigateSourcePlaylistEditor(nav, selectedId) }) { SfIcon("plus", null) } else null,
            if (page == LibraryPage.Cloud && cloudViewModel != null) meiloxHeaderAction("source-library-upload", stringResource(R.string.cloud_upload),
                { cloudViewModel.upload() }, enabled = cloudState?.isUploading != true) { SfIcon("arrow.up.circle.fill", null) } else null,
            if (page == LibraryPage.History && historyState.canClearLocalHistory && historyViewModel != null)
                meiloxHeaderAction("source-library-clear-history", stringResource(R.string.clear_history), historyViewModel::clearHistory) { SfIcon("trash", null) } else null,
            if (isNavigationTab) globalProfileHeaderAction() else null) }) { contentPadding ->
        LibraryCollectionRefresh(enabled = hasCoreCollection || mergePodcasts || cloudViewModel != null || historyViewModel != null,
            isRefreshing = pullRefreshing && collectionLoading, topPadding = contentPadding.calculateTopPadding(),
            onRefresh = {
                if (!collectionLoading) {
                    pullRefreshing = true
                    refresh()
                }
            }) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = (maxWidth / 180.dp).toInt().coerceAtLeast(2)
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(
                start = if (landscape) 32.dp else 16.dp, end = if (landscape) 32.dp else 16.dp,
                top = contentPadding.calculateTopPadding() + if (landscape) LandscapeSearchTopAdjustment else 0.dp,
                bottom = contentPadding.calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                if (!landscape) item("source-library-large-title") { Text(title, style = IosTypography.largeTitle, color = colors.content,
                    modifier = Modifier.offset(y = (-10).dp).padding(vertical = 6.dp).padding(bottom = 12.dp)) }
                item("source-library-search") { GlasmioSearchBar(query = query, onQueryChange = { query = it }, active = searchActive,
                    onActiveChange = { searchActive = it }, onClose = { query = TextFieldValue(); searchActive = false },
                    placeholder = stringResource(R.string.search_bar_search), closeContentDescription = stringResource(R.string.cancel),
                    onSearch = { focus.clearFocus() }, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) }
                if (category == null && !showArtists) item("source-library-pages") { GlasmioSegmentedControl(
                    items = pages.map { it to stringResource(it.titleRes) }, selected = page, onSelected = ::openPage,
                    positionAnimation = tabPosition, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) }
                if (page == LibraryPage.Playlists && !showArtists) item("source-library-filter") { GlasmioSegmentedControl(
                    items = listOf("all" to stringResource(R.string.library_filter_all), "created" to stringResource(R.string.library_filter_created), "saved" to stringResource(R.string.library_filter_subscribed)),
                    selected = playlistFilter, onSelected = { playlistFilter = it }, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) }
                if (page == LibraryPage.Playlists && !showArtists && query.text.isBlank() && hasListeningRank && rankAccount != null) {
                    item("source-library-playlist-rank") {
                        GlasmioGroupedList(Modifier.padding(bottom = 12.dp)) {
                            GlasmioListRow(title = stringResource(R.string.account_listening_rank), systemName = "chart.bar.xaxis",
                                showTopSeparator = false, onClick = {
                                    Screen.AccountListeningRank.navigate(nav) {
                                        addPath(Uri.encode(rankAccount.ref.accountId.value))
                                        addQuery("providerId", Uri.encode(selectedId))
                                    }
                                })
                        }
                    }
                }
                if (page == LibraryPage.Songs && !showArtists && filtered.isNotEmpty()) item("source-library-play-all") { GlasmioGroupedList(Modifier.padding(bottom = 12.dp)) {
                    GlasmioListRow(title = stringResource(R.string.library_play_all), systemName = "play.fill", showTopSeparator = false,
                        onClick = { playVisible(filtered.filterIsInstance<SourceItem.TrackItem>().map { it.track }, 0) })
                    if (hasRadio) GlasmioListRow(title = stringResource(R.string.library_heart_mode), systemName = "heart.circle.fill",
                        onClick = { player?.fmStart(filtered.filterIsInstance<SourceItem.TrackItem>().randomOrNull()?.track?.ref?.trackId, selectedId) })
                } }
                if (page == LibraryPage.Cloud && !showArtists) {
                    cloudState?.let { libraryCloudItems(it, player, query.text.trim()) { cloudViewModel?.loadMore() } }
                } else if (page == LibraryPage.History && !showArtists) {
                    libraryHistoryItems(historyState, player, query.text.trim())
                } else {
                if (page in setOf(LibraryPage.Playlists, LibraryPage.Songs) && !showArtists && filtered.isNotEmpty()) item("source-library-top-divider") {
                    HorizontalDivider(Modifier.padding(horizontal = if (landscape) 0.dp else 4.dp),
                        thickness = 0.5.dp, color = songListDividerColor())
                }
                if (page == LibraryPage.Albums || showArtists) {
                    if (mergePodcasts && hasCoreCollection) item("source-library-albums-heading") {
                        Box(Modifier.padding(bottom = 12.dp)) { LibraryCollectionHeading(stringResource(R.string.library_page_albums)) }
                    }
                    items(filtered.chunked(columns), key = { row -> "source-library-grid:${row.first().ref}" }) { row ->
                        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            row.forEach { item -> LibraryGridItem(title = item.title, subtitle = when (item) {
                                is SourceItem.AlbumItem -> item.album.artists.joinToString(" / ") { it.name }
                                else -> ""
                            }, cover = item.artworkUrl, onClick = { navigateSourceContent(nav, item.ref) }, modifier = Modifier.weight(1f),
                                captionSpacing = 8.dp, titleStyle = IosTypography.body.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)) }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                } else items(filtered, key = { "source-library-row:${it.ref}" }) { item ->
                    when (item) {
                        is SourceItem.TrackItem -> LandscapeMediaRow(modifier = Modifier.padding(horizontal = if (landscape) 0.dp else 4.dp), landscape = landscape,
                            image = item.artworkUrl, title = item.title, artist = item.track.artists.joinToString(" / ") { it.name },
                            detail = if (landscape) formatDuration(item.track.durationMs) else "", album = item.track.album.title,
                            isCurrentTrack = item.ref == playingLikedTrack,
                            favorite = true, showDivider = item.ref != filtered.lastOrNull()?.ref,
                            onClick = { val tracks = filtered.filterIsInstance<SourceItem.TrackItem>().map { it.track }; playVisible(tracks, tracks.indexOf(item.track)) },
                            onMore = { selection = item.track to it })
                        is SourceItem.PlaylistItem -> LandscapeMediaRow(modifier = Modifier.padding(horizontal = if (landscape) 0.dp else 4.dp), image = item.artworkUrl,
                            title = item.title, artist = item.playlist.creatorName, detail = stringResource(R.string.playlist_song_count, item.playlist.trackCount),
                            onClick = { navigateSourceContent(nav, item.ref) }, navigationArrow = true, showDivider = item.ref != filtered.lastOrNull()?.ref)
                        else -> Unit
                    }
                }
                    if (hasCoreCollection) sourceBrowseStatus(combined, filtered.size, emptyText,
                        emptySymbol = if (showArtists) SfSymbol.PersonFilled else page.symbol,
                        onRetry = ::refresh, onLoadMore = ::more, onConfigure = { openSourceConfiguration(viewModel.pluginId(), nav) })
                    if (mergePodcasts && podcastViewModel != null && podcastState != null) {
                        item("source-library-podcasts-heading") { Box(Modifier.padding(bottom = 12.dp)) { LibraryCollectionHeading(stringResource(R.string.library_page_podcasts)) } }
                        libraryPodcastItems(nav, podcastState, podcastViewModel, query.text.trim(), columns, rowSpacing = 12.dp)
                    }
                }

            }
            if (playingLikedTrack != null && !searchActive && query.text.isBlank() && filtered.isNotEmpty()) {
                SourceTrackLocateButton(
                    viewModel, SourceContentRequest.Library(collection), ownState, playingLikedTrack, listState,
                    // Search, play actions, divider, and the optional title and page tabs.
                    leadingItems = 3 + (if (landscape) 0 else 1) + (if (category == null) 1 else 0),
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }
        }
    }
    SourceTrackActions(selection, { selection = null }, viewModel)
}


@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal interface SourceLibraryOnlineEntryPoint {
    fun repository(): com.ljyh.mei.source.shared.SharedOnlineRepository
    fun history(): com.ljyh.mei.di.repository.HistoryRepository
}

@Composable
private inline fun <reified T : ViewModel> sourceLibraryFeatureViewModel(key: String, noinline instantiate: () -> T): T {
    val factory = remember(key) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = instantiate() as V
        }
    }
    return androidx.lifecycle.viewmodel.compose.viewModel(key = key, factory = factory)
}

@Composable
fun SourceAccountScreen(
    providerId: String,
    viewModel: SourceContentViewModel = hiltViewModel(),
) {
    val profileViewModel: SourceProfileViewModel = hiltViewModel(LocalActivity.current as ComponentActivity)
    val state by viewModel.state.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val installed by viewModel.installedPlugins.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    val provider = providers.firstOrNull { it.id == providerId }
    val pluginId = installed.firstOrNull { it.manifest.providerId == providerId }?.manifest?.id
    val nav = LocalNavController.current
    val bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    val account = state.account.takeIf { state.providerId == providerId }
    LaunchedEffect(providerId) { viewModel.open(SourceContentRequest.Account(providerId)) }
    LaunchedEffect(providerId, state.providerId, state.loading, state.error, account) {
        if (state.providerId == providerId && !state.loading && state.error == null &&
            profileViewModel.state.value.providerId == providerId) profileViewModel.refresh(force = false)
    }

    MeiloXPinnedListPage(
        title = provider?.displayName ?: "音源账号",
        bottomPadding = bottom,
        onNavigateBack = { nav.navigateUp() },
        actions = { listOf(meiloxHeaderAction("source-account-refresh", "刷新", viewModel::refresh) { SfIcon("arrow.clockwise", null, size = 22.dp) }) },
    ) {
        item("source-account-profile") {
            GlasmioGroupedList {
                GlasmioListRow(
                    title = account?.displayName ?: when {
                        state.loading || state.providerId != providerId -> "正在加载"
                        state.error != null && state.error != SourceErrorCode.AUTH_REQUIRED && state.error != SourceErrorCode.AUTH_EXPIRED ->
                            provider?.displayName ?: "音源账号"
                        else -> "未登录"
                    },
                    showTopSeparator = false,
                    showDisclosure = false,
                    leading = {
                        Box(Modifier.size(56.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                            if (account?.avatarUrl.isNullOrBlank()) SfIcon("person.crop.circle.fill", null, size = 50.dp,
                                tint = LocalGlassColors.current.secondaryContent)
                            else AsyncImage(account?.avatarUrl.orEmpty(), null, modifier = Modifier.size(56.dp), contentScale = ContentScale.Crop)
                        }
                    },
                )
            }
        }
        if (state.providerId == providerId && state.loading) item("source-account-loading") { SourceBrowseLoading() }
        if (state.providerId == providerId) state.error?.let { error ->
            item("source-account-error") {
                GlasmioGroupedList {
                    GlasmioListRow(title = sourceBrowseErrorText(error), trailing = { Text("重试") },
                        showDisclosure = false, showTopSeparator = false, onClick = viewModel::refresh)
                }
            }
        }
        item("source-account-settings") {
            GlasmioGroupedList {
                GlasmioListRow(title = "配置音源", systemName = "slider.horizontal.3", showTopSeparator = false,
                    onClick = { openSourceConfiguration(pluginId, nav) })
                if ("webLogin" in (provider as? SourceFeatureCapability)?.features?.get("login").orEmpty()) {
                    GlasmioListRow(title = "网页登录", systemName = "globe", enabled = !busy,
                        onClick = { viewModel.webLogin(providerId) })
                }
                if (account != null && pluginId != null) GlasmioListRow(title = "退出登录", systemName = "rectangle.portrait.and.arrow.right",
                    enabled = !busy, showDisclosure = false, onClick = { viewModel.signOut(providerId) })
            }
        }
    }
    GlasmioAlertDialog(
        visible = message != null,
        onDismissRequest = viewModel::dismissMessage,
        title = "音源账号",
        message = message?.let(::sourceBrowseErrorText).orEmpty(),
        buttons = listOf(GlasmioAlertButtonSpec("好", onClick = viewModel::dismissMessage)),
    )
}

private fun LazyListScope.sourceBrowseStatus(
    state: SourceContentState,
    itemCount: Int,
    emptyText: String,
    horizontalPadding: Dp = 0.dp,
    emptySymbol: SfSymbol? = null,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onConfigure: () -> Unit,
) {
    if (state.loading) item("source-content-loading") { SourceBrowseLoading() }
    state.error?.let { error ->
        item("source-content-error") {
            GlasmioGroupedList(Modifier.padding(horizontal = horizontalPadding, vertical = 10.dp)) {
                GlasmioListRow(title = sourceBrowseErrorText(error), trailing = { Text("重试") },
                    showTopSeparator = false, showDisclosure = false, onClick = onRetry)
                if (error == SourceErrorCode.AUTH_REQUIRED || error == SourceErrorCode.AUTH_EXPIRED) {
                    GlasmioListRow(title = "配置音源", onClick = onConfigure)
                }
            }
        }
    }
    if (!state.loading && state.error == null && !state.hasMore && itemCount == 0) item("source-content-empty") {
        if (emptySymbol != null) EmptyState(emptyText, emptySymbol)
        else GlasmioGroupedList(Modifier.padding(horizontal = horizontalPadding, vertical = 10.dp)) {
            GlasmioListRow(title = emptyText, showTopSeparator = false, showDisclosure = false)
        }
    }
    if (!state.loading && state.hasMore && state.error == null) item("source-content-next:${state.nextOffset}") {
        LaunchedEffect(state.providerId, state.providerVersion, state.accountRevision, state.nextOffset, state.nextCursor) {
            onLoadMore()
        }
        SourceBrowseLoading()
    }
}

@Composable
private fun SourceBrowseLoading() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = LocalGlassColors.current.accent, strokeWidth = 2.dp)
    }
}

@Composable
private fun SourceBrowseScrollRequests(listState: LazyListState) {
    val nav = LocalNavController.current
    val entry = nav.currentBackStackEntry
    val scrollToTop by entry?.savedStateHandle?.getStateFlow("scrollToTop", false)?.collectAsState(initial = false)
        ?: remember { mutableStateOf(false) }
    LaunchedEffect(scrollToTop, listState) {
        if (scrollToTop) {
            listState.animateScrollToItem(0)
            entry?.savedStateHandle?.set("scrollToTop", false)
        }
    }
}

private fun openSourceConfiguration(pluginId: String?, nav: com.ljyh.mei.ui.navigation.MeiNavigator) {
    if (pluginId == null) Screen.SourceManagement.navigate(nav)
    else Screen.SourcePluginSettings.navigate(nav) { addPath(Uri.encode(pluginId)) }
}

private fun libraryCollectionTitle(collection: LibraryCollection): String = when (collection) {
    LibraryCollection.LIKED_TRACKS -> "收藏歌曲"
    LibraryCollection.CREATED_PLAYLISTS -> "自建歌单"
    LibraryCollection.SAVED_PLAYLISTS -> "收藏歌单"
    LibraryCollection.SAVED_ALBUMS -> "收藏专辑"
    LibraryCollection.FOLLOWED_ARTISTS -> "关注歌手"
}

@Composable
private fun libraryCollectionEmpty(collection: LibraryCollection, playlistFilter: String): String = when (collection) {
    LibraryCollection.LIKED_TRACKS -> stringResource(R.string.library_empty_songs)
    LibraryCollection.CREATED_PLAYLISTS, LibraryCollection.SAVED_PLAYLISTS -> stringResource(when (playlistFilter) {
        "created" -> R.string.library_empty_created_playlists
        "saved" -> R.string.library_empty_subscribed_playlists
        else -> R.string.library_empty_collected
    })
    LibraryCollection.SAVED_ALBUMS -> stringResource(R.string.library_empty_albums)
    LibraryCollection.FOLLOWED_ARTISTS -> "暂无关注歌手"
}

private fun sourceBrowseErrorText(code: SourceErrorCode): String = when (code) {
    SourceErrorCode.AUTH_REQUIRED -> "请先登录此音源"
    SourceErrorCode.AUTH_EXPIRED -> "登录已失效，请更新音源配置"
    SourceErrorCode.MISSING_PROVIDER -> "此音源已移除或停用"
    SourceErrorCode.UNSUPPORTED -> "此音源暂不支持这项功能"
    SourceErrorCode.NOT_FOUND -> "内容已不可用"
    SourceErrorCode.TIMEOUT -> "加载超时"
    SourceErrorCode.PLUGIN, SourceErrorCode.INVALID_RESPONSE -> "音源返回异常"
    SourceErrorCode.NETWORK -> "网络连接失败"
    else -> "内容暂不可用"
}
