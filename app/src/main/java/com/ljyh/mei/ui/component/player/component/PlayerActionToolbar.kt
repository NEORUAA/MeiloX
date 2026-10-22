package com.ljyh.mei.ui.component.player.component

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.media3.common.util.UnstableApi
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.constants.PlayerActionKey
import com.ljyh.mei.constants.PlayerStyle
import com.ljyh.mei.constants.PlayerStyleKey
import com.ljyh.mei.playback.PlayMode
import com.ljyh.mei.playback.SleepTimerState
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.model.PlayerAction
import com.ljyh.mei.utils.TimeUtils.makeTimeString
import com.ljyh.mei.utils.rememberPreference
import com.ljyh.mei.utils.rememberEnumPreference
import timber.log.Timber

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun PlayerActionToolbar(
    modifier: Modifier = Modifier,
    lyricsVisible: Boolean,
    queueVisible: Boolean = false,
    onLyricClick: () -> Unit,
    onPlaylistClick: () -> Unit,
    onSleepTimerClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current
    val playerStyle by rememberEnumPreference(PlayerStyleKey, PlayerStyle.AppleMusic)
    val pressedAlpha = if (playerStyle == PlayerStyle.AppleMusic) 0.6f else 0.3f

    val (actionString, _) = rememberPreference(
        key = PlayerActionKey,
        defaultValue = PlayerAction.toSettings(PlayerAction.defaultActions)
    )
    val actions = remember(actionString) {
        val actions = PlayerAction.fromSettings(actionString)
        Timber.tag("PlayerActionToolbar").d(actionString)
        actions
    }


    // 播放模式
    val playModeValue by playerConnection.repeatMode.collectAsState()
    val playMode = remember(playModeValue) {
        PlayMode.fromInt(playModeValue) ?: PlayMode.REPEAT_MODE_ALL
    }

    val sleepTimer = playerConnection.service.sleepTimer
    val sleepTimerEnabled = sleepTimer.isActive

    Spacer(Modifier.height(16.dp))

    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        // Apply shared opacity after the lyrics cutout has been composed.
        modifier = modifier.fillMaxWidth()
            .then(if (playerStyle == PlayerStyle.AppleMusic) Modifier.alpha(0.5f) else Modifier)
    ) {

        actions.forEach { action ->
            when (action) {
                PlayerAction.PLAY_MODE -> {
                    ShadowedIconButton(
                        pressedAlpha = pressedAlpha,
                        onClick = { playerConnection.switchPlayMode() }
                    ) {
                        val systemName = when (playMode) {
                            PlayMode.REPEAT_MODE_ONE -> "repeat.1"
                            PlayMode.REPEAT_MODE_ALL -> "repeat"
                            PlayMode.SHUFFLE_MODE_ALL -> "shuffle"
                        }
                        AnimatedContent(targetState = systemName, label = "PlayModeIcon") { target ->
                            SfIcon(
                                target,
                                stringResource(com.ljyh.mei.R.string.player_action_play_mode),
                                tint = Color.White,
                                weight = FontWeight.Medium,
                            )
                        }
                    }
                }

                PlayerAction.QUEUE -> {
                    PlayerPageActionButton(
                        systemName = action.systemName,
                        contentDescription = stringResource(action.labelRes),
                        isSelected = queueVisible,
                        iconSize = 22.dp,
                        iconWeight = FontWeight.SemiBold,
                        pressedAlpha = pressedAlpha,
                        onClick = onPlaylistClick
                    )
                }

                PlayerAction.LYRICS -> {
                    PlayerPageActionButton(
                        systemName = if (lyricsVisible) "quote.bubble.fill" else action.systemName,
                        contentDescription = stringResource(action.labelRes),
                        isSelected = lyricsVisible,
                        pressedAlpha = pressedAlpha,
                        onClick = onLyricClick
                    )
                }

                PlayerAction.SLEEP_TIMER -> {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedContent(
                            targetState = sleepTimerEnabled,
                            label = "sleepTimer"
                        ) { isEnabled ->
                            if (isEnabled) {
                                Box(
                                    modifier = Modifier
                                        .clip(ContinuousRoundedRectangle(50))
                                        .background(Color.White.copy(alpha = 0.2f))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = onSleepTimerClick,
                                        )
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = if (sleepTimer.state == SleepTimerState.EndOfTrack) {
                                            stringResource(com.ljyh.mei.R.string.sleep_timer_track_short)
                                        } else {
                                            makeTimeString(((sleepTimer.remainingMillis + 999L) / 1_000L) * 1_000L)
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            } else {
                                // 普通图标状态
                                ShadowedIconButton(
                                    pressedAlpha = pressedAlpha,
                                    onClick = onSleepTimerClick
                                ) {
                                    SfIcon(
                                        action.systemName,
                                        stringResource(action.labelRes),
                                        tint = Color.White,
                                        size = 22.dp,
                                        weight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }
                }

                PlayerAction.ADD_TO_PLAYLIST -> {
                    ShadowedIconButton(
                        pressedAlpha = pressedAlpha,
                        onClick = onAddToPlaylistClick
                    ) {
                        SfIcon(
                            action.systemName,
                            stringResource(action.labelRes),
                            tint = Color.White,
                            size = 22.dp,
                            weight = FontWeight.Medium,
                        )
                    }
                }

                PlayerAction.DOWNLOAD -> {
                    ShadowedIconButton(
                        pressedAlpha = pressedAlpha,
                        onClick = onDownloadClick,
                    ) {
                        SfIcon(
                            action.systemName,
                            stringResource(action.labelRes),
                            tint = Color.White,
                            weight = FontWeight.Medium,
                        )
                    }
                }

                PlayerAction.MORE -> {
                    ShadowedIconButton(
                        pressedAlpha = pressedAlpha,
                        onClick = onMoreClick
                    ) {
                        SfIcon(
                            action.systemName,
                            stringResource(action.labelRes),
                            tint = Color.White,
                            size = 22.dp,
                            weight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

    }
}

@Composable
private fun PlayerPageActionButton(
    systemName: String,
    contentDescription: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    iconSize: Dp = 24.dp,
    iconWeight: FontWeight = FontWeight.Medium,
    pressedAlpha: Float = 0.3f,
) {
    ShadowedIconButton(
        modifier = Modifier.semantics { selected = isSelected },
        onClick = onClick,
        pressedAlpha = pressedAlpha,
        dimOnPress = !isSelected,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .then(
                    if (isSelected) Modifier.graphicsLayer {
                        // Keep the glyph cutout inside the button background.
                        compositingStrategy = CompositingStrategy.Offscreen
                    } else Modifier
                )
                .background(if (isSelected) Color.White else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            SfIcon(
                systemName = systemName,
                contentDescription = contentDescription,
                modifier = if (isSelected) Modifier.graphicsLayer {
                    blendMode = BlendMode.DstOut
                    compositingStrategy = CompositingStrategy.Offscreen
                } else Modifier,
                tint = Color.White,
                size = iconSize,
                weight = iconWeight,
            )
        }
    }
}

@Composable
fun ShadowedIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pressedAlpha: Float = 0.3f,
    dimOnPress: Boolean = true,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = modifier
            .size(48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.graphicsLayer {
                alpha = if (isPressed && dimOnPress) pressedAlpha else 1f
            }
        ) {
            content()
        }
    }
}
