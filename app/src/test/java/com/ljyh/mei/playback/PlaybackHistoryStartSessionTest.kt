package com.ljyh.mei.playback

import androidx.media3.common.Player
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackHistoryStartSessionTest {
    @Test
    fun preparedAndPausedItemsDoNotCreateAStartUntilActuallyPlaying() {
        val session = PlaybackHistoryStartSession()

        session.onMediaItemTransition(
            mediaId = "101",
            reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
        )
        assertNull(session.recordStartIfNeeded("101", isPlaying = false, nowMs = 20L))
        assertEquals(30L, session.recordStartIfNeeded("101", isPlaying = true, nowMs = 30L))
    }

    @Test
    fun pauseResumeAndBufferingDoNotDuplicateTheStart() {
        val session = PlaybackHistoryStartSession()

        assertEquals(100L, session.recordStartIfNeeded("202", true, 100L))
        assertNull(session.recordStartIfNeeded("202", false, 200L))
        assertNull(session.recordStartIfNeeded("202", true, 300L))
        assertNull(session.recordStartIfNeeded("202", false, 400L))
        assertNull(session.recordStartIfNeeded("202", true, 500L))
    }

    @Test
    fun playlistChangedSameMediaIdKeepsTheExistingSession() {
        val session = PlaybackHistoryStartSession()

        assertEquals(0L, session.recordStartIfNeeded("303", true, 0L))
        session.onMediaItemTransition(
            mediaId = "303",
            reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
        )
        assertNull(session.recordStartIfNeeded("303", true, 2_000L))
    }

    @Test
    fun autoSeekAndRepeatTransitionsRestartEvenForTheSameMediaId() {
        listOf(
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK,
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
        ).forEachIndexed { index, reason ->
            val session = PlaybackHistoryStartSession()
            assertEquals(0L, session.recordStartIfNeeded("404", true, 0L))
            val restartedAt = (index + 1) * 1_000L
            session.onMediaItemTransition("404", reason)
            assertEquals(
                restartedAt,
                session.recordStartIfNeeded("404", true, restartedAt),
            )
        }
    }

    @Test
    fun endedAndStoppedSessionsCanStartAgain() {
        val session = PlaybackHistoryStartSession()

        assertEquals(0L, session.recordStartIfNeeded("505", true, 0L))
        session.onPlaybackStateChanged(Player.STATE_ENDED)
        assertEquals(1_000L, session.recordStartIfNeeded("505", true, 1_000L))
        session.onPlaybackStateChanged(Player.STATE_IDLE)
        assertEquals(2_000L, session.recordStartIfNeeded("505", true, 2_000L))
    }

    @Test
    fun fastSwitchPreservesEveryAlreadyEmittedStart() {
        val session = PlaybackHistoryStartSession()

        val first = session.recordStartIfNeeded("606", true, 100L)
        session.onMediaItemTransition(
            mediaId = "707",
            reason = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
        )
        val second = session.recordStartIfNeeded("707", true, 200L)

        assertEquals(100L, first)
        assertEquals(200L, second)
    }

    @Test
    fun localMediaUsesTheSameStartGuard() {
        val session = PlaybackHistoryStartSession()

        assertEquals(10L, session.recordStartIfNeeded("local_file", true, 10L))
        assertNull(session.recordStartIfNeeded("local_file", true, 20L))
    }

    @Test
    fun persistenceContinuesAfterParentCancellationOnceItHasStarted() = runBlocking {
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0

        val job = scope.launchPlaybackHistoryPersistence {
            entered.complete(Unit)
            release.await()
            writes++
        }

        entered.await()
        parent.cancel()
        release.complete(Unit)
        job.join()

        assertEquals(1, writes)
    }

    @Test
    fun pausedTransitionDoesNotStartPersistence() = runBlocking {
        val session = PlaybackHistoryStartSession()
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        var writes = 0

        session.onMediaItemTransition(
            mediaId = "paused",
            reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
        )
        val startedAt = session.recordStartIfNeeded("paused", isPlaying = false, nowMs = 10L)
        if (startedAt != null) {
            scope.launchPlaybackHistoryPersistence { writes++ }
        }

        assertNull(startedAt)
        assertEquals(0, writes)
        scope.cancel()
    }
}
