package com.ljyh.mei.ui.component.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.kyant.capsule.ContinuousRoundedRectangle
import com.kyant.shapes.Capsule
import com.ljyh.mei.R
import com.ljyh.mei.constants.LoopPlaybackKey
import com.ljyh.mei.utils.rememberPreference
import com.ljyh.mei.constants.LandscapeMiniPlayerHeight
import com.ljyh.mei.extensions.togglePlayPause
import com.ljyh.mei.ui.component.player.component.applemusic.AppleMusicSkipIcon
import com.ljyh.mei.ui.component.player.component.applemusic.AppleMusicMarqueeText
import com.ljyh.mei.ui.component.player.component.applemusic.AppleMusicPlayPauseMotionContent
import com.ljyh.mei.ui.component.player.component.PlayerTransportButton
import com.ljyh.mei.ui.component.sheet.LocalPlayerSheet
import com.ljyh.mei.ui.component.sheet.recordPlayerContent
import com.ljyh.mei.ui.local.LocalPlayerConnection
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.glass.GlassSurface
import io.github.neoruaa.glasmio.glass.GlassSurfaceStyle
import io.github.neoruaa.glasmio.glass.render.Backdrop
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun LandscapeMiniPlayer(
    modifier: Modifier,
    backdrop: Backdrop,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
    onLyricsClick: () -> Unit,
    onQueueClick: () -> Unit,
    onCoverBoundsChanged: ((Rect) -> Unit)? = null,
    gestureModifier: Modifier = Modifier,
) {
    val connection = LocalPlayerConnection.current ?: return
    val sheet = LocalPlayerSheet.current
    SideEffect { sheet?.miniArtworkCornerRadius = 6.dp }
    val colors = LocalGlassColors.current
    val metadata by connection.mediaMetadata.collectAsState()
    val playing by connection.isPlaying.collectAsState()
    val playbackState by connection.playbackState.collectAsState()
    val error by connection.error.collectAsState()
    val previous by connection.canSkipPrevious.collectAsState()
    val next by connection.canSkipNext.collectAsState()
    var previousClicks by remember(connection) { mutableIntStateOf(0) }
    var nextClicks by remember(connection) { mutableIntStateOf(0) }
    val shuffle by connection.shuffleModeEnabled.collectAsState()
    val fmEnabled by connection.isFMMode.collectAsState()
    val autoplayEnabled by connection.queueAutoplayEnabled.collectAsState()
    val (_, setLoopPlayback) = rememberPreference(LoopPlaybackKey, true)
    var repeat by remember(connection) { mutableIntStateOf(connection.player.repeatMode) }
    DisposableEffect(connection) {
        val listener = object : Player.Listener {
            override fun onRepeatModeChanged(repeatMode: Int) {
                repeat = repeatMode
            }
        }
        connection.player.addListener(listener)
        onDispose { connection.player.removeListener(listener) }
    }
    val stringPrevious = stringResource(R.string.pip_previous)
    val stringNext = stringResource(R.string.player_next)
    val playPauseDescription = stringResource(if (playing) R.string.player_pause else R.string.player_play)
    val artwork = LocalPlayerArtwork.current
    val transitioning by remember(sheet) { derivedStateOf { sheet?.isTransitioning == true } }
    Box(
        modifier.widthIn(max = 768.dp).fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().height(LandscapeMiniPlayerHeight)
                .then(gestureModifier)
                .testTag("MeiloXLandscapeMiniPlayer")
                .onGloballyPositioned {
                    sheet?.miniBounds = Rect(it.localToRoot(Offset.Zero),
                        it.localToRoot(Offset(it.size.width.toFloat(), it.size.height.toFloat())))
                },
            backdrop = backdrop,
            shape = Capsule(),
            style = GlassSurfaceStyle.Navigation,
            onVisualBoundsChanged = { bounds, light -> sheet?.updateMiniPressHighlight(bounds, light) },
            cancelPressFeedback = transitioning,
            pressHighlight = remember(sheet) { { sheet?.collapsedPressHighlight } },
            onClick = {
                sheet?.prepareTransition()
                sheet?.releasePressHighlight()
                onClick()
            },
        ) {
            Row(
                Modifier.fillMaxSize()
                    .onGloballyPositioned { sheet?.miniLayoutCoordinates = it }
                    .padding(start = 12.dp, end = 20.dp)
                    .onGloballyPositioned {
                        sheet?.miniContentCoordinates = it
                        sheet?.miniLayoutBounds(it)?.let { bounds -> sheet.miniContentBounds = bounds }
                    }
                    .then(if (sheet != null) Modifier.recordPlayerContent(sheet, sheet.miniContent,
                        warmWhileVisible = true) { !sheet.isTransitioning } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LandscapePlayerButton("shuffle", stringResource(R.string.notification_mode_shuffle),
                    iconSize = 20.dp, selected = shuffle, toggle = true, enabled = !fmEnabled || autoplayEnabled,
                    onClick = { connection.service.setShuffleModeEnabled(!shuffle) })
                PlayerTransportButton(
                    appleMusicStyle = true,
                    onClick = { previousClicks++; connection.seekToPrevious() },
                    enabled = previous,
                    modifier = Modifier.size(44.dp).semantics {
                        contentDescription = stringPrevious
                    },
                ) {
                    AppleMusicSkipIcon(forward = false, clickCount = previousClicks, size = 30.dp,
                        tint = colors.content.copy(alpha = if (previous) 1f else .3f),
                        weight = FontWeight.SemiBold)
                }
                PlayerTransportButton(
                    appleMusicStyle = true,
                    onClick = {
                        if (playbackState == Player.STATE_ENDED) {
                            connection.player.seekTo(0, 0)
                            connection.player.playWhenReady = true
                        } else connection.player.togglePlayPause()
                    },
                    modifier = Modifier.size(44.dp).semantics {
                        contentDescription = playPauseDescription
                    },
                ) {
                    AppleMusicPlayPauseMotionContent(if (playing) "pause.fill" else "play.fill",
                        Modifier.size(32.dp)) { symbol ->
                        SfIcon(symbol, null, tint = colors.content, size = 32.dp, weight = FontWeight.Bold,
                            modifier = Modifier.absoluteOffset(x = if (symbol == "play.fill") 2.dp else 0.dp))
                    }
                }
                PlayerTransportButton(
                    appleMusicStyle = true,
                    onClick = { nextClicks++; connection.seekToNext() },
                    enabled = next,
                    modifier = Modifier.size(44.dp).semantics {
                        contentDescription = stringNext
                    },
                ) {
                    AppleMusicSkipIcon(forward = true, clickCount = nextClicks, size = 30.dp,
                        tint = colors.content.copy(alpha = if (next) 1f else .3f),
                        weight = FontWeight.SemiBold)
                }
                LandscapePlayerButton(if (repeat == Player.REPEAT_MODE_ONE) "repeat.1" else "repeat",
                    stringResource(R.string.loop_playback), iconSize = 20.dp, selected = repeat != Player.REPEAT_MODE_OFF, toggle = true,
                    onClick = {
                        val nextMode = when (repeat) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                        connection.player.repeatMode = nextMode
                        setLoopPlayback(nextMode != Player.REPEAT_MODE_OFF)
                    })
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(46.dp)
                    .onGloballyPositioned { coordinates ->
                        sheet?.miniArtworkCoordinates = coordinates
                        sheet?.miniLayoutBounds(coordinates)?.let { bounds ->
                            sheet.miniArtworkBounds = bounds
                        }
                        onCoverBoundsChanged?.invoke(coordinates.boundsInRoot())
                    }
                    .drawWithContent { if (sheet?.sharesArtwork != true) drawContent() }
                    .clip(ContinuousRoundedRectangle(6.dp))
                    .background(colors.content.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                    if (artwork != null) Image(artwork.second, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().testTag("LandscapeMiniArtwork"))
                    else SfIcon("music.note", null, tint = colors.secondaryContent, size = 24.dp)
                    if (error != null) Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        SfIcon(SfSymbol.Warning, stringResource(R.string.player_playback_error),
                            tint = androidx.compose.material3.MaterialTheme.colorScheme.error, size = 24.dp)
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.Center) {
                    val track = metadata
                    val titleFont = remember {
                        FontFamily(Font(R.font.sf_pro, weight = FontWeight.Medium,
                            variationSettings = FontVariation.Settings(FontVariation.weight(500))))
                    }
                    val titleStyle = IosTypography.body.copy(
                        fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = titleFont,
                    )
                    val artistStyle = IosTypography.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Normal)
                    if (track != null) MiniPlayerTrackSwipe(connection, track) { visibleTrack, allowMarquee ->
                        AppleMusicMarqueeText(
                            text = visibleTrack.title, style = titleStyle,
                            color = colors.content, edgeWidth = if (allowMarquee) 8.dp else 0.dp,
                            enabled = allowMarquee && sheet?.state?.isCollapsed != false,
                        )
                        val artists = visibleTrack.artists.joinToString { it.name }
                        if (artists.isNotBlank()) AppleMusicMarqueeText(
                            text = artists, style = artistStyle,
                            color = colors.content, edgeWidth = if (allowMarquee) 8.dp else 0.dp,
                            enabled = allowMarquee && sheet?.state?.isCollapsed != false,
                        )
                    } else Text(stringResource(R.string.landscape_player_not_playing), style = titleStyle,
                        color = colors.secondaryContent, maxLines = 1)
                }
                Row(Modifier
                    .onGloballyPositioned { coordinates ->
                        sheet?.miniTrailingCoordinates = coordinates
                        sheet?.miniLayoutBounds(coordinates)?.let { sheet.miniTrailingBounds = it }
                    }
                    .then(if (sheet != null) Modifier.recordPlayerContent(
                        sheet, sheet.miniTrailingContent, warmWhileVisible = true,
                    ) { !sheet.isTransitioning } else Modifier),
                ) {
                    LandscapePlayerButton("ellipsis", stringResource(R.string.more_actions_title),
                        targetSize = 48.dp, iconSize = 24.dp, onClick = onMoreClick)
                    LandscapePlayerButton("quote.bubble", stringResource(R.string.player_action_lyrics),
                        targetSize = 48.dp, iconSize = 24.dp, onClick = onLyricsClick)
                    LandscapePlayerButton("list.bullet", stringResource(R.string.player_action_queue),
                        targetSize = 48.dp, iconSize = 24.dp, onClick = onQueueClick)
                }
            }
        }
    }
}

@Composable
private fun LandscapePlayerButton(
    symbol: String,
    description: String,
    targetSize: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    enabled: Boolean = true,
    selected: Boolean = false,
    toggle: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalGlassColors.current
    PlayerTransportButton(appleMusicStyle = true, onClick = onClick, enabled = enabled,
        modifier = Modifier.size(targetSize).semantics {
        contentDescription = description
        this.selected = selected
    }) {
        SfIcon(symbol, null, tint = colors.content
            .copy(alpha = when {
                !enabled -> 0.3f
                toggle && !selected -> 0.5f
                else -> 1f
            }), size = iconSize, weight = FontWeight.SemiBold)
    }
}
