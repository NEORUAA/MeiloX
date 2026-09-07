package com.ljyh.mei.playback

import android.util.Log
import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.data.repository.PLAYBACK_HISTORY_DIAGNOSTIC_ENDPOINT
import com.ljyh.mei.data.repository.diagnosticSummary
import com.ljyh.mei.data.repository.failureSummary
import com.ljyh.mei.data.repository.playbackExceptionReason
import com.ljyh.mei.data.repository.playbackExceptionType
import com.ljyh.mei.utils.log.logPlaybackHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Serializes NetEase playback history events without blocking local playback. */
class PlaybackHistoryReporter(
    private val repository: MeloXRepository,
) {
    private data class ActivePlayback(
        val mediaId: String,
        val songId: Long,
        val source: PlaybackHistorySource,
    )

    private val reporterJob = SupervisorJob()
    private val scope = CoroutineScope(reporterJob + Dispatchers.IO)
    private val lock = Any()
    private var activePlayback: ActivePlayback? = null
    private var submissionJob: Job? = null
    private var closed = false

    internal fun recordStart(
        mediaId: String,
        songId: Long,
        source: PlaybackHistorySource,
    ) {
        if (songId <= 0L || source.sourceId <= 0L) return
        synchronized(lock) {
            if (closed || activePlayback?.mediaId == mediaId) return
            activePlayback = ActivePlayback(mediaId, songId, source)
            enqueueLocked("startplay->play") {
                val result = repository.recordPlaybackStart(
                    songId = songId,
                    sourceId = source.sourceId,
                    source = source.source,
                )
                if (result?.accepted == true) {
                    logPlaybackHistory(Log.INFO, "Playback history start accepted")
                } else {
                    logPlaybackHistory(
                        Log.WARN,
                        "Playback history start was not accepted: %s",
                        result?.failureSummary()
                            ?: "startplay={not attempted} play={not attempted}",
                    )
                }
            }
        }
    }

    internal fun recordDuration(mediaId: String, playedDurationMs: Long) {
        synchronized(lock) {
            if (closed) return
            val playback = activePlayback?.takeIf { it.mediaId == mediaId } ?: return
            activePlayback = null
            val timeSeconds = playedDurationMs.coerceAtLeast(0L) / 1_000L
            enqueueLocked("play duration") {
                val result = repository.recordPlaybackDuration(
                    songId = playback.songId,
                    sourceId = playback.source.sourceId,
                    source = playback.source.source,
                    timeSeconds = timeSeconds,
                )
                if (result?.businessAccepted == true) {
                    logPlaybackHistory(
                        Log.INFO,
                        "Playback history duration accepted time=%s",
                        timeSeconds,
                    )
                } else {
                    logPlaybackHistory(
                        Log.WARN,
                        "Playback history duration was not accepted time=%s: %s",
                        timeSeconds,
                        result?.diagnosticSummary() ?: "not attempted",
                    )
                }
            }
        }
    }

    /** Starts a non-blocking drain and rejects all future events. */
    fun close() {
        val pending = synchronized(lock) {
            if (closed) return
            closed = true
            submissionJob
        }
        if (pending == null) {
            reporterJob.cancel()
            return
        }

        scope.launch {
            pending.join()
            reporterJob.cancel()
        }
    }

    private fun enqueueLocked(operation: String, block: suspend () -> Unit) {
        val previous = submissionJob
        submissionJob = scope.launch {
            previous?.join()
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logPlaybackHistory(
                    Log.WARN,
                    "Playback history request failed endpoint=%s action=%s " +
                        "exceptionType=%s reason=%s",
                    PLAYBACK_HISTORY_DIAGNOSTIC_ENDPOINT,
                    operation,
                    playbackExceptionType(error),
                    playbackExceptionReason(error),
                )
            }
        }
    }
}
