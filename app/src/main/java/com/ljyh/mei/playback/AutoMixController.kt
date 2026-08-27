package com.ljyh.mei.playback

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.ljyh.mei.constants.AutoMixDurationKey
import com.ljyh.mei.constants.AutoMixEnabledKey
import com.ljyh.mei.constants.AutoMixFadeCurve
import com.ljyh.mei.constants.AutoMixFadeCurveKey
import com.ljyh.mei.constants.AutoMixMaxTempoAdjustmentKey
import com.ljyh.mei.constants.AutoMixMode
import com.ljyh.mei.constants.AutoMixModeKey
import com.ljyh.mei.constants.AutoMixTailCutBarsKey
import com.ljyh.mei.constants.AutoMixTempoMatchingKey
import com.ljyh.mei.constants.AutoMixTransitionBarsKey
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.toEnum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class AutoMixRuntimeConfiguration(
    val enabled: Boolean = false,
    val mode: AutoMixMode = AutoMixMode.Smart,
    val durationMs: Long = 8_000,
    val fadeCurve: AutoMixFadeCurve = AutoMixFadeCurve.EqualPower,
    val tempoMatching: Boolean = true,
    val maximumTempoAdjustmentPercent: Float = 8f,
    val transitionBars: Int = 8,
    val tailCutBars: Int = 0,
)

/**
 * Two-deck crossfade coordinator. The primary deck remains the MediaSession player while the
 * secondary deck overlaps the next item, then hands the elapsed incoming position back.
 */
class AutoMixController(
    context: Context,
    private val primary: ExoPlayer,
    private val secondary: ExoPlayer,
    private val scope: CoroutineScope,
    private val sourceResolver: suspend (MediaItem) -> android.net.Uri,
) : Player.Listener {
    var isTransitioning: Boolean = false
        private set

    private var configuration = AutoMixRuntimeConfiguration()
    private var preparedMediaId: String? = null
    private var preparedTargetIndex = -1
    private var transitionJob: Job? = null
    private var analysisJob: Job? = null
    private var smartPlan: SmartAutoMixPlan? = null
    private var analyzedAttempt: Pair<String, String>? = null
    @Volatile
    private var sourceGeneration = 0L
    private val analyzer = BeatNetAutoMixAnalyzer(context.applicationContext)
    private var pausingPrimaryForHandoff = false
    private var monitorJob: Job? = null
    private var preferenceJob: Job? = null

    init {
        primary.addListener(this)
        secondary.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                preparedMediaId = null
                preparedTargetIndex = -1
            }
        })
        preferenceJob = scope.launch {
            context.dataStore.data.collectLatest { preferences ->
                configuration = preferences.toAutoMixConfiguration()
                if (!configuration.enabled) cancelTransition()
                prepareNext()
            }
        }
        monitorJob = scope.launch {
            while (isActive) {
                monitorPosition()
                delay(40)
            }
        }
    }

    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
        if (!isTransitioning) prepareNext()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (isTransitioning) {
            if (mediaItem?.mediaId == preparedMediaId) {
                pausingPrimaryForHandoff = true
                primary.pause()
                primary.volume = 0f
            } else {
                cancelTransition()
            }
        } else {
            prepareNext()
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isTransitioning) {
            if (pausingPrimaryForHandoff && !isPlaying) {
                pausingPrimaryForHandoff = false
                return
            }
            if (isPlaying) secondary.play() else secondary.pause()
        }
    }

    fun release() {
        transitionJob?.cancel()
        analysisJob?.cancel()
        monitorJob?.cancel()
        preferenceJob?.cancel()
        primary.removeListener(this)
        secondary.release()
        analyzer.close()
    }

    /** Drops secondary-deck state so it cannot reuse a source from the old quality. */
    fun resetForQualityChange() {
        sourceGeneration++
        transitionJob?.cancel()
        transitionJob = null
        analysisJob?.cancel()
        analysisJob = null
        smartPlan = null
        analyzedAttempt = null
        isTransitioning = false
        pausingPrimaryForHandoff = false
        primary.volume = 1f
        primary.playbackParameters = PlaybackParameters.DEFAULT
        clearSecondary()
    }

    private fun prepareNext() {
        if (!configuration.enabled || isTransitioning || primary.mediaItemCount < 2) {
            clearSecondary()
            return
        }
        val nextIndex = primary.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET || nextIndex !in 0 until primary.mediaItemCount) {
            clearSecondary()
            return
        }
        val next = primary.getMediaItemAt(nextIndex)
        if (next.mediaId == preparedMediaId && nextIndex == preparedTargetIndex) return
        preparedMediaId = next.mediaId
        preparedTargetIndex = nextIndex
        analysisJob?.cancel()
        analysisJob = null
        smartPlan = null
        analyzedAttempt = null
        secondary.stop()
        secondary.clearMediaItems()
        secondary.volume = 0f
        secondary.setMediaItem(next)
        secondary.prepare()
    }

    private fun monitorPosition() {
        if (!configuration.enabled || isTransitioning || !primary.isPlaying) return
        if (primary.repeatMode == Player.REPEAT_MODE_ONE) return
        val duration = primary.duration
        if (duration <= 0 || duration == androidx.media3.common.C.TIME_UNSET) return
        val remaining = duration - primary.currentPosition
        if (configuration.mode == AutoMixMode.Smart && remaining <= 60_000) {
            ensureSmartPlan(duration)
        }
        val plan = smartPlan
        val shouldBegin = if (configuration.mode == AutoMixMode.Smart && plan != null) {
            primary.currentPosition >= plan.outgoingStartMs
        } else {
            remaining in 1..configuration.durationMs
        }
        if (
            shouldBegin &&
            secondary.playbackState == Player.STATE_READY &&
            preparedTargetIndex == primary.nextMediaItemIndex
        ) {
            beginTransition(plan)
        }
    }

    private fun ensureSmartPlan(outgoingDurationMs: Long) {
        if (analysisJob != null || smartPlan != null || preparedTargetIndex < 0) return
        val outgoing = primary.currentMediaItem ?: return
        val incoming = primary.getMediaItemAt(preparedTargetIndex)
        val attempt = outgoing.mediaId to incoming.mediaId
        if (analyzedAttempt == attempt) return
        val generation = sourceGeneration
        analyzedAttempt = attempt
        analysisJob = scope.launch(Dispatchers.Default) {
            try {
                val pair = analyzer.analyzePair(
                    outgoingId = outgoing.mediaId,
                    outgoingUri = sourceResolver(outgoing),
                    outgoingDurationMs = outgoingDurationMs,
                    incomingId = incoming.mediaId,
                    incomingUri = sourceResolver(incoming),
                )
                val plan = BeatNetAutoMixAnalyzer.makePlan(
                    pair = pair,
                    outgoingDurationMs = outgoingDurationMs,
                    transitionBars = configuration.transitionBars,
                    tailCutBars = configuration.tailCutBars,
                    tempoMatching = configuration.tempoMatching,
                    maximumTempoAdjustmentPercent = configuration.maximumTempoAdjustmentPercent,
                )
                if (
                    generation == sourceGeneration &&
                    preparedMediaId == incoming.mediaId &&
                    primary.currentMediaItem?.mediaId == outgoing.mediaId
                ) {
                    smartPlan = plan
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Timber.tag("AutoMix").w(error, "BeatNet analysis failed; using fixed crossfade")
            } finally {
                if (generation == sourceGeneration) {
                    analysisJob = null
                }
            }
        }
    }

    private fun beginTransition(plan: SmartAutoMixPlan?) {
        if (transitionJob != null || preparedTargetIndex < 0) return
        val targetIndex = preparedTargetIndex
        val targetId = preparedMediaId ?: return
        val transitionDuration = (plan?.durationMs ?: configuration.durationMs).coerceAtLeast(1_000)
        isTransitioning = true
        secondary.seekTo(plan?.incomingStartMs ?: 0)
        secondary.playbackParameters = PlaybackParameters(plan?.incomingStartRate ?: 1f)
        secondary.volume = 0f
        secondary.play()
        transitionJob = scope.launch {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            while (isActive) {
                val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                val progress = (elapsed.toFloat() / transitionDuration).coerceIn(0f, 1f)
                val (outgoing, incoming) = gains(progress, configuration.fadeCurve)
                primary.volume = outgoing
                secondary.volume = incoming
                if (plan != null) {
                    primary.playbackParameters = PlaybackParameters(lerp(1f, plan.outgoingEndRate, progress))
                    secondary.playbackParameters = PlaybackParameters(lerp(plan.incomingStartRate, 1f, progress))
                }
                if (progress >= 1f) break
                delay(16)
            }
            val incomingPosition = secondary.currentPosition.coerceAtLeast(0)
            secondary.pause()
            if (targetIndex in 0 until primary.mediaItemCount && primary.getMediaItemAt(targetIndex).mediaId == targetId) {
                primary.seekTo(targetIndex, incomingPosition)
                primary.volume = 1f
                primary.playbackParameters = PlaybackParameters.DEFAULT
                primary.play()
            } else {
                primary.volume = 1f
                primary.playbackParameters = PlaybackParameters.DEFAULT
            }
            isTransitioning = false
            transitionJob = null
            clearSecondary()
            prepareNext()
        }
    }

    private fun cancelTransition() {
        transitionJob?.cancel()
        transitionJob = null
        isTransitioning = false
        pausingPrimaryForHandoff = false
        primary.volume = 1f
        primary.playbackParameters = PlaybackParameters.DEFAULT
        clearSecondary()
    }

    private fun clearSecondary() {
        if (isTransitioning) return
        secondary.stop()
        secondary.clearMediaItems()
        secondary.volume = 0f
        secondary.playbackParameters = PlaybackParameters.DEFAULT
        preparedMediaId = null
        preparedTargetIndex = -1
    }

    private fun gains(progress: Float, curve: AutoMixFadeCurve): Pair<Float, Float> = when (curve) {
        AutoMixFadeCurve.EqualPower -> {
            val angle = progress * (PI / 2.0)
            cos(angle).toFloat() to sin(angle).toFloat()
        }
        AutoMixFadeCurve.Smooth -> {
            val smooth = progress * progress * (3f - 2f * progress)
            (1f - smooth) to smooth
        }
        AutoMixFadeCurve.Linear -> (1f - progress) to progress
    }

    private fun lerp(start: Float, end: Float, progress: Float) = start + (end - start) * progress
}

private fun Preferences.toAutoMixConfiguration() = AutoMixRuntimeConfiguration(
    enabled = this[AutoMixEnabledKey] ?: false,
    mode = this[AutoMixModeKey].toEnum(AutoMixMode.Smart),
    durationMs = (((this[AutoMixDurationKey] ?: 8f).coerceIn(3f, 20f)) * 1_000).toLong(),
    fadeCurve = this[AutoMixFadeCurveKey].toEnum(AutoMixFadeCurve.EqualPower),
    tempoMatching = this[AutoMixTempoMatchingKey] ?: true,
    maximumTempoAdjustmentPercent = (this[AutoMixMaxTempoAdjustmentKey] ?: 8f).coerceIn(0f, 8f),
    transitionBars = (this[AutoMixTransitionBarsKey] ?: 8).coerceIn(4, 16),
    tailCutBars = (this[AutoMixTailCutBarsKey] ?: 0).coerceIn(0, 8),
)
