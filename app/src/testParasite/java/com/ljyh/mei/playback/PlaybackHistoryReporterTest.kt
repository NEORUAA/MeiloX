package com.ljyh.mei.playback

import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.parasite.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackHistoryReporterTest {
    private var identity = SessionIdentity(10, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private val bridge = HostPlaybackReportBridge(sessions)
    private data class Event(val action: String, val fields: Map<String, Any>, val owner: SessionStamp)
    private val events = mutableListOf<Event>()
    private val source = PlaybackHistorySource(456, "list")
    private val started = 1_700_000_000_000L
    init {
        bridge.bind(object : HostPlaybackReportBackend {
            override fun emit(action: String, fields: Map<String, Any>, owner: SessionStamp) {
                events += Event(action, fields, owner)
            }
        })
    }
    private fun completed(id: String = "123", time: Long = 70_999, start: Long = started) =
        CompletedPlaybackHistorySession(id, time, start, "ui")

    @Test fun startDoesNotFinishPlaybackAndDurationKeepsTheCapturedTimeAndOwner() = runTest {
        val reporter = PlaybackHistoryReporter(bridge, StandardTestDispatcher(testScheduler))
        reporter.recordStart("123", 123, source, started, PlaybackReportDetails(started, "Local title", "Local artist", 123_000))
        runCurrent()
        assertEquals(listOf("startplay"), events.map { it.action })
        reporter.recordDuration(completed(), started + 74_000)
        runCurrent()
        assertEquals(listOf("startplay", "play"), events.map { it.action })
        assertEquals(70L, events.last().fields["time"])
        assertEquals(started / 1_000, events.last().fields["startlogtime"])
        assertEquals(started + 74_000, events.last().fields["logtime"])
        assertEquals("ui", events.last().fields["end"])
        assertEquals(events.first().owner, events.last().owner)
        assertTrue(events.all { it.fields.keys.none { key -> key in setOf("MUSIC_U", "cookie", "deviceId", "mainsiteWeb") } })
        assertEquals(setOf("type", "id", "source", "sourceId", "startlogtime", "logtime", "time", "end"), events.last().fields.keys)
        reporter.close()
        runCurrent()
    }

    @Test fun pauseAndBufferTimeStayOutOfTheOfficialCompletionEvent() = runTest {
        val reporter = PlaybackHistoryReporter(bridge, StandardTestDispatcher(testScheduler))
        val timer = PlaybackHistorySession()
        timer.update("123", true, started, 0).startedAtMs?.let { reporter.recordStart("123", 123, source, it) }
        timer.update("123", false, started + 35_000, 35_000)
        timer.update("123", true, started + 40_000, 40_000)
        reporter.recordDuration(checkNotNull(timer.finish(75_000)), started + 75_000)
        runCurrent()
        assertEquals(70L, events.last().fields["time"])
        reporter.close()
        runCurrent()
    }

    @Test fun queuedOldSessionEventsAreNotSubmittedUnderANewAuthorization() = runTest {
        val reporter = PlaybackHistoryReporter(bridge, StandardTestDispatcher(testScheduler))
        reporter.recordStart("123", 123, source, started)
        reporter.recordDuration(completed(), started + 80_000)
        sessions.invalidate()
        runCurrent()
        assertTrue(events.isEmpty())
        reporter.close()
        runCurrent()
    }

    @Test fun invalidationAfterStartCannotFinalizeUnderAnotherAccount() = runTest {
        val reporter = PlaybackHistoryReporter(bridge, StandardTestDispatcher(testScheduler))
        reporter.recordStart("123", 123, source, started)
        runCurrent()
        identity = SessionIdentity(20, true, false)
        reporter.recordDuration(completed(), started + 80_000)
        runCurrent()
        assertEquals(listOf("startplay"), events.map { it.action })
        reporter.close()
        runCurrent()
    }

    @Test fun staleOrDuplicateCompletionsDoNotCloseTheCurrentPlayback() = runTest {
        val reporter = PlaybackHistoryReporter(bridge, StandardTestDispatcher(testScheduler))
        reporter.recordStart("123", 123, source, started)
        reporter.recordStart("123", 123, source, started)
        reporter.recordDuration(completed(id = "456"), started + 80_000)
        reporter.recordDuration(completed(start = started - 1), started + 80_000)
        reporter.recordDuration(completed(), started + 80_000)
        reporter.recordDuration(completed(), started + 80_000)
        runCurrent()
        assertEquals(listOf("startplay", "play"), events.map { it.action })
        reporter.close()
        runCurrent()
    }

    @Test fun closeDrainsCurrentEventsAndRejectsLaterStarts() = runTest {
        val reporter = PlaybackHistoryReporter(bridge, StandardTestDispatcher(testScheduler))
        reporter.recordStart("123", 123, source, started)
        reporter.recordDuration(completed(), started + 80_000)
        reporter.close()
        reporter.recordStart("456", 456, source, started + 80_000)
        runCurrent()
        assertEquals(listOf("startplay", "play"), events.map { it.action })
    }

    @Test fun discardCancelsPendingWorkButAllowsAFreshPlayback() = runTest {
        val reporter = PlaybackHistoryReporter(bridge, StandardTestDispatcher(testScheduler))
        reporter.recordStart("123", 123, source, started)
        reporter.discardSession()
        reporter.recordStart("456", 456, source, started + 1)
        runCurrent()
        assertEquals(1, events.size)
        assertEquals(456L, events.single().fields["id"])
        reporter.close()
        runCurrent()
    }

    @Test fun guestAndRecoveryPlaybackDoNotCreateAccountEvents() = runTest {
        val reporter = PlaybackHistoryReporter(bridge, StandardTestDispatcher(testScheduler))
        sessions.setRecoveryRequired(true)
        reporter.recordStart("123", 123, source, started)
        sessions.setRecoveryRequired(false)
        identity = SessionIdentity(0, false, true)
        reporter.recordStart("123", 123, source, started)
        runCurrent()
        assertTrue(events.isEmpty())
        reporter.close()
        runCurrent()
    }

    @Test fun directHostBoundaryRejectsForeignCloudSourcesAndEntryIdBodies() = runTest {
        val owner = sessions.snapshot()
        val cloud = SongSourceIdentity(999, 88, 10, 17)
        val details = PlaybackReportDetails(started, songSource = cloud)
        assertTrue(runCatching { bridge.submit("startplay", mapOf("id" to 17L), owner, details) }
            .exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { bridge.submit("startplay", mapOf("id" to 999L), owner,
            details.copy(songSource = cloud.copy(accountId = 20))) }.exceptionOrNull() is com.ljyh.mei.data.session.SessionChangedException)
        assertTrue(events.isEmpty())
        bridge.submit("startplay", mapOf("id" to 999L), owner, details)
        assertEquals(999L, events.single().fields["id"])
        assertEquals(setOf("id"), events.single().fields.keys)
    }
}
