package com.ljyh.mei.ui.component.player

import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.ljyh.mei.ui.component.sheet.LocalPlayerSheet
import com.ljyh.mei.ui.component.sheet.rememberPlayerSheetLayers
import com.ljyh.mei.ui.component.sheet.PlayerSheetArtworkOverlay
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
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
import com.ljyh.mei.ui.glass.rememberRasterizedPlayerBackdrop
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Precision
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import com.ljyh.mei.utils.rememberEnumPreference
import com.ljyh.mei.ui.screen.playlist.PlaylistViewModel
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

internal val LocalPlayerArtwork = staticCompositionLocalOf<Pair<String, androidx.compose.ui.graphics.painter.Painter>?> { null }

internal val LocalPlayerBackgroundReadyChanged = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

@OptIn(UnstableApi::class)
@RequiresApi(Build.VERSION_CODES.S)
@Composable
fun BottomSheetPlayer(
    state: BottomSheetState,
    modifier: Modifier = Modifier,
    compactMiniPlayerProgress: State<Float>,
    miniPlayerVerticalOffset: () -> Dp,
    onBackgroundReadyChanged: (Boolean) -> Unit = {},
    playerViewModel: PlayerViewModel = hiltViewModel(),
    playlistViewModel: PlaylistViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val navController = LocalNavController.current
    val device = rememberDeviceInfo()
    val collapsedBackdrop = LocalGlassBackdrop.current
    // Record the mesh background directly so glass shares the same live Compose layer.
    val playerBackgroundBackdrop = rememberLayerBackdrop()
    // Record foreground content independently to keep glass out of its own sample source.
    // Window-space wrappers preserve sampling when a Popup or ModalBottomSheet animates
    // in another Compose owner.
    val playerContentBackdrop = rememberLayerBackdrop()
    // The expanded cover is intentionally rendered above the player content. Record it in a
    // separate source so sheets can sample it without changing that visual z-order.
    val playerCoverBackdrop = rememberLayerBackdrop()
    val playerBackdrop = rememberCombinedBackdrop(
        rememberRasterizedPlayerBackdrop(playerBackgroundBackdrop),
        rememberRasterizedPlayerBackdrop(playerContentBackdrop),
        rememberRasterizedPlayerBackdrop(playerCoverBackdrop),
    )

    // 获取播放器样式
    val playerStyle by rememberEnumPreference(PlayerStyleKey, defaultValue = PlayerStyle.AppleMusic)

    // 创建公共状态容器
    val progressUpdatesEnabled by remember(state) { derivedStateOf { state.isContentVisible } }
    val stateContainer = rememberPlayerStateContainer(
        playerViewModel = playerViewModel,
        playerConnection = playerConnection,
        progressUpdatesEnabled = progressUpdatesEnabled,
    )

    // 创建弹窗处理器
    val overlayHandler = rememberOverlayHandler(
        stateContainer = stateContainer,
        playlistViewModel = playlistViewModel,
        navController = navController
    )

    val currentMetadata by playerConnection.mediaMetadata.collectAsState()
    val context = LocalContext.current
    val artwork = currentMetadata?.coverUrl?.let { url ->
        key(url) {
            url to rememberAsyncImagePainter(
                ImageRequest.Builder(context)
                    .data(url)
                    .size(1440)
                    .precision(Precision.INEXACT)
                    .build(),
            )
        }
    }
    val sheetLayers = rememberPlayerSheetLayers(state)

    // 单入口、双实现 - 根据样式渲染不同的播放器
    CompositionLocalProvider(
        LocalPlayerSheet provides sheetLayers,
        LocalPlayerArtwork provides artwork,
        LocalPlayerBackgroundReadyChanged provides onBackgroundReadyChanged,
        LocalGlassBackdrop provides playerBackdrop,
        LocalBlurBackdrop provides playerBackdrop,
    ) {
        Box(Modifier.fillMaxSize()) {
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
            PlayerSheetArtworkOverlay(sheetLayers)
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
