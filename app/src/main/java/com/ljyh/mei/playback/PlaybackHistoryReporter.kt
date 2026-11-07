package com.ljyh.mei.playback

import android.util.Log
import com.google.gson.JsonObject
import com.ljyh.mei.source.api.TrackRef
import com.ljyh.mei.source.core.BoundSourceFeatures
import com.ljyh.mei.source.core.SourceFeatureClient
import com.ljyh.mei.utils.log.logPlaybackHistory
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Serializes provider-owned platform reports independently of local playback history. */
internal class PlaybackHistoryReporter(private val features: SourceFeatureClient) {
    private class ActivePlayback(
        val mediaId: String,
        val ref: TrackRef,
        val startedAtMs: Long,
        val parameters: JsonObject,
    ) {
        var owner: BoundSourceFeatures? = null
    }

    private val reporterJob = SupervisorJob()
    private val scope = CoroutineScope(reporterJob + Dispatchers.IO)
    private val lock = Any()
    private var activePlayback: ActivePlayback? = null
    private var submissionJob: Job? = null
    private var closed = false

    fun recordStart(mediaId: String, ref: TrackRef, startedAtMs: Long, title: String,
        artist: String, durationMs: Long?) {
        synchronized(lock) {
            if (closed || activePlayback?.mediaId == mediaId) return
            val parameters = JsonObject().apply {
                addProperty("songId", ref.trackId)
                addProperty("sourceId", ref.trackId)
                addProperty("source", "track")
                addProperty("startedAtMs", startedAtMs)
                addProperty("title", title)
                addProperty("artist", artist)
                durationMs?.takeIf { it > 0 }?.let { addProperty("durationMs", it) }
            }
            val playback = ActivePlayback(mediaId, ref, startedAtMs, parameters)
            activePlayback = playback
            enqueueLocked("start") {
                val owner = features.bind(ref.providerId, "playback:${UUID.randomUUID()}")
                if (!owner.supports("history", "recordPlaybackStart")) return@enqueueLocked
                playback.owner = owner
                owner.invoke("history", "recordPlaybackStart", parameters)
            }
        }
    }

    fun recordDuration(completed: CompletedPlaybackHistorySession, endedAtMs: Long) {
        synchronized(lock) {
            if (closed) return
            val playback = activePlayback?.takeIf {
                it.mediaId == completed.mediaId && it.startedAtMs == completed.startedAtMs
            } ?: return
            activePlayback = null
            enqueueLocked("duration") {
                val owner = playback.owner ?: return@enqueueLocked
                if (!owner.supports("history", "recordPlaybackDuration")) return@enqueueLocked
                val parameters = playback.parameters.deepCopy().apply {
                    addProperty("timeSeconds", completed.playedDurationMs.coerceAtLeast(0L) / 1_000L)
                    addProperty("playedDurationMs", completed.playedDurationMs.coerceAtLeast(0L))
                    addProperty("endedAtMs", endedAtMs)
                    addProperty("endReason", completed.endReason)
                }
                owner.invoke("history", "recordPlaybackDuration", parameters)
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
        if (pending == null) { reporterJob.cancel(); return }
        scope.launch { pending.join(); reporterJob.cancel() }
    }

    private fun enqueueLocked(operation: String, block: suspend () -> Unit) {
        val previous = submissionJob
        submissionJob = scope.launch {
            previous?.join()
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                logPlaybackHistory(Log.WARN, "Source playback history unavailable action=%s exceptionType=%s",
                    operation, error.javaClass.simpleName)
            }
        }
    }
}
