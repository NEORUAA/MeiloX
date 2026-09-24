package com.ljyh.mei.playback

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface SleepTimerState {
    data object Off : SleepTimerState
    data object EndOfTrack : SleepTimerState
    data class Countdown(val minutes: Int, val deadlineElapsedRealtimeMs: Long) : SleepTimerState
}

class SleepTimer(
    private val scope: CoroutineScope,
    private val player: Player,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val onStateChanged: (SleepTimerState) -> Unit = {},
) : Player.Listener {
    private var sleepTimerJob: Job? = null

    var state by mutableStateOf<SleepTimerState>(SleepTimerState.Off)
        private set
    var remainingMillis by mutableLongStateOf(0L)
        private set
    val isActive: Boolean
        get() = state != SleepTimerState.Off

    fun start(minutes: Int) {
        require(minutes > 0 || minutes == END_OF_TRACK)
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        remainingMillis = if (minutes == END_OF_TRACK) 0L else minutes * 60_000L
        state = if (minutes == END_OF_TRACK) {
            SleepTimerState.EndOfTrack
        } else {
            SleepTimerState.Countdown(minutes, elapsedRealtime() + remainingMillis)
        }
        onStateChanged(state)

        val countdown = state as? SleepTimerState.Countdown ?: return
        sleepTimerJob = scope.launch {
            // A monotonic deadline survives wall-clock changes and delayed coroutine resumes.
            while (true) {
                remainingMillis = (countdown.deadlineElapsedRealtimeMs - elapsedRealtime()).coerceAtLeast(0L)
                if (remainingMillis == 0L) break
                delay(minOf(remainingMillis, 1_000L))
            }
            sleepTimerJob = null
            finish()
        }
    }

    fun clear() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        remainingMillis = 0L
        state = SleepTimerState.Off
        onStateChanged(state)
    }

    private fun finish() {
        clear()
        player.pause()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (state == SleepTimerState.EndOfTrack) finish()
    }

    override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
        if (playbackState == Player.STATE_ENDED && state == SleepTimerState.EndOfTrack) finish()
    }

    companion object {
        const val END_OF_TRACK = -1
    }
}
