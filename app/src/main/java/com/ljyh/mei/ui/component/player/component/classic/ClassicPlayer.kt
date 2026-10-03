package com.ljyh.mei.ui.component.player.component.classic

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.constants.MiniPlayerHeight
import com.ljyh.mei.ui.component.player.MiniPlayer
import com.ljyh.mei.ui.component.player.component.FluidBackground
import com.ljyh.mei.ui.component.player.overlay.PlayerOverlayHandler
import com.ljyh.mei.ui.component.player.state.PlayerStateContainer
import com.ljyh.mei.ui.component.sheet.BottomSheet
import com.ljyh.mei.ui.component.sheet.BottomSheetState
import com.ljyh.mei.ui.component.sheet.HorizontalSwipeDirection
import com.ljyh.mei.ui.component.utils.rememberDeviceInfo
import com.ljyh.mei.ui.glass.trackBackdropPosition
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

    // --- 从状态容器获取数据 ---
    val mediaMetadata by stateContainer.mediaMetadata
    val isPlaying by stateContainer.isPlaying
    val sliderPosition by remember { derivedStateOf { stateContainer.sliderPosition } }
    val duration by remember { derivedStateOf { stateContainer.duration } }
    BottomSheet(
        state = state,
        modifier = modifier,
        collapsedDragOffset = miniPlayerVerticalOffset,
        collapsedDragHeight = MiniPlayerHeight,
        transitionBackdrop = collapsedBackdrop,
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
                beatMeter = stateContainer.playerConnection.service.beatMeter,
                isPlaying = isPlaying,
                alpha = 1f,
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
