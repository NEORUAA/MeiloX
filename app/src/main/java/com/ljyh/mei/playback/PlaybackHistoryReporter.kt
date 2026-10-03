package com.ljyh.mei.playback

import android.util.Log
import com.ljyh.mei.data.network.netease.NcblSessionContext
import com.ljyh.mei.data.network.netease.NcblSongInfo
import com.ljyh.mei.data.network.netease.NeteaseClientLogClient
import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.data.repository.PLAYBACK_HISTORY_DIAGNOSTIC_ENDPOINT
import com.ljyh.mei.data.repository.diagnosticSummary
import com.ljyh.mei.data.repository.playbackExceptionReason
import com.ljyh.mei.data.repository.playbackExceptionType
import com.ljyh.mei.utils.log.logPlaybackHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

/** Serializes NetEase playback history events without blocking local playback. */
internal class PlaybackHistoryReporter(
    private val repository: MeloXRepository,
    private val ncblClient: NeteaseClientLogClient,
) {
    private class ActivePlayback(
        val mediaId: String,
        val songId: Long,
        val source: PlaybackHistorySource,
        val startedAtMs: Long,
        val ncblSong: NcblSongInfo,
    ) {
        var ncblSession: NcblSessionContext? = null
    }

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
        startedAtMs: Long,
        song: NcblSongInfo,
    ) {
        if (songId <= 0L || source.sourceId <= 0L) return
        synchronized(lock) {
            if (closed || activePlayback?.mediaId == mediaId) return
            val playback = ActivePlayback(mediaId, songId, source, startedAtMs, song)
            activePlayback = playback
            enqueueLocked("playback start") {
                coroutineScope {
                    launch { submitWeblogStart(playback) }
                    launch { submitNcblStart(playback) }
                }
            }
        }
    }

    internal fun recordDuration(completed: CompletedPlaybackHistorySession, endedAtMs: Long) {
        synchronized(lock) {
            if (closed) return
            val playback = activePlayback?.takeIf {
                it.mediaId == completed.mediaId && it.startedAtMs == completed.startedAtMs
            } ?: return
            activePlayback = null
            val timeSeconds = completed.playedDurationMs.coerceAtLeast(0L) / 1_000L
            enqueueLocked("play duration") {
                coroutineScope {
                    launch { submitWeblogDuration(playback, completed, endedAtMs, timeSeconds) }
                    launch { submitNcblDuration(playback, completed, endedAtMs) }
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

    private suspend fun submitNcblStart(playback: ActivePlayback) {
        try {
            val session = ncblClient.beginSession(
                song = playback.ncblSong.copy(id = playback.songId),
                source = playback.source.source,
                sourceId = playback.source.sourceId,
                startedAtMs = playback.startedAtMs,
            ) ?: return
            playback.ncblSession = session
            ncblClient.submitStart(session, eventTimeMs = playback.startedAtMs)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logPlaybackHistory(
                Log.WARN,
                "NCBL session initialization failed exceptionType=%s",
                playbackExceptionType(error),
            )
        }
    }

    private suspend fun submitNcblDuration(
        playback: ActivePlayback,
        completed: CompletedPlaybackHistorySession,
        endedAtMs: Long,
    ) {
        val session = playback.ncblSession ?: return
        try {
            ncblClient.submitEnd(
                session = session,
                playedDurationMs = completed.playedDurationMs,
                eventTimeMs = endedAtMs,
                endReason = completed.endReason,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logPlaybackHistory(
                Log.WARN,
                "NCBL completion failed action=_pld exceptionType=%s",
                playbackExceptionType(error),
            )
        }
    }

    private suspend fun submitWeblogStart(playback: ActivePlayback) {
        try {
            val result = repository.recordPlaybackStart(
                songId = playback.songId,
                sourceId = playback.source.sourceId,
                source = playback.source.source,
                startedAtMs = playback.startedAtMs,
            )
            if (result?.businessAccepted == true) {
                logPlaybackHistory(Log.INFO, "Playback history start accepted")
            } else {
                logPlaybackHistory(
                    Log.WARN,
                    "Playback history start was not accepted: %s",
                    result?.diagnosticSummary() ?: "not attempted",
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logPlaybackHistory(
                Log.WARN,
                "Playback history request failed endpoint=%s action=startplay exceptionType=%s reason=%s",
                PLAYBACK_HISTORY_DIAGNOSTIC_ENDPOINT,
                playbackExceptionType(error),
                playbackExceptionReason(error),
            )
        }
    }

    private suspend fun submitWeblogDuration(
        playback: ActivePlayback,
        completed: CompletedPlaybackHistorySession,
        endedAtMs: Long,
        timeSeconds: Long,
    ) {
        try {
            val result = repository.recordPlaybackDuration(
                songId = playback.songId,
                sourceId = playback.source.sourceId,
                source = playback.source.source,
                timeSeconds = timeSeconds,
                startedAtMs = completed.startedAtMs,
                endedAtMs = endedAtMs,
                endReason = completed.endReason,
            )
            if (result?.businessAccepted == true) {
                logPlaybackHistory(Log.INFO, "Playback history duration accepted time=%s", timeSeconds)
            } else {
                logPlaybackHistory(
                    Log.WARN,
                    "Playback history duration was not accepted time=%s: %s",
                    timeSeconds,
                    result?.diagnosticSummary() ?: "not attempted",
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logPlaybackHistory(
                Log.WARN,
                "Playback history request failed endpoint=%s action=play duration exceptionType=%s reason=%s",
                PLAYBACK_HISTORY_DIAGNOSTIC_ENDPOINT,
                playbackExceptionType(error),
                playbackExceptionReason(error),
            )
        }
    }
}
