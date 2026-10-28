package com.ljyh.mei.ui.component.player.component.classic

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.Capsule
import com.ljyh.mei.constants.PlayerHorizontalPadding
import com.ljyh.mei.constants.PlayerStyle
import com.ljyh.mei.constants.PlayerStyleKey
import com.ljyh.mei.constants.ProgressBarStyle
import com.ljyh.mei.constants.ProgressBarStyleKey
import com.ljyh.mei.constants.TabletAnimationStyle
import com.ljyh.mei.constants.TabletAnimationStyleKey
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.ui.component.player.component.FluidProgressSlider
import com.ljyh.mei.ui.component.player.component.LyricScreen
import com.ljyh.mei.ui.component.player.component.PlayerActionToolbar
import com.ljyh.mei.ui.component.player.component.PlayerControls
import com.ljyh.mei.ui.component.player.component.PlayerProgressSlider
import com.ljyh.mei.ui.component.player.component.PlayerProgressSection
import com.ljyh.mei.ui.component.player.component.PlayerTableControls
import com.ljyh.mei.ui.component.player.component.applemusic.AppleMusicDimensions
import com.ljyh.mei.ui.component.player.component.applemusic.Title
import com.ljyh.mei.ui.component.player.component.applemusic.playerAdditiveBlend
import com.ljyh.mei.ui.component.player.component.applemusic.playerMetadataAnchorBounds
import com.ljyh.mei.ui.component.player.component.classic.component.Cover
import com.ljyh.mei.ui.component.player.component.classic.component.PlayerHeader
import com.ljyh.mei.ui.component.player.component.sheet.PlaylistContent
import com.ljyh.mei.ui.component.player.overlay.PlayerOverlayHandler
import com.ljyh.mei.ui.component.player.state.PlayerStateContainer
import com.ljyh.mei.ui.component.sheet.LocalPlayerSheet
import com.ljyh.mei.utils.rememberEnumPreference

@Composable
fun ClassicTabletLayout(
    stateContainer: PlayerStateContainer,
    overlayHandler: PlayerOverlayHandler
) {
    var controlPaneBounds by remember { mutableStateOf<Rect?>(null) }
    var headerTextCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var headerRowCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val mediaMetadata by stateContainer.mediaMetadata
    val isPlaying by stateContainer.isPlaying
    val playbackState by stateContainer.playbackState
    val sliderPosition by remember { derivedStateOf { stateContainer.sliderPosition } }
    val duration by remember { derivedStateOf { stateContainer.duration } }
    val lyricLine by remember { derivedStateOf { stateContainer.lyricLine } }
    val isLiked by stateContainer.isLiked
    val playerStyle by rememberEnumPreference(PlayerStyleKey, PlayerStyle.AppleMusic)

    var isShowingPlaylist by remember { mutableStateOf(false) }
    var footerHeightPx by remember { mutableStateOf(0) }
    var albumActionsHeightPx by remember { mutableStateOf(0) }
    val appleMusicStyle = playerStyle == PlayerStyle.AppleMusic
    val sheet = LocalPlayerSheet.current
    val sheetExpanded by remember(sheet) { derivedStateOf { sheet?.state?.isExpanded != false } }

    val (progressBarStyle, _) = rememberEnumPreference(
        key = ProgressBarStyleKey,
        defaultValue = ProgressBarStyle.LINEAR
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val safePadding = WindowInsets.displayCutout.asPaddingValues()
        val lyricStart = maxWidth / 2 - 58.dp / 2
        val footerHeight = with(density) { footerHeightPx.toDp() }.takeIf { it > 0.dp } ?: 220.dp
        // The same margin anchors all four visible edges, including the lyric text.
        val tabletMargin = maxOf(
            maxWidth / 16,
            32.dp,
            safePadding.calculateTopPadding(),
            safePadding.calculateStartPadding(layoutDirection),
            safePadding.calculateEndPadding(layoutDirection),
            lyricStart + footerHeight + 24.dp - maxHeight,
        ).coerceAtMost(lyricStart / 2)
        val paneWidth = if (appleMusicStyle) {
            (lyricStart - tabletMargin * 2).coerceAtLeast(0.dp)
        } else ((maxWidth - 96.dp) / 2).coerceAtLeast(0.dp)
        val paneWidthPx = with(density) { paneWidth.roundToPx() }
        val lyricVerticalPadding = maxHeight * 0.025f
        val paneCenterFraction = paneWidth / (paneWidth + 32.dp) / 2f
        val lyricTransformOrigin = TransformOrigin(
            if (layoutDirection == LayoutDirection.Ltr) paneCenterFraction else 1f - paneCenterFraction,
            0.5f,
        )

        if (appleMusicStyle) {
            Box(
                Modifier.align(Alignment.TopCenter)
                    .padding(top = safePadding.calculateTopPadding() + AppleMusicDimensions.handleTopInset)
                    .size(width = 58.dp, height = 4.dp)
                    .playerAdditiveBlend()
                    .background(Color.White.copy(alpha = 0.3f), Capsule()),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = if (appleMusicStyle) tabletMargin else 32.dp)
        ){

            Column(
                modifier = Modifier
                    .width(paneWidth)
                    .fillMaxHeight()
                    .onGloballyPositioned { controlPaneBounds = it.boundsInWindow() }
                    .padding(top = if (appleMusicStyle) tabletMargin else 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (appleMusicStyle) Arrangement.Top else Arrangement.Center
            ) {

                mediaMetadata?.let {
                    Cover(
                        playerConnection = stateContainer.playerConnection,
                        mediaMetadata = it,
                        isPlaying = isPlaying,
                        modifier = if (appleMusicStyle) Modifier.size(paneWidth) else Modifier
                            .weight(1f, fill = false)
                            .widthIn(max = paneWidth * 0.6f)
                            .aspectRatio(1f)
                    )
                }



                Column(
                    modifier = if (appleMusicStyle) Modifier.fillMaxWidth()
                        .wrapContentHeight(unbounded = true)
                        .onSizeChanged { footerHeightPx = it.height }
                    else Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(if (appleMusicStyle) 36.dp else 16.dp))
                    mediaMetadata?.let {
                        if (appleMusicStyle) {
                            val albumTitleStyle = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 18.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold,
                            )
                            val albumArtistStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal,
                            )
                            // Match portrait: fallback glyph metrics must not resize the row.
                            val albumInfoHeight = with(density) {
                                maxOf(
                                    albumTitleStyle.lineHeight.toDp() + albumArtistStyle.lineHeight.toDp(),
                                    albumActionsHeightPx.toDp(),
                                )
                            }
                            Box(
                                modifier = Modifier.fillMaxWidth().height(albumInfoHeight),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Title(
                                    title = it.title,
                                    subTitle = it.artists.joinToString { artist -> artist.name },
                                    isLiked = isLiked,
                                    showLikeButton = !it.isLocal && !it.isPodcast,
                                    onLikeClick = { stateContainer.playerViewModel.like(it.id.toString()) },
                                    onMoreClick = { overlayHandler.showMoreAction() },
                                    onTitleClick = {
                                        overlayHandler.showAlbumArtist(
                                            it.album, it.artists, it.coverUrl,
                                            anchorBounds = playerMetadataAnchorBounds(headerTextCoordinates, headerRowCoordinates),
                                            viewportBounds = controlPaneBounds,
                                        )
                                    },
                                    titleStyle = albumTitleStyle,
                                    subTitleStyle = albumArtistStyle,
                                    onActionsSizeChanged = { albumActionsHeightPx = it.height },
                                    needShadow = false,
                                    enabled = sheetExpanded,
                                    marqueeEnabled = sheetExpanded,
                                    onTextPositioned = { headerTextCoordinates = it },
                                    onRowPositioned = { headerRowCoordinates = it },
                                    modifier = Modifier.fillMaxWidth().wrapContentHeight(unbounded = true),
                                )
                            }
                        } else {
                            PlayerHeader(
                                mediaMetadata = it,
                                applyStatusBarPadding = false,
                                modifier = Modifier
                                    .fillMaxWidth(0.7f)
                                    .padding(horizontal = PlayerHorizontalPadding),
                                onClick = {
                                    overlayHandler.showAlbumArtist(
                                        it.album,
                                        it.artists,
                                        it.coverUrl,
                                        anchorBounds = playerMetadataAnchorBounds(headerTextCoordinates, headerRowCoordinates),
                                        viewportBounds = controlPaneBounds,
                                    )
                                },
                                onTextPositioned = { headerTextCoordinates = it },
                                onRowPositioned = { headerRowCoordinates = it },
                                onMoreClick = {
                                    overlayHandler.showMoreAction()
                                },
                                isLiked = isLiked,
                                onLikeClick = {
                                    stateContainer.playerViewModel.like(it.id.toString())
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(if (appleMusicStyle) 20.dp else 32.dp))


                    if (appleMusicStyle) {
                        PlayerProgressSection(
                            position = { stateContainer.sliderPosition.toLong() },
                            duration = { stateContainer.duration },
                            isPlaying = isPlaying,
                            playerConnection = stateContainer.playerConnection,
                            horizontalPadding = 0.dp,
                        )
                    } else if (progressBarStyle == ProgressBarStyle.LINEAR) {
                        FluidProgressSlider(
                            position = sliderPosition.toLong(),
                            duration = duration,
                            onPositionChange = { newPosition ->
                                stateContainer.playerConnection.player.seekTo(newPosition)
                            },
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .padding(horizontal = PlayerHorizontalPadding + 8.dp)
                        )
                    } else {
                        PlayerProgressSlider(
                            position = sliderPosition.toLong(),
                            duration = duration,
                            isPlaying = isPlaying, // 波浪进度条需要这个参数
                            onPositionChange = { newPosition ->
                                stateContainer.playerConnection.player.seekTo(newPosition)
                            },
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .padding(horizontal = PlayerHorizontalPadding + 8.dp)
                        )
                    }




                    Spacer(Modifier.height(16.dp))


                    PlayerTableControls(
                        playerConnection = stateContainer.playerConnection,
                        canSkipPrevious = stateContainer.canSkipPrevious.value,
                        canSkipNext = stateContainer.canSkipNext.value,
                        isPlaying = isPlaying,
                        playbackState = playbackState,
                        modifier = Modifier.fillMaxWidth(if (appleMusicStyle) 1f else 0.7f),
                        onPlaylistClick = {
                            isShowingPlaylist = !isShowingPlaylist
                        },
                        queueVisible = isShowingPlaylist,
                    )

                }
            }

            Spacer(Modifier.width(if (appleMusicStyle) tabletMargin - AppleMusicDimensions.lyricRendererInset else 32.dp))

            val tabletAnimStyle by rememberEnumPreference(
                key = TabletAnimationStyleKey,
                defaultValue = TabletAnimationStyle.FLIP_3D
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                val lyricContent = @Composable {
                    LyricScreen(
                        lyricData = lyricLine,
                        showSourceBadge = false,
                        topFadeHeight = if (playerStyle == PlayerStyle.AppleMusic) AppleMusicDimensions.lyricTopFade else 20.dp,
                        bottomFadeHeight = if (playerStyle == PlayerStyle.AppleMusic) AppleMusicDimensions.lyricBottomFade else 100.dp,
                        rendererVerticalPadding = if (playerStyle == PlayerStyle.AppleMusic) 0.dp else 8.dp,
                        lyricOffset = if (appleMusicStyle) tabletMargin + paneWidth / 2 else 48.dp,
                        playerConnection = stateContainer.playerConnection,
                        contentPadding = PaddingValues(
                            start = if (appleMusicStyle) 0.dp else PlayerHorizontalPadding,
                            end = if (appleMusicStyle) tabletMargin - AppleMusicDimensions.lyricRendererInset else PlayerHorizontalPadding + 32.dp,
                            top = if (playerStyle == PlayerStyle.AppleMusic) 0.dp else lyricVerticalPadding,
                            bottom = if (playerStyle == PlayerStyle.AppleMusic) 0.dp else lyricVerticalPadding,
                        ),
                        modifier = Modifier.fillMaxSize(),
                        onClick = {},
                        onLongClick = {},
                        controlsVisible = stateContainer.controlsVisible,
                        onToggleControls = {},
                    )
                }

                val playlistContent = @Composable {
                    PlaylistContent(
                        onDismiss = { isShowingPlaylist = false },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = PlayerHorizontalPadding)
                    )
                }

                val playlistModifier = Modifier
                    .width(paneWidth)
                    .fillMaxHeight(0.95f)
                    .align(Alignment.CenterStart)

                when (tabletAnimStyle) {
                    TabletAnimationStyle.FLIP_3D -> {
                        val rotation by animateFloatAsState(
                            targetValue = if (isShowingPlaylist) 180f else 0f,
                            animationSpec = tween(600, easing = FastOutSlowInEasing)
                        )
                        val density = LocalDensity.current

                        if (rotation < 90f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        rotationY = rotation
                                        transformOrigin = lyricTransformOrigin
                                        cameraDistance = 12f * density.density
                                    }
                            ) {
                                lyricContent()
                            }
                        }

                        if (rotation > 90f) {
                            Box(
                                modifier = playlistModifier
                                    .graphicsLayer {
                                        rotationY = rotation - 180f
                                        cameraDistance = 12f * density.density
                                    }
                            ) {
                                playlistContent()
                            }
                        }
                    }

                    TabletAnimationStyle.SLIDE -> {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !isShowingPlaylist,
                            enter = slideInHorizontally { -paneWidthPx } + fadeIn(),
                            exit = slideOutHorizontally { -paneWidthPx } + fadeOut(),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            lyricContent()
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            visible = isShowingPlaylist,
                            enter = slideInHorizontally { it } + fadeIn(),
                            exit = slideOutHorizontally { it } + fadeOut(),
                            modifier = playlistModifier
                        ) {
                            playlistContent()
                        }
                    }

                    TabletAnimationStyle.CROSSFADE -> {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !isShowingPlaylist,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            lyricContent()
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            visible = isShowingPlaylist,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = playlistModifier
                        ) {
                            playlistContent()
                        }
                    }

                    TabletAnimationStyle.ZOOM -> {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !isShowingPlaylist,
                            enter = scaleIn(initialScale = 0.92f, transformOrigin = lyricTransformOrigin) + fadeIn(),
                            exit = scaleOut(targetScale = 0.92f, transformOrigin = lyricTransformOrigin) + fadeOut(),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            lyricContent()
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            visible = isShowingPlaylist,
                            enter = scaleIn(initialScale = 0.92f) + fadeIn(),
                            exit = scaleOut(targetScale = 0.92f) + fadeOut(),
                            modifier = playlistModifier
                        ) {
                            playlistContent()
                        }
                    }
                }
            }
        }
    }
}
