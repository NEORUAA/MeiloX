package com.ljyh.mei.playback

import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackReportSinkTest {
    @Test fun reporterNeedsOnlyTheSharedContractAndNeverCopiesCredentials() = runTest {
        val sessions = SessionStore().apply { bind { SessionIdentity(1, true, false) } }
        val events = mutableListOf<Pair<String, Map<String, Any>>>()
        val sink = object : PlaybackReportSink {
            override val sessions = sessions
            override fun requireOwner(owner: SessionStamp) = sessions.requireCurrent(owner)
            override fun submit(action: String, fields: Map<String, Any>, owner: SessionStamp) {
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
