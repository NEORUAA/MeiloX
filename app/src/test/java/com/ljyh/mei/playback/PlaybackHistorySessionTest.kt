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

class PlaybackHistorySessionTest {
    @Test
    fun playbackAcrossMidnightKeepsOriginalStartAndExcludesPausedTime() {
        val session = PlaybackHistorySession()
        val midnight = 1_790_006_400_000L
        val startedAt = midnight - 20_000L
        assertEquals(startedAt, session.update("123", true, startedAt, 1_000L).startedAtMs)
        session.update("123", false, midnight - 10_000L, 11_000L)
        assertNull(session.update("123", true, midnight + 10_000L, 31_000L).startedAtMs)

        val completed = session.finish(61_000L, "playend")!!

        assertEquals(startedAt, completed.startedAtMs)
        assertEquals(40_000L, completed.playedDurationMs)
        assertEquals("playend", completed.endReason)
        assertNull(session.finish(62_000L))
    }

    @Test
    fun replayingSameSongNextDayUsesANewStartTimestamp() {
        val session = PlaybackHistorySession()
        val firstStart = 1_790_006_390_000L
        val nextDay = firstStart + 86_400_000L
        session.update("123", true, firstStart, 1_000L)
        val first = session.finish(61_000L, "playend")!!
        session.update("123", true, nextDay, 86_401_000L)
        val second = session.finish(86_521_000L, "playend")!!

        assertEquals(firstStart, first.startedAtMs)
        assertEquals(nextDay, second.startedAtMs)
        assertEquals(60_000L, first.playedDurationMs)
        assertEquals(120_000L, second.playedDurationMs)
    }

    @Test
    fun transitionReportsNaturalCompletionOnlyForAutoAndRepeat() {
        mapOf(
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO to "playend",
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT to "playend",
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK to "ui",
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED to "interrupt",
        ).forEach { (reason, endReason) ->
            val session = PlaybackHistorySession()
            session.update("123", true, 1_790_006_390_000L, 1_000L)
            val completed = session.onMediaItemTransition("456", reason, 31_000L)!!
            assertEquals(endReason, completed.endReason)
            assertEquals(30_000L, completed.playedDurationMs)
            assertEquals(1_790_006_390_000L, completed.startedAtMs)
        }
    }

    @Test
    fun wallClockChangesDoNotChangeOriginalStartOrListeningDuration() {
        val session = PlaybackHistorySession()
        session.update("123", true, 1_790_006_390_000L, 1_000L)
        session.update("123", true, 1_789_006_390_000L, 31_000L)
        val completed = session.finish(61_000L)!!
        assertEquals(1_790_006_390_000L, completed.startedAtMs)
        assertEquals(60_000L, completed.playedDurationMs)
        assertEquals("interrupt", completed.endReason)
    }

    @Test
    fun preparedAndPausedItemsDoNotStartUntilActuallyPlaying() {
        val session = PlaybackHistorySession()

        assertNull(
            session.onMediaItemTransition(
                mediaId = "101",
                reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
                realtimeMs = 10L,
            ),
        )
        assertNull(session.update("101", false, 20L, 20L).startedAtMs)
        assertEquals(30L, session.update("101", true, 30L, 30L).startedAtMs)
    }

    @Test
    fun onlyActualPlayingIntervalsContributeToDuration() {
        val session = PlaybackHistorySession()

        assertEquals(100L, session.update("202", true, 100L, 1_000L).startedAtMs)
        session.update("202", false, 200L, 2_000L)
        session.update("202", false, 300L, 8_000L)
        assertNull(session.update("202", true, 400L, 10_000L).startedAtMs)

        val completed = session.finish(11_500L)

        assertEquals("202", completed?.mediaId)
        assertEquals(2_500L, completed?.playedDurationMs)
    }

    @Test
    fun switchingSongsCompletesOldSessionAndStartsNewSessionOnce() {
        val session = PlaybackHistorySession()
        session.update("303", true, 100L, 1_000L)

        val completed = session.onMediaItemTransition(
            mediaId = "404",
            reason = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            realtimeMs = 4_000L,
        )
        val next = session.update("404", true, 4_100L, 4_100L)

        assertEquals("303", completed?.mediaId)
        assertEquals(3_000L, completed?.playedDurationMs)
        assertEquals(4_100L, next.startedAtMs)
        assertNull(next.completed)
        assertNull(session.update("404", true, 4_200L, 4_200L).startedAtMs)
    }

    @Test
    fun playlistChangedForSameMediaKeepsCurrentSession() {
        val session = PlaybackHistorySession()
        session.update("505", true, 100L, 1_000L)

        assertNull(
            session.onMediaItemTransition(
                mediaId = "505",
                reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
                realtimeMs = 2_000L,
            ),
        )
        assertNull(session.update("505", true, 200L, 2_000L).startedAtMs)
        assertEquals(2_000L, session.finish(3_000L)?.playedDurationMs)
    }

    @Test
    fun autoSeekAndRepeatTransitionsRestartSameMediaSession() {
        listOf(
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK,
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
        ).forEach { reason ->
            val session = PlaybackHistorySession()
            session.update("606", true, 100L, 1_000L)

            val completed = session.onMediaItemTransition("606", reason, 2_500L)
            val restarted = session.update("606", true, 200L, 2_500L)

            assertEquals(1_500L, completed?.playedDurationMs)
            assertEquals(200L, restarted.startedAtMs)
        }
    }

    @Test
    fun updateRecoversAChangedMediaItemWithoutLosingCompletion() {
        val session = PlaybackHistorySession()
        session.update("707", true, 100L, 1_000L)

        val update = session.update("808", true, 200L, 3_000L)

        assertEquals("707", update.completed?.mediaId)
        assertEquals(2_000L, update.completed?.playedDurationMs)
        assertEquals(200L, update.startedAtMs)
    }

    @Test
    fun finishingTwiceDoesNotDuplicateDuration() {
        val session = PlaybackHistorySession()
        session.update("909", true, 100L, 1_000L)

        assertEquals(1_000L, session.finish(2_000L)?.playedDurationMs)
        assertNull(session.finish(3_000L))
        assertEquals(400L, session.update("909", true, 400L, 4_000L).startedAtMs)
    }

    @Test
    fun nonMonotonicClockDoesNotProduceNegativeDuration() {
        val session = PlaybackHistorySession()
        session.update("1001", true, 100L, 2_000L)

        assertEquals(0L, session.finish(1_000L)?.playedDurationMs)
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
    fun pausedSessionDoesNotStartPersistence() = runBlocking {
        val session = PlaybackHistorySession()
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        var writes = 0

        session.onMediaItemTransition(
            mediaId = "paused",
            reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
            realtimeMs = 0L,
        )
        val startedAt = session.update("paused", false, 10L, 10L).startedAtMs
        if (startedAt != null) {
            scope.launchPlaybackHistoryPersistence { writes++ }
        }

        assertNull(startedAt)
        assertEquals(0, writes)
        scope.cancel()
    }
}
