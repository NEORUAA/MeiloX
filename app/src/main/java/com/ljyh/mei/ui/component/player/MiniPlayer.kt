package com.ljyh.mei.ui.component.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import com.ljyh.mei.ui.component.sheet.LocalPlayerSheet
import com.ljyh.mei.ui.component.sheet.recordPlayerContent
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.kyant.backdrop.Backdrop
import com.kyant.capsule.ContinuousRoundedRectangle
import com.kyant.shapes.Capsule
import com.ljyh.mei.R
import com.ljyh.mei.constants.MiniPlayerHeight
import com.ljyh.mei.constants.NavigationBarHeight
import com.ljyh.mei.constants.ThumbnailCornerRadius
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.extensions.togglePlayPause
import com.ljyh.mei.ui.glass.GlassSurface
import com.ljyh.mei.ui.glass.GlassSurfaceStyle
import com.ljyh.mei.ui.glass.LocalGlassBackdrop
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.local.LocalPlayerConnection
import kotlin.math.roundToInt

private fun Modifier.compactMiniPlayerHorizontalPadding(
    compactProgress: State<Float>,
): Modifier = layout { measurable, constraints ->
    val progress = compactProgress.value.coerceIn(0f, 1f)
    val horizontalPadding = (20.dp + 60.dp * progress).roundToPx()
    val totalPadding = horizontalPadding * 2
    val childConstraints = constraints.copy(
        minWidth = (constraints.minWidth - totalPadding).coerceAtLeast(0),
        maxWidth = (constraints.maxWidth - totalPadding).coerceAtLeast(0),
    )
    val placeable = measurable.measure(childConstraints)
    val width = (placeable.width + totalPadding)
        .coerceIn(constraints.minWidth, constraints.maxWidth)
    val height = placeable.height.coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(width, height) {
        placeable.placeRelative(horizontalPadding, 0)
    }
}

private fun Modifier.compactNextButtonWidth(
    compactProgress: State<Float>,
): Modifier = layout { measurable, constraints ->
    val visibility = 1f - compactProgress.value.coerceIn(0f, 1f)
    val width = (40.dp.toPx() * visibility).roundToInt()
        .coerceIn(constraints.minWidth, constraints.maxWidth)
    val placeable = measurable.measure(
        constraints.copy(minWidth = width, maxWidth = width),
    )
    layout(width, placeable.height) {
        placeable.placeRelative(0, 0)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MiniPlayer(
    @Suppress("UNUSED_PARAMETER") position: Long,
    @Suppress("UNUSED_PARAMETER") duration: Long,
    modifier: Modifier = Modifier,
    backdrop: Backdrop = LocalGlassBackdrop.current,
    compactProgress: State<Float>,
    onClick: () -> Unit,
    onCoverBoundsChanged: ((Rect) -> Unit)? = null,
) {
    val sheet = LocalPlayerSheet.current
    val sheetTransitioning by remember(sheet) {
        derivedStateOf { sheet?.state?.isTransitioning == true }
    }
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val playbackState by playerConnection.playbackState.collectAsState()
    val error by playerConnection.error.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val canSkipNext by playerConnection.canSkipNext.collectAsState()
    val showNextButton by remember(compactProgress) {
        derivedStateOf { 1f - compactProgress.value.coerceIn(0f, 1f) > 0.001f }
    }
    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(MiniPlayerHeight)
            .compactMiniPlayerHorizontalPadding(compactProgress)
            .onGloballyPositioned {
                // This modifier is outside GlassSurface's press layer. Avoid boundsInRoot,
                // which can clip the hidden mini player against the moving sheet outline.
                sheet?.miniBounds = Rect(it.localToRoot(Offset.Zero), Size(it.size.width.toFloat(), it.size.height.toFloat()))
            },
        backdrop = backdrop,
        shape = Capsule(),
        onVisualBoundsChanged = { bounds, light -> sheet?.updateMiniPressHighlight(bounds, light) },
        cancelPressFeedback = sheetTransitioning,
        morphProgress = compactProgress,
        morphVerticalTravel = NavigationBarHeight - 16.dp,
        style = GlassSurfaceStyle.Navigation,
        onClick = onClick,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { sheet?.miniLayoutCoordinates = it }
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .onGloballyPositioned {
                    sheet?.miniContentCoordinates = it
                    sheet?.miniLayoutBounds(it)?.let { bounds -> sheet.miniContentBounds = bounds }
                }
                .then(
                    if (sheet != null) {
                        Modifier.recordPlayerContent(sheet, sheet.miniContent) { true }
                    } else Modifier,
                ),
        ) {
            Box(Modifier.weight(1f)) {
                mediaMetadata?.let {
                    MiniMediaInfo(
                        mediaMetadata = it,
                        error = error,
                        onCoverBoundsChanged = onCoverBoundsChanged,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    modifier = Modifier.size(40.dp),
                    onClick = {
                        if (playbackState == Player.STATE_ENDED) {
                            playerConnection.player.seekTo(0, 0)
                            playerConnection.player.playWhenReady = true
                        } else {
                            playerConnection.player.togglePlayPause()
                        }
                    },
                ) {
                    SfIcon(
                        symbol = when {
                            playbackState == Player.STATE_ENDED -> SfSymbol.ArrowClockwise
                            isPlaying -> SfSymbol.PauseFilled
                            else -> SfSymbol.PlayFilled
                        },
                        contentDescription = stringResource(
                            if (isPlaying) R.string.player_pause else R.string.player_play,
                        ),
                        tint = MaterialTheme.colorScheme.onSurface,
                        size = 22.dp,
                        weight = FontWeight.SemiBold,
                    )
                }

                if (showNextButton) {
                    Box(
                        modifier = Modifier
                            .compactNextButtonWidth(compactProgress)
                            .graphicsLayer {
                                val nextVisibility = 1f - compactProgress.value.coerceIn(0f, 1f)
                                alpha = nextVisibility
                                val scale = 0.82f + 0.18f * nextVisibility
                                scaleX = scale
                                scaleY = scale
                                clip = true
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        IconButton(
                            modifier = Modifier.size(40.dp),
                            enabled = canSkipNext,
                            onClick = playerConnection::seekToNext,
                        ) {
                            SfIcon(
                                symbol = SfSymbol.ForwardFilled,
                                contentDescription = stringResource(R.string.player_next),
                                tint = MaterialTheme.colorScheme.onSurface,
                                size = 22.dp,
                                weight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MiniMediaInfo(
    mediaMetadata: MediaMetadata,
    error: PlaybackException?,
    modifier: Modifier = Modifier,
    onCoverBoundsChanged: ((Rect) -> Unit)? = null,
) {
    val sheet = LocalPlayerSheet.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Box(modifier = Modifier.padding(4.dp)) {
            Spacer(modifier = Modifier.size(32.dp))
            val artwork = LocalPlayerArtwork.current
            AnimatedContent(
                targetState = artwork,
                transitionSpec = {
                    (fadeIn(tween(400)) + scaleIn(initialScale = 0.92f, animationSpec = tween(400)))
                        .togetherWith(fadeOut(tween(400)))
                },
                label = "MiniCoverTransition",
                modifier = Modifier.size(32.dp)
                    .onGloballyPositioned { coordinates ->
                        val bounds = coordinates.boundsInRoot()
                        sheet?.miniArtworkCoordinates = coordinates
                        sheet?.miniLayoutBounds(coordinates)?.let { layoutBounds ->
                            sheet.miniArtworkBounds = layoutBounds
                        }
                        onCoverBoundsChanged?.invoke(bounds)
                    }
                    .drawWithContent { if (sheet?.drawsArtworkOverlay != true) drawContent() }
                    .clip(ContinuousRoundedRectangle(ThumbnailCornerRadius)),
            ) { cover ->
                if (cover != null) Image(
                    painter = cover.second,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = error != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = ContinuousRoundedRectangle(ThumbnailCornerRadius),
                        ),
                ) {
                    SfIcon(
                        symbol = SfSymbol.Warning,
                        contentDescription = stringResource(R.string.player_playback_error),
                        tint = MaterialTheme.colorScheme.error,
                        size = 22.dp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp),
        ) {
            Text(
                text = mediaMetadata.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(),
            )
            Text(
                text = mediaMetadata.artists.joinToString { it.name },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(),
            )
        }
    }
}
