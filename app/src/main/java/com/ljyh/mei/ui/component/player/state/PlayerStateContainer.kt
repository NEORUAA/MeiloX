package com.ljyh.mei.ui.component.player.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.Player.STATE_READY
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.constants.DebugKey
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.data.model.qq.u.SearchResult
import com.ljyh.mei.data.model.room.Playlist
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.ui.component.player.PlayerViewModel
import com.ljyh.mei.ui.component.utils.LifecycleStartedEffect
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.model.LyricData
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

    lateinit var checkSongLike: State<Resource<Boolean>>
        internal set

    lateinit var isLiked: State<Boolean>
        internal set

    lateinit var allPlaylist: State<List<Playlist>>
        internal set

    lateinit var myPlaylist: State<List<Playlist>>
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
        sliderPosition = 0f
        duration = 0L
    }
}

@Composable
internal fun rememberPlayerLikeState(result: Resource<Boolean>): State<Boolean> =
    produceState(initialValue = result is Resource.Success && result.data, key1 = result) {
        when (result) {
            is Resource.Success -> value = result.data
            Resource.Loading -> Unit
            is Resource.Error -> {
                Timber.tag("Player State").d(result.message)
                value = false
            }
        }
    }

@Composable
@UnstableApi
fun rememberPlayerStateContainer(
    playerViewModel: PlayerViewModel = hiltViewModel(),
    playerConnection: PlayerConnection,
    progressUpdatesEnabled: Boolean = true,
): PlayerStateContainer {

    val container = remember(playerConnection) {
        PlayerStateContainer(
            playerViewModel = playerViewModel,
            playerConnection = playerConnection
        )
    }

    container.playbackState = playerConnection.playbackState.collectAsStateWithLifecycle()
    container.isPlaying = playerConnection.isPlaying.collectAsStateWithLifecycle()
    container.mediaMetadata = playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    container.canSkipPrevious = playerConnection.canSkipPrevious.collectAsStateWithLifecycle()
    container.canSkipNext = playerConnection.canSkipNext.collectAsStateWithLifecycle()
    container.isFMMode = playerConnection.isFMMode.collectAsStateWithLifecycle()

    container.lyricResult = playerViewModel.lyric.collectAsStateWithLifecycle()
    container.qqLyricSearch = playerViewModel.searchResult.collectAsStateWithLifecycle()
    container.checkSongLike = playerViewModel.like.collectAsStateWithLifecycle()
    container.allPlaylist = playerViewModel.localPlaylists.collectAsStateWithLifecycle()
    container.myPlaylist = playerViewModel.myPlaylists.collectAsStateWithLifecycle()

    // Loading a new track's status must not briefly display an unliked star.
    container.isLiked = rememberPlayerLikeState(container.checkSongLike.value)

    LaunchedEffect(container.mediaMetadata.value?.id) {
        container.mediaMetadata.value?.let { meta ->
            playerViewModel.mediaMetadata = meta
            playerViewModel.getLike(meta.id)
        }
    }

    // Refresh as soon as sheet motion begins, before the full foreground fades in.
    // Keep the same clock during collapse; stop only when the player is fully hidden.
    LifecycleStartedEffect(
        container.playbackState.value,
        container.isPlaying.value,
        container.isDragging,
        progressUpdatesEnabled,
    ) {
        if (!progressUpdatesEnabled) return@LifecycleStartedEffect

        val playbackState = container.playbackState.value
        val isPlaying = container.isPlaying.value
        val isDragging = container.isDragging

        if (playbackState == STATE_READY && isPlaying && !isDragging) {
            while (isActive) {
                container.sliderPosition = playerConnection.player.currentPosition.toFloat()
                container.duration = playerConnection.player.duration.coerceAtLeast(1L)
                // Progress needs four updates per second; lyrics and audio response
                // use their own faster clocks.
                delay(250)
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
            container.lyricLine = playerViewModel.lyricManager.lyricData.value
        }
    }

    LaunchedEffect(container.lyricResult.value) {
        container.lyricLine = container.lyricResult.value
    }

    val currentWindowIndex by playerConnection.currentWindowIndex.collectAsStateWithLifecycle()
    val queueWindows by playerConnection.queueWindows.collectAsStateWithLifecycle()

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
