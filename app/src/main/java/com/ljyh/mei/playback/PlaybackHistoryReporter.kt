package com.ljyh.mei.playback

import android.util.Log
import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.data.repository.PLAYBACK_HISTORY_DIAGNOSTIC_ENDPOINT
import com.ljyh.mei.data.repository.failureSummary
import com.ljyh.mei.data.repository.playbackExceptionReason
import com.ljyh.mei.data.repository.playbackExceptionType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.ljyh.mei.utils.log.logPlaybackHistory

/** Serializes NetEase playback history events without blocking local playback. */
class PlaybackHistoryReporter(
    private val repository: MeloXRepository,
) {
    private val reporterJob = SupervisorJob()
    private val scope = CoroutineScope(reporterJob + Dispatchers.IO)
    private val lock = Any()
    private var submissionJob: Job? = null
    private var closed = false

    internal fun recordStart(
        songId: Long,
        source: PlaybackHistorySource,
    ) {
        if (songId <= 0L || source.sourceId <= 0L) return
        enqueue(
            "startplay->play",
        ) {
            val result = repository.recordPlaybackStart(
                songId = songId,
                sourceId = source.sourceId,
                source = source.source,
            )
            if (result?.accepted == true) {
                logPlaybackHistory(Log.INFO, "Playback history weblog accepted")
            } else {
                logPlaybackHistory(
                    Log.WARN,
                    "Playback history weblog was not accepted: %s",
                    result?.failureSummary()
                        ?: "startplay={not attempted} play={not attempted}",
                )
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

    private fun enqueue(operation: String, block: suspend () -> Unit) {
        synchronized(lock) {
            if (closed) return
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
}
