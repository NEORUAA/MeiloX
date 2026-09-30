package com.ljyh.mei.playback

import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.model.SongSourceIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackReportSinkTest {
    @Test fun queuedEventsRetainTheirOwnMetadataAndWaitForSuspendingDelivery() = runTest {
        val sessions = SessionStore().apply { bind { SessionIdentity(1, true, false) } }
        val events = mutableListOf<Pair<String, PlaybackReportDetails>>()
        val gate = CompletableDeferred<Unit>()
        val first = PlaybackReportDetails(1_000_123, "First", "Artist One", 240_000)
        val second = PlaybackReportDetails(1_050_987, "Second", "Artist Two", 180_000)
        val sink = object : PlaybackReportSink {
            override val sessions = sessions
            override fun requireOwner(owner: SessionStamp) = sessions.requireCurrent(owner)
            override suspend fun submit(action: String, fields: Map<String, Any>, owner: SessionStamp, details: PlaybackReportDetails) {
                requireOwner(owner)
                if (events.isEmpty()) gate.await()
                events += action to details
            }
        }
        val reporter = PlaybackHistoryReporter(sink, StandardTestDispatcher(testScheduler))
        try {
            reporter.recordStart("1", 1, PlaybackHistorySource(456, "list"), first.startedAtMs, first)
            reporter.recordDuration(CompletedPlaybackHistorySession("1", 45_999, first.startedAtMs, "ui"), 1_050_000)
            reporter.recordStart("2", 2, PlaybackHistorySource(456, "list"), second.startedAtMs, second)
            runCurrent()
            assertTrue(events.isEmpty())
            gate.complete(Unit)
            runCurrent()
            val capturedFirst = first.copy(songSource = SongSourceIdentity(1))
            val capturedSecond = second.copy(songSource = SongSourceIdentity(2))
            assertEquals(listOf("startplay" to capturedFirst, "play" to capturedFirst, "startplay" to capturedSecond), events)
        } finally { reporter.close(); runCurrent() }
    }

    @Test fun discardCancelsSuspendingDeliveryAndPendingOldEvents() = runTest {
        val sessions = SessionStore().apply { bind { SessionIdentity(1, true, false) } }
        val started = mutableListOf<Long>()
        val cancelled = mutableListOf<Long>()
        val sink = object : PlaybackReportSink {
            override val sessions = sessions
            override fun requireOwner(owner: SessionStamp) = sessions.requireCurrent(owner)
            override suspend fun submit(action: String, fields: Map<String, Any>, owner: SessionStamp, details: PlaybackReportDetails) {
                val id = fields["id"] as Long
                started += id
                try { awaitCancellation() } finally { cancelled += id }
            }
        }
        val reporter = PlaybackHistoryReporter(sink, StandardTestDispatcher(testScheduler))
        reporter.recordStart("1", 1, PlaybackHistorySource(456, "list"), 1_000_123)
        reporter.recordDuration(CompletedPlaybackHistorySession("1", 45_000, 1_000_123, "ui"), 1_050_000)
        runCurrent()
        reporter.discardSession()
        runCurrent()
        assertEquals(listOf(1L), started)
        assertEquals(listOf(1L), cancelled)
        reporter.recordStart("2", 2, PlaybackHistorySource(456, "list"), 1_100_456)
        runCurrent()
        assertEquals(listOf(1L, 2L), started)
        reporter.discardSession()
        reporter.close()
        runCurrent()
        assertEquals(listOf(1L, 2L), cancelled)
    }

    @Test fun reporterNeedsOnlyTheSharedContractAndNeverCopiesCredentials() = runTest {
        val sessions = SessionStore().apply { bind { SessionIdentity(1, true, false) } }
        val events = mutableListOf<Pair<String, Map<String, Any>>>()
        val sink = object : PlaybackReportSink {
            override val sessions = sessions
            override fun requireOwner(owner: SessionStamp) = sessions.requireCurrent(owner)
            override suspend fun submit(action: String, fields: Map<String, Any>, owner: SessionStamp, details: PlaybackReportDetails) {
                requireOwner(owner)
                events += action to fields
            }
        }
        val reporter = PlaybackHistoryReporter(sink, StandardTestDispatcher(testScheduler))
        try {
            reporter.recordStart("123", 123, PlaybackHistorySource(456, "list"), 1_000_000)
            reporter.recordDuration(CompletedPlaybackHistorySession("123", 45_000, 1_000_000, "ui"), 1_050_000)
            runCurrent()
            assertEquals(listOf("startplay", "play"), events.map { it.first })
            assertEquals(45L, events.last().second["time"])
            assertEquals(1000L, events.last().second["startlogtime"])
            assertEquals(setOf("type", "id", "source", "sourceId", "startlogtime", "logtime", "time", "end"), events.last().second.keys)
        } finally { reporter.close(); runCurrent() }
    }
}
