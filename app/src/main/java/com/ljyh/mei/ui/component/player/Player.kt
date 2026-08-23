package com.ljyh.mei.ui.component.player

import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.constants.PlayerStyle
import com.ljyh.mei.constants.PlayerStyleKey
import com.ljyh.mei.ui.component.player.component.applemusic.AppleMusicPlayer
import com.ljyh.mei.ui.component.player.component.classic.ClassicPlayer
import com.ljyh.mei.ui.component.player.overlay.CommonOverlayHandler
import com.ljyh.mei.ui.component.player.overlay.PlayerOverlayHandler
import com.ljyh.mei.ui.component.player.overlay.rememberOverlayHandler
import com.ljyh.mei.ui.component.player.state.PlayerStateContainer
import com.ljyh.mei.ui.component.player.state.rememberPlayerStateContainer
import com.ljyh.mei.ui.component.sheet.BottomSheetState
import com.ljyh.mei.ui.component.utils.rememberDeviceInfo
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.glass.LocalBlurBackdrop
import com.ljyh.mei.ui.glass.LocalGlassBackdrop
import com.ljyh.mei.ui.glass.LocalGroupedListBackgroundAlpha
import com.ljyh.mei.ui.glass.SheetGroupedListBackgroundAlpha
import com.ljyh.mei.ui.glass.rememberCrossWindowBackdrop
import com.ljyh.mei.utils.rememberEnumPreference
import com.ljyh.mei.ui.screen.playlist.PlaylistViewModel
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/** Publishes a capturable frame for the player's native GL background. */
val LocalPlayerBackdropFrame = staticCompositionLocalOf<MutableState<ImageBitmap?>?> { null }

@OptIn(UnstableApi::class)
@RequiresApi(Build.VERSION_CODES.S)
@Composable
fun BottomSheetPlayer(
    state: BottomSheetState,
    modifier: Modifier = Modifier,
    compactMiniPlayerProgress: State<Float>,
    miniPlayerVerticalOffset: () -> Dp,
    playerViewModel: PlayerViewModel = hiltViewModel(),
    playlistViewModel: PlaylistViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val navController = LocalNavController.current
    val device = rememberDeviceInfo()
    val collapsedBackdrop = LocalGlassBackdrop.current
    val backdropFrame = remember { mutableStateOf<ImageBitmap?>(null) }
    // GLSurfaceView owns a separate Surface and must never be drawn again inside a Compose
    // recording layer. A dedicated, empty Compose source records the latest PixelCopy frame.
    val playerBackgroundBackdrop = rememberLayerBackdrop(
        onDraw = {
            backdropFrame.value?.let { frame ->
                val scale = maxOf(size.width / frame.width, size.height / frame.height)
                val w = frame.width * scale
                val h = frame.height * scale
                withTransform({
                    translate(left = (size.width - w) / 2f, top = (size.height - h) / 2f)
                }) {
                    drawImage(frame, dstSize = IntSize(w.toInt(), h.toInt()))
                }
            }
        },
    )
    // Foreground content is a regular Compose layer, so it can be recorded independently
    // without ever touching the native GL Surface. Window-space wrappers keep both sources
    // stable when a Popup or ModalBottomSheet animates in another Compose owner.
    val playerContentBackdrop = rememberLayerBackdrop()
    // The expanded cover is intentionally rendered above the player content. Record it in a
    // separate source so sheets can sample it without changing that visual z-order.
    val playerCoverBackdrop = rememberLayerBackdrop()
    val playerBackdrop = rememberCombinedBackdrop(
        rememberCrossWindowBackdrop(playerBackgroundBackdrop),
        rememberCrossWindowBackdrop(playerContentBackdrop),
        rememberCrossWindowBackdrop(playerCoverBackdrop),
    )

    // 获取播放器样式
    val playerStyle by rememberEnumPreference(PlayerStyleKey, defaultValue = PlayerStyle.AppleMusic)

    // 创建公共状态容器
    val stateContainer = rememberPlayerStateContainer(
        playerViewModel = playerViewModel,
        playerConnection = playerConnection,
        progressUpdatesEnabled = !state.isCollapsed,
    )

    // 创建弹窗处理器
    val overlayHandler = rememberOverlayHandler(
        stateContainer = stateContainer,
        playlistViewModel = playlistViewModel,
        navController = navController
    )

    // 单入口、双实现 - 根据样式渲染不同的播放器
    CompositionLocalProvider(
        LocalPlayerBackdropFrame provides backdropFrame,
        LocalGlassBackdrop provides playerBackdrop,
        LocalBlurBackdrop provides playerBackdrop,
    ) {
        when (playerStyle) {
            PlayerStyle.AppleMusic -> {
                // 横屏模式下直接进入经典模式
                if( device.isLandscape){
                    ClassicPlayer(
                        state = state,
                        modifier = modifier,
                        stateContainer = stateContainer,
                        overlayHandler = overlayHandler,
                        collapsedBackdrop = collapsedBackdrop,
                        playerBackgroundBackdrop = playerBackgroundBackdrop,
                        playerContentBackdrop = playerContentBackdrop,
                        compactMiniPlayerProgress = compactMiniPlayerProgress,
                        miniPlayerVerticalOffset = miniPlayerVerticalOffset,
                    )
                }else{
                    AppleMusicPlayer(
                        state = state,
                        modifier = modifier,
                        stateContainer = stateContainer,
                        overlayHandler = overlayHandler,
                        collapsedBackdrop = collapsedBackdrop,
                        playerBackgroundBackdrop = playerBackgroundBackdrop,
                        playerContentBackdrop = playerContentBackdrop,
                        playerCoverBackdrop = playerCoverBackdrop,
                        compactMiniPlayerProgress = compactMiniPlayerProgress,
                        miniPlayerVerticalOffset = miniPlayerVerticalOffset,
                    )
                }

            }
            PlayerStyle.Classic -> {
                ClassicPlayer(
                    state = state,
                    modifier = modifier,
                    stateContainer = stateContainer,
                    overlayHandler = overlayHandler,
                    collapsedBackdrop = collapsedBackdrop,
                    playerBackgroundBackdrop = playerBackgroundBackdrop,
                    playerContentBackdrop = playerContentBackdrop,
                    compactMiniPlayerProgress = compactMiniPlayerProgress,
                    miniPlayerVerticalOffset = miniPlayerVerticalOffset,
                )
            }
        }
    }

    // 公共的弹窗处理层
    CompositionLocalProvider(
        LocalGlassBackdrop provides playerBackdrop,
        LocalBlurBackdrop provides playerBackdrop,
        LocalGroupedListBackgroundAlpha provides SheetGroupedListBackgroundAlpha,
    ) {
        CommonOverlayHandler(
            overlayHandler = overlayHandler,
            stateContainer = stateContainer,
            sheetState = state,
        )
    }
}
