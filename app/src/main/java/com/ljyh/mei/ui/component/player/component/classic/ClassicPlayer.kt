package com.ljyh.mei.ui.component.player.component.classic

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.ljyh.mei.constants.MiniPlayerHeight
import com.ljyh.mei.ui.component.player.MiniPlayer
import com.ljyh.mei.ui.component.player.component.FluidBackground
import com.ljyh.mei.ui.component.player.overlay.PlayerOverlayHandler
import com.ljyh.mei.ui.component.player.state.PlayerStateContainer
import com.ljyh.mei.ui.component.sheet.BottomSheet
import com.ljyh.mei.ui.component.sheet.BottomSheetState
import com.ljyh.mei.ui.component.sheet.HorizontalSwipeDirection
import com.ljyh.mei.ui.component.utils.rememberDeviceInfo
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.trackBackdropPosition
import com.ljyh.mei.utils.audio.AudioVisualizerManager
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop


@SuppressLint("ConfigurationScreenWidthHeight")
@OptIn(UnstableApi::class)
@Composable
fun ClassicPlayer(
    state: BottomSheetState,
    modifier: Modifier = Modifier,
    stateContainer: PlayerStateContainer,
    overlayHandler: PlayerOverlayHandler,
    collapsedBackdrop: Backdrop,
    playerBackgroundBackdrop: LayerBackdrop,
    playerContentBackdrop: LayerBackdrop,
    compactMiniPlayerProgress: State<Float>,
    miniPlayerVerticalOffset: () -> Dp,
) {

    val device = rememberDeviceInfo()

    val isDark = LocalGlassColors.current.isDark



    // --- 从状态容器获取数据 ---
    val mediaMetadata by stateContainer.mediaMetadata
    val isPlaying by stateContainer.isPlaying
    val sliderPosition by remember { derivedStateOf { stateContainer.sliderPosition } }
    val duration by remember { derivedStateOf { stateContainer.duration } }
    val context = LocalContext.current
    val audioVisualizerManager = remember { AudioVisualizerManager(context) }

    // Match the Apple Music player's continuous fade while the sheet approaches the mini
    // player. The background view is removed at the collapsed anchor, so it must reach zero
    // before that discrete composition change to avoid a final-frame flash in landscape mode.
    val playerBackgroundAlpha = state.revealProgress
    val sheetProgress = state.progress

    LaunchedEffect(stateContainer.playerConnection.player) {
        val player = stateContainer.playerConnection.player as? ExoPlayer
        player?.audioSessionId?.let { sessionId ->
            audioVisualizerManager.attachToPlayer(sessionId)
        }
    }

    // 背景颜色计算
    val colorScheme = MaterialTheme.colorScheme
    val backgroundColor = remember(isDark, sheetProgress, colorScheme) {
        if (isDark && sheetProgress > 0f) {
            lerp(colorScheme.surfaceContainer, Color.Black, sheetProgress)
        } else {
            colorScheme.surfaceContainer
        }
    }




    BottomSheet(
        state = state,
        modifier = modifier,
        backgroundColor = backgroundColor,
        collapsedDragOffset = miniPlayerVerticalOffset,
        collapsedDragHeight = MiniPlayerHeight,
        collapsedContentPadding = 2.dp,
        onDismiss = {
            stateContainer.playerConnection.player.stop()
            stateContainer.playerConnection.player.clearMediaItems()
        },
        onHorizontalSwipe = { direction ->
            when (direction) {
                HorizontalSwipeDirection.Left -> stateContainer.playerConnection.seekToNext()
                HorizontalSwipeDirection.Right -> stateContainer.playerConnection.seekToPrevious()
            }
        },
        backgroundContent = {
            FluidBackground(
                imageUrl = mediaMetadata?.coverUrl,
                audioVisualizerManager = audioVisualizerManager,
                isPlaying = isPlaying,
                alpha = playerBackgroundAlpha,
                backdrop = playerBackgroundBackdrop,
            )
        },
        collapsedContent = {
            MiniPlayer(
                position = sliderPosition.toLong(),
                duration = duration,
                backdrop = collapsedBackdrop,
                compactProgress = compactMiniPlayerProgress,
                onClick = state::expandSoft,
            )
        }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(playerContentBackdrop)
                .trackBackdropPosition(playerContentBackdrop),
        ) {
            val layoutMode = when {
                device.isTablet && device.isLandscape -> PlayerLayoutMode.Tablet
                !device.isTablet && device.isLandscape -> PlayerLayoutMode.ImmersiveLandscape
                else -> PlayerLayoutMode.PhonePortrait
            }

//            Timber.tag("PlayerLayoutMode").d(layoutMode.name)


            when (layoutMode) {
                PlayerLayoutMode.PhonePortrait -> ClassicPhoneLayout(stateContainer, overlayHandler)
                PlayerLayoutMode.Tablet -> ClassicTabletLayout(stateContainer, overlayHandler)
                PlayerLayoutMode.ImmersiveLandscape -> ClassicImmersiveLayout(stateContainer, overlayHandler)
            }
        }


    }
}
