package com.ljyh.mei.ui.component.player.component

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.EaseOutQuad
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.common.util.UnstableApi
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.extensions.togglePlayPause
import com.ljyh.mei.constants.PlayerStyle
import com.ljyh.mei.constants.PlayerStyleKey
import com.ljyh.mei.ui.component.player.component.applemusic.AppleMusicPlayPauseIcon
import com.ljyh.mei.ui.component.player.component.applemusic.AppleMusicSkipIcon
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.utils.rememberEnumPreference
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PlayerControls(
    modifier: Modifier = Modifier,
    playerConnection: PlayerConnection,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    isPlaying: Boolean,
    playbackState: Int,
){
    val playerStyle by rememberEnumPreference(PlayerStyleKey, PlayerStyle.AppleMusic)
    val appleMusicStyle = playerStyle == PlayerStyle.AppleMusic
    val skipTargetSize = if (appleMusicStyle) 64.dp else 48.dp
    val playTargetSize = if (appleMusicStyle) 64.dp else 84.dp
    val skipIconSize = if (appleMusicStyle) 44.dp else 32.dp
    val playIconSize = if (appleMusicStyle) 44.dp else 48.dp
    Box(modifier = modifier){
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (appleMusicStyle) Modifier.height(84.dp) else Modifier)
        ) {
            if (appleMusicStyle) Spacer(Modifier.weight(0.4f))

            //previous
            Box(modifier = Modifier.weight(1f)) {
                PlayerTransportButton(
                    appleMusicStyle = appleMusicStyle,
                    enabled = canSkipPrevious,
                    onClick = playerConnection::seekToPrevious,
                    pressOffsetX = 2.dp,
                    modifier = Modifier
                        .size(skipTargetSize)
                        .align(Alignment.Center)
                ) { clickCount ->
                    if (appleMusicStyle) {
                        AppleMusicSkipIcon(forward = false, clickCount = clickCount)
                    } else SfIcon(
                        systemName = "backward.fill",
                        contentDescription = null,
                        tint = Color.White,
                        size = skipIconSize,
                    )
                }
            }
            //play/pause
            Box(modifier = Modifier.weight(1f)) {
                PlayerTransportButton(
                    appleMusicStyle = appleMusicStyle,
                    onClick = {
                        if (playbackState == STATE_ENDED) {
                            playerConnection.player.seekTo(0, 0)
                            playerConnection.player.playWhenReady = true
                        } else {
                            playerConnection.player.togglePlayPause()
                        }
                    },
                    modifier = Modifier.size(playTargetSize).align(Alignment.Center),
                ) {
                    val systemName = if (playbackState == STATE_ENDED) "arrow.counterclockwise" else if (isPlaying) "pause.fill" else "play.fill"
                    if (appleMusicStyle) {
                        AppleMusicPlayPauseIcon(systemName)
                    } else {
                        SfIcon(
                            systemName = systemName,
                            contentDescription = null,
                            tint = Color.White,
                            size = playIconSize,
                        )
                    }
                }
            }


            //next
            Box(modifier = Modifier.weight(1f)) {
                PlayerTransportButton(
                    appleMusicStyle = appleMusicStyle,
                    enabled = canSkipNext,
                    onClick = playerConnection::seekToNext,
                    pressOffsetX = (-2).dp,
                    modifier = Modifier
                        .size(skipTargetSize)
                        .align(Alignment.Center)
                ) { clickCount ->
                    if (appleMusicStyle) {
                        AppleMusicSkipIcon(forward = true, clickCount = clickCount)
                    } else SfIcon(
                        systemName = "forward.fill",
                        contentDescription = null,
                        tint = Color.White,
                        size = skipIconSize,
                    )
                }
            }

            if (appleMusicStyle) Spacer(Modifier.weight(0.4f))
        }
    }

}

@Composable
internal fun PlayerTransportButton(
    appleMusicStyle: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pressOffsetX: Dp = 0.dp,
    content: @Composable (clickCount: Int) -> Unit,
) {
    if (appleMusicStyle) {
        val interactionSource = remember { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()
        var clickCount by remember { mutableIntStateOf(0) }
        var clickFeedback by remember { mutableStateOf(false) }
        LaunchedEffect(clickCount) {
            if (clickCount > 0) {
                // Keep a very quick tap visible even if press/release share a frame.
                clickFeedback = true
                delay(40)
                clickFeedback = false
            }
        }
        val washAlpha = animateFloatAsState(
            targetValue = if (enabled && (pressed || clickFeedback)) 0.1f else 0f,
            animationSpec = tween(
                durationMillis = if (pressed || clickFeedback) 40 else 300,
                easing = EaseOutQuad,
            ),
            label = "AppleMusicTransportPress",
        )
        Box(
            modifier = modifier.absoluteOffset(x = pressOffsetX).clip(CircleShape).drawBehind {
                drawCircle(Color.White.copy(alpha = washAlpha.value))
            }.clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    clickCount++
                    onClick()
                },
            ),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.absoluteOffset(x = -pressOffsetX), contentAlignment = Alignment.Center) {
                content(clickCount)
            }
        }
    } else {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.clip(ContinuousRoundedRectangle(4.dp)),
        ) { content(0) }
    }
}
