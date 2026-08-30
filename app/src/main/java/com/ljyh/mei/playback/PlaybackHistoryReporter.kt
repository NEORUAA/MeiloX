package com.ljyh.mei.playback

import androidx.media3.common.MediaItem
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.data.repository.MeloXRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

/** Serializes NetEase playback history events without blocking local playback. */
class PlaybackHistoryReporter(
    private val scope: CoroutineScope,
    private val repository: MeloXRepository,
) {
    private data class ActivePlayback(
        val mediaId: String,
        val songId: Long,
        val sourceId: Long,
        val durationMs: Long,
        var positionMs: Long,
    )

    private var active: ActivePlayback? = null
    private var submissionJob: Job? = null

    fun recordStart(mediaItem: MediaItem, positionMs: Long = 0) {
        val target = mediaItem.toReportTarget(positionMs) ?: return
        if (active?.mediaId == target.mediaId) {
            active?.positionMs = positionMs.coerceAtLeast(0)
            return
        }
        finishActive()
        active = target
        enqueue("start songId=${target.songId}") {
            repository.recordRecentPlayback(target.songId, target.sourceId)
        }
    }

    fun updatePosition(mediaId: String?, positionMs: Long) {
        active?.takeIf { it.mediaId == mediaId }?.positionMs = positionMs.coerceAtLeast(0)
    }

    fun finish(mediaId: String?, positionMs: Long, completed: Boolean = false) {
        val current = active ?: return
        if (mediaId != null && current.mediaId != mediaId) return
        current.positionMs = positionMs.coerceAtLeast(0)
        finishActive(completed)
    }

    fun finishIfChanged(mediaItem: MediaItem?, completedPrevious: Boolean = false) {
        val current = active ?: return
        if (mediaItem?.mediaId != current.mediaId) finishActive(completedPrevious)
    }

    private fun finishActive(completed: Boolean = false) {
        val current = active ?: return
        active = null
        val recordedMs = if (completed && current.durationMs > 0) {
            current.durationMs
        } else if (current.durationMs > 0) {
            current.positionMs.coerceAtMost(current.durationMs)
        } else {
            current.positionMs
        }
        val timeSeconds = (recordedMs / 1_000L).coerceAtLeast(0).toInt()
        enqueue("duration songId=${current.songId} time=$timeSeconds") {
            repository.recordPlaybackDuration(current.songId, current.sourceId, timeSeconds)
        }
    }

    private fun enqueue(operation: String, block: suspend () -> Unit) {
        val previous = submissionJob
        submissionJob = scope.launch {
            previous?.join()
            try {
                block()
                Timber.tag(TAG).d("Playback history report succeeded: %s", operation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.tag(TAG).w(error, "Playback history report failed: %s", operation)
            }
        }
    }

    private fun MediaItem.toReportTarget(positionMs: Long): ActivePlayback? {
        val metadata = metadata ?: return null
        if (metadata.isPodcast || metadata.isLocal) return null
        val songId = mediaId.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return ActivePlayback(
            mediaId = mediaId,
            songId = songId,
            sourceId = metadata.album.id.coerceAtLeast(0),
            durationMs = metadata.duration.coerceAtLeast(0),
            positionMs = positionMs.coerceAtLeast(0),
        )
    }

    private companion object {
        const val TAG = "PlaybackHistory"
    }
}
