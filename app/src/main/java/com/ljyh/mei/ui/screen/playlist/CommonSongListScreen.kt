package com.ljyh.mei.ui.screen.playlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.compose.LazyPagingItems
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberCanvasBackdrop
import com.ljyh.mei.constants.DetailPageHorizontalPadding
import com.ljyh.mei.constants.PlaylistCoverStyle
import com.ljyh.mei.constants.PlaylistCoverStyleKey
import com.ljyh.mei.constants.PlaylistTrackTableHeaderKey
import com.ljyh.mei.data.model.mediaId
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.ui.component.playlist.rememberCoverBackground
import com.ljyh.mei.ui.component.shimmer.ShimmerHost
import com.ljyh.mei.ui.component.utils.rememberDeviceInfo
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import com.ljyh.mei.ui.navigation.MeiloXPinnedPage
import com.ljyh.mei.ui.navigation.meiloxHeaderAction
import com.ljyh.mei.ui.screen.playlist.component.PlaylistHeader
import com.ljyh.mei.ui.screen.playlist.component.PlaylistShimmer
import com.ljyh.mei.ui.screen.playlist.component.TrackItemShimmer
import com.ljyh.mei.ui.screen.playlist.component.playlistTrackItems
import com.ljyh.mei.utils.rememberEnumPreference
import com.ljyh.mei.utils.rememberPreference
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.core.LocalGroupedListBackgroundAlpha
import io.github.neoruaa.glasmio.core.SheetGroupedListBackgroundAlpha
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.glass.rememberGlasmioMenuAnchorState
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.scaffold.GlasmioHeaderAction
import io.github.neoruaa.glasmio.scaffold.GlasmioHeaderSearchState
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioHeaderSearch
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioCascadingMenu
import io.github.neoruaa.glasmio.ui.GlasmioCascadingMenuItem

enum class SongListLandscapeLayout {
    Stacked,
    Split,
    AlbumHeader,
}

/** Original song-list presentation shared by every source; identity belongs to callbacks. */
data class SongListDetailData(
    val title: String,
    val count: Int,
    val subscriberCount: Long = 0,
    val cover: String = "",
    val coverList: List<String> = emptyList(),
    val creatorName: String = "",
    val tracks: List<MediaMetadata> = emptyList(),
    val playCount: Long? = null,
    val isSubscribed: Boolean = false,
)

@dagger.hilt.android.lifecycle.HiltViewModel
class DetailArtworkViewModel @javax.inject.Inject constructor(
    private val colors: com.ljyh.mei.di.repository.ColorRepository,
) : androidx.lifecycle.ViewModel() {
    fun getCachedColor(url: String) = colors.getFromMemory(url)
    suspend fun getOrExtractColor(url: String) = colors.getColorOrExtract(com.ljyh.mei.AppContext.instance, url)
    fun getCachedCoverIsDark(url: String) = colors.getCachedCoverIsDark(url)
    suspend fun getCoverIsDarkOrExtract(url: String) = colors.getCoverIsDarkOrExtract(com.ljyh.mei.AppContext.instance, url)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun SongListDetailScreen(
    uiData: SongListDetailData,
    pagingItems: LazyPagingItems<MediaMetadata>? = null,
    isLoading: Boolean,
    onPlayAll: () -> Unit,
    onHeaderAction: () -> Unit,
    onDownload: (() -> Unit)? = null,
    headerActionIcon: ImageVector,
    headerActionLabel: String,
    isSubscribed: Boolean = uiData.isSubscribed,
    headerActionPending: Boolean = false,
    showHeaderAction: Boolean = true,
    onTrackClick: (MediaMetadata, Int) -> Unit,
    onBack: () -> Unit,
    playlistSearchQuery: String = "",
    isPlaylistSearchActive: Boolean = false,
    onPlaylistSearchQueryChange: ((String) -> Unit)? = null,
    onPlaylistSearchActiveChange: (Boolean) -> Unit = {},
    onShufflePlay: () -> Unit = onPlayAll,
    detailMenu: List<io.github.neoruaa.glasmio.ui.GlasmioCascadingMenuItem>? = null,
    selectionMode: Boolean = false,
    selectedTrackIds: Set<String> = emptySet(),
    onSelectionDone: () -> Unit = {},
    detailMenuTitle: String =
        androidx.compose.ui.res.stringResource(com.ljyh.mei.R.string.album_menu_title),
    listState: androidx.compose.foundation.lazy.LazyListState =
        androidx.compose.foundation.lazy.rememberLazyListState(),
    headerMetadata: String? = null,
    footer: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null,
    useCoverBackground: Boolean = false,
    usePlaylistTrackStyle: Boolean = useCoverBackground,
    showTrackArtwork: Boolean = true,
    hasLoadedContent: Boolean = true,
    trackNumber: (MediaMetadata, Int) -> Int = { _, index -> index + 1 },
    landscapeLayout: SongListLandscapeLayout = SongListLandscapeLayout.Stacked,
    usePlaylistPortraitHeader: Boolean = false,
    favoriteTrackIds: Set<String> = emptySet(),
    onTrackMore: (MediaMetadata, Rect) -> Unit,
    overlayContent: @Composable BoxScope.() -> Unit = {},
    artwork: DetailArtworkViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current
    val currentTrackId = playerConnection?.mediaMetadata?.collectAsState()?.value?.mediaId
    val isPlaying = playerConnection?.isPlaying?.collectAsState()?.value == true
    var detailMenuOpen by remember { mutableStateOf(false) }
    val detailMenuAnchor = rememberGlasmioMenuAnchorState()
    val device = rememberDeviceInfo()
    val adaptiveLandscape = device.isLandscape && landscapeLayout != SongListLandscapeLayout.Stacked
    val bottomPadding =
        LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    val playlistTrackTableHeader by rememberPreference(PlaylistTrackTableHeaderKey, false)
    val actionSearch = onPlaylistSearchQueryChange?.let { onQueryChange ->
        rememberGlasmioHeaderSearch(
            active = isPlaylistSearchActive,
            initialQuery = TextFieldValue(playlistSearchQuery),
            onQueryChange = { onQueryChange(it.text) },
            onSearch = {},
            onDismiss = { onPlaylistSearchActiveChange(false) },
            placeholder = "搜索歌名、歌手或专辑",
            cancelLabel = "关闭搜索",
        )
    }
    val coverStyle by rememberEnumPreference(PlaylistCoverStyleKey, PlaylistCoverStyle.Combination)
    val backgroundCover =
        if (useCoverBackground) {
            when (coverStyle) {
                PlaylistCoverStyle.FirstSongImage -> uiData.coverList.firstOrNull() ?: uiData.cover
                else -> uiData.cover
            }
        } else ""
    val coverBackground =
        rememberCoverBackground(
            coverUrl = backgroundCover,
            getCachedColor = artwork::getCachedColor,
            getOrExtractColor = artwork::getOrExtractColor,
            ownerKey = artwork,
        )
    val headerBackdropBrush = rememberUpdatedState(coverBackground.brush)
    val headerBackdrop =
        if (useCoverBackground) {
            // Observe the animation during sampling so its changes invalidate the glass layer.
            rememberCanvasBackdrop { drawRect(brush = headerBackdropBrush.value) }
        } else null
    val listBackgroundAlpha =
        if (useCoverBackground) {
            SheetGroupedListBackgroundAlpha
        } else LocalGroupedListBackgroundAlpha.current
    val layoutState =
        rememberSongListLayoutState(listState, isContentReady = !isLoading && hasLoadedContent)

    CompositionLocalProvider(LocalGroupedListBackgroundAlpha provides listBackgroundAlpha) {
        Box(Modifier.fillMaxSize()) {
            val hero: @Composable () -> Unit = {
                val headerContent: @Composable () -> Unit = {
                    PlaylistHeader(
                        modifier = Modifier.padding(horizontal = if (!device.isLandscape && usePlaylistTrackStyle) 4.dp else 0.dp),
                        title = uiData.title,
                        metadata = headerMetadata,
                        cover = uiData.cover,
                        coverList = uiData.coverList,
                        creator = uiData.creatorName,
                        onPlayAll = onPlayAll,
                        onShufflePlay = onShufflePlay,
                        onDownload = onDownload,
                        actionIcon = headerActionIcon,
                        actionLabel = headerActionLabel,
                        count = uiData.count,
                        playCount = uiData.playCount ?: -1L,
                        subscribeCount = uiData.subscriberCount,
                        isSubscribed = isSubscribed,
                        isSubscriptionPending = headerActionPending,
                        showSubscriptionAction = showHeaderAction,
                        onSubscribed = { onHeaderAction() },
                        detailAction = useCoverBackground,
                        horizontal =
                            adaptiveLandscape &&
                                landscapeLayout == SongListLandscapeLayout.AlbumHeader,
                        isLoading = isLoading,
                        allowActionScroll = adaptiveLandscape,
                        topPadding = if (adaptiveLandscape && landscapeLayout == SongListLandscapeLayout.Split) 0.dp else 16.dp,
                        playlistPortrait = usePlaylistPortraitHeader && !device.isLandscape,
                    )
                }
                if (isLoading) {
                    ShimmerHost { headerContent() }
                } else if (headerBackdrop != null) {
                    CompositionLocalProvider(LocalGlassBackdrop provides headerBackdrop) {
                        headerContent()
                    }
                } else {
                    headerContent()
                }
            }
            SongListDetailPage(
                title = uiData.title,
                subtitle = uiData.creatorName.takeIf { it.isNotBlank() },
                listState = layoutState,
                backgroundColor = coverBackground.color,
                backgroundBrush = coverBackground.brush.takeIf { useCoverBackground },
                bottomPadding = if (selectionMode) 84.dp else bottomPadding,
                horizontalContentPadding =
                    if (adaptiveLandscape && landscapeLayout == SongListLandscapeLayout.AlbumHeader) 32.dp
                    else if (usePlaylistTrackStyle) { if (device.isLandscape) DetailPageHorizontalPadding else 20.dp } else 16.dp,
                splitHeader = adaptiveLandscape && landscapeLayout == SongListLandscapeLayout.Split,
                hero = if (!isLoading || adaptiveLandscape) hero else null,
                onNavigateBack = if (selectionMode) onSelectionDone else onBack,
                actionSearch = actionSearch,
                actions = {
                    val searchAction =
                        if (onPlaylistSearchQueryChange != null) {
                            listOf(
                                meiloxHeaderAction(
                                    "search",
                                    "搜索歌单",
                                    { onPlaylistSearchActiveChange(true) },
                                ) {
                                    SfIcon(SfSymbol.Search, null)
                                }
                            )
                        } else emptyList()
                    val detailAction =
                        when {
                            selectionMode ->
                                listOf(
                                    meiloxHeaderAction(
                                        "done",
                                        androidx.compose.ui.res.stringResource(
                                            com.ljyh.mei.R.string.done
                                        ),
                                        onSelectionDone,
                                    ) {
                                        Text(
                                            androidx.compose.ui.res.stringResource(
                                                com.ljyh.mei.R.string.done
                                            ),
                                            color = LocalGlassColors.current.accent,
                                        )
                                    }
                                )
                            detailMenu != null ->
                                listOf(
                                    meiloxHeaderAction(
                                        "detail-menu",
                                        detailMenuTitle,
                                        { detailMenuOpen = true },
                                        enabled = !isLoading,
                                        menuAnchor = detailMenuAnchor,
                                    ) {
                                        SfIcon(SfSymbol.Ellipsis, null)
                                    }
                                )
                            else -> emptyList()
                        }
                    searchAction + detailAction
                },
            ) {
                if (isLoading) {
                    if (adaptiveLandscape) {
                        items(10) {
                            ShimmerHost {
                                TrackItemShimmer(
                                    isTablet = false,
                                    flatDetailStyle = usePlaylistTrackStyle,
                                    showArtwork = showTrackArtwork,
                                    showDuration = adaptiveLandscape &&
                                        landscapeLayout == SongListLandscapeLayout.AlbumHeader,
                                )
                            }
                        }
                    } else
                        item(key = "playlist-loading") {
                            Box(Modifier.fillMaxWidth().height(620.dp)) {
                                PlaylistShimmer(flatDetailStyle = usePlaylistTrackStyle)
                            }
                        }
                } else {
                    playlistTrackItems(
                        pagingItems = pagingItems,
                        staticTracks = uiData.tracks,
                        isTablet = device.isTablet && device.isLandscape,
                        showTableHeader = playlistTrackTableHeader,
                        onTrackClick = onTrackClick,
                        selectionMode = selectionMode,
                        selectedTrackIds = selectedTrackIds,
                        flatDetailStyle = usePlaylistTrackStyle,
                        showArtwork = showTrackArtwork,
                        showDuration = adaptiveLandscape &&
                            landscapeLayout == SongListLandscapeLayout.AlbumHeader,
                        currentTrackId = currentTrackId,
                        isPlaying = isPlaying,
                        favoriteTrackIds = favoriteTrackIds,
                        showLeadingDivider = useCoverBackground,
                        trackNumber = trackNumber,
                        onMoreClick = onTrackMore,
                        emptyMessage =
                            playlistSearchQuery.takeIf { it.isNotBlank() }?.let { "未找到匹配的歌曲" },
                    )
                }
                footer?.invoke(this)
            }

            if (detailMenuOpen && detailMenu != null) {
                io.github.neoruaa.glasmio.ui.GlasmioCascadingMenu(
                    anchorBounds = detailMenuAnchor.bounds,
                    items = detailMenu,
                    menuAnchorState = detailMenuAnchor,
                    title = detailMenuTitle,
                    expandedDescription =
                        androidx.compose.ui.res.stringResource(com.ljyh.mei.R.string.menu_expanded),
                    collapsedDescription =
                        androidx.compose.ui.res.stringResource(
                            com.ljyh.mei.R.string.menu_collapsed
                        ),
                    onDismiss = { detailMenuOpen = false },
                )
            }

            overlayContent()
        }
    }
}

@Composable
internal fun rememberSongListLayoutState(
    contentState: LazyListState,
    isContentReady: Boolean,
): LazyListState {
    // Measuring a single placeholder with restored content state would clamp its saved position.
    val loadingState = remember { LazyListState() }
    return if (isContentReady) contentState else loadingState
}

/** Small reusable iOS action used by callers that still expose a text action. */
@Composable
fun ActionButton(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    color: androidx.compose.ui.graphics.Color = LocalGlassColors.current.secondaryContent,
) {
    GlasmioButton(onClick = onClick) {
        SfIcon(SfSymbol.Ellipsis, text, size = 18.dp, tint = color)
        Text(
            text,
            style = IosTypography.caption,
            color = color,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
internal fun SongListDetailPage(
    title: String,
    subtitle: String?,
    listState: LazyListState,
    backgroundColor: Color,
    backgroundBrush: Brush?,
    bottomPadding: Dp,
    horizontalContentPadding: Dp,
    splitHeader: Boolean,
    hero: (@Composable () -> Unit)?,
    onNavigateBack: () -> Unit,
    actionSearch: GlasmioHeaderSearchState? = null,
    actions: @Composable () -> List<GlasmioHeaderAction>,
    content: LazyListScope.() -> Unit,
) {
    if (!splitHeader) {
        MeiloXPinnedListPage(
            title = title,
            subtitle = subtitle,
            listState = listState,
            backgroundColor = backgroundColor,
            backgroundBrush = backgroundBrush,
            bottomPadding = bottomPadding,
            horizontalContentPadding = horizontalContentPadding,
            showsLargeTitle = false,
            verticalArrangement = Arrangement.spacedBy(0.dp),
            onNavigateBack = onNavigateBack,
            actionSearch = actionSearch,
            actions = actions,
        ) {
            if (hero != null) item(key = "playlist-hero") { hero() }
            content()
        }
        return
    }
    MeiloXPinnedPage(
        title = title,
        subtitle = subtitle,
        backgroundColor = backgroundColor,
        backgroundBrush = backgroundBrush,
        bottomPadding = bottomPadding,
        onNavigateBack = onNavigateBack,
        actionSearch = actionSearch,
        actions = actions,
        topBarBlurProgress =
            if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
                1f
            else 0f,
    ) { padding ->
        val panePadding = remember(padding, horizontalContentPadding) {
            object : PaddingValues by padding {
                override fun calculateLeftPadding(layoutDirection: LayoutDirection) = horizontalContentPadding
                override fun calculateRightPadding(layoutDirection: LayoutDirection) = horizontalContentPadding
            }
        }
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f)
                    .fillMaxHeight()
                    .testTag("DetailHeroPane")
                    .verticalScroll(rememberScrollState())
                    .padding(panePadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                hero?.invoke()
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxHeight().testTag("DetailTrackPane"),
                contentPadding = panePadding,
            ) {
                // Preserve the hero slot so list indices survive orientation changes.
                item(key = "playlist-hero") {}
                content()
            }
        }
    }
}
