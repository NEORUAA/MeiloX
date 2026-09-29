package com.ljyh.mei.ui.component.player.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player.STATE_READY
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.constants.DebugKey
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.data.model.qq.u.SearchResult
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.ui.component.player.PlayerViewModel
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSource
import com.ljyh.mei.utils.lyric.createDefaultLyricData
import com.ljyh.mei.utils.rememberPreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import timber.log.Timber

@UnstableApi
class PlayerStateContainer(
    val playerViewModel: PlayerViewModel,
    val playerConnection: PlayerConnection
) {
    var sliderPosition by mutableFloatStateOf(0f)
        internal set

    var duration by mutableLongStateOf(0L)
        internal set

    var isDragging by mutableStateOf(false)
        internal set

    var lyricLine by mutableStateOf(createDefaultLyricData("歌词加载中"))
        internal set

    var currentSongId: String? = null
        internal set

    lateinit var playbackState: State<Int>
        internal set

    lateinit var isPlaying: State<Boolean>
        internal set

    lateinit var mediaMetadata: State<MediaMetadata?>
        internal set

    lateinit var lyricResult: State<LyricData>
        internal set

    lateinit var qqLyricSearch: State<Resource<SearchResult>>
        internal set

    lateinit var checkSongLike: State<PlayerLikeSnapshot>
        internal set

    fun likeAction(metadata: MediaMetadata?): () -> Unit {
        val expected = checkSongLike.value
        return {
            if (metadata != null && !metadata.isLocal && !metadata.isPodcast && expected.songId == metadata.id) {
                playerViewModel.like(expected)
            }
        }
    }

    lateinit var isLiked: State<Boolean>
        internal set

    lateinit var canSkipPrevious: State<Boolean>
        internal set

    lateinit var canSkipNext: State<Boolean>
        internal set

    lateinit var isFMMode: State<Boolean>
        internal set

    var controlsVisible by mutableStateOf(true)
        internal set

    fun reset() {
        lyricLine = createDefaultLyricData("歌词加载中", source = LyricSource.Loading)
        sliderPosition = 0f
        duration = 0L
    }
}

@Composable
@UnstableApi
fun rememberPlayerStateContainer(
    playerViewModel: PlayerViewModel = viewModel(),
    playerConnection: PlayerConnection,
    progressUpdatesEnabled: Boolean = true,
): PlayerStateContainer {

    val container = remember(playerConnection) {
        PlayerStateContainer(
            playerViewModel = playerViewModel,
            playerConnection = playerConnection
        )
    }

    container.playbackState = playerConnection.playbackState.collectAsState()
    container.isPlaying = playerConnection.isPlaying.collectAsState()
    container.mediaMetadata = playerConnection.mediaMetadata.collectAsState()
    container.canSkipPrevious = playerConnection.canSkipPrevious.collectAsState()
    container.canSkipNext = playerConnection.canSkipNext.collectAsState()
    container.isFMMode = playerConnection.isFMMode.collectAsState()

    container.lyricResult = playerViewModel.lyric.collectAsState()
    container.qqLyricSearch = playerViewModel.searchResult.collectAsState()
    container.checkSongLike = playerViewModel.like.collectAsState()

    container.isLiked = remember {
        derivedStateOf {
            val result = container.checkSongLike.value
            val metadata = container.mediaMetadata.value
            metadata != null && !metadata.isLocal && !metadata.isPodcast &&
                result.songId == metadata.id && result.liked == true
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(playerViewModel, context) {
        playerViewModel.likeMessages.collect { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val metadata = container.mediaMetadata.value
    LaunchedEffect(metadata?.id, metadata?.isLocal, metadata?.isPodcast) {
        playerViewModel.selectLikeSong(metadata)
    }

    // The collapsed player does not render a progress indicator. Avoid publishing a 50 ms
    // Compose-state tick while it is hidden, but resume immediately as soon as the expanded
    // player starts consuming the position.
    LaunchedEffect(
        container.playbackState.value,
        container.isPlaying.value,
        container.isDragging,
        progressUpdatesEnabled,
    ) {
        if (!progressUpdatesEnabled) return@LaunchedEffect

        val playbackState = container.playbackState.value
        val isPlaying = container.isPlaying.value
        val isDragging = container.isDragging

        if (playbackState == STATE_READY && isPlaying && !isDragging) {
            while (isActive) {
                container.sliderPosition = playerConnection.player.currentPosition.toFloat()
                container.duration = playerConnection.player.duration.coerceAtLeast(1L)
                delay(50)
            }
        } else if (!isPlaying && !isDragging) {
            container.sliderPosition = playerConnection.player.currentPosition.toFloat()
            container.duration = playerConnection.player.duration.coerceAtLeast(1L)
        }
    }

    LaunchedEffect(container.mediaMetadata.value?.id) {
        container.mediaMetadata.value?.let { meta ->
            container.currentSongId = meta.id.toString()
            container.reset()
            playerViewModel.lyricManager.loadLyrics(meta)
        }
    }

    LaunchedEffect(container.lyricResult.value) {
        container.lyricLine = container.lyricResult.value
    }

    val currentWindowIndex by playerConnection.currentWindowIndex.collectAsState()
    val queueWindows by playerConnection.queueWindows.collectAsState()

    LaunchedEffect(currentWindowIndex, queueWindows.size) {
        val idx = currentWindowIndex
        if (idx >= 0 && idx + 1 < queueWindows.size) {
            val nextMeta = queueWindows[idx + 1].mediaItem?.metadata
            if (nextMeta != null) {
                playerViewModel.lyricManager.preloadLyrics(nextMeta)
            }
        }
    }

    return container
}
