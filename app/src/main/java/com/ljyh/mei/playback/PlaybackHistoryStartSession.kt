package com.ljyh.mei.playback

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Guards one actual-play start per logical playback session.
 *
 * Pausing and buffering keep the session alive. Explicit item transitions and stop/end boundaries
 * create a new one without inspecting duration or playback position.
 */
internal class PlaybackHistoryStartSession {
    private var currentMediaId: String? = null
    private var hasStarted = false

    fun onMediaItemTransition(mediaId: String?, reason: Int) {
        if (mediaId != currentMediaId || reason in NEW_SESSION_TRANSITION_REASONS) {
            hasStarted = false
        }
        currentMediaId = mediaId
    }

    fun recordStartIfNeeded(
        mediaId: String?,
        isPlaying: Boolean,
        nowMs: Long,
    ): Long? {
        if (mediaId != currentMediaId) {
            currentMediaId = mediaId
            hasStarted = false
        }
        return if (isPlaying) startIfNeeded(nowMs) else null
    }

    fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
            hasStarted = false
        }
    }

    private fun startIfNeeded(nowMs: Long): Long? {
        if (hasStarted || currentMediaId == null) return null
        hasStarted = true
        return nowMs
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
