package com.ljyh.mei.playback

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class CompletedPlaybackHistorySession(
    val mediaId: String,
    val playedDurationMs: Long,
)

internal data class PlaybackHistorySessionUpdate(
    val startedAtMs: Long? = null,
    val completed: CompletedPlaybackHistorySession? = null,
)

/** Tracks actual playing time for one logical media-item playback session. */
internal class PlaybackHistorySession {
    private var currentMediaId: String? = null
    private var hasStarted = false
    private var playedDurationMs = 0L
    private var playingSinceRealtimeMs: Long? = null

    fun onMediaItemTransition(
        mediaId: String?,
        reason: Int,
        realtimeMs: Long,
    ): CompletedPlaybackHistorySession? {
        val startsNewSession = mediaId != currentMediaId || reason in NEW_SESSION_TRANSITION_REASONS
        if (!startsNewSession) {
            currentMediaId = mediaId
            return null
        }
        val completed = finish(realtimeMs)
        currentMediaId = mediaId
        return completed
    }

    fun update(
        mediaId: String?,
        isPlaying: Boolean,
        wallClockMs: Long,
        realtimeMs: Long,
    ): PlaybackHistorySessionUpdate {
        val completed = if (mediaId != currentMediaId) {
            finish(realtimeMs).also { currentMediaId = mediaId }
        } else {
            null
        }

        if (!isPlaying || mediaId == null) {
            pauseTimer(realtimeMs)
            return PlaybackHistorySessionUpdate(completed = completed)
        }

        val startedAtMs = if (!hasStarted) {
            hasStarted = true
            wallClockMs
        } else {
            null
        }
        if (playingSinceRealtimeMs == null) {
            playingSinceRealtimeMs = realtimeMs
        }
        return PlaybackHistorySessionUpdate(
            startedAtMs = startedAtMs,
            completed = completed,
        )
    }

    fun finish(realtimeMs: Long): CompletedPlaybackHistorySession? {
        pauseTimer(realtimeMs)
        val mediaId = currentMediaId
        val completed = if (hasStarted && mediaId != null) {
            CompletedPlaybackHistorySession(mediaId, playedDurationMs)
        } else {
            null
        }
        hasStarted = false
        playedDurationMs = 0L
        playingSinceRealtimeMs = null
        return completed
    }

    private fun pauseTimer(realtimeMs: Long) {
        val playingSince = playingSinceRealtimeMs ?: return
        playedDurationMs += (realtimeMs - playingSince).coerceAtLeast(0L)
        playingSinceRealtimeMs = null
    }

    private companion object {
        val NEW_SESSION_TRANSITION_REASONS = setOf(
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK,
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
        )
    }
}

internal fun CoroutineScope.launchPlaybackHistoryPersistence(
    block: suspend () -> Unit,
): Job = launch(start = CoroutineStart.UNDISPATCHED) {
    withContext(Dispatchers.IO + NonCancellable) {
        block()
    }
}
