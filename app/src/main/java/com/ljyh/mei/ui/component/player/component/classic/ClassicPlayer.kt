package com.ljyh.mei.ui.component.player.component.classic

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.constants.MiniPlayerHeight
import com.ljyh.mei.ui.component.player.MiniPlayer
import com.ljyh.mei.ui.component.player.component.FluidBackground
import com.ljyh.mei.ui.component.player.overlay.PlayerOverlayHandler
import com.ljyh.mei.ui.component.player.state.PlayerStateContainer
import com.ljyh.mei.ui.component.sheet.BottomSheet
import io.github.neoruaa.glasmio.scaffold.GlasmioBottomSheetState
import io.github.neoruaa.glasmio.scaffold.HorizontalSwipeDirection
import com.ljyh.mei.ui.component.utils.rememberDeviceInfo
import io.github.neoruaa.glasmio.glass.trackBackdropPosition
import com.ljyh.mei.ui.component.player.component.PrivateFmModeSelector
import io.github.neoruaa.glasmio.glass.render.Backdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.LayerBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.layerBackdrop


@SuppressLint("ConfigurationScreenWidthHeight")
@OptIn(UnstableApi::class)
@Composable
fun ClassicPlayer(
    state: GlasmioBottomSheetState,
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
                // These parameters are unused by the mini player.
                position = 0L,
                duration = 0L,
                backdrop = collapsedBackdrop,
                compactProgress = compactMiniPlayerProgress,
                onClick = state::expandSoft,
            )
        }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
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
                PlayerLayoutMode.PhonePortrait -> ClassicPhoneLayout(stateContainer, overlayHandler, playerBackgroundBackdrop)
                PlayerLayoutMode.Tablet -> ClassicTabletLayout(stateContainer, overlayHandler)
                PlayerLayoutMode.ImmersiveLandscape -> ClassicImmersiveLayout(stateContainer, overlayHandler)
            }
            if (layoutMode != PlayerLayoutMode.PhonePortrait && state.isExpanded) {
                ClassicFmModeSelector(
                    stateContainer = stateContainer,
                    backdrop = playerBackgroundBackdrop,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 24.dp)
                        .widthIn(min = 280.dp, max = 420.dp),
                )
            }
        }


    }
}

@Composable
internal fun ClassicFmModeSelector(
    stateContainer: PlayerStateContainer,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    if (!stateContainer.isFMMode.value ||
        stateContainer.playerConnection.service.queueManager.similarFmSourceIds != null
    ) return
    PrivateFmModeSelector(
        connection = stateContainer.playerConnection,
        modifier = modifier.fillMaxWidth(),
        backdrop = backdrop,
    )
}
