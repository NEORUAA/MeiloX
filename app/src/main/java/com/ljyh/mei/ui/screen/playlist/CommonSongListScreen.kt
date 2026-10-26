package com.ljyh.mei.ui.screen.playlist

import io.github.neoruaa.glasmio.glass.ToolbarDetachProgress
import io.github.neoruaa.glasmio.ui.GlasmioCascadingMenu
import io.github.neoruaa.glasmio.ui.GlasmioCascadingMenuItem
import io.github.neoruaa.glasmio.ui.GlasmioIconButton

import com.ljyh.mei.constants.MusicQuality

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.compose.LazyPagingItems
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.shapes.Capsule
import com.ljyh.mei.constants.DetailPageHorizontalPadding
import com.ljyh.mei.constants.PlaylistTrackTableHeaderKey
import com.ljyh.mei.constants.PlaylistCoverStyle
import com.ljyh.mei.constants.PlaylistCoverStyleKey
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.room.Like
import com.ljyh.mei.ui.component.player.OverlayState
import com.ljyh.mei.ui.component.playlist.rememberCoverBackground
import com.ljyh.mei.ui.component.playlist.rememberFavoriteTrackIds
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.glass.GlassSurface
import io.github.neoruaa.glasmio.scaffold.GlasmioPinnedListPage
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.glass.glasmioMenuAnchor
import io.github.neoruaa.glasmio.glass.rememberGlasmioMenuAnchorState
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.core.LocalGlassDimensions
import io.github.neoruaa.glasmio.core.LocalGroupedListBackgroundAlpha
import io.github.neoruaa.glasmio.core.SheetGroupedListBackgroundAlpha
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import com.ljyh.mei.ui.component.utils.rememberDeviceInfo
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.model.UiPlaylist
import com.ljyh.mei.ui.screen.playlist.component.PlaylistActionOverlay
import com.ljyh.mei.ui.screen.playlist.component.PlaylistHeader
import com.ljyh.mei.ui.screen.playlist.component.PlaylistShimmer
import com.ljyh.mei.ui.screen.playlist.component.playlistTrackItems
import com.ljyh.mei.utils.rememberPreference
import com.ljyh.mei.utils.rememberEnumPreference
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow

@Composable
fun CommonSongListScreen(
    uiData: UiPlaylist,
    pagingItems: LazyPagingItems<MediaMetadata>? = null,
    isLoading: Boolean,
    onPlayAll: () -> Unit,
    onHeaderAction: () -> Unit,
    onDownload: (() -> Unit)? = null,
    headerActionIcon: ImageVector,
    headerActionLabel: String,
    isSubscribed: Boolean = uiData.isSubscribed,
    headerActionPending: Boolean = false,
    onTrackClick: (MediaMetadata, Int) -> Unit,
    onTrackDownload: ((MediaMetadata, MusicQuality) -> Unit)? = null,
    onBack: () -> Unit,
    playlistSearchQuery: String = "",
    isPlaylistSearchActive: Boolean = false,
    onPlaylistSearchQueryChange: ((String) -> Unit)? = null,
    onPlaylistSearchActiveChange: (Boolean) -> Unit = {},
    viewModel: PlaylistViewModel = hiltViewModel(),
    onShufflePlay: () -> Unit = onPlayAll,
    detailMenu: List<io.github.neoruaa.glasmio.ui.GlasmioCascadingMenuItem>? = null,
    selectionMode: Boolean = false,
    selectedTrackIds: Set<String> = emptySet(),
    onSelectionDone: () -> Unit = {},
    detailMenuTitle: String = androidx.compose.ui.res.stringResource(com.ljyh.mei.R.string.album_menu_title),
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    headerMetadata: String? = null,
    footer: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null,
    useCoverBackground: Boolean = false,
    showTrackArtwork: Boolean = true,
    hasLoadedContent: Boolean = true,
    trackNumbers: Map<Long, Int> = emptyMap(),
) {
    val playerConnection = LocalPlayerConnection.current
    val currentTrackId = playerConnection?.mediaMetadata?.collectAsState()?.value?.id?.toString()
    val isPlaying = playerConnection?.isPlaying?.collectAsState()?.value == true
    val favoriteTrackIds = rememberFavoriteTrackIds(
        tracks = pagingItems?.itemSnapshotList?.items ?: uiData.tracks,
        enabled = useCoverBackground,
    )
    var detailMenuOpen by remember { mutableStateOf(false) }
    val detailMenuAnchor = rememberGlasmioMenuAnchorState()
    val device = rememberDeviceInfo()
    val bottomPadding = LocalPlayerAwareWindowInsets.current
        .asPaddingValues()
        .calculateBottomPadding()
    val allMePlaylist by viewModel.playlist.collectAsState()
    var currentOverlay by remember { mutableStateOf<OverlayState>(OverlayState.None) }
    val playlistTrackTableHeader by rememberPreference(PlaylistTrackTableHeaderKey, false)
    val searchFocusRequester = remember { FocusRequester() }
    val searchTransition = remember { Animatable(if (isPlaylistSearchActive) 1f else 0f) }
    val searchProgress = searchTransition.value.coerceIn(0f, 1f)
    // Keep icon slots fixed while the liquid neck separates, then grow the input to the left.
    val searchSeparation = (searchProgress / 0.7f).coerceIn(0f, 1f)
    val searchExpansionStart = 0.7f * io.github.neoruaa.glasmio.glass.ToolbarDetachProgress
    val searchExpansion = ((searchProgress - searchExpansionStart) / (1f - searchExpansionStart)).coerceIn(0f, 1f)
        .let { it * it * (3f - 2f * it) }
    val focusManager = LocalFocusManager.current
    val coverStyle by rememberEnumPreference(PlaylistCoverStyleKey, PlaylistCoverStyle.Cover)
    val backgroundCover = if (useCoverBackground) {
        when (coverStyle) {
            PlaylistCoverStyle.FirstSongImage -> uiData.coverList.firstOrNull() ?: uiData.cover
            else -> uiData.cover
        }
    } else ""
    val coverBackground = rememberCoverBackground(
        coverUrl = backgroundCover,
        getCachedColor = viewModel::getCachedColor,
        getOrExtractColor = viewModel::getOrExtractColor,
        ownerKey = viewModel,
    )
    val headerBackdropBrush = rememberUpdatedState(coverBackground.brush)
    val headerBackdrop = if (useCoverBackground) {
        // Observe the animation during sampling so its changes invalidate the glass layer.
        rememberCanvasBackdrop { drawRect(brush = headerBackdropBrush.value) }
    } else null
    val listBackgroundAlpha = if (useCoverBackground) {
        SheetGroupedListBackgroundAlpha
    } else LocalGroupedListBackgroundAlpha.current
    val layoutState = rememberSongListLayoutState(listState, isContentReady = !isLoading && hasLoadedContent)

    LaunchedEffect(isPlaylistSearchActive, onPlaylistSearchQueryChange != null) {
        coroutineScope {
            launch {
                searchTransition.animateTo(
                    if (isPlaylistSearchActive) 1f else 0f,
                    spring(dampingRatio = 0.82f, stiffness = 160f),
                )
            }
            if (isPlaylistSearchActive && onPlaylistSearchQueryChange != null) {
                snapshotFlow { searchTransition.value }.first { it >= 0.85f }
                searchFocusRequester.requestFocus()
            }
        }
    }

    LaunchedEffect(uiData.title, uiData.tracks) {
        if (uiData.title.endsWith("喜欢的音乐")) {
            viewModel.updateAllLike(uiData.tracks.map { Like(it.id.toString()) })
        }
    }

    CompositionLocalProvider(
        LocalGroupedListBackgroundAlpha provides listBackgroundAlpha,
    ) {
        Box(Modifier.fillMaxSize()) {
            GlasmioPinnedListPage(
                title = if (isPlaylistSearchActive || searchProgress > 0.05f) "" else uiData.title,
                subtitle = uiData.creatorName.takeIf {
                    !isPlaylistSearchActive && searchProgress <= 0.05f && it.isNotBlank()
                },
                showsLargeTitle = false,
                listState = layoutState,
                backgroundColor = coverBackground.color,
                backgroundBrush = coverBackground.brush.takeIf { useCoverBackground },
                bottomPadding = if (selectionMode) 84.dp else bottomPadding,
                horizontalContentPadding = if (useCoverBackground) DetailPageHorizontalPadding else 16.dp,
                verticalArrangement = Arrangement.spacedBy(0.dp),
                onNavigateBack = if (selectionMode) onSelectionDone else onBack,
                actionsSeparationProgress = searchSeparation,
                actions = {
                    if (onPlaylistSearchQueryChange != null) {
                        BoxWithConstraints(Modifier.weight(1f, fill = false)) {
                            val colors = LocalGlassColors.current
                            val buttonSize = LocalGlassDimensions.current.iconButtonSize
                            val expandedWidth = (maxWidth - buttonSize - 8.dp)
                                .coerceAtLeast(buttonSize)
                            val animatedWidth = buttonSize + (expandedWidth - buttonSize) * searchExpansion
                            val searchContentAlpha = ((searchExpansion - 0.12f) / 0.65f).coerceIn(0f, 1f)
                            GlassSurface(
                                modifier = Modifier
                                    .width(animatedWidth.coerceIn(buttonSize, maxWidth.coerceAtLeast(buttonSize)))
                                    .height(buttonSize),
                                shape = Capsule(),
                                onClick = if (isPlaylistSearchActive) null else {
                                    { onPlaylistSearchActiveChange(true) }
                                },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(Capsule()),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier.weight(1f),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        if (isPlaylistSearchActive || searchContentAlpha > 0f) {
                                            BasicTextField(
                                                value = playlistSearchQuery,
                                                onValueChange = onPlaylistSearchQueryChange,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(start = 16.dp)
                                                    .focusRequester(searchFocusRequester)
                                                    .graphicsLayer { alpha = searchContentAlpha },
                                                singleLine = true,
                                                textStyle = IosTypography.body.copy(color = colors.content),
                                                cursorBrush = SolidColor(colors.accent),
                                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                                decorationBox = { innerTextField ->
                                                    if (playlistSearchQuery.isEmpty()) {
                                                        Text(
                                                            text = "搜索歌名、歌手或专辑",
                                                            style = IosTypography.body,
                                                            color = colors.tertiaryContent,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                    }
                                                    innerTextField()
                                                },
                                            )
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(buttonSize)
                                            .then(
                                                if (isPlaylistSearchActive) {
                                                    Modifier.clickable(
                                                        interactionSource = null,
                                                        indication = null,
                                                        role = Role.Button,
                                                    ) {
                                                        onPlaylistSearchQueryChange("")
                                                        focusManager.clearFocus()
                                                        onPlaylistSearchActiveChange(false)
                                                    }
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        androidx.compose.animation.Crossfade(
                                            targetState = searchExpansion > 0.15f,
                                            animationSpec = androidx.compose.animation.core.tween(120),
                                            label = "PlaylistSearchIcon",
                                        ) { active ->
                                            SfIcon(
                                                if (active) SfSymbol.Close else SfSymbol.Search,
                                                if (active) "关闭歌单搜索" else "搜索歌单",
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (selectionMode) {
                        GlasmioButton(onClick = onSelectionDone, modifier = Modifier.height(LocalGlassDimensions.current.iconButtonSize)) {
                            Text(androidx.compose.ui.res.stringResource(com.ljyh.mei.R.string.done), color = LocalGlassColors.current.accent)
                        }
                    } else if (detailMenu != null) {
                        io.github.neoruaa.glasmio.ui.GlasmioIconButton(
                            onClick = { detailMenuOpen = true },
                            modifier = Modifier.glasmioMenuAnchor(detailMenuAnchor),
                            enabled = !isLoading,
                        ) { SfIcon(SfSymbol.Ellipsis, detailMenuTitle) }
                    }
                },
            ) {
                if (isLoading) {
                    item(key = "playlist-loading") {
                        Box(Modifier.fillMaxWidth().height(620.dp)) {
                            PlaylistShimmer(flatDetailStyle = useCoverBackground)
                        }
                    }
                } else {
                    item(key = "playlist-hero") {
                        val headerContent: @Composable () -> Unit = {
                            PlaylistHeader(
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
                                onSubscribed = { onHeaderAction() },
                                detailAction = useCoverBackground,
                            )
                        }
                        if (headerBackdrop != null) {
                            CompositionLocalProvider(LocalGlassBackdrop provides headerBackdrop) {
                                headerContent()
                            }
                        } else {
                            headerContent()
                        }
                    }
                    playlistTrackItems(
                        pagingItems = pagingItems,
                        staticTracks = uiData.tracks,
                        isTablet = device.isTablet && device.isLandscape,
                        showTableHeader = playlistTrackTableHeader,
                        onTrackClick = onTrackClick,
                        selectionMode = selectionMode,
                        selectedTrackIds = selectedTrackIds,
                        flatDetailStyle = useCoverBackground,
                        showArtwork = showTrackArtwork,
                        currentTrackId = currentTrackId,
                        isPlaying = isPlaying,
                        favoriteTrackIds = favoriteTrackIds,
                        trackNumbers = trackNumbers,
                        onMoreClick = { track, anchor -> currentOverlay = OverlayState.TrackActionMenu(track, anchor) },
                        emptyMessage = playlistSearchQuery.takeIf { it.isNotBlank() }
                            ?.let { "未找到匹配的歌曲" },
                    )
                }
                footer?.invoke(this)
            }

            if (detailMenuOpen && detailMenu != null) {
                io.github.neoruaa.glasmio.ui.GlasmioCascadingMenu(
                    anchorBounds = detailMenuAnchor.bounds, items = detailMenu,
                    menuAnchorState = detailMenuAnchor,
                    title = detailMenuTitle,
                    expandedDescription = androidx.compose.ui.res.stringResource(com.ljyh.mei.R.string.menu_expanded),
                    collapsedDescription = androidx.compose.ui.res.stringResource(com.ljyh.mei.R.string.menu_collapsed),
                    onDismiss = { detailMenuOpen = false },
                )
            }
            PlaylistActionOverlay(
                overlay = currentOverlay,
                isCreator = uiData.isCreator,
                playlistId = uiData.id,
                allMePlaylist = allMePlaylist,
                onDismiss = { currentOverlay = OverlayState.None },
                onUpdateOverlay = { currentOverlay = it },
                onDownloadTrack = onTrackDownload,
                viewModel = viewModel,
            )
        }
    }
}

@Composable
internal fun rememberSongListLayoutState(contentState: LazyListState, isContentReady: Boolean): LazyListState {
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
        Text(text, style = IosTypography.caption, color = color, modifier = Modifier.padding(start = 6.dp))
    }
}
