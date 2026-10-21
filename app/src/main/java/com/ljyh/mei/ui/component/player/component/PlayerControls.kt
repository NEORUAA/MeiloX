package com.ljyh.mei.ui.component.player.component

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.IconButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.common.util.UnstableApi
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.extensions.togglePlayPause
import com.ljyh.mei.constants.PlayerStyle
import com.ljyh.mei.constants.PlayerStyleKey
import com.ljyh.mei.ui.component.player.component.applemusic.AppleMusicPlayPauseIcon
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.utils.rememberEnumPreference

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
    val skipTargetSize = if (appleMusicStyle) 61.dp else 48.dp
    val playTargetSize = if (appleMusicStyle) 60.5.dp else 84.dp
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
                    modifier = Modifier
                        .size(skipTargetSize)
                        .align(Alignment.Center)
                ) {
                    SfIcon(
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
                    modifier = Modifier
                        .size(skipTargetSize)
                        .align(Alignment.Center)
                ) {
                    SfIcon(
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
    content: @Composable () -> Unit,
) {
    if (appleMusicStyle) {
        Box(
            modifier = modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, color = Color.White),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
            contentAlignment = Alignment.Center,
        ) { content() }
    } else {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.clip(ContinuousRoundedRectangle(4.dp)),
        ) { content() }
    }
}
