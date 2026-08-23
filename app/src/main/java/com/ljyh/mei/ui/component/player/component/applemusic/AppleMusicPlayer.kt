package com.ljyh.mei.ui.component.player.component.applemusic

import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Size
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.capsule.ContinuousRoundedRectangle
import com.kyant.shapes.Capsule
import com.ljyh.mei.constants.MiniPlayerHeight
import com.ljyh.mei.constants.PlayerHorizontalPadding
import com.ljyh.mei.constants.ThumbnailCornerRadius
import com.ljyh.mei.ui.component.player.MiniPlayer
import com.ljyh.mei.ui.component.player.OverlayState
import com.ljyh.mei.ui.component.player.component.FluidBackground
import com.ljyh.mei.ui.component.player.component.LyricScreen
import com.ljyh.mei.ui.component.player.component.PlayerControlsSection
import com.ljyh.mei.ui.component.player.overlay.PlayerOverlayHandler
import com.ljyh.mei.ui.component.player.state.PlayerStateContainer
import com.ljyh.mei.ui.component.sheet.BottomSheet
import com.ljyh.mei.ui.component.sheet.BottomSheetState
import com.ljyh.mei.ui.component.sheet.HorizontalSwipeDirection
import com.ljyh.mei.ui.component.utils.lerp
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.trackBackdropPosition
import com.ljyh.mei.ui.model.LyricSource
import com.ljyh.mei.utils.UnitUtils.toPx
import com.ljyh.mei.utils.audio.AudioVisualizerManager
import kotlin.math.min

private class MiniPlayerCoverBoundsHolder {
    var value: Rect? = null
}



@OptIn(UnstableApi::class)
@Composable
fun AppleMusicPlayer(
    state: BottomSheetState,
    modifier: Modifier = Modifier,
    stateContainer: PlayerStateContainer,
    overlayHandler: PlayerOverlayHandler,
    collapsedBackdrop: Backdrop,
    playerBackgroundBackdrop: LayerBackdrop,
    playerContentBackdrop: LayerBackdrop,
    playerCoverBackdrop: LayerBackdrop,
    compactMiniPlayerProgress: State<Float>,
    miniPlayerVerticalOffset: () -> Dp,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val isDark = LocalGlassColors.current.isDark
    val configuration = LocalConfiguration.current

    // --- Apple Music 特定状态 ---
    var showLyrics by remember { mutableStateOf(false) }
    var playerBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
    val miniCoverBoundsInRoot = remember { MiniPlayerCoverBoundsHolder() }

    // --- 从状态容器获取数据 ---
    val mediaMetadata by stateContainer.mediaMetadata
    val isPlaying by stateContainer.isPlaying
    val playbackState by stateContainer.playbackState
    val sliderPosition by remember { derivedStateOf { stateContainer.sliderPosition } }
    val duration by remember { derivedStateOf { stateContainer.duration } }
    val isDragging by remember { derivedStateOf { stateContainer.isDragging } }
    val lyricLine by remember { derivedStateOf { stateContainer.lyricLine } }
    val isLiked by stateContainer.isLiked

    // --- Apple Music 特定的 LaunchedEffect ---
    LaunchedEffect(state.isCollapsed) {
        if (state.isCollapsed) {
            showLyrics = false
        }
    }
    BackHandler(enabled = state.isExpanded && showLyrics) {
        showLyrics = false
    }


    // --- Animation & Geometry Calculation ---
    val lyricTransition = updateTransition(targetState = showLyrics, label = "LyricMode")
    val lyricAnimFraction by lyricTransition.animateFloat(
        label = "Fraction",
        transitionSpec = { spring(stiffness = Spring.StiffnessLow) }
    ) { if (it) 1f else 0f }

    val sheetProgress = state.progress

    val colorScheme = MaterialTheme.colorScheme
    val backgroundColor = remember(isDark, state.value, state.collapsedBound) {
        if (isDark && state.value > state.collapsedBound) {
            lerp(colorScheme.surfaceContainer, Color.Black, state.progress)
        } else {
            colorScheme.surfaceContainer
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInRoot()
                if (playerBoundsInRoot != bounds) playerBoundsInRoot = bounds
            },
    ) {
        val screenWidth = maxWidth
        val maxWidthPx = constraints.maxWidth.toFloat()
        val maxHeightPx = constraints.maxHeight.toFloat()

        // --- 响应式布局判断 ---
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val isCompactHeight = maxHeightPx < with(density) { 600.dp.toPx() }

        // --- 1. 定义关键尺寸参数 ---

        // A. Mini Player (Bottom)
        val measuredMiniBounds = miniCoverBoundsInRoot.value
        val measuredPlayerBounds = playerBoundsInRoot
        val miniSize = measuredMiniBounds?.width?.takeIf { it > 0f }
            ?: with(density) { 32.dp.toPx() }
        val miniStart = if (measuredMiniBounds != null && measuredPlayerBounds != null) {
            measuredMiniBounds.left - measuredPlayerBounds.left
        } else {
            with(density) { (20.dp + 60.dp * compactMiniPlayerProgress.value).toPx() }
        }
        val miniRadius = with(density) { ThumbnailCornerRadius.toPx() }
        val collapsedBoundPx = with(density) { state.collapsedBound.toPx() }
        val miniAbsTop = if (measuredMiniBounds != null && measuredPlayerBounds != null) {
            measuredMiniBounds.top - measuredPlayerBounds.top
        } else {
            maxHeightPx - collapsedBoundPx +
                with(density) { (8.dp + miniPlayerVerticalOffset()).toPx() }
        }

        // B. Normal Expanded
        val statusBarTop = with(density) { WindowInsets.statusBars.getTop(this).toFloat() }
        val topSafeArea = (statusBarTop - (playerBoundsInRoot?.top ?: 0f)).coerceAtLeast(0f)

        val bottomControlsHeightDp = if (isCompactHeight || isLandscape) 220.dp else 300.dp
        val bottomControlsHeight = with(density) { bottomControlsHeightDp.toPx() }

        // --- 动态计算封面大小 ---
        val normalPaddingH = with(density) { (PlayerHorizontalPadding + 24.dp).toPx() }
        val maxAvailableWidth = maxWidthPx - (normalPaddingH * 2)

        val minTopMargin = with(density) { 16.dp.toPx() }
        val availableVerticalSpace = maxHeightPx - bottomControlsHeight - topSafeArea - minTopMargin

        val normalSize = min(maxAvailableWidth, availableVerticalSpace.coerceAtLeast(0f))

        val realAvailableHeight = (maxHeightPx - bottomControlsHeight - topSafeArea)
        val verticalBias = (realAvailableHeight - normalSize) / 2
        val normalTop = topSafeArea + verticalBias.coerceAtLeast(with(density) { 12.dp.toPx() })

        val normalStart = (maxWidthPx - normalSize) / 2

        // C. Header (Top Left Small)
        val headerSize = with(density) { 46.dp.toPx() }
        val headerTop = topSafeArea + with(density) { 40.dp.toPx() }
        val headerStart = with(density) { PlayerHorizontalPadding.toPx() }
        val headerRadius = with(density) { 4.dp.toPx() }

        // --- 2. 坐标插值 ---
        val targetSize = lerp(normalSize, headerSize, lyricAnimFraction)
        val targetTop = lerp(normalTop, headerTop, lyricAnimFraction)
        val targetStart = lerp(normalStart, headerStart, lyricAnimFraction)
        val targetRadius = with(density) { lerp(12.dp.toPx(), headerRadius, lyricAnimFraction) }

        val finalSize = lerp(miniSize, targetSize, sheetProgress)
        val finalTop = lerp(miniAbsTop, targetTop, sheetProgress)
        val finalStart = lerp(miniStart, targetStart, sheetProgress)
        val finalRadius = lerp(miniRadius, targetRadius, sheetProgress)

        val shadowAlpha = if (sheetProgress > 0.8f) (1f - lyricAnimFraction) else 0f
        var mShadowElevation = 16.dp * shadowAlpha

        val coverUrl = mediaMetadata?.coverUrl
        val audioVisualizerManager = remember { AudioVisualizerManager(context) }
        LaunchedEffect(stateContainer.playerConnection.player) {
            val player = stateContainer.playerConnection.player as? ExoPlayer
            player?.audioSessionId?.let(audioVisualizerManager::attachToPlayer)
        }
        // Keep the mesh and expanded artwork out of the first part of the capsule reveal;
        // this avoids a bright frame flashing through the iOS glass transition.
        val playerBackgroundAlpha = ((sheetProgress - 0.12f) / 0.28f).coerceIn(0f, 1f)


        // --- 3. UI Structure ---
        BottomSheet(
            state = state,
            modifier = Modifier.fillMaxSize(),
            backgroundColor = backgroundColor,
            collapsedDragOffset = miniPlayerVerticalOffset,
            collapsedDragHeight = MiniPlayerHeight,
            collapsedContentPadding = 2.dp,
            onDismiss = {
                stateContainer.playerConnection.player.stop()
                stateContainer.playerConnection.player.clearMediaItems()
            },
            onHorizontalSwipe = { direction ->
                if (!state.isExpanded) {
                    when (direction) {
                        HorizontalSwipeDirection.Left -> stateContainer.playerConnection.seekToNext()
                        HorizontalSwipeDirection.Right -> stateContainer.playerConnection.seekToPrevious()
                    }
                }
            },
            backgroundContent = {
                FluidBackground(
                    imageUrl = coverUrl,
                    audioVisualizerManager = audioVisualizerManager,
                    isPlaying = isPlaying,
                    alpha = playerBackgroundAlpha,
                    backdrop = playerBackgroundBackdrop,
                )
            },
            collapsedContent = {
                MiniPlayer(
                    position = sliderPosition.toLong(),
                    duration = duration,
                    backdrop = collapsedBackdrop,
                    compactProgress = compactMiniPlayerProgress,
                    onClick = state::expandSoft,
                    onCoverBoundsChanged = { bounds ->
                        miniCoverBoundsInRoot.value = bounds
                    },
                )
            }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(playerContentBackdrop)
                    .trackBackdropPosition(playerContentBackdrop),
            ) {

                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = with(density) { topSafeArea.toDp() } + 7.dp)
                        .size(width = 58.dp, height = 4.dp)
                        .background(
                            Color.White.copy(alpha = if (isDark) 0.48f else 0.62f),
                            Capsule(),
                        ),
                )


                // Mode B: Lyric Player
                AnimatedVisibility(
                    visible = showLyrics,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(modifier = Modifier.height(with(density) { (headerTop + headerSize).toDp() + 16.dp }))

                        LyricScreen(
                            lyricData = lyricLine,
                            playerConnection = stateContainer.playerConnection,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = PlayerHorizontalPadding),
                            onClick = {
                                mediaMetadata?.let {
                                    if (overlayHandler.currentOverlayValue is OverlayState.None) {
                                        stateContainer.playerViewModel.searchQQSong(it.title)
                                        overlayHandler.showQQMusicSelection(
                                            mediaMetadata = it
                                        )
                                    }
                                }
                            },
                            onLongClick = { source ->
                                if (source == LyricSource.QQMusic && mediaMetadata != null) {
                                    stateContainer.playerViewModel.deleteSongById(id = mediaMetadata!!.id.toString())
                                    android.widget.Toast.makeText(context, "已删除QQ音乐歌词", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            controlsVisible = stateContainer.controlsVisible,
                            onToggleControls = { stateContainer.controlsVisible = it },
                        )
                        val spacerHeight by animateDpAsState(
                            targetValue = if (stateContainer.controlsVisible) bottomControlsHeightDp else 16.dp,
                            label = "spacer"
                        )
                        Spacer(modifier = Modifier.height(spacerHeight))
                    }
                }


                // Mode C: Header Info
                if (mediaMetadata != null) {
                    val fraction = lyricAnimFraction
                    val enterThreshold = 0.4f

                    val headerTextAlpha = if (fraction > enterThreshold) {
                        ((fraction - enterThreshold) / (1f - enterThreshold)).coerceIn(
                            0f,
                            1f
                        ) * sheetProgress
                    } else {
                        0f
                    }

                    if (headerTextAlpha > 0.01f) {
                        val headerTextWidth =
                            screenWidth - with(density) { (headerStart + headerSize).toDp() } - 24.dp
                        val slideUpOffset =
                            with(density) { (20.dp.toPx() * (1f - fraction)).toInt() }

                        val currentX = (targetStart + headerSize + 12.dp.toPx(context)).toInt()
                        val fixedY = headerTop.toInt() + slideUpOffset

                        Box(
                            modifier = Modifier
                                .graphicsLayer {
                                    alpha = headerTextAlpha
                                    val scale = 0.9f + (0.1f * fraction)
                                    scaleX = scale
                                    scaleY = scale
                                }
                                .offset {
                                    IntOffset(
                                        x = currentX,
                                        y = fixedY
                                    )
                                }
                                .height(with(density) { headerSize.toDp() })
                                .width(headerTextWidth),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Title(
                                title = mediaMetadata!!.title,
                                subTitle = mediaMetadata!!.artists.joinToString { it.name },
                                isLiked = isLiked,
                                onLikeClick = { mediaMetadata?.let { stateContainer.playerViewModel.like(it.id.toString()) } },
                                onMoreClick = { overlayHandler.showMoreAction() },
                                onTitleClick = {
                                    mediaMetadata?.let {
                                        overlayHandler.showAlbumArtist(
                                            album = it.album,
                                            artists = it.artists,
                                            cover = it.coverUrl
                                        )
                                    }
                                },
                                titleStyle = MaterialTheme.typography.titleMedium,
                                subTitleStyle = MaterialTheme.typography.bodySmall,
                                needShadow = false,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }

                // --- 统一的底部控制区域 ---
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Transparent)
                        .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                ) {
                    val isBottomBarVisible = !showLyrics || stateContainer.controlsVisible

                    AnimatedVisibility(
                        visible = isBottomBarVisible,
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut()
                    ) {
                        Column(Modifier.fillMaxWidth()) {

                            val isTitleVisible = !showLyrics && (!isCompactHeight && !isLandscape)

                            if (isTitleVisible) {
                                mediaMetadata?.let {
                                    Title(
                                        title = it.title,
                                        subTitle = it.artists.joinToString { artist -> artist.name },
                                        isLiked = isLiked,
                                        onLikeClick = { stateContainer.playerViewModel.like(it.id.toString()) },
                                        onMoreClick = { overlayHandler.showMoreAction() },
                                        onTitleClick = {
                                            overlayHandler.showAlbumArtist(
                                                album = it.album,
                                                artists = it.artists,
                                                cover = it.coverUrl
                                            )
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = PlayerHorizontalPadding)
                                            .padding(bottom = 12.dp)
                                            .padding(start = 8.dp)
                                    )
                                }
                            }

                            PlayerControlsSection(
                                sliderPosition = sliderPosition,
                                duration = duration,
                                isPlaying = isPlaying,
                                playbackState = playbackState,
                                playerConnection = stateContainer.playerConnection,
                                onLyricClick = { showLyrics = !showLyrics },
                                onPlaylistClick = { overlayHandler.showPlaylist() },
                                onSleepTimerClick = { overlayHandler.showSleepTimer() },
                                onAddToPlaylistClick = {
                                    mediaMetadata?.let {
                                        overlayHandler.showAddToPlaylist(it.id)
                                    }
                                },
                                onDownloadClick = {
                                    overlayHandler.handleMoreAction(com.ljyh.mei.ui.model.MoreAction.DOWNLOAD)
                                },
                                onMoreClick = { overlayHandler.showMoreAction() },
                                isCompact = isCompactHeight || isLandscape
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .layerBackdrop(playerCoverBackdrop)
                .trackBackdropPosition(playerCoverBackdrop),
        ) {
            AnimatedContent(
                targetState = mediaMetadata,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(durationMillis = 400)) +
                            scaleIn(initialScale = 0.92f, animationSpec = tween(durationMillis = 400)))
                        .togetherWith(
                            fadeOut(animationSpec = tween(durationMillis = 400))
                        )
                },
                label = "CoverTransition",
                modifier = Modifier
                    .graphicsLayer {
                        alpha = state.revealProgress
                        translationX = finalStart
                        translationY = finalTop
                        shadowElevation = mShadowElevation.toPx()
                        shape = ContinuousRoundedRectangle(finalRadius)
                        clip = true
                    }
                    .size(
                        width = with(density) { finalSize.toDp() },
                        height = with(density) { finalSize.toDp() }
                    )
                    .clickable {
                        if (!state.isExpanded) {
                            state.expandSoft()
                        } else {
                            showLyrics = !showLyrics
                        }
                    }
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) { currentMetadata ->
                if (currentMetadata != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(currentMetadata.coverUrl)
                            .size(Size.ORIGINAL)
                            .precision(Precision.EXACT)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Cover",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
                }
            }
        }
    }
}
