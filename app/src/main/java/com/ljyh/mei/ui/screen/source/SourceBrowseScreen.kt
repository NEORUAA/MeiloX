package com.ljyh.mei.ui.screen.source

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
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
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow

@Composable
fun SourceFeedScreen(
    title: String,
    kind: FeedKind,
    viewModel: SourceContentViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val selectedId by viewModel.selectedProviderId.collectAsState()
    val providers by viewModel.providers.collectAsState()
    var categoryId by rememberSaveable(kind, selectedId) { mutableStateOf<String?>(null) }
    var selection by remember(selectedId) { mutableStateOf<Pair<Track, Rect>?>(null) }
    val listState = remember(kind, selectedId, categoryId) { LazyListState() }
    val nav = LocalNavController.current
    val bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    val play = rememberSourceQueuePlayer("source-feed:$selectedId:${kind.name}", title)
    val choices = browsableProviders(providers)
    val categories = state.categories.takeIf { state.providerId == selectedId }.orEmpty()
    SourceBrowseScrollRequests(listState)
    LaunchedEffect(kind, categoryId) { viewModel.open(SourceContentRequest.Feed(kind, categoryId)) }

    MeiloXPinnedListPage(
        title = title,
        bottomPadding = bottom + HomeSectionSpacing,
        horizontalContentPadding = 0.dp,
        largeTitleHorizontalPadding = 20.dp,
        listState = listState,
        verticalArrangement = Arrangement.spacedBy(HomeSectionSpacing),
        actions = {
            listOfNotNull(
                sourceOptionsHeaderAction("source-feed-provider", "music.note.list", "切换音源", selectedId,
                    choices.map { it.id to it.displayName }, viewModel::selectProvider),
                if (categories.isNotEmpty()) sourceOptionsHeaderAction(
                    "source-feed-category", "line.3.horizontal.decrease", "内容分类", categoryId.orEmpty(),
                    listOf("" to "全部") + categories.map { it.id to it.title },
                    { categoryId = it.takeIf(String::isNotEmpty) },
                ) else null,
                meiloxHeaderAction("source-feed-refresh", "刷新", viewModel::refresh) { SfIcon("arrow.clockwise", null, size = 22.dp) },
                globalProfileHeaderAction(),
            )
        },
    ) {
        if (state.providerId == selectedId) {
            state.sections.forEach { section ->
                item(key = "source-feed:${state.providerId}:${section.id}") {
                    SourceFeedSectionContent(section,
                        onOpen = { navigateSourceContent(nav, it) },
                        onPlay = play,
                        onMore = { track, anchor -> selection = track to anchor },
                    )
                }
            }
            sourceBrowseStatus(state, state.sections.sumOf { it.items.size },
                emptyText = if (kind == FeedKind.HOME) "暂无推荐内容" else "暂无发现内容",
                horizontalPadding = HomeRowHorizontalPadding,
                onRetry = viewModel::refresh, onLoadMore = viewModel::loadMore,
                onConfigure = { openSourceConfiguration(viewModel.pluginId(), nav) },
            )
        } else item("source-feed-switching") { SourceBrowseLoading() }
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
        if (section.layout == SourceFeedLayout.TRACKS && tracks.isNotEmpty()) {
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
                    HomeSongListRow(
                        coverUrl = track.artworkUrl,
                        title = track.title,
                        subtitle = track.artists.joinToString(" / ") { it.name },
                        coverSize = songListCoverSize(landscape),
                        onClick = { onPlay(tracks, index) },
                        onMore = { onMore(track, it) },
                    )
                }
            }
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth().swipeBackExcluded(),
                contentPadding = PaddingValues(horizontal = HomeRowHorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                items(section.items, key = { "${it.ref.providerId}:${it.ref.type.wireName}:${it.ref.contentId}" }) { value ->
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
                    if (section.layout == SourceFeedLayout.HERO) {
                        RecommendCard(
                            cover = value.artworkUrl,
                            title = value.title,
                            extInfo = CardExtInfo(text = subtitle),
                            showPlay = value is SourceItem.TrackItem,
                            cardWidth = if (landscape) RecommendCardWidth else PortraitRecommendCardWidth,
                            cardHeight = if (landscape) RecommendCardHeight else PortraitRecommendCardHeight,
                            onClick = open,
                        )
                    } else {
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
}

@Composable
fun SourceLibraryScreen(
    isNavigationTab: Boolean = true,
    initialCollection: LibraryCollection? = null,
    viewModel: SourceContentViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val selectedId by viewModel.selectedProviderId.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val provider = providers.firstOrNull { it.id == selectedId }
    val available = LibraryCollection.entries.filter { it in (provider as? LibraryCapability)?.collections.orEmpty() }
    var collectionName by rememberSaveable(initialCollection) { mutableStateOf((initialCollection ?: LibraryCollection.LIKED_TRACKS).name) }
    val collection = available.firstOrNull { it.name == collectionName }
        ?: initialCollection?.takeIf { it.name == collectionName }
        ?: available.firstOrNull() ?: LibraryCollection.LIKED_TRACKS
    var selection by remember(selectedId, collection) { mutableStateOf<Pair<Track, Rect>?>(null) }
    val listState = remember(selectedId, collection) { LazyListState() }
    val nav = LocalNavController.current
    val title = stringResource(R.string.app_tab_library)
    val bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    val choices = libraryProviders(providers)
    val canCreate = PlaylistEditAction.CREATE in (provider as? PlaylistEditCapability)?.playlistEditActions.orEmpty()
    val play = rememberSourceQueuePlayer(
        "source-library:$selectedId:${collection.name}", libraryCollectionTitle(collection),
        viewModel = viewModel, queueRequest = SourceContentRequest.Library(collection, selectedId),
    )
    SourceBrowseScrollRequests(listState)
    LaunchedEffect(collection) { viewModel.open(SourceContentRequest.Library(collection)) }

    MeiloXPinnedListPage(
        title = title,
        listState = listState,
        bottomPadding = bottom,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        onNavigateBack = if (isNavigationTab) null else ({ nav.navigateUp() }),
        actions = {
            listOfNotNull(
                sourceOptionsHeaderAction("source-library-provider", "music.note.list", "切换音源", selectedId,
                    choices.map { it.id to it.displayName }, viewModel::selectProvider),
                if (available.isNotEmpty()) sourceOptionsHeaderAction(
                    "source-library-category", "line.3.horizontal.decrease", "媒体库分类", collection.name,
                    available.map { it.name to libraryCollectionTitle(it) }, { collectionName = it },
                ) else null,
                meiloxHeaderAction("source-library-refresh", "刷新", viewModel::refresh) { SfIcon("arrow.clockwise", null, size = 22.dp) },
                if (isNavigationTab) globalProfileHeaderAction() else null,
            )
        },
    ) {
        item("source-library-local") {
            GlasmioGroupedList(Modifier.padding(top = 10.dp)) {
                GlasmioListRow(title = stringResource(R.string.app_tab_library_downloads), systemName = "arrow.down.circle",
                    showTopSeparator = false, onClick = { Screen.DownloadManage.navigate(nav) })
                GlasmioListRow(title = "本地音乐", systemName = "folder", onClick = { Screen.LocalMusic.navigate(nav) })
            }
        }
        item("source-library-heading") {
            Text(libraryCollectionTitle(collection),
                modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 10.dp),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = LocalGlassColors.current.content)
        }
        if (collection == LibraryCollection.CREATED_PLAYLISTS && canCreate) item("source-library-create") {
            GlasmioGroupedList(Modifier.padding(bottom = 10.dp)) {
                GlasmioListRow(title = "新建歌单", systemName = "plus", showTopSeparator = false,
                    onClick = { navigateSourcePlaylistEditor(nav, selectedId) })
            }
        }
        if (state.providerId == selectedId) {
            sourceContentItems(state.items, providers,
                onOpen = { navigateSourceContent(nav, it) }, onPlay = play,
                onMore = { track, anchor -> selection = track to anchor },
                keyPrefix = "source-library:$selectedId:${collection.name}", firstItemTopPadding = 0.dp)
            sourceBrowseStatus(state, state.items.size, libraryCollectionEmpty(collection),
                onRetry = viewModel::refresh, onLoadMore = viewModel::loadMore,
                onConfigure = { openSourceConfiguration(viewModel.pluginId(), nav) })
        } else item("source-library-switching") { SourceBrowseLoading() }
    }
    SourceTrackActions(selection, { selection = null }, viewModel)
}

@Composable
fun SourceAccountScreen(
    providerId: String,
    viewModel: SourceContentViewModel = hiltViewModel(),
) {
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

    MeiloXPinnedListPage(
        title = provider?.displayName ?: "音源账号",
        bottomPadding = bottom,
        onNavigateBack = { nav.navigateUp() },
        actions = { listOf(meiloxHeaderAction("source-account-refresh", "刷新", viewModel::refresh) { SfIcon("arrow.clockwise", null, size = 22.dp) }) },
    ) {
        item("source-account-profile") {
            GlasmioGroupedList {
                GlasmioListRow(
                    title = account?.displayName ?: if (state.loading || state.providerId != providerId) "正在加载" else "未登录",
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
        GlasmioGroupedList(Modifier.padding(horizontal = horizontalPadding, vertical = 10.dp)) {
            GlasmioListRow(title = emptyText, showTopSeparator = false, showDisclosure = false)
        }
    }
    if (!state.loading && state.hasMore) item("source-content-next") {
        GlasmioGroupedList(Modifier.padding(horizontal = horizontalPadding, vertical = 10.dp)) {
            GlasmioListRow(title = "加载更多", showTopSeparator = false, showDisclosure = false, onClick = onLoadMore)
        }
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

private fun libraryCollectionEmpty(collection: LibraryCollection): String = when (collection) {
    LibraryCollection.LIKED_TRACKS -> "暂无收藏歌曲"
    LibraryCollection.CREATED_PLAYLISTS -> "暂无自建歌单"
    LibraryCollection.SAVED_PLAYLISTS -> "暂无收藏歌单"
    LibraryCollection.SAVED_ALBUMS -> "暂无收藏专辑"
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
