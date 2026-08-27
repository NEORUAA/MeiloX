package com.ljyh.mei.ui.screen.playlist

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.kyant.shapes.Capsule
import com.ljyh.mei.constants.PlaylistTrackTableHeaderKey
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.room.Like
import com.ljyh.mei.ui.component.player.OverlayState
import com.ljyh.mei.ui.glass.GlassButton
import com.ljyh.mei.ui.glass.GlassSurface
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.IosTypography
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.LocalGlassDimensions
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.component.utils.rememberDeviceInfo
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.model.UiPlaylist
import com.ljyh.mei.ui.screen.playlist.component.PlaylistActionOverlay
import com.ljyh.mei.ui.screen.playlist.component.PlaylistHeader
import com.ljyh.mei.ui.screen.playlist.component.PlaylistShimmer
import com.ljyh.mei.ui.screen.playlist.component.playlistTrackItems
import com.ljyh.mei.utils.rememberPreference

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
    onTrackClick: (MediaMetadata, Int) -> Unit,
    onTrackDownload: ((MediaMetadata) -> Unit)? = null,
    onBack: () -> Unit,
    playlistSearchQuery: String = "",
    isPlaylistSearchActive: Boolean = false,
    onPlaylistSearchQueryChange: ((String) -> Unit)? = null,
    onPlaylistSearchActiveChange: (Boolean) -> Unit = {},
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val device = rememberDeviceInfo()
    val bottomPadding = LocalPlayerAwareWindowInsets.current
        .asPaddingValues()
        .calculateBottomPadding()
    val allMePlaylist by viewModel.playlist.collectAsState()
    var currentOverlay by remember { mutableStateOf<OverlayState>(OverlayState.None) }
    val playlistTrackTableHeader by rememberPreference(PlaylistTrackTableHeaderKey, false)
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(isPlaylistSearchActive) {
        if (isPlaylistSearchActive && onPlaylistSearchQueryChange != null) {
            searchFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(uiData.title, uiData.tracks) {
        if (uiData.title.endsWith("喜欢的音乐")) {
            viewModel.updateAllLike(uiData.tracks.map { Like(it.id.toString()) })
        }
    }

    Box(Modifier.fillMaxSize()) {
        IosPinnedListPage(
            title = if (isPlaylistSearchActive) "" else uiData.title,
            subtitle = uiData.creatorName.takeIf {
                !isPlaylistSearchActive && it.isNotBlank()
            },
            showsLargeTitle = false,
            bottomPadding = bottomPadding,
            verticalArrangement = Arrangement.spacedBy(0.dp),
            onNavigateBack = onBack,
            actions = {
                if (onPlaylistSearchQueryChange != null) {
                    BoxWithConstraints {
                        val colors = LocalGlassColors.current
                        val buttonSize = LocalGlassDimensions.current.iconButtonSize
                        val expandedWidth = (maxWidth - buttonSize - 8.dp)
                            .coerceAtLeast(buttonSize)
                        val animatedWidth by animateDpAsState(
                            targetValue = if (isPlaylistSearchActive) expandedWidth else buttonSize,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                            label = "PlaylistSearchWidth",
                        )

                        GlassSurface(
                            modifier = Modifier
                                .width(animatedWidth.coerceIn(buttonSize, expandedWidth))
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
                                    if (isPlaylistSearchActive) {
                                        BasicTextField(
                                            value = playlistSearchQuery,
                                            onValueChange = onPlaylistSearchQueryChange,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 16.dp)
                                                .focusRequester(searchFocusRequester),
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
                                    SfIcon(
                                        if (isPlaylistSearchActive) SfSymbol.Close else SfSymbol.Search,
                                        if (isPlaylistSearchActive) "关闭歌单搜索" else "搜索歌单",
                                        size = 20.dp,
                                    )
                                }
                            }
                        }
                    }
                }
            },
        ) {
            if (isLoading) {
                item(key = "playlist-loading") {
                    Box(Modifier.fillMaxWidth().height(620.dp)) {
                        PlaylistShimmer()
                    }
                }
            } else {
                item(key = "playlist-hero") {
                    PlaylistHeader(
                        title = uiData.title,
                        cover = uiData.cover,
                        coverList = uiData.coverList,
                        creator = uiData.creatorName,
                        onPlayAll = onPlayAll,
                        onDownload = onDownload,
                        actionIcon = headerActionIcon,
                        actionLabel = headerActionLabel,
                        count = uiData.count,
                        playCount = uiData.playCount ?: -1L,
                        subscribeCount = uiData.subscriberCount,
                        isSubscribed = isSubscribed,
                        onSubscribed = { onHeaderAction() },
                    )
                }
                playlistTrackItems(
                    pagingItems = pagingItems,
                    staticTracks = uiData.tracks,
                    isTablet = device.isTablet && device.isLandscape,
                    showTableHeader = playlistTrackTableHeader,
                    onTrackClick = onTrackClick,
                    onMoreClick = { currentOverlay = OverlayState.TrackActionMenu(it) },
                    emptyMessage = playlistSearchQuery.takeIf { it.isNotBlank() }
                        ?.let { "未找到匹配的歌曲" },
                )
            }
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

/** Small reusable iOS action used by callers that still expose a text action. */
@Composable
fun ActionButton(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    color: androidx.compose.ui.graphics.Color = LocalGlassColors.current.secondaryContent,
) {
    GlassButton(onClick = onClick) {
        SfIcon(SfSymbol.Ellipsis, text, size = 18.dp, tint = color)
        Text(text, style = IosTypography.caption, color = color, modifier = Modifier.padding(start = 6.dp))
    }
}
