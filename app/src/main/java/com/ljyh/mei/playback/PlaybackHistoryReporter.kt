package com.ljyh.mei.playback

import com.ljyh.mei.data.session.SessionStamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/** Serializes actual playback events for the selected backend's reporting sink. */
internal class PlaybackHistoryReporter(
    private val bridge: PlaybackReportSink,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val report: (String) -> Unit = {},
) {
    private data class ActivePlayback(
        val mediaId: String,
        val songId: Long,
        val source: PlaybackHistorySource,
        val startedAtMs: Long,
        val owner: SessionStamp,
        val details: PlaybackReportDetails,
    )

    private val reporterJob = SupervisorJob()
    private val scope = CoroutineScope(reporterJob + dispatcher)
    private val lock = Any()
    private var activePlayback: ActivePlayback? = null
    private var submissionJob: Job? = null
    private var closed = false

    fun recordStart(
        mediaId: String, songId: Long, source: PlaybackHistorySource, startedAtMs: Long,
        details: PlaybackReportDetails = PlaybackReportDetails(startedAtMs),
    ) {
        if (songId <= 0 || source.sourceId <= 0 || startedAtMs <= 0) return
        require(details.startedAtMs == startedAtMs)
        val owner = runCatching { bridge.sessions.snapshot().also(bridge::requireOwner) }.getOrNull() ?: return
        synchronized(lock) {
            if (closed || activePlayback?.let { it.mediaId == mediaId && it.startedAtMs == startedAtMs } == true) return
            val playback = ActivePlayback(mediaId, songId, source, startedAtMs, owner, details)
            activePlayback = playback
            enqueueLocked { bridge.submit("startplay", playback.fields(startedAtMs), owner, details) }
        }
    }

    fun recordDuration(completed: CompletedPlaybackHistorySession, endedAtMs: Long) {
        synchronized(lock) {
            if (closed) return
            val playback = activePlayback?.takeIf {
                it.mediaId == completed.mediaId && it.startedAtMs == completed.startedAtMs
            } ?: return
            activePlayback = null
            val fields = playback.fields(endedAtMs) + mapOf(
                "time" to completed.playedDurationMs.coerceAtLeast(0) / 1_000,
                "end" to completed.endReason,
            )
            enqueueLocked { bridge.submit("play", fields, playback.owner, playback.details) }
        }
    }

    fun discardSession() = synchronized(lock) {
        activePlayback = null
        reporterJob.cancelChildren()
        submissionJob = null
    }

    fun close() {
        val pending = synchronized(lock) {
            if (closed) return
            closed = true
            submissionJob
        }
        scope.launch {
            pending?.join()
            reporterJob.cancel()
        }
    }

    private fun enqueueLocked(block: suspend () -> Unit) {
        val previous = submissionJob
        submissionJob = scope.launch {
            previous?.join()
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                report("playback_report_failed type=${error.javaClass.simpleName}")
            }
        }
    }

    private fun ActivePlayback.fields(loggedAtMs: Long): Map<String, Any> = mapOf(
        "type" to "song", "id" to songId, "source" to source.source,
        "sourceId" to source.sourceId.toString(), "startlogtime" to startedAtMs / 1_000,
        "logtime" to loggedAtMs, "time" to 0L,
    )
}
