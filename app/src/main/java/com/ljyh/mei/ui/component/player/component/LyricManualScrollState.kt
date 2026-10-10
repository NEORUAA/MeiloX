package com.ljyh.mei.ui.component.player.component

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class LyricManualScrollState {
    var isBrowsing by mutableStateOf(false)
        private set
    var isDragging by mutableStateOf(false)
        private set
    var returnToPlaybackPending = false
        private set
    private var lastInteractionMs = 0L

    fun onDragStarted(nowMs: Long) {
        isDragging = true
        onUserScroll(nowMs)
    }

    fun onUserScroll(nowMs: Long) {
        lastInteractionMs = nowMs
        isBrowsing = true
        returnToPlaybackPending = true
    }

    fun onDragStopped(nowMs: Long) {
        isDragging = false
        onUserScroll(nowMs)
    }

    fun resumeIfIdle(nowMs: Long, isScrollInProgress: Boolean) {
        // Wait for both the last gesture's grace period and any remaining fling.
        if (isBrowsing && !isDragging && !isScrollInProgress &&
            nowMs - lastInteractionMs >= 5_000L
        ) {
            isBrowsing = false
        }
    }

    fun onLineSelected() {
        isBrowsing = false
        returnToPlaybackPending = true
    }

    fun onPlaybackPositioned() {
        // A new drag may start while the previous scroll's child coroutine finishes.
        if (!isBrowsing) returnToPlaybackPending = false
    }
}
