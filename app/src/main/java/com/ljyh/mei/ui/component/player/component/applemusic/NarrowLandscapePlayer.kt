package com.ljyh.mei.ui.component.player.component.applemusic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.Capsule
import com.ljyh.mei.ui.component.player.component.LyricScreen
import com.ljyh.mei.ui.component.player.component.PlayerActionToolbar
import com.ljyh.mei.ui.component.player.component.PlayerControls
import com.ljyh.mei.ui.component.player.component.PlayerProgressSection
import com.ljyh.mei.ui.component.player.overlay.PlayerOverlayHandler
import com.ljyh.mei.ui.component.player.state.PlayerStateContainer
import com.ljyh.mei.ui.component.sheet.LocalPlayerSheet
import com.ljyh.mei.ui.model.MoreAction
import kotlin.math.roundToInt

/** Phone landscape layout with artwork left and portrait detail behavior on the right. */
@Composable
internal fun NarrowLandscapePlayer(
    state: PlayerStateContainer,
    overlay: PlayerOverlayHandler,
    page: AppleMusicPlayerPage,
    active: Boolean,
    onPageSelected: (AppleMusicPlayerPage) -> Unit,
    artwork: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metadata by state.mediaMetadata
    val playing by state.isPlaying
    val playbackState by state.playbackState
    val liked by state.isLiked
    val canLike by state.canLike
    val lyrics by remember { derivedStateOf { state.lyricLine } }
    val sheet = LocalPlayerSheet.current
    SideEffect { sheet?.coverSwipeEnabled = active }
    DisposableEffect(sheet) { onDispose { sheet?.coverSwipeEnabled = false } }
    val expanded by remember(sheet) {
        derivedStateOf { sheet?.state?.isExpanded == true && !sheet.isTransitioning }
    }
    val showDetail = page != AppleMusicPlayerPage.Artwork
    val showLyrics = page == AppleMusicPlayerPage.Lyrics
    val showQueue = page == AppleMusicPlayerPage.Queue
    var lastDetailPage by remember { mutableStateOf(AppleMusicPlayerPage.Lyrics) }
    LaunchedEffect(page) {
        state.controlsVisible = true
        if (showDetail) lastDetailPage = page
    }
    val selectedDetail = if (showDetail) page else lastDetailPage
    val entry = animateFloatAsState(
        targetValue = if (showDetail) 1f else 0f,
        animationSpec = if (expanded) appleMusicLyricSpring() else snap(),
        label = "NarrowDetailEntry",
    )
    val detailAtRest by remember { derivedStateOf { entry.value >= 1f } }
    val detailStarted by remember { derivedStateOf { entry.value > 0f } }
    val detailProgress = { if (expanded) entry.value.coerceIn(0f, 1f) else if (showDetail) 1f else 0f }
    val queueHeaderFraction = remember { Animatable(0f) }
    LaunchedEffect(selectedDetail) {
        val target = if (selectedDetail == AppleMusicPlayerPage.Queue) 1f else 0f
        if (entry.value == 0f) queueHeaderFraction.snapTo(target)
        else queueHeaderFraction.animateTo(target, appleMusicLyricSpring())
    }
    val queueHeaderSettled by remember { derivedStateOf { queueHeaderFraction.value == 1f } }
    val animateQueueEntryScroll by remember {
        derivedStateOf { entry.value > 0f && queueHeaderFraction.value > 0f }
    }
    var queueHeaderPosition by remember { mutableStateOf<State<PlayerQueueHeaderPosition>?>(null) }
    val queueOwnsTitle = showQueue && (!expanded || detailAtRest && queueHeaderSettled)
    val controlsShown = !showLyrics || state.controlsVisible
    val toolbarVisibility = animateFloatAsState(
        targetValue = if (controlsShown) 1f else 0f,
        animationSpec = if (expanded) appleMusicControlsFadeSpring(controlsShown) else snap(),
        label = "NarrowToolbarVisibility",
    )
    val density = LocalDensity.current
    val titleHeight = with(density) { maxOf(42.sp.toDp(), 34.dp) }
    val detailHorizontalInset = AppleMusicDimensions.albumMarqueeEdgeWidth
    var toolbarHeightPx by remember { mutableIntStateOf(0) }
    var titleTextCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var titleRowCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var rightBounds by remember { mutableStateOf<Rect?>(null) }


    val title: @Composable (Modifier) -> Unit = { titleModifier ->
        metadata?.let { track ->
            Title(
                title = track.title,
                subTitle = track.artists.joinToString { it.name },
                isLiked = liked,
                showLikeButton = canLike,
                onLikeClick = { state.playerViewModel.like(track.id.toString()) },
                onMoreClick = overlay::showMoreAction,
                onTitleClick = {
                    overlay.showAlbumArtist(track.album, track.artists, track.coverUrl,
                        anchorBounds = playerMetadataAnchorBounds(titleTextCoordinates, titleRowCoordinates),
                        viewportBounds = rightBounds)
                },
                onTextPositioned = { titleTextCoordinates = it },
                onRowPositioned = { titleRowCoordinates = it },
                titleStyle = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 18.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                subTitleStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 17.sp, lineHeight = 21.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                enabled = active,
                marqueeEnabled = expanded,
                needShadow = false,
                modifier = titleModifier.fillMaxWidth().height(titleHeight),
            )
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val safe = WindowInsets.displayCutout.asPaddingValues()
        val direction = LocalLayoutDirection.current
        val start = maxOf(64.dp, safe.calculateStartPadding(direction))
        val end = maxOf(64.dp, safe.calculateEndPadding(direction))
        val top = maxOf(40.dp, safe.calculateTopPadding())
        val bottom = maxOf(40.dp, safe.calculateBottomPadding())
        val coverSize = minOf(
            (maxHeight - top - bottom).coerceAtLeast(1.dp),
            (maxWidth - start - end - 32.dp).coerceAtLeast(1.dp) * 0.46f,
        )
        artwork(Modifier.padding(start = start, top = top).size(coverSize))
        Box(Modifier.align(Alignment.TopCenter)
            .padding(top = safe.calculateTopPadding() + AppleMusicDimensions.handleTopInset)
            .size(58.dp, AppleMusicDimensions.handleHeight)
            .playerAdditiveBlend()
            .background(Color.White.copy(alpha = 0.3f), Capsule()))

        BoxWithConstraints(Modifier.fillMaxSize().padding(start = start + coverSize + 32.dp, end = end)
            .onGloballyPositioned { rightBounds = it.boundsInWindow() }) {
            val heightPx = constraints.maxHeight.toFloat()
            val topPx = with(density) { top.toPx() }
            val bottomPx = with(density) { bottom.toPx() }
            val titleTop = { topPx + with(density) { 32.dp.toPx() - 24.dp.toPx() * detailProgress() } }
            val titleBottom = { titleTop() + with(density) { titleHeight.toPx() } }
            val toolbarHeight = with(density) { toolbarHeightPx.toDp().coerceAtLeast(40.dp) }
            val obstruction = { (bottomPx + with(density) { toolbarHeight.toPx() }) * toolbarVisibility.value }
            val contentTop = { titleBottom() + with(density) { 16.dp.toPx() } }
            val bodyHeight = (maxHeight - top - 32.dp - titleHeight - 16.dp - bottom - toolbarHeight).coerceAtLeast(1.dp)
            val lyricOffset = (coverSize / 2 - 8.dp - titleHeight - 16.dp).coerceAtLeast(0.dp)

            val metadataHeaderTop = {
                appleMusicInfoOffset(
                    albumOrigin = Offset(0f, topPx + with(density) { 32.dp.toPx() }),
                    headerOrigin = Offset(0f, topPx + with(density) { 8.dp.toPx() } +
                        (queueHeaderPosition?.value?.top ?: 0f) * queueHeaderFraction.value),
                    progress = detailProgress(),
                    fromAlbum = true,
                    placementOrigin = Offset.Zero,
                ).y
            }
            val headerOpacity = {
                val position = queueHeaderPosition?.value ?: PlayerQueueHeaderPosition(0f, 0f)
                val headerHeight = with(density) { titleHeight.toPx() }
                val target = appleMusicQueueArtworkTarget(
                    Rect(0f, titleTop() + position.top, headerHeight, titleTop() + position.top + headerHeight),
                    titleTop(), titleTop() + position.viewportHeight,
                )
                1f - (1f - target.opacity) * queueHeaderFraction.value * detailProgress()
            }


            Box(Modifier.fillMaxWidth().height(bodyHeight)
                .offset { IntOffset(0, (topPx + with(density) { 32.dp.toPx() + titleHeight.toPx() + 16.dp.toPx() }).roundToInt()) }
                .narrowPlayerLayer(opacity = { 1f - detailProgress() }, enabled = active && !showDetail)) {
                Column(Modifier.align(Alignment.Center).offset(y = (-12).dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PlayerProgressSection(
                        position = { state.sliderPosition.toLong() }, duration = { state.duration },
                        isPlaying = playing, playerConnection = state.playerConnection, horizontalPadding = 1.dp,
                        onQualityMenuVisibilityChanged = { state.qualityMenuVisible = it },
                    )
                    PlayerControls(
                        playerConnection = state.playerConnection, canSkipPrevious = state.canSkipPrevious.value,
                        canSkipNext = state.canSkipNext.value, isPlaying = playing, playbackState = playbackState,
                        showSideSpacers = false, evenSpacing = true,
                    )
                }
            }

            val detailTop = titleTop
            val detailTranslation = {
                (heightPx - bottomPx - with(density) { toolbarHeight.toPx() } - contentTop())
                    .coerceAtLeast(0f) * (1f - detailProgress())
            }
            fun controlsFade(viewportHeight: Float, origin: Float): ClosedFloatingPointRange<Float> =
                appleMusicLyricsControlsFadeRange(
                    viewportHeight = viewportHeight,
                    shift = 0.6f * (1f - toolbarVisibility.value),
                    translationY = detailTranslation(),
                    controlsTop = heightPx - bottomPx - with(density) { toolbarHeight.toPx() } - origin,
                )
            val detailFade: (Float) -> ClosedFloatingPointRange<Float> = { controlsFade(it, detailTop()) }
            val lyricFade: (Float) -> ClosedFloatingPointRange<Float> = { controlsFade(it, contentTop()) }
            // Measure the resident viewport to the moving toolbar boundary. Once it is
            // hidden, the viewport reaches the actual screen bottom, including the inset.
            Box(Modifier.fillMaxWidth().offset { IntOffset(0, detailTop().roundToInt()) }
                .layout { measurable, constraints ->
                    val height = (heightPx - detailTop() - obstruction()).roundToInt().coerceAtLeast(1)
                    val inset = detailHorizontalInset.roundToPx()
                    val expanded = constraints.offset(horizontal = inset * 2)
                    val placeable = measurable.measure(expanded.copy(minHeight = height, maxHeight = height))
                    layout(constraints.constrainWidth(placeable.width - inset * 2), placeable.height) {
                        placeable.placeRelative(-inset, 0)
                    }
                }.clipToBounds()) {
                AppleMusicDetailPages(
                    queueSelected = selectedDetail == AppleMusicPlayerPage.Queue,
                    active = active && showDetail,
                    animate = expanded && showDetail && detailAtRest,
                    preloadQueue = active,
                    modifier = Modifier.fillMaxSize().narrowPlayerLayer(
                        opacity = { appleMusicLyricsAlpha(detailProgress()) },
                        enabled = active && showDetail,
                        translationY = detailTranslation,
                    ),
                    lyrics = { selected ->
                        LyricScreen(
                            lyricData = lyrics, playerConnection = state.playerConnection, active = selected,
                            controlsVisible = state.controlsVisible,
                            onToggleControls = { state.controlsVisible = it },
                            controlsAutoHideBlocked = state.qualityMenuVisible,
                            onClick = {}, onLongClick = {}, showSourceBadge = false,
                            synchronizeEntryWithParent = true,
                            topFadeHeight = 20.dp,
                            bottomFadeHeight = AppleMusicDimensions.lyricBottomFade,
                            rendererVerticalPadding = 0.dp,
                            contentPadding = PaddingValues(horizontal = 0.dp),
                            controlsFadeRange = lyricFade,
                            lyricOffset = lyricOffset,
                            mainLyricAnchorFraction = 0f,
                            modifier = Modifier.fillMaxSize().padding(
                                top = titleHeight + 16.dp,
                                start = detailHorizontalInset - AppleMusicDimensions.lyricRendererInset,
                                end = detailHorizontalInset - AppleMusicDimensions.lyricRendererInset,
                            ),
                        )
                    },
                    queue = { selected ->
                        PlayerQueuePage(
                            playerConnection = state.playerConnection, active = selected,
                            selected = showQueue, animateEntryScroll = expanded && animateQueueEntryScroll,
                            onHeaderPositionChanged = { queueHeaderPosition = it },
                            currentHeader = {
                                Spacer(Modifier.fillMaxWidth().height(titleHeight))
                            },
                            controlsFadeRange = detailFade,
                            currentHeaderHeight = titleHeight,
                            horizontalPadding = detailHorizontalInset, contentEndPadding = detailHorizontalInset,
                            bottomContentPadding = 0.dp,
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                )
            }

            // One resident header owns rendering and input above both detail pages.
            // The queue's measured slot supplies its scroll position.
            title(Modifier.offset { IntOffset(0, metadataHeaderTop().roundToInt()) }
                .graphicsLayer {
                    clip = queueOwnsTitle
                    if (clip) {
                        shape = object : Shape {
                            override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
                                val top = (titleTop() - metadataHeaderTop()).coerceIn(0f, size.height)
                                val viewportHeight = queueHeaderPosition?.value?.viewportHeight ?: 0f
                                val bottom = (titleTop() + viewportHeight - metadataHeaderTop()).coerceIn(top, size.height)
                                return Outline.Rectangle(Rect(-with(density) { detailHorizontalInset.toPx() }, top, size.width, bottom))
                            }
                        }
                    }
                }
                .narrowPlayerLayer(opacity = headerOpacity, enabled = active))

            Box(Modifier.fillMaxWidth()
                .offset {
                    IntOffset(0, (heightPx - (bottomPx + with(density) { toolbarHeight.toPx() }) * toolbarVisibility.value).roundToInt())
                }
                .onSizeChanged { toolbarHeightPx = it.height }
                .narrowPlayerLayer(opacity = { toolbarVisibility.value }, enabled = active && controlsShown)) {
                PlayerActionToolbar(
                    modifier = Modifier.playerAdditiveBlend(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    lyricsVisible = showLyrics, queueVisible = showQueue,
                    onLyricClick = { onPageSelected(if (showLyrics) AppleMusicPlayerPage.Artwork else AppleMusicPlayerPage.Lyrics) },
                    onPlaylistClick = { onPageSelected(if (showQueue) AppleMusicPlayerPage.Artwork else AppleMusicPlayerPage.Queue) },
                    onSleepTimerClick = overlay::showSleepTimer,
                    onAddToPlaylistClick = { metadata?.let { overlay.showAddToPlaylist(it) } },
                    onDownloadClick = { overlay.handleMoreAction(MoreAction.DOWNLOAD) },
                    onMoreClick = overlay::showMoreAction,
                )
            }
        }
    }
}

/** Keep fading layers measured, but remove hidden input targets. */
private fun Modifier.narrowPlayerLayer(
    opacity: () -> Float,
    enabled: Boolean,
    translationY: () -> Float = { 0f },
): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        if (opacity() > 0f) placeable.place(0, 0)
    }
}.graphicsLayer {
    alpha = opacity().coerceIn(0f, 1f)
    this.translationY = translationY()
    compositingStrategy = CompositingStrategy.ModulateAlpha
}.pointerInput(enabled) {
    if (!enabled) awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}
