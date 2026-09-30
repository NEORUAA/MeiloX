package com.ljyh.mei.parasite

import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.playback.CompletedPlaybackHistorySession
import com.ljyh.mei.playback.PlaybackHistoryReporter
import com.ljyh.mei.playback.PlaybackHistorySource
import com.ljyh.mei.playback.PlaybackReportDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** A separate synthetic report bridge: never bind the real graph or call the official SDK. */
internal object HostCloudPlaybackProbe {
    fun run() = runBlocking {
        var account = SessionIdentity(7, true, false)
        val sessions = SessionStore().apply { bind { account } }
        val events = mutableListOf<Pair<String, Map<String, Any>>>()
        val bridge = HostPlaybackReportBridge(sessions).apply {
            bind(object : HostPlaybackReportBackend {
                override fun emit(action: String, fields: Map<String, Any>, owner: SessionStamp) {
                    events += action to fields
                }
            })
        }
        val reporter = PlaybackHistoryReporter(bridge, Dispatchers.Unconfined)
        val cloud = SongSourceIdentity(999, 88, 7, 17)
        val started = 1_700_000_000_123L
        val origin = PlaybackHistorySource(456, "list")
        try {
            reporter.recordStart(cloud.key, cloud, origin, started)
            reporter.recordDuration(CompletedPlaybackHistorySession(cloud.key, 70_999, started, "ui"), started + 90_000)
            check(events.map { it.first } == listOf("startplay", "play"))
            check(events.all { it.second["id"] == 999L && it.second["sourceId"] == "456" &&
                it.second["source"] == "list" && cloud.key !in it.second.values && cloud.downloadId !in it.second.values })
            check(events.last().second["time"] == 70L)
            val owner = sessions.snapshot()
            val details = PlaybackReportDetails(started, songSource = cloud)
            check(runCatching { bridge.submit("startplay", mapOf("id" to 17L), owner, details) }.isFailure)
            check(runCatching { bridge.submit("startplay", mapOf("id" to 999L), owner,
                details.copy(songSource = cloud.copy(accountId = 8))) }.exceptionOrNull() is SessionChangedException)
            sessions.setRecoveryRequired(true)
            reporter.recordStart(cloud.key, cloud, origin, started + 100_000)
            sessions.setRecoveryRequired(false)
            account = SessionIdentity(8, true, false)
            reporter.recordStart(cloud.key, cloud, origin, started + 100_001)
            check(events.size == 2)
            HostRuntimeProbe.report("cloud_playback_closed_passed audio_id=true full_source_key=true " +
                "account_guard=true recovery_guard=true timer_seconds=true synthetic_only=true official_reports=0")
        } finally { reporter.close() }
    }
}
