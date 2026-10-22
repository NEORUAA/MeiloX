package com.ljyh.mei.ui.component.player.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Player.STATE_READY
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.data.model.qq.u.SearchResult
import com.ljyh.mei.data.model.room.Playlist
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.playback.nextPlaybackMediaItemIndex
import com.ljyh.mei.ui.component.player.PlayerViewModel
import com.ljyh.mei.ui.component.utils.LifecycleStartedEffect
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.utils.lyric.createDefaultLyricData
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import timber.log.Timber

@UnstableApi
class PlayerStateContainer(
    val playerViewModel: PlayerViewModel,
    val playerConnection: PlayerConnection
) {
    var sliderPosition by mutableFloatStateOf(playerConnection.player.currentPosition.coerceAtLeast(0L).toFloat())
        internal set

    var duration by mutableLongStateOf(
        playerConnection.player.currentMediaItem?.let {
            playerProgressDuration(playerConnection.player.duration, it.metadata?.duration)
        } ?: 0L
    )
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

    internal fun refreshProgress() {
        val player = playerConnection.player
        val currentItem = player.currentMediaItem
        duration = if (currentItem == null) 0L else {
            playerProgressDuration(player.duration, currentItem.metadata?.duration)
        }
        if (!isDragging) {
            sliderPosition = if (currentItem == null) 0f else player.currentPosition.coerceAtLeast(0L).toFloat()
        }
    }
}

internal fun playerProgressDuration(playerDuration: Long, metadataDuration: Long?): Long =
    playerDuration.takeIf { it > 0L } ?: metadataDuration?.takeIf { it > 0L } ?: 0L

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
    val currentMediaItem = remember(playerConnection) { mutableStateOf(playerConnection.player.currentMediaItem) }
    container.mediaMetadata = remember(playerConnection) { derivedStateOf { currentMediaItem.value?.metadata } }
    var nextMediaItem by remember(playerConnection) { mutableStateOf<MediaItem?>(null) }
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

    DisposableEffect(playerConnection) {
        val player = playerConnection.player
        fun refreshItems() {
            currentMediaItem.value = player.currentMediaItem
            val nextIndex = player.nextPlaybackMediaItemIndex()
            nextMediaItem = if (nextIndex in 0 until player.mediaItemCount && nextIndex != player.currentMediaItemIndex) {
                player.getMediaItemAt(nextIndex)
            } else null
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_MEDIA_METADATA_CHANGED,
                        Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_POSITION_DISCONTINUITY,
                        Player.EVENT_PLAYBACK_STATE_CHANGED,
                    )
                ) {
                    container.refreshProgress()
                }
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_MEDIA_METADATA_CHANGED,
                        Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        Player.EVENT_REPEAT_MODE_CHANGED,
                    )
                ) {
                    refreshItems()
                }
            }
        }
        player.addListener(listener)
        // Event updates keep paused seeks and new metadata current without a timer.
        container.refreshProgress()
        refreshItems()
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(container.mediaMetadata.value) {
        container.mediaMetadata.value?.let { meta ->
            playerViewModel.mediaMetadata = meta
        }
    }

    LaunchedEffect(container.mediaMetadata.value?.id) {
        container.mediaMetadata.value?.let { meta ->
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
                container.refreshProgress()
                // Progress needs four updates per second; lyrics and audio response
                // use their own faster clocks.
                delay(250)
            }
        } else if (!isDragging) {
            container.refreshProgress()
        }
    }

    LaunchedEffect(container.mediaMetadata.value?.id) {
        container.mediaMetadata.value?.let { meta ->
            container.currentSongId = meta.id.toString()
            playerViewModel.lyricManager.loadLyrics(meta)
            container.lyricLine = playerViewModel.lyricManager.lyricData.value
        }
    }

    LaunchedEffect(container.lyricResult.value) {
        container.lyricLine = container.lyricResult.value
    }

    LaunchedEffect(nextMediaItem) {
        // MediaItem includes queue-entry identity and metadata, so same-sized queue
        // edits and metadata hydration also refresh the intended preload target.
        nextMediaItem?.metadata?.let(playerViewModel.lyricManager::preloadLyrics)
    }

    return container
}
