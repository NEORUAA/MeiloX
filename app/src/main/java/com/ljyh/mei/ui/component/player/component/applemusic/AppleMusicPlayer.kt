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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import com.ljyh.mei.ui.glass.trackBackdropPosition
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

private enum class AppleMusicPlayerPage { Artwork, Lyrics, Queue }

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
    val configuration = LocalConfiguration.current

    // --- Apple Music 特定状态 ---
    var page by remember { mutableStateOf(AppleMusicPlayerPage.Artwork) }
    val showLyrics = page == AppleMusicPlayerPage.Lyrics
    val showQueue = page == AppleMusicPlayerPage.Queue
    val showDetail = page != AppleMusicPlayerPage.Artwork
    var lastDetailPage by remember { mutableStateOf(AppleMusicPlayerPage.Lyrics) }
    var playerBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
    var albumTextCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var headerTextCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var albumRowCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var headerRowCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    LaunchedEffect(page) {
        if (showDetail) lastDetailPage = page
    }

    DisposableEffect(overlayHandler) {
        val openPlaylist: () -> Unit = { page = AppleMusicPlayerPage.Queue }
        overlayHandler.onShowPlaylist = openPlaylist
        onDispose {
            if (overlayHandler.onShowPlaylist === openPlaylist) overlayHandler.onShowPlaylist = null
        }
    }

    // --- 从状态容器获取数据 ---
    val sharedArtwork = LocalPlayerArtwork.current
    val mediaMetadata by stateContainer.mediaMetadata
    val isPlaying by stateContainer.isPlaying
    val playbackState by stateContainer.playbackState
    val isDragging by remember { derivedStateOf { stateContainer.isDragging } }
    val lyricLine by remember { derivedStateOf { stateContainer.lyricLine } }
    val isLiked by stateContainer.isLiked
    val sheetExpanded by remember(state) { derivedStateOf { state.isExpanded } }
    val sheetContentVisible by remember(state) { derivedStateOf { state.isContentVisible } }

    // --- Apple Music 特定的 LaunchedEffect ---
    BackHandler(enabled = sheetExpanded && showDetail) {
        page = AppleMusicPlayerPage.Artwork
    }


    // --- Animation & Geometry Calculation ---
    val lyricAnimFraction = remember { Animatable(0f) }
    LaunchedEffect(showDetail, sheetExpanded) {
        // Keep the measured sheet endpoint stable while its shared artwork owns motion.
        if (sheetExpanded) {
            lyricAnimFraction.animateTo(
                if (showDetail) 1f else 0f,
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
        var controlsHeightPx by remember(screenWidth, maxHeightPx) { mutableIntStateOf(0) }
        var albumRowSize by remember { mutableStateOf(IntSize.Zero) }
        val measuredControlsHeight = controlsHeightPx.takeIf { it > 0 }?.toFloat() ?: bottomControlsHeight
        val albumInformationHeight = albumRowSize.height.takeIf { it > 0 }?.toFloat()
            ?: with(density) { 42.sp.toPx() }
        val showAlbumInfo = !isCompactHeight && !isLandscape
        val albumTop = appleMusicAlbumInfoTop(
            maxHeightPx, measuredControlsHeight, albumInformationHeight,
            with(density) { AppleMusicDimensions.albumInfoBottomGap.toPx() }, topSafeArea,
        )
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
            bottomLimit = if (showAlbumInfo) albumTop - minTopMargin
                else maxHeightPx - measuredControlsHeight - minTopMargin,
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
                    if (currentExpanded.value) animatedPlaybackCoverScale.value else if (currentPlaying.value) 1f else 0.75f,
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
                            Color.White.copy(alpha = 0.3f),
                            Capsule(),
                        ),
                )


                var progressTrackTopInRoot by remember(screenWidth, maxHeightPx) { mutableStateOf<Float?>(null) }
                val albumInsetPx = with(density) { AppleMusicDimensions.albumInfoInset.toPx() }
                val lyricEntryTranslationY = {
                    if (showAlbumInfo) {
                        appleMusicLyricsTranslationY(
                            lyricProgress.value, albumTop + albumRowSize.height, lyricViewportTop,
                        )
                    } else 0f
                }

                // Lyrics and queue share the same artwork, header and playback controls.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            layout(placeable.width, placeable.height) {
                                // Premeasure the resident host, but remove hidden input targets.
                                if (showDetail || lyricVisibilityAlpha.value > 0f) placeable.place(0, 0)
                            }
                        }
                        .playerContentFade(lyricVisibilityAlpha)
                        .then(if (showDetail && sheetExpanded) Modifier else Modifier.clearAndSetSemantics { }),
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(modifier = Modifier.height(with(density) { lyricViewportTop.toDp() }))

                        AppleMusicDetailPages(
                            queueSelected = if (showDetail) showQueue else lastDetailPage == AppleMusicPlayerPage.Queue,
                            active = showDetail && sheetContentVisible,
                            animate = showDetail && headerInteractive,
                            preloadQueue = sheetExpanded,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            queue = { active ->
                                val queueBottom = progressTrackTopInRoot
                                    ?.minus(playerBoundsInRoot?.top ?: 0f)
                                    ?: (maxHeightPx - controlsHeightPx)
                                Box(
                                    Modifier.fillMaxWidth()
                                        .playerQueueViewport(with(density) {
                                            (queueBottom - lyricViewportTop).coerceAtLeast(0f).toDp()
                                        }),
                                ) {
                                    PlayerQueuePage(
                                        playerConnection = stateContainer.playerConnection,
                                        active = active,
                                        indication = null,
                                        controlsFadeRange = { listHeight ->
                                            appleMusicLyricsControlsFadeRange(
                                                viewportHeight = maxHeightPx - lyricViewportTop,
                                                shift = controlsFadeShift.value,
                                                translationY = lyricEntryTranslationY(),
                                                controlsTop = listHeight,
                                            )
                                        },
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer { translationY = lyricEntryTranslationY() }
                                            .padding(top = 16.dp),
                                    )
                                }
                            },
                            lyrics = { active ->
                                LyricScreen(
                                    lyricData = lyricLine,
                                    active = active,
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
                                        .fillMaxSize()
                                        // Both upstream lyric text renderers add 16.dp internally.
                                        .padding(horizontal = AppleMusicDimensions.lyricTextInset - AppleMusicDimensions.lyricRendererInset),
                                    onClick = {},
                                    onLongClick = {},
                                    controlsVisible = stateContainer.controlsVisible,
                                    onToggleControls = { stateContainer.controlsVisible = it },
                                )
                            },
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
                                showLikeButton = !metadata.isLocal && !metadata.isPodcast,
                                onLikeClick = { stateContainer.playerViewModel.like(metadata.id.toString()) },
                                onMoreClick = { overlayHandler.showMoreAction() },
                                onTitleClick = {
                                    overlayHandler.showAlbumArtist(
                                        metadata.album,
                                        metadata.artists,
                                        metadata.coverUrl,
                                        anchorBounds = playerMetadataAnchorBounds(albumTextCoordinates, albumRowCoordinates),
                                    )
                                },
                                enabled = sheetExpanded && !showDetail && albumInteractive,
                                marqueeEnabled = sheetExpanded && albumInfoVisible,
                                titleStyle = albumTitleStyle,
                                subTitleStyle = albumArtistStyle,
                                needShadow = false,
                                onTextSizeChanged = { albumTextSize = it },
                                onTextPositioned = { albumTextCoordinates = it },
                                onRowPositioned = { albumRowCoordinates = it },
                                onActionsSizeChanged = { albumActionsSize = it },
                                textModifier = Modifier.playerInfoMotion(
                                    albumInfoAlpha, lyricProgress, albumTextOrigin, headerTextOrigin, true,
                                    layerOutsets = if (infoRtl) {
                                        LayerOutsets(right = AppleMusicDimensions.albumMarqueeEdgeWidth)
                                    } else LayerOutsets(left = AppleMusicDimensions.albumMarqueeEdgeWidth),
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
                            showLikeButton = !metadata.isLocal && !metadata.isPodcast,
                            onLikeClick = { stateContainer.playerViewModel.like(metadata.id.toString()) },
                            onMoreClick = { overlayHandler.showMoreAction() },
                            onTitleClick = {
                                overlayHandler.showAlbumArtist(
                                    metadata.album,
                                    metadata.artists,
                                    metadata.coverUrl,
                                    anchorBounds = playerMetadataAnchorBounds(headerTextCoordinates, headerRowCoordinates),
                                )
                            },
                            enabled = sheetExpanded && showDetail && headerInteractive,
                            marqueeEdgeWidth = AppleMusicDimensions.headerMarqueeEdgeWidth,
                            marqueeEnabled = sheetExpanded && headerInfoVisible,
                            titleStyle = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold,
                            ),
                            subTitleStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal,
                            ),
                            needShadow = false,
                            onTextSizeChanged = { headerTextSize = it },
                            onTextPositioned = { headerTextCoordinates = it },
                            onRowPositioned = { headerRowCoordinates = it },
                            onActionsSizeChanged = { headerActionsSize = it },
                            textModifier = Modifier.playerInfoMotion(
                                headerInfoAlpha, lyricProgress, albumTextOrigin, headerTextOrigin, false,
                                layerOutsets = if (infoRtl) {
                                    LayerOutsets(right = AppleMusicDimensions.headerMarqueeEdgeWidth)
                                } else LayerOutsets(left = AppleMusicDimensions.headerMarqueeEdgeWidth),
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
                                lyricsVisible = showLyrics,
                                queueVisible = showQueue,
                                onLyricClick = {
                                    page = if (showLyrics) AppleMusicPlayerPage.Artwork else AppleMusicPlayerPage.Lyrics
                                },
                                onPlaylistClick = {
                                    if (showQueue) page = AppleMusicPlayerPage.Artwork
                                    else overlayHandler.showPlaylist()
                                },
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
                            trackContainerTop = { showDetail || lyricAnimFraction.value > 0f },
                        )
                        .graphicsLayer {
                            val frame = artworkFrame.value
                            val scale = frame.bounds.width / size.width.coerceAtLeast(1f)
                            shadowElevation = frame.shadowElevation / scale.coerceAtLeast(0.001f)
                            shape = ContinuousRoundedRectangle(frame.cornerRadius / scale.coerceAtLeast(0.001f))
                            clip = true
                        }
                        .then(
                            if (sheetExpanded) Modifier.clickable {
                                page = if (showDetail) AppleMusicPlayerPage.Artwork else AppleMusicPlayerPage.Lyrics
                            }
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

/** Keep expensive page state alive; only the selected page owns input and clocks. */
@Composable
internal fun AppleMusicDetailPages(
    queueSelected: Boolean,
    active: Boolean,
    animate: Boolean,
    preloadQueue: Boolean,
    modifier: Modifier = Modifier,
    lyrics: @Composable (active: Boolean) -> Unit,
    queue: @Composable (active: Boolean) -> Unit,
) {
    var queuePrepared by remember { mutableStateOf(false) }
    LaunchedEffect(preloadQueue) {
        if (preloadQueue && !queuePrepared) {
            // Leave the sheet's settling frame free of queue initialization.
            withFrameNanos { }
            queuePrepared = true
        }
    }
    LaunchedEffect(queueSelected) {
        if (queueSelected) queuePrepared = true
    }
    val queueOpacity = remember { Animatable(if (queueSelected) 1f else 0f) }
    val lyricOpacity = remember { Animatable(if (queueSelected) 0f else 1f) }
    LaunchedEffect(queueSelected, animate) {
        val incoming = if (queueSelected) queueOpacity else lyricOpacity
        val outgoing = if (queueSelected) lyricOpacity else queueOpacity
        if (animate) {
            // The fades overlap during 100..400ms within one 500ms switch.
            coroutineScope {
                launch {
                    if (outgoing.value > 0f) outgoing.animateTo(0f, tween(400))
                }
                launch {
                    if (incoming.value < 1f) {
                        incoming.animateTo(1f, tween(400, delayMillis = 100))
                    }
                }
            }
        } else {
            outgoing.snapTo(0f)
            incoming.snapTo(1f)
        }
    }
    Box(modifier) {
        val lyricsActive = active && !queueSelected
        Box(Modifier.fillMaxSize().playerDetailPage(lyricOpacity.asState(), lyricsActive)) {
            lyrics(lyricsActive)
        }
        if (queuePrepared || queueSelected) {
            val queueActive = active && queueSelected
            Box(Modifier.fillMaxSize().playerDetailPage(queueOpacity.asState(), queueActive)) {
                queue(queueActive)
            }
        }
    }
}

@Composable
private fun Modifier.playerDetailPage(opacity: State<Float>, active: Boolean): Modifier {
    val visible = remember(opacity) { derivedStateOf { opacity.value > 0f } }
    return layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            if (visible.value) placeable.place(0, 0)
        }
    }
        .playerContentFade(opacity)
        .then(if (active) Modifier else Modifier.clearAndSetSemantics { })
        .pointerInput(active) {
            // Consume inactive-page gestures, including empty space, until its
            // fade finishes. Keep draw order stable when a fade reverses.
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (!active) event.changes.forEach { it.consume() }
                }
            }
        }
}

internal fun Modifier.playerQueueViewport(height: Dp): Modifier =
    // The resident host can pass a full-height minimum. Release it before measuring
    // the queue, keep the viewport top-aligned, and clip before translating content.
    wrapContentHeight(align = Alignment.Top, unbounded = true)
        .height(height)
        .clipToBounds()

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
    layerOutsets: LayerOutsets = LayerOutsets.Zero,
): Modifier = graphicsLayer {
    val offset = appleMusicInfoOffset(albumOrigin, headerOrigin, progress.value, fromAlbum)
    translationX = offset.x
    translationY = offset.y
    alpha = opacity.value
    clip = false
    // Keep the leading marquee fade inside the additive layer's offscreen buffer.
    outsets = layerOutsets
    blendMode = BlendMode.Plus
    compositingStrategy = CompositingStrategy.Offscreen
}
