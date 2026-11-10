package com.ljyh.mei.ui.component.player.component.classic

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.constants.MiniPlayerHeight
import com.ljyh.mei.constants.PlayerStyle
import com.ljyh.mei.constants.PlayerStyleKey
import com.ljyh.mei.utils.rememberEnumPreference
import com.ljyh.mei.ui.component.sheet.LocalPlayerSheet
import com.ljyh.mei.ui.component.sheet.isUsable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.ljyh.mei.ui.component.player.MiniPlayer
import com.ljyh.mei.ui.component.player.component.FluidBackground
import com.ljyh.mei.ui.component.player.overlay.PlayerOverlayHandler
import com.ljyh.mei.ui.component.player.state.PlayerStateContainer
import com.ljyh.mei.ui.component.sheet.BottomSheet
import io.github.neoruaa.glasmio.scaffold.GlasmioBottomSheetState
import io.github.neoruaa.glasmio.scaffold.HorizontalSwipeDirection
import com.ljyh.mei.ui.component.utils.rememberDeviceInfo
import io.github.neoruaa.glasmio.glass.trackBackdropPosition
import io.github.neoruaa.glasmio.glass.render.Backdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.LayerBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.layerBackdrop
import io.github.neoruaa.glasmio.glass.rememberRasterizedBackdrop


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
    val queueBackdrop = rememberRasterizedBackdrop(playerBackgroundBackdrop)

    val playerStyle by rememberEnumPreference(PlayerStyleKey, PlayerStyle.AppleMusic)
    val sheetLayers = LocalPlayerSheet.current
    val scope = rememberCoroutineScope()
    var pendingExpansion by remember { mutableStateOf<Job?>(null) }

    fun openLandscapeDetail(queueSelected: Boolean) {
        val previousBounds = sheetLayers?.fullArtworkBounds
        val needsArtworkPlacement = device.isTablet && playerStyle == PlayerStyle.AppleMusic &&
            !state.isExpanded && !stateContainer.landscapeDetailVisible.value &&
            previousBounds?.isUsable() == true
        stateContainer.landscapeQueueSelected.value = queueSelected
        stateContainer.landscapeDetailVisible.value = true
        if (pendingExpansion?.isActive == true) return
        if (needsArtworkPlacement) {
            pendingExpansion = scope.launch {
                // Freeze the transition target only after the selected layout has placed
                // its artwork. A frame delay alone does not acknowledge new geometry.
                snapshotFlow { sheetLayers!!.fullArtworkBounds }
                    .first { it != previousBounds }
                state.expandSoft()
            }
        } else {
            state.expandSoft()
        }
    }
    val openLandscapeLyrics: () -> Unit = { openLandscapeDetail(queueSelected = false) }
    val openLandscapeQueue: () -> Unit = { openLandscapeDetail(queueSelected = true) }
    if (device.isLandscape) {
        DisposableEffect(overlayHandler, stateContainer, state, playerStyle, device.isTablet) {
            overlayHandler.onShowPlaylist = openLandscapeQueue
            onDispose {
                if (overlayHandler.onShowPlaylist === openLandscapeQueue) {
                    overlayHandler.onShowPlaylist = null
                }
            }
        }
    }

    // --- 从状态容器获取数据 ---
    val mediaMetadata by stateContainer.mediaMetadata
    val isPlaying by stateContainer.isPlaying
    BottomSheet(
        state = state,
        modifier = modifier,
        collapsedDragOffset = miniPlayerVerticalOffset,
        collapsedDragHeight = if (device.isLandscape) com.ljyh.mei.constants.LandscapeMiniPlayerHeight else MiniPlayerHeight,
        topDragHeight = if (device.isTablet && device.isLandscape) 96.dp else 0.dp,
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
        collapsedContent = { gestures ->
            MiniPlayer(
                // These parameters are unused by the mini player.
                position = 0L,
                duration = 0L,
                backdrop = collapsedBackdrop,
                compactProgress = compactMiniPlayerProgress,
                gestureModifier = gestures,
                onClick = state::expandSoft,
                onMoreClick = overlayHandler::showMoreAction,
                onLyricsClick = if (device.isLandscape) openLandscapeLyrics else state::expandSoft,
                onQueueClick = if (device.isLandscape) openLandscapeQueue else overlayHandler::showPlaylist,
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
                PlayerLayoutMode.PhonePortrait -> ClassicPhoneLayout(stateContainer, overlayHandler)
                PlayerLayoutMode.Tablet -> ClassicTabletLayout(stateContainer, overlayHandler, queueBackdrop)
                PlayerLayoutMode.ImmersiveLandscape -> ClassicImmersiveLayout(stateContainer, overlayHandler, queueBackdrop)
            }
        }


    }
}
