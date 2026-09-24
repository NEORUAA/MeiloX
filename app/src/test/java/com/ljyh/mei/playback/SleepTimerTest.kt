package com.ljyh.mei.playback

import androidx.media3.common.Player
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {
    private class Fixture(scope: TestScope) {
        var pauses = 0
        var clockOffset = 0L
        val notifications = mutableListOf<SleepTimerState>()
        private val player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "pause" -> { pauses++; null }
                else -> error("Unexpected player call: ${method.name}")
            }
        } as Player
        val timer = SleepTimer(
            scope = scope,
            player = player,
            elapsedRealtime = { scope.testScheduler.currentTime + clockOffset },
            onStateChanged = notifications::add,
        )
    }

    @Test
    fun countdownPublishesRemainingTimeAndExpiresOnlyOnce() = runTest {
        val f = Fixture(this)
        f.timer.start(1)
        assertEquals(60_000L, f.timer.remainingMillis)
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(45_000L, f.timer.remainingMillis)
        assertTrue(f.timer.isActive)
        assertEquals(1, f.notifications.size)
        advanceTimeBy(45_000)
        runCurrent()
        assertFalse(f.timer.isActive)
        assertEquals(0L, f.timer.remainingMillis)
        assertEquals(1, f.pauses)
        assertEquals(SleepTimerState.Off, f.notifications.last())
        advanceTimeBy(60_000)
        assertEquals(1, f.pauses)
    }

    @Test
    fun switchingToEndOfTrackCancelsTheOldCountdown() = runTest {
        val f = Fixture(this)
        f.timer.start(1)
        advanceTimeBy(10_000)
        f.timer.start(SleepTimer.END_OF_TRACK)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, f.pauses)
        assertEquals(0L, f.timer.remainingMillis)
        assertEquals(SleepTimerState.EndOfTrack, f.timer.state)
        f.timer.onPlaybackStateChanged(Player.STATE_ENDED)
        assertEquals(1, f.pauses)
        assertEquals(SleepTimerState.Off, f.timer.state)
    }

    @Test
    fun switchingToCountdownClearsEndOfTrackBehavior() = runTest {
        val f = Fixture(this)
        f.timer.start(SleepTimer.END_OF_TRACK)
        f.timer.start(1)
        f.timer.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        f.timer.onPlaybackStateChanged(Player.STATE_ENDED)
        assertEquals(0, f.pauses)
        assertTrue(f.timer.state is SleepTimerState.Countdown)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, f.pauses)
    }

    @Test
    fun updatingCountdownReplacesItsDeadline() = runTest {
        val f = Fixture(this)
        f.timer.start(1)
        advanceTimeBy(30_000)
        f.timer.start(2)
        assertEquals(120_000L, f.timer.remainingMillis)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(0, f.pauses)
        assertEquals(90_000L, f.timer.remainingMillis)
        advanceTimeBy(90_000)
        runCurrent()
        assertEquals(1, f.pauses)
        assertFalse(f.timer.isActive)
    }

    @Test
    fun cancellationNeverPausesAndClearsTheNotification() = runTest {
        val f = Fixture(this)
        f.timer.start(1)
        f.timer.clear()
        advanceTimeBy(120_000)
        assertEquals(0, f.pauses)
        assertFalse(f.timer.isActive)
        assertEquals(0L, f.timer.remainingMillis)
        assertEquals(SleepTimerState.Off, f.notifications.last())
        f.timer.start(SleepTimer.END_OF_TRACK)
        f.timer.clear()
        f.timer.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        assertEquals(0, f.pauses)
    }

    @Test
    fun trackTransitionAndEndedCallbacksDoNotPauseTwice() = runTest {
        for (reason in listOf(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)) {
            val f = Fixture(this)
            f.timer.start(SleepTimer.END_OF_TRACK)
            f.timer.onMediaItemTransition(null, reason)
            f.timer.onPlaybackStateChanged(Player.STATE_ENDED)
            assertEquals(1, f.pauses)
            assertEquals(SleepTimerState.Off, f.timer.state)
        }
    }

    @Test
    fun overdueTimerExpiresOnFirstResumeInsteadOfWaitingTheFullDuration() = runTest {
        val f = Fixture(this)
        f.timer.start(1)
        runCurrent()
        f.clockOffset = 90_000L
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, f.pauses)
        assertEquals(0L, f.timer.remainingMillis)
        assertFalse(f.timer.isActive)
    }

    @Test
    fun invalidDurationDoesNotReplaceAnExistingTimer() = runTest {
        val f = Fixture(this)
        f.timer.start(SleepTimer.END_OF_TRACK)
        for (duration in listOf(0, -2)) {
            val error = runCatching { f.timer.start(duration) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertEquals(SleepTimerState.EndOfTrack, f.timer.state)
        }
    }
}
