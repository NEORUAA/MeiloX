package com.ljyh.mei.ui.component.player.component


import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.common.util.UnstableApi
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.extensions.togglePlayPause
import com.ljyh.mei.constants.PlayerStyle
import com.ljyh.mei.constants.PlayerStyleKey
import com.ljyh.mei.playback.PlayMode
import com.ljyh.mei.ui.component.player.component.applemusic.AppleMusicPlayPauseIcon
import com.ljyh.mei.ui.component.player.component.applemusic.AppleMusicSkipIcon
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.utils.rememberEnumPreference

@OptIn(UnstableApi::class)
@Composable
fun PlayerTableControls(
    modifier: Modifier = Modifier,
    playerConnection: PlayerConnection,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    isPlaying: Boolean,
    playbackState: Int,
    onPlaylistClick: () -> Unit
){
    val playerStyle by rememberEnumPreference(PlayerStyleKey, PlayerStyle.AppleMusic)
    val appleMusicStyle = playerStyle == PlayerStyle.AppleMusic
    val skipTargetSize = if (appleMusicStyle) 64.dp else 48.dp
    val playTargetSize = if (appleMusicStyle) 64.dp else 84.dp
    val skipIconSize = if (appleMusicStyle) 44.dp else 30.dp
    val playIconSize = if (appleMusicStyle) 44.dp else 48.dp
    val playModeValue by playerConnection.repeatMode.collectAsState()
    val playMode = remember(playModeValue) {
        PlayMode.fromInt(playModeValue) ?: PlayMode.REPEAT_MODE_ALL
    }
    Box(modifier = modifier){
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (appleMusicStyle) Modifier.height(84.dp) else Modifier)
        ) {

            Box(modifier = Modifier.weight(1f)){
                IconButton(
                    modifier = Modifier.size(36.dp)
                        .align(Alignment.Center)
                        .clip(ContinuousRoundedRectangle(4.dp)),
                    onClick = { playerConnection.switchPlayMode() }
                ) {
                    val systemName = when (playMode) {
                        PlayMode.REPEAT_MODE_ONE -> "repeat.1"
                        PlayMode.REPEAT_MODE_ALL -> "repeat"
                        PlayMode.SHUFFLE_MODE_ALL -> "shuffle"
                    }
                    AnimatedContent(targetState = systemName, label = "PlayModeIcon") { target ->
                        SfIcon(
                            systemName = target,
                            contentDescription = "播放模式",
                            tint = Color.White,
                            size = 22.dp,
                        )
                    }
                }
            }


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

            Box(modifier = Modifier.weight(1f)){
                IconButton(
                    onClick = onPlaylistClick,
                    modifier = Modifier
                        .size(48.dp)
                        .align(Alignment.Center)
                        .clip(ContinuousRoundedRectangle(4.dp))
                ) {
                    SfIcon(
                        systemName = "music.note.list",
                        contentDescription = "播放队列",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}
