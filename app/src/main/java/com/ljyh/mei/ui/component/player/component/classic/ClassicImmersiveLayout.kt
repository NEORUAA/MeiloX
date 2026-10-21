package com.ljyh.mei.ui.component.player.component.classic

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.ljyh.mei.constants.PlayerHorizontalPadding
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.ui.component.player.OverlayState
import com.ljyh.mei.ui.component.player.component.LyricScreen
import com.ljyh.mei.ui.component.player.component.classic.component.Cover
import com.ljyh.mei.ui.component.player.overlay.PlayerOverlayHandler
import com.ljyh.mei.ui.component.player.state.PlayerStateContainer
import com.ljyh.mei.ui.model.LyricSource

@Composable
fun ClassicImmersiveLayout(
    stateContainer: PlayerStateContainer,
    overlayHandler: PlayerOverlayHandler
) {
    val context = LocalContext.current
    val mediaMetadata by stateContainer.mediaMetadata
    val isPlaying by stateContainer.isPlaying
    val lyricLine by remember { derivedStateOf { stateContainer.lyricLine } }


    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Preserve the cover and lyric geometry while the badge host reaches the edge.
        val coverPaneWidth = ((maxWidth - 64.dp) * 0.45f).coerceAtLeast(0.dp)
        val navigationPadding = WindowInsets.navigationBars.asPaddingValues()
        val safePadding = WindowInsets.navigationBars.union(WindowInsets.displayCutout).asPaddingValues()
        val badgeBottomPadding = maxOf(16.dp, navigationPadding.calculateBottomPadding())
        val badgeEndPadding = maxOf(badgeBottomPadding, safePadding.calculateEndPadding(LocalLayoutDirection.current))

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 32.dp)
        ) {


            mediaMetadata?.let {
                Column(
                    modifier = Modifier.fillMaxHeight()
                        .width(coverPaneWidth),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Cover(
                        playerConnection = stateContainer.playerConnection,
                        mediaMetadata = it,
                        isPlaying = isPlaying,
                        modifier = Modifier
                            .widthIn(max = coverPaneWidth * 0.75f)
                            .aspectRatio(1f),
                        onDoubleClick = { offset, width ->

                            val player = stateContainer.playerConnection.player
                            val third = width / 3f

                            when {
                                offset.x < third -> {
                                    // 左侧：上一首
                                    if (player.hasPreviousMediaItem()) {
                                        player.seekToPreviousMediaItem()
                                    }
                                }

                                offset.x < third * 2 -> {
                                    player.playWhenReady = !player.playWhenReady
                                }

                                else -> {
                                    // 右侧：下一首
                                    if (player.hasNextMediaItem()) {
                                        player.seekToNextMediaItem()
                                    }
                                }
                            }
                        }
                    )
                }

            }

            Spacer(Modifier.width(32.dp))



            LyricScreen(
                lyricData = lyricLine,
                playerConnection = stateContainer.playerConnection,
                contentPadding = PaddingValues(
                    start = PlayerHorizontalPadding,
                    end = PlayerHorizontalPadding + 32.dp,
                ),
                sourceBadgeEndOffset = -badgeEndPadding,
                sourceBadgeBottomPadding = badgeBottomPadding,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                onClick = {
                    mediaMetadata?.let {
                        if (overlayHandler.currentOverlayValue is OverlayState.None) {
                            stateContainer.playerViewModel.searchQQSong(it.title)
                            overlayHandler.showQQMusicSelection(
                                mediaMetadata = it
                            )
                        }
                    }
                },
                onLongClick = { source ->
                    if (source == LyricSource.QQMusic && mediaMetadata != null) {
                        stateContainer.playerViewModel.deleteSongById(id = mediaMetadata!!.id.toString())
                        Toast.makeText(context, "已删除QQ音乐歌词", Toast.LENGTH_SHORT)
                            .show()
                    }
                },
                controlsVisible = stateContainer.controlsVisible,
                onToggleControls = {},
            )
        }
    }
}
