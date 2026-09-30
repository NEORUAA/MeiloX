package com.ljyh.mei.playback

import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudPlaybackReportIdentityTest {
    private val cloud = SongSourceIdentity(999, 88, 7, 17)
    private val origin = PlaybackHistorySource(456, "list")
    private val started = 1_700_000_000_123L
    private data class Event(val action: String, val fields: Map<String, Any>, val details: PlaybackReportDetails)
    private class Sink : PlaybackReportSink {
        var identity = SessionIdentity(7, true, false)
        override val sessions = SessionStore().apply { bind { identity } }
        val events = mutableListOf<Event>()
        var gate: CompletableDeferred<Unit>? = null
        override fun requireOwner(owner: SessionStamp) {
            sessions.requireCurrent(owner)
            if (!owner.identity.authenticated || owner.identity.anonymous || sessions.recoveryRequired.value) {
                throw SessionChangedException()
            }
        }
        override suspend fun submit(action: String, fields: Map<String, Any>, owner: SessionStamp, details: PlaybackReportDetails) {
            gate?.await()
            requireReportSource(fields, owner, details)
            events += Event(action, fields, details)
        }
    }

    @Test fun cloudAudioIdIsSeparateFromEntryFileOwnerAccountAndPlaybackOrigin() = runTest {
        val sink = Sink()
        val reporter = PlaybackHistoryReporter(sink, StandardTestDispatcher(testScheduler))
        try {
            reporter.recordStart(cloud.key, cloud, origin, started)
            reporter.recordDuration(CompletedPlaybackHistorySession(cloud.key, 70_999, started, "ui"), started + 90_000)
            runCurrent()
            assertEquals(listOf("startplay", "play"), sink.events.map { it.action })
            for (event in sink.events) {
                assertEquals(999L, event.fields["id"])
                assertEquals("list", event.fields["source"])
                assertEquals("456", event.fields["sourceId"])
                assertEquals(started / 1_000, event.fields["startlogtime"])
                assertEquals(cloud, event.details.songSource)
                assertFalse(event.fields.values.contains(cloud.key))
                assertFalse(event.fields.values.contains(cloud.downloadId))
            }
            assertEquals(70L, sink.events.last().fields["time"])
            assertEquals(setOf("type", "id", "source", "sourceId", "startlogtime", "logtime", "time", "end"),
                sink.events.last().fields.keys)
        } finally { reporter.close(); runCurrent() }
    }

    @Test fun foreignCloudSourcesAndForgedEntryKeysCannotStartAReport() = runTest {
        val sink = Sink()
        val reporter = PlaybackHistoryReporter(sink, StandardTestDispatcher(testScheduler))
        try {
            reporter.recordStart(cloud.key, cloud.copy(accountId = 8), origin, started)
            reporter.recordStart("17", cloud, origin, started)
            reporter.recordStart(cloud.key, 17, origin, started)
            reporter.recordStart(cloud.key, cloud, origin, started, PlaybackReportDetails(started, songSource = cloud.copy(cloudOwnerId = 89)))
            reporter.recordStart(cloud.key, cloud.copy(cloudOwnerId = 0), origin, started)
            runCurrent()
            assertTrue(sink.events.isEmpty())
        } finally { reporter.close(); runCurrent() }
    }

    @Test fun staleFileCompletionsCannotCloseANewFileWithTheSameEntryAndAudio() = runTest {
        val sink = Sink()
        val reporter = PlaybackHistoryReporter(sink, StandardTestDispatcher(testScheduler))
        val replacement = cloud.copy(cloudOwnerId = 89)
        try {
            reporter.recordStart(cloud.key, cloud, origin, started)
            reporter.recordStart(replacement.key, replacement, origin, started)
            reporter.recordDuration(CompletedPlaybackHistorySession(cloud.key, 80_000, started, "ui"), started + 80_000)
            reporter.recordDuration(CompletedPlaybackHistorySession("17", 80_000, started, "ui"), started + 80_000)
            reporter.recordDuration(CompletedPlaybackHistorySession(replacement.key, 45_000, started, "ui"), started + 80_000)
            runCurrent()
            assertEquals(listOf("startplay", "startplay", "play"), sink.events.map { it.action })
            assertEquals(replacement, sink.events.last().details.songSource)
            assertEquals(45L, sink.events.last().fields["time"])
        } finally { reporter.close(); runCurrent() }
    }

    @Test fun recoveryBeforeQueuedDeliveryDropsBothCloudEvents() = runTest {
        val sink = Sink()
        val reporter = PlaybackHistoryReporter(sink, StandardTestDispatcher(testScheduler))
        try {
            reporter.recordStart(cloud.key, cloud, origin, started)
            reporter.recordDuration(CompletedPlaybackHistorySession(cloud.key, 45_000, started, "ui"), started + 80_000)
            sink.sessions.setRecoveryRequired(true)
            runCurrent()
            assertTrue(sink.events.isEmpty())
        } finally { reporter.close(); runCurrent() }
    }

    @Test fun accountChangeWhileDeliveryIsSuspendedCannotFinalizeAnOldCloudFile() = runTest {
        val sink = Sink().apply { gate = CompletableDeferred() }
        val reporter = PlaybackHistoryReporter(sink, StandardTestDispatcher(testScheduler))
        try {
            reporter.recordStart(cloud.key, cloud, origin, started)
            reporter.recordDuration(CompletedPlaybackHistorySession(cloud.key, 45_000, started, "ui"), started + 80_000)
            runCurrent()
            sink.identity = SessionIdentity(8, true, false)
            sink.gate!!.complete(Unit)
            runCurrent()
            assertTrue(sink.events.isEmpty())
        } finally { reporter.close(); runCurrent() }
    }

    @Test fun sameAccountReauthorizationDoesNotDeliverQueuedCloudEvents() = runTest {
        val sink = Sink()
        val reporter = PlaybackHistoryReporter(sink, StandardTestDispatcher(testScheduler))
        try {
            reporter.recordStart(cloud.key, cloud, origin, started)
            reporter.recordDuration(CompletedPlaybackHistorySession(cloud.key, 45_000, started, "ui"), started + 80_000)
            sink.sessions.invalidate()
            runCurrent()
            assertTrue(sink.events.isEmpty())
        } finally { reporter.close(); runCurrent() }
    }

    @Test fun boundaryRejectsAnEntryIdBodyOrForeignAccountWithoutCallingTheSink() {
        val sink = Sink()
        val owner = sink.sessions.snapshot()
        val details = PlaybackReportDetails(started, songSource = cloud)
        sink.requireReportSource(mapOf("id" to 999L), owner, details)
        assertThrows(IllegalArgumentException::class.java) { sink.requireReportSource(mapOf("id" to 17L), owner, details) }
        assertThrows(SessionChangedException::class.java) {
            sink.requireReportSource(mapOf("id" to 999L), owner, details.copy(songSource = cloud.copy(accountId = 8)))
        }
        assertTrue(sink.events.isEmpty())
    }
}
