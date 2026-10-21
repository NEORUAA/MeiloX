package com.ljyh.mei.ui.component.player.component.applemusic

import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.foundation.Image
import com.ljyh.mei.ui.component.player.LocalPlayerArtwork
import com.ljyh.mei.ui.component.sheet.playerArtwork
import com.ljyh.mei.ui.component.sheet.PlayerArtworkGeometry
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.media3.common.util.UnstableApi
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.capsule.ContinuousRoundedRectangle
import com.kyant.shapes.Capsule
import com.ljyh.mei.constants.MiniPlayerHeight
import com.ljyh.mei.constants.PlayerHorizontalPadding
import com.ljyh.mei.constants.ThumbnailCornerRadius
import com.ljyh.mei.ui.component.player.MiniPlayer
import com.ljyh.mei.ui.component.player.component.FluidBackground
import com.ljyh.mei.ui.component.player.component.LyricScreen
import com.ljyh.mei.ui.component.player.component.PlayerControlsSection
import com.ljyh.mei.ui.component.player.overlay.PlayerOverlayHandler
import com.ljyh.mei.ui.component.player.state.PlayerStateContainer
import com.ljyh.mei.ui.component.sheet.BottomSheet
import com.ljyh.mei.ui.component.sheet.BottomSheetState
import com.ljyh.mei.ui.component.sheet.HorizontalSwipeDirection
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.trackBackdropPosition
import kotlin.math.min
import kotlin.math.roundToInt

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
    val isDark = LocalGlassColors.current.isDark
    val configuration = LocalConfiguration.current

    // --- Apple Music 特定状态 ---
    var showLyrics by remember { mutableStateOf(false) }
    var playerBoundsInRoot by remember { mutableStateOf<Rect?>(null) }

    // --- 从状态容器获取数据 ---
    val sharedArtwork = LocalPlayerArtwork.current
    val mediaMetadata by stateContainer.mediaMetadata
    val isPlaying by stateContainer.isPlaying
    val playbackState by stateContainer.playbackState
    val isDragging by remember { derivedStateOf { stateContainer.isDragging } }
    val lyricLine by remember { derivedStateOf { stateContainer.lyricLine } }
    val isLiked by stateContainer.isLiked
    val sheetExpanded by remember(state) { derivedStateOf { state.isExpanded } }

    // --- Apple Music 特定的 LaunchedEffect ---
    BackHandler(enabled = sheetExpanded && showLyrics) {
        showLyrics = false
    }


    // --- Animation & Geometry Calculation ---
    val lyricAnimFraction = remember { Animatable(0f) }
    LaunchedEffect(showLyrics, sheetExpanded) {
        // Keep the measured sheet endpoint stable while its shared artwork owns motion.
        if (sheetExpanded) {
            lyricAnimFraction.animateTo(
                if (showLyrics) 1f else 0f,
                appleMusicLyricSpring(),
            )
        }
    }

    val albumInfoAlpha = remember { derivedStateOf { appleMusicAlbumInfoAlpha(lyricAnimFraction.value) } }
    val headerInfoAlpha = remember { derivedStateOf { appleMusicHeaderInfoAlpha(lyricAnimFraction.value) } }
    val albumInfoVisible by remember { derivedStateOf { albumInfoAlpha.value > 0f } }
    val headerInfoVisible by remember { derivedStateOf { headerInfoAlpha.value > 0f } }

    // Metadata travel follows the same reversible clock as the artwork.
    val lyricProgress = lyricAnimFraction.asState()
    val lyricVisibilityAlpha = remember { derivedStateOf { appleMusicLyricsAlpha(lyricAnimFraction.value) } }
    val albumInteractive by remember { derivedStateOf { lyricAnimFraction.value == 0f } }
    val headerInteractive by remember { derivedStateOf { lyricAnimFraction.value == 1f } }

    val animatedPlaybackCoverScale = rememberAppleMusicPlaybackScale(isPlaying, sheetExpanded)
    val currentPlaying = rememberUpdatedState(isPlaying)
    val currentExpanded = rememberUpdatedState(sheetExpanded)


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

        // B. Normal Expanded
        val statusBarTop = with(density) { WindowInsets.statusBars.getTop(this).toFloat() }
        val topSafeArea = (statusBarTop - (playerBoundsInRoot?.top ?: 0f)).coerceAtLeast(0f)

        val bottomControlsHeightDp = if (isCompactHeight || isLandscape) 220.dp else 300.dp
        val bottomControlsHeight = with(density) { bottomControlsHeightDp.toPx() }
        val controlsShown = !showLyrics || stateContainer.controlsVisible
        val controlsFadeShift = animateFloatAsState(
            targetValue = if (controlsShown) 0f else 0.6f,
            animationSpec = appleMusicControlsFadeSpring(controlsShown),
            label = "LyricControlsFade",
        )
        // --- 动态计算封面大小 ---
        // Recover the existing bottom edge before changing the visible cover width.
        val normalPaddingH = with(density) { (PlayerHorizontalPadding + 24.dp).toPx() }
        val maxAvailableWidth = maxWidthPx - (normalPaddingH * 2)

        val minTopMargin = with(density) { 16.dp.toPx() }
        val availableVerticalSpace = maxHeightPx - bottomControlsHeight - topSafeArea - minTopMargin

        val previousSize = min(maxAvailableWidth, availableVerticalSpace.coerceAtLeast(0f)).coerceAtLeast(1f)

        val realAvailableHeight = (maxHeightPx - bottomControlsHeight - topSafeArea)
        val verticalBias = (realAvailableHeight - previousSize) / 2
        val previousTop = topSafeArea + verticalBias.coerceAtLeast(with(density) { 12.dp.toPx() })

        val previousStart = (maxWidthPx - previousSize) / 2
        val normalBounds = appleMusicAlbumBounds(
            Rect(previousStart, previousTop, previousStart + previousSize, previousTop + previousSize),
            viewportWidth = maxWidthPx,
            // Flamingo uses 15.dp outer padding plus 9.dp inner padding while playing.
            horizontalInset = with(density) { AppleMusicDimensions.artworkInset.toPx() },
            topLimit = topSafeArea + with(density) { 12.dp.toPx() },
        )
        val normalSize = normalBounds.width

        // C. Header (Top Left Small)
        val headerSize = with(density) { AppleMusicDimensions.headerSize.toPx() }
        val headerTop = topSafeArea + with(density) { AppleMusicDimensions.headerTopInset.toPx() }
        val lyricViewportTop = headerTop + headerSize - with(density) { 2.dp.toPx() }
        val headerStart = with(density) { AppleMusicDimensions.headerInset.toPx() }
        val headerRadius = with(density) { AppleMusicDimensions.headerCorner.toPx() }
        val headerBounds = Rect(headerStart, headerTop, headerStart + headerSize, headerTop + headerSize)
        val normalRadius = with(density) { 12.dp.toPx() }
        val normalShadow = with(density) { 16.dp.toPx() }
        // Keep image measurement stable; only layer properties observe animation frames.
        val artworkLayoutSize = normalSize.roundToInt().coerceAtLeast(1)
        val artworkFrame = remember(normalBounds, headerBounds, density) {
            derivedStateOf {
                appleMusicArtworkFrame(
                    normalBounds, headerBounds, normalRadius, headerRadius, normalShadow,
                    if (currentExpanded.value) animatedPlaybackCoverScale.value else if (currentPlaying.value) 1f else 0.9f,
                    lyricAnimFraction.value,
                )
            }
        }

        val coverUrl = mediaMetadata?.coverUrl
        // --- 3. UI Structure ---
        BottomSheet(
            state = state,
            modifier = Modifier.fillMaxSize(),
            collapsedDragOffset = miniPlayerVerticalOffset,
            collapsedDragHeight = MiniPlayerHeight,
            transitionBackdrop = collapsedBackdrop,
            onDismiss = {
                stateContainer.playerConnection.player.stop()
                stateContainer.playerConnection.player.clearMediaItems()
            },
            onHorizontalSwipe = { direction ->
                if (!sheetExpanded) {
                    when (direction) {
                        HorizontalSwipeDirection.Left -> stateContainer.playerConnection.seekToNext()
                        HorizontalSwipeDirection.Right -> stateContainer.playerConnection.seekToPrevious()
                    }
                }
            },
            backgroundContent = {
                FluidBackground(
                    imageUrl = coverUrl,
                    beatMeter = stateContainer.playerConnection.service.beatMeter,
                    isPlaying = isPlaying,
                    alpha = 1f,
                    backdrop = playerBackgroundBackdrop,
                )
            },
            collapsedContent = {
                MiniPlayer(
                    // The mini player has no progress indicator, so it does not
                    // need to subscribe to the expanded player's position clock.
                    position = 0L,
                    duration = 0L,
                    backdrop = collapsedBackdrop,
                    compactProgress = compactMiniPlayerProgress,
                    onClick = state::expandSoft,
                )
            }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer()
                    .layerBackdrop(playerContentBackdrop)
                    .trackBackdropPosition(playerContentBackdrop),
            ) {

                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = with(density) { topSafeArea.toDp() } + AppleMusicDimensions.handleTopInset)
                        .size(width = 58.dp, height = 4.dp)
                        .playerAdditiveBlend()
                        .background(
                            Color.White.copy(alpha = if (isDark) 0.48f else 0.62f),
                            Capsule(),
                        ),
                )


                var controlsHeightPx by remember(screenWidth, maxHeightPx) { mutableIntStateOf(0) }
                var progressTrackTopInRoot by remember(screenWidth, maxHeightPx) { mutableStateOf<Float?>(null) }
                var albumRowSize by remember { mutableStateOf(IntSize.Zero) }
                val showAlbumInfo = !isCompactHeight && !isLandscape
                val albumInsetPx = with(density) { AppleMusicDimensions.albumInfoInset.toPx() }
                val albumTop = appleMusicAlbumInfoTop(
                    maxHeightPx, controlsHeightPx.toFloat(), albumRowSize.height.toFloat(),
                    with(density) { AppleMusicDimensions.albumInfoBottomGap.toPx() }, topSafeArea,
                )
                val lyricEntryTranslationY = {
                    if (showAlbumInfo) {
                        appleMusicLyricsTranslationY(
                            lyricProgress.value, albumTop + albumRowSize.height, lyricViewportTop,
                        )
                    } else 0f
                }

                // Mode B: Lyric Player
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            layout(placeable.width, placeable.height) {
                                // Premeasure the resident host, but remove hidden input targets.
                                if (showLyrics || lyricVisibilityAlpha.value > 0f) placeable.place(0, 0)
                            }
                        }
                        .playerContentFade(lyricVisibilityAlpha)
                        .then(if (showLyrics && sheetExpanded) Modifier else Modifier.clearAndSetSemantics { }),
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(modifier = Modifier.height(with(density) { lyricViewportTop.toDp() }))

                        LyricScreen(
                            lyricData = lyricLine,
                            active = showLyrics,
                            showSourceBadge = false,
                            topFadeHeight = AppleMusicDimensions.lyricTopFade,
                            bottomFadeHeight = AppleMusicDimensions.lyricBottomFade,
                            rendererVerticalPadding = 0.dp,
                            synchronizeEntryWithParent = true,
                            lyricContentModifier = Modifier.graphicsLayer {
                                translationY = lyricEntryTranslationY()
                            },
                            controlsFadeRange = { viewportHeight ->
                                appleMusicLyricsControlsFadeRange(
                                    viewportHeight, controlsFadeShift.value, lyricEntryTranslationY(),
                                    controlsTop = (progressTrackTopInRoot?.minus(playerBoundsInRoot?.top ?: 0f)
                                        ?: (maxHeightPx - controlsHeightPx)) - lyricViewportTop,
                                )
                            },
                            playerConnection = stateContainer.playerConnection,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                // Both upstream lyric text renderers add 16.dp internally.
                                .padding(horizontal = AppleMusicDimensions.lyricTextInset - AppleMusicDimensions.lyricRendererInset),
                            onClick = {},
                            onLongClick = {},
                            controlsVisible = stateContainer.controlsVisible,
                            onToggleControls = { stateContainer.controlsVisible = it },
                        )
                    }
                }


                // Metadata lives above the footer's animated clip. Both endpoints stay measured.
                var albumTextSize by remember { mutableStateOf(IntSize.Zero) }
                var albumActionsSize by remember { mutableStateOf(IntSize.Zero) }
                var headerTextSize by remember { mutableStateOf(IntSize.Zero) }
                var headerActionsSize by remember { mutableStateOf(IntSize.Zero) }
                val infoRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                val actionsGapPx = with(density) { 5.dp.toPx() }
                val headerTextLeft = headerStart + headerSize + with(density) { 12.dp.toPx() }
                val headerTextOrigin = Offset(
                    headerTextLeft + if (infoRtl) headerActionsSize.width + actionsGapPx else 0f,
                    headerTop + (headerSize - headerTextSize.height) / 2f,
                )
                val headerActionsOrigin = Offset(
                    if (infoRtl) headerTextLeft else maxWidthPx - headerStart - headerActionsSize.width,
                    headerTop + (headerSize - headerActionsSize.height) / 2f,
                )
                val albumTextOrigin = if (showAlbumInfo) {
                    Offset(albumInsetPx + if (infoRtl) albumActionsSize.width + actionsGapPx else 0f,
                        albumTop + (albumRowSize.height - albumTextSize.height) / 2f)
                } else headerTextOrigin
                val albumActionsOrigin = if (showAlbumInfo) {
                    Offset(if (infoRtl) albumInsetPx else maxWidthPx - albumInsetPx - albumActionsSize.width,
                        albumTop + (albumRowSize.height - albumActionsSize.height) / 2f)
                } else headerActionsOrigin

                mediaMetadata?.let { metadata ->
                    if (showAlbumInfo) {
                        val albumTitleStyle = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 18.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold,
                        )
                        val albumArtistStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal,
                        )
                        // Anchor the row independently of fallback-font text height, like the lyric header.
                        val albumInfoHeight = with(density) {
                            maxOf(
                                albumTitleStyle.lineHeight.toDp() + albumArtistStyle.lineHeight.toDp(),
                                albumActionsSize.height.toDp(),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .align(AbsoluteAlignment.TopLeft)
                                .absoluteOffset { IntOffset(albumInsetPx.roundToInt(), albumTop.roundToInt()) }
                                .width(screenWidth - AppleMusicDimensions.albumInfoInset * 2)
                                .height(albumInfoHeight)
                                .onSizeChanged { albumRowSize = it }
                                .playerInfoVisibility(albumInfoVisible),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Title(
                                title = metadata.title,
                                subTitle = metadata.artists.joinToString { it.name },
                                isLiked = isLiked,
                                onLikeClick = { stateContainer.playerViewModel.like(metadata.id.toString()) },
                                onMoreClick = { overlayHandler.showMoreAction() },
                                onTitleClick = {
                                    overlayHandler.showAlbumArtist(metadata.album, metadata.artists, metadata.coverUrl)
                                },
                                enabled = sheetExpanded && !showLyrics && albumInteractive,
                                marqueeEnabled = sheetExpanded && albumInfoVisible,
                                titleStyle = albumTitleStyle,
                                subTitleStyle = albumArtistStyle,
                                needShadow = false,
                                onTextSizeChanged = { albumTextSize = it },
                                onActionsSizeChanged = { albumActionsSize = it },
                                textModifier = Modifier.playerInfoMotion(
                                    albumInfoAlpha, lyricProgress, albumTextOrigin, headerTextOrigin, true,
                                ),
                                actionsModifier = Modifier.playerInfoMotion(
                                    albumInfoAlpha, lyricProgress, albumActionsOrigin, headerActionsOrigin, true,
                                ),
                                modifier = Modifier.wrapContentHeight(unbounded = true),
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .align(AbsoluteAlignment.TopLeft)
                            .absoluteOffset { IntOffset(headerTextLeft.roundToInt(), headerTop.roundToInt()) }
                            .width(screenWidth - with(density) { headerTextLeft.toDp() } - AppleMusicDimensions.headerInset)
                            .height(AppleMusicDimensions.headerSize)
                            .playerInfoVisibility(headerInfoVisible),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Title(
                            title = metadata.title,
                            subTitle = metadata.artists.joinToString { it.name },
                            isLiked = isLiked,
                            onLikeClick = { stateContainer.playerViewModel.like(metadata.id.toString()) },
                            onMoreClick = { overlayHandler.showMoreAction() },
                            onTitleClick = {
                                overlayHandler.showAlbumArtist(metadata.album, metadata.artists, metadata.coverUrl)
                            },
                            enabled = sheetExpanded && showLyrics && headerInteractive,
                            marqueeEdgeWidth = 12.dp,
                            marqueeEnabled = sheetExpanded && headerInfoVisible,
                            titleStyle = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold,
                            ),
                            subTitleStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal,
                            ),
                            needShadow = false,
                            onTextSizeChanged = { headerTextSize = it },
                            onActionsSizeChanged = { headerActionsSize = it },
                            textModifier = Modifier.playerInfoMotion(
                                headerInfoAlpha, lyricProgress, albumTextOrigin, headerTextOrigin, false,
                            ),
                            actionsModifier = Modifier.playerInfoMotion(
                                headerInfoAlpha, lyricProgress, albumActionsOrigin, headerActionsOrigin, false,
                            ),
                            modifier = Modifier.wrapContentHeight(unbounded = true),
                        )
                    }
                }

                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
                ) {
                    AnimatedVisibility(
                        visible = controlsShown,
                        enter = slideInVertically { it },
                        exit = slideOutVertically { it },
                    ) {
                        val controlsAlpha = transition.animateFloat(
                            transitionSpec = { spring(stiffness = Spring.StiffnessMediumLow) },
                            label = "PlayerControlsOpacity",
                        ) {
                            if (it == EnterExitState.Visible) 1f else 0f
                        }
                        DisposableEffect(Unit) {
                            onDispose { progressTrackTopInRoot = null }
                        }
                        Box(Modifier.playerContentFade(controlsAlpha)
                            .onSizeChanged { controlsHeightPx = it.height }) {
                            PlayerControlsSection(
                                position = { stateContainer.sliderPosition.toLong() },
                                duration = { stateContainer.duration },
                                isPlaying = isPlaying,
                                playbackState = playbackState,
                                playerConnection = stateContainer.playerConnection,
                                onLyricClick = { showLyrics = !showLyrics },
                                onPlaylistClick = { overlayHandler.showPlaylist() },
                                onSleepTimerClick = { overlayHandler.showSleepTimer() },
                                onAddToPlaylistClick = {
                                    mediaMetadata?.let { overlayHandler.showAddToPlaylist(it.id) }
                                },
                                onDownloadClick = {
                                    overlayHandler.handleMoreAction(com.ljyh.mei.ui.model.MoreAction.DOWNLOAD)
                                },
                                onMoreClick = { overlayHandler.showMoreAction() },
                                isCompact = isCompactHeight || isLandscape,
                                onProgressTrackTopChanged = { progressTrackTopInRoot = it },
                            )
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer()
                    .layerBackdrop(playerCoverBackdrop)
                    .trackBackdropPosition(playerCoverBackdrop),
            ) {
                AnimatedContent(
                    targetState = coverUrl,
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
                            val frame = artworkFrame.value
                            transformOrigin = TransformOrigin(0f, 0f)
                            translationX = frame.bounds.left
                            translationY = frame.bounds.top
                            scaleX = frame.bounds.width / size.width.coerceAtLeast(1f)
                            scaleY = frame.bounds.height / size.height.coerceAtLeast(1f)
                        }
                        .size(with(density) { artworkLayoutSize.toDp() })
                        .playerArtwork(
                            geometry = {
                                PlayerArtworkGeometry(
                                    artworkFrame.value.bounds,
                                    IntSize(artworkLayoutSize, artworkLayoutSize),
                                )
                            },
                            cornerRadius = {
                                val frame = artworkFrame.value
                                with(density) { (frame.cornerRadius * artworkLayoutSize / frame.bounds.width.coerceAtLeast(1f)).toDp() }
                            },
                            shadowElevation = {
                                val frame = artworkFrame.value
                                with(density) { (frame.shadowElevation * artworkLayoutSize / frame.bounds.width.coerceAtLeast(1f)).toDp() }
                            },
                            trackContainerTop = { showLyrics || lyricAnimFraction.value > 0f },
                        )
                        .graphicsLayer {
                            val frame = artworkFrame.value
                            val scale = frame.bounds.width / size.width.coerceAtLeast(1f)
                            shadowElevation = frame.shadowElevation / scale.coerceAtLeast(0.001f)
                            shape = ContinuousRoundedRectangle(frame.cornerRadius / scale.coerceAtLeast(0.001f))
                            clip = true
                        }
                        .then(
                            if (sheetExpanded) Modifier.clickable { showLyrics = !showLyrics }
                            else Modifier.clearAndSetSemantics { },
                        )
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) { currentCoverUrl ->
                    if (currentCoverUrl != null) {
                        val retainedPainter = remember(currentCoverUrl) {
                            sharedArtwork?.takeIf { it.first == currentCoverUrl }?.second
                        }
                        val painter = sharedArtwork?.takeIf { it.first == currentCoverUrl }?.second ?: retainedPainter
                        if (painter != null) Image(
                            painter = painter,
                            contentDescription = "Cover",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
                    }
                }
            }
        }
    }
}

internal fun Modifier.playerContentFade(alpha: State<Float>): Modifier = graphicsLayer {
    this.alpha = alpha.value
    // Keep descendant Plus blending connected to the background during the fade.
    compositingStrategy = CompositingStrategy.ModulateAlpha
}

internal fun Modifier.playerAdditiveBlend(): Modifier = graphicsLayer {
    blendMode = BlendMode.Plus
    compositingStrategy = CompositingStrategy.Offscreen
}

/** Unplace hidden endpoints without changing their measured geometry. */
private fun Modifier.playerInfoVisibility(visible: Boolean): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            if (visible) placeable.place(0, 0)
        }
    }.then(if (visible) Modifier else Modifier.clearAndSetSemantics { })

internal fun Modifier.playerInfoMotion(
    opacity: State<Float>,
    progress: State<Float>,
    albumOrigin: Offset,
    headerOrigin: Offset,
    fromAlbum: Boolean,
): Modifier = graphicsLayer {
    val offset = appleMusicInfoOffset(albumOrigin, headerOrigin, progress.value, fromAlbum)
    translationX = offset.x
    translationY = offset.y
    alpha = opacity.value
    clip = false
    blendMode = BlendMode.Plus
    compositingStrategy = CompositingStrategy.Offscreen
}
