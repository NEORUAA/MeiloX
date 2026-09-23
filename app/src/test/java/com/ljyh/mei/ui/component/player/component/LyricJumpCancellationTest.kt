package com.ljyh.mei.ui.component.player.component

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricJumpCancellationTest {
    @Test(timeout = 5_000)
    fun successfulJumpStaysHiddenUntilTheCallerFadesIn() = runBlocking {
        var alpha = 1f
        val completed = runInterruptibleLyricJump(restoreVisibility = { alpha = 1f }) {
            alpha = 0f
            true
        }
        assertTrue(completed)
        assertEquals(0f, alpha)
    }

    @Test(timeout = 5_000)
    fun gestureStartingDuringFadeRestoresVisibilityWhenJumpIsSkipped() = runBlocking {
        var alpha = 1f
        val completed = runInterruptibleLyricJump(restoreVisibility = { alpha = 1f }) {
            alpha = 0f
            false
        }
        assertEquals(false, completed)
        assertEquals(1f, alpha)
    }

    @Test(timeout = 5_000)
    fun existingGestureRejectsJumpWithoutStoppingPlaybackUpdates() = runBlocking {
        val mutex = MutatorMutex()
        val gestureStarted = CompletableDeferred<Unit>()
        val gesture = launch {
            mutex.mutate(MutatePriority.UserInput) {
                gestureStarted.complete(Unit)
                awaitCancellation()
            }
        }
        gestureStarted.await()
        var alpha = 1f
        val completed = runInterruptibleLyricJump(restoreVisibility = { alpha = 1f }) {
            alpha = 0f
            mutex.mutate { true }
        }
        assertEquals(false, completed)
        assertEquals(1f, alpha)
        assertTrue(currentCoroutineContext().isActive)
        gesture.cancelAndJoin()
        assertTrue(runInterruptibleLyricJump(restoreVisibility = {}) { mutex.mutate { true } })
    }

    @Test(timeout = 5_000)
    fun incomingGestureCancelsOnlyTheJumpAndRestoresVisibility() = runBlocking {
        val mutex = MutatorMutex()
        val jumpStarted = CompletableDeferred<Unit>()
        var alpha = 1f
        var playbackUpdatesContinued = false
        val playbackUpdates = launch {
            val completed = runInterruptibleLyricJump(restoreVisibility = {
                yield()
                alpha = 1f
            }) {
                alpha = 0f
                mutex.mutate {
                    jumpStarted.complete(Unit)
                    awaitCancellation()
                }
            }
            assertEquals(false, completed)
            playbackUpdatesContinued = true
        }
        jumpStarted.await()
        mutex.mutate(MutatePriority.UserInput) { }
        playbackUpdates.join()
        assertEquals(1f, alpha)
        assertTrue(playbackUpdatesContinued)
    }

    @Test(timeout = 5_000)
    fun leavingTheScreenStillCancelsPlaybackUpdates() = runBlocking {
        val jumpStarted = CompletableDeferred<Unit>()
        var alpha = 1f
        var playbackUpdatesContinued = false
        val playbackUpdates = launch {
            runInterruptibleLyricJump(restoreVisibility = {
                yield()
                alpha = 1f
            }) {
                alpha = 0f
                jumpStarted.complete(Unit)
                awaitCancellation()
            }
            playbackUpdatesContinued = true
        }
        jumpStarted.await()
        playbackUpdates.cancelAndJoin()
        assertEquals(1f, alpha)
        assertEquals(false, playbackUpdatesContinued)
    }
}
