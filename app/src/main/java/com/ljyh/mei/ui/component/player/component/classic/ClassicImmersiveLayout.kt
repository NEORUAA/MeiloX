package com.ljyh.mei.ui.component.player.component.classic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.Capsule
import com.ljyh.mei.constants.PlayerStyle
import com.ljyh.mei.constants.PlayerStyleKey
import com.ljyh.mei.ui.component.player.component.LyricScreen
import com.ljyh.mei.ui.component.player.component.applemusic.AppleMusicDimensions
import com.ljyh.mei.ui.component.player.component.applemusic.Title
import com.ljyh.mei.ui.component.player.component.applemusic.playerAdditiveBlend
import com.ljyh.mei.ui.component.player.component.applemusic.playerMetadataAnchorBounds
import com.ljyh.mei.ui.component.player.component.classic.component.Cover
import com.ljyh.mei.ui.component.player.component.classic.component.PlayerHeader
import com.ljyh.mei.ui.component.player.overlay.PlayerOverlayHandler
import com.ljyh.mei.ui.component.player.state.PlayerStateContainer
import com.ljyh.mei.ui.component.sheet.LocalPlayerSheet
import com.ljyh.mei.utils.rememberEnumPreference

@Composable
fun ClassicImmersiveLayout(
    stateContainer: PlayerStateContainer,
    overlayHandler: PlayerOverlayHandler
) {
    val mediaMetadata by stateContainer.mediaMetadata
    val isPlaying by stateContainer.isPlaying
    val lyricLine by remember { derivedStateOf { stateContainer.lyricLine } }
    val isLiked by stateContainer.isLiked
    val playerStyle by rememberEnumPreference(PlayerStyleKey, PlayerStyle.AppleMusic)
    val sheet = LocalPlayerSheet.current
    val sheetExpanded by remember(sheet) { derivedStateOf { sheet?.state?.isExpanded != false } }
    var textCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var rowCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layoutDirection = LocalLayoutDirection.current
        // Transient system bars overlay the player without changing its geometry.
        val safePadding = WindowInsets.displayCutout.asPaddingValues()
        val startInset = maxOf(32.dp, safePadding.calculateStartPadding(layoutDirection))
        val topSafeInset = safePadding.calculateTopPadding()
        val contentTop = maxOf(topSafeInset + 42.dp, maxHeight * 0.105f)
        // Anchor the artwork's trailing edge to the centered 58dp handle.
        val coverEnd = maxWidth / 2 - 58.dp / 2 - 6.dp
        val coverPaneWidth = (coverEnd - startInset).coerceAtLeast(0.dp)
        val metadataInset = AppleMusicDimensions.albumMarqueeEdgeWidth
        val lyricInset = metadataInset - AppleMusicDimensions.lyricRendererInset
        val coverSize = minOf(coverPaneWidth, (maxHeight - contentTop * 2).coerceAtLeast(0.dp))
        val contentEndInset = coverEnd - coverSize

        if (playerStyle == PlayerStyle.AppleMusic) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topSafeInset + AppleMusicDimensions.handleTopInset)
                    .size(width = 58.dp, height = 4.dp)
                    .playerAdditiveBlend()
                    .background(Color.White.copy(alpha = 0.3f), Capsule()),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = startInset, top = contentTop)
        ) {
            Box(
                modifier = Modifier
                    .width(coverPaneWidth)
                    .fillMaxHeight()
                    .padding(bottom = contentTop),
                contentAlignment = Alignment.CenterEnd,
            ) {
                mediaMetadata?.let {
                    Cover(
                        playerConnection = stateContainer.playerConnection,
                        mediaMetadata = it,
                        isPlaying = isPlaying,
                        modifier = Modifier.size(coverSize),
                        onDoubleClick = { offset, width ->

                            val player = stateContainer.playerConnection.player
                            val third = width / 3f

                            when {
                                offset.x < third -> {
                                    // Left third: previous track.
                                    if (player.hasPreviousMediaItem()) {
                                        player.seekToPreviousMediaItem()
                                    }
                                }

                                offset.x < third * 2 -> {
                                    player.playWhenReady = !player.playWhenReady
                                }

                                else -> {
                                    // Right third: next track.
                                    if (player.hasNextMediaItem()) {
                                        player.seekToNextMediaItem()
                                    }
                                }
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f).fillMaxHeight()) {
                mediaMetadata?.let { metadata ->
                    val headerModifier = Modifier
                        .fillMaxWidth()
                        .padding(start = metadataInset, end = contentEndInset)
                    val onTitleClick = {
                        overlayHandler.showAlbumArtist(
                            metadata.album,
                            metadata.artists,
                            metadata.coverUrl,
                            anchorBounds = playerMetadataAnchorBounds(textCoordinates, rowCoordinates),
                        )
                    }
                    if (playerStyle == PlayerStyle.AppleMusic) {
                        Title(
                            title = metadata.title,
                            subTitle = metadata.artists.joinToString { it.name },
                            isLiked = isLiked,
                            showLikeButton = !metadata.isLocal && !metadata.isPodcast,
                            onLikeClick = { stateContainer.playerViewModel.like(metadata.id.toString()) },
                            onMoreClick = { overlayHandler.showMoreAction() },
                            onTitleClick = onTitleClick,
                            titleStyle = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 18.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold,
                            ),
                            subTitleStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal,
                            ),
                            marqueeEdgeWidth = metadataInset,
                            needShadow = false,
                            enabled = sheetExpanded,
                            marqueeEnabled = sheetExpanded,
                            onTextPositioned = { textCoordinates = it },
                            onRowPositioned = { rowCoordinates = it },
                            modifier = headerModifier,
                        )
                    } else {
                        PlayerHeader(
                            modifier = headerModifier,
                            mediaMetadata = metadata,
                            applyStatusBarPadding = false,
                            isLiked = isLiked,
                            onClick = onTitleClick,
                            onMoreClick = { overlayHandler.showMoreAction() },
                            onLikeClick = { stateContainer.playerViewModel.like(metadata.id.toString()) },
                            onTextPositioned = { textCoordinates = it },
                            onRowPositioned = { rowCoordinates = it },
                        )
                    }
                }

                LyricScreen(
                    lyricData = lyricLine,
                    playerConnection = stateContainer.playerConnection,
                    contentPadding = PaddingValues(
                        start = lyricInset,
                        end = (contentEndInset - AppleMusicDimensions.lyricRendererInset).coerceAtLeast(0.dp),
                    ),
                    showSourceBadge = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    onClick = {},
                    onLongClick = {},
                    controlsVisible = stateContainer.controlsVisible,
                    onToggleControls = {},
                )
            }
        }
    }
}
