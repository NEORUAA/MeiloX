package com.ljyh.mei.ui.component.player.component

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.constants.PlayerHorizontalPadding
import com.ljyh.mei.constants.PlayerStyle
import com.ljyh.mei.constants.PlayerStyleKey
import com.ljyh.mei.constants.ProgressBarStyle
import com.ljyh.mei.constants.ProgressBarStyleKey
import com.ljyh.mei.ui.component.player.component.applemusic.AppleMusicDimensions
import com.ljyh.mei.ui.component.player.component.applemusic.playerAdditiveBlend
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.utils.rememberEnumPreference

@OptIn(UnstableApi::class)
@Composable
fun PlayerControlsSection(
    position: () -> Long,
    duration: () -> Long,
    isPlaying: Boolean,
    playbackState: Int,
    playerConnection: PlayerConnection,
    lyricsVisible: Boolean,
    queueVisible: Boolean,
    onLyricClick: () -> Unit,
    onPlaylistClick: () -> Unit,
    onSleepTimerClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onMoreClick: () -> Unit,
    isCompact: Boolean = false,
    onProgressTrackTopChanged: ((Float) -> Unit)? = null,
) {
    // 紧凑模式下间距减半
    val spacerHeight = if (isCompact) 8.dp else 24.dp

    val isFM = playerConnection.isFMMode.collectAsStateWithLifecycle()
    val playerStyle = rememberEnumPreference(PlayerStyleKey, PlayerStyle.AppleMusic).value

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        PlayerProgressSection(position, duration, isPlaying, playerConnection, onProgressTrackTopChanged)

        Spacer(Modifier.height(spacerHeight))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (playerStyle == PlayerStyle.AppleMusic) 0.dp else PlayerHorizontalPadding)
        ) {
            PlayerControls(
                playerConnection = playerConnection,
                canSkipPrevious = !isFM.value || playerConnection.player.hasPreviousMediaItem(),
                canSkipNext = playerConnection.player.hasNextMediaItem(),
                isPlaying = isPlaying,
                playbackState = playbackState,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 紧凑模式下可能需要隐藏底部 Toolbar 或者减小间距
        if (!isCompact) {
            Spacer(Modifier.height(spacerHeight))
            PlayerActionToolbar(
                modifier = Modifier.padding(horizontal = PlayerHorizontalPadding).playerAdditiveBlend(),
                lyricsVisible = lyricsVisible,
                queueVisible = queueVisible,
                onLyricClick = onLyricClick,
                onPlaylistClick = onPlaylistClick,
                onSleepTimerClick = onSleepTimerClick,
                onAddToPlaylistClick = onAddToPlaylistClick,
                onDownloadClick = onDownloadClick,
                onMoreClick = onMoreClick
            )
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            Spacer(Modifier.height(16.dp))
        } else {
            // 紧凑模式底部留白少一点
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerProgressSection(
    position: () -> Long,
    duration: () -> Long,
    isPlaying: Boolean,
    playerConnection: PlayerConnection,
    onProgressTrackTopChanged: ((Float) -> Unit)?,
) {
    // Read the ticking position here so only progress recomposes, leaving the
    // title, sheet and glass playback controls outside this update boundary.
    val progressBarStyle = rememberEnumPreference(
        key = ProgressBarStyleKey,
        defaultValue = ProgressBarStyle.LINEAR,
    ).value
    val currentPosition = position()
    val currentDuration = duration()
    val playerStyle = rememberEnumPreference(PlayerStyleKey, PlayerStyle.AppleMusic).value
    val progressPadding = if (playerStyle == PlayerStyle.AppleMusic && progressBarStyle == ProgressBarStyle.LINEAR) {
        AppleMusicDimensions.progressInset
    } else PlayerHorizontalPadding + 8.dp
    val modifier = Modifier
        .fillMaxWidth()
        .playerAdditiveBlend()
        .padding(horizontal = progressPadding)
    val seek: (Long) -> Unit = { playerConnection.player.seekTo(it) }
    if (progressBarStyle == ProgressBarStyle.LINEAR) {
        FluidProgressSlider(currentPosition, currentDuration, seek, modifier, onProgressTrackTopChanged)
    } else {
        PlayerProgressSlider(currentPosition, currentDuration, isPlaying, seek, modifier)
    }
}
