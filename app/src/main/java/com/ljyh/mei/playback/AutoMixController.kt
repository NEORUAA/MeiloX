package com.ljyh.mei.playback

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.Preferences
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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
 * Coordinates two physical ExoPlayer decks behind one stable application player.
 *
 * The standby deck owns the complete playlist, seeks to the incoming start while paused, and is
 * promoted in place after the overlap. Promotion never pauses or seeks the incoming audio.
 */
class AutoMixController(
    context: Context,
    private val player: StableDeckPlayer,
    private val scope: CoroutineScope,
    private val sourceResolver: suspend (MediaItem) -> Uri,
) : Player.Listener {
    private data class ActiveTransition(
        val outgoingDeck: ExoPlayer,
        val incomingDeck: ExoPlayer,
        val outgoingMediaId: String,
        val incomingMediaId: String,
        val outgoingStartPositionMs: Long,
        val incomingStartPositionMs: Long,
        val durationMs: Long,
        val fadeCurve: AutoMixFadeCurve,
        val outgoingEndRate: Float,
        val incomingStartRate: Float,
        var wantsPlayback: Boolean,
        var lastProgress: Float = 0f,
    )

    val isTransitioning: Boolean
        get() = activeTransition != null

    private var configuration = AutoMixRuntimeConfiguration()
    private var preparedMediaId: String? = null
    private var preparedTargetIndex = C.INDEX_UNSET
    private var preparedIncomingStartMs = 0L
    private var preparationGeneration = 0L
    private var activeTransition: ActiveTransition? = null
    private var transitionJob: Job? = null
    private var analysisJob: Job? = null
    private var smartPlan: SmartAutoMixPlan? = null
    private var analyzedAttempt: Pair<String, String>? = null
    private var sourceGeneration = 0L
    private var monitorJob: Job? = null
    private var preferenceJob: Job? = null
    private var retryJob: Job? = null
    private var retryTargetMediaId: String? = null
    private var retryTargetIndex = C.INDEX_UNSET
    private var retryAttempts = 0
    private var isPromotingDeck = false
    private var released = false
    private val analyzer = BeatNetAutoMixAnalyzer(context.applicationContext)

    private val deckListeners = player.deckPlayers.associateWith { deck ->
        object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                handleDeckError(deck, error)
            }
        }.also(deck::addListener)
    }

    init {
        player.addListener(this)
        preferenceJob = scope.launch {
            context.dataStore.data.collectLatest { preferences ->
                val previous = configuration
                configuration = preferences.toAutoMixConfiguration()
                if (!configuration.enabled) {
                    resetRetryState()
                    cancelTransition(prepareAfterCancel = false)
                } else if (configuration != previous) {
                    if (isTransitioning) {
                        cancelTransition(prepareAfterCancel = true)
                    } else {
                        prepareNext(force = true)
                    }
                } else {
                    prepareNext()
                }
            }
        }
        monitorJob = scope.launch {
            while (isActive) {
                monitorPosition()
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (isPromotingDeck) return
        if (isTransitioning) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                cancelTransition(prepareAfterCancel = true)
            }
            return
        }
        prepareNext(force = reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (isPromotingDeck) return
        val transition = activeTransition
        if (transition == null) {
            prepareNext()
            return
        }
        when (mediaItem?.mediaId) {
            transition.incomingMediaId -> finishTransition(transition.wantsPlayback)
            transition.outgoingMediaId -> Unit
            else -> cancelTransition(prepareAfterCancel = true)
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (isPromotingDeck) return
        val transition = activeTransition ?: return
        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION &&
            newPosition.mediaItem?.mediaId == transition.incomingMediaId
        ) {
            finishTransition(transition.wantsPlayback)
        } else if (reason != Player.DISCONTINUITY_REASON_INTERNAL) {
            cancelTransition(prepareAfterCancel = true)
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        val transition = activeTransition ?: return
        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
            finishTransition(transition.wantsPlayback)
            return
        }
        transition.wantsPlayback = playWhenReady
        transition.incomingDeck.playWhenReady = playWhenReady
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        if (isPromotingDeck) return
        if (isTransitioning) {
            cancelTransition(prepareAfterCancel = true)
        } else {
            prepareNext(force = true)
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        if (isPromotingDeck) return
        if (isTransitioning) {
            cancelTransition(prepareAfterCancel = true)
        } else {
            prepareNext(force = true)
        }
    }

    fun release() {
        if (released) return
        released = true
        preparationGeneration++
        transitionJob?.cancel()
        analysisJob?.cancel()
        monitorJob?.cancel()
        preferenceJob?.cancel()
        retryJob?.cancel()
        player.removeListener(this)
        deckListeners.forEach { (deck, listener) -> deck.removeListener(listener) }
        activeTransition?.outgoingDeck?.setPauseAtEndOfMediaItems(false)
        activeTransition = null
        analyzer.close()
    }

    /** Drops deck state so neither player can reuse a source resolved for the old quality. */
    fun resetForQualityChange() {
        sourceGeneration++
        analysisJob?.cancel()
        analysisJob = null
        smartPlan = null
        analyzedAttempt = null
        resetRetryState()
        cancelTransition(prepareAfterCancel = false)
        prepareNext(force = true)
    }

    private fun prepareNext(
        force: Boolean = false,
        isScheduledRetry: Boolean = false,
    ) {
        if (released || !configuration.enabled || isTransitioning ||
            player.repeatMode == Player.REPEAT_MODE_ONE || player.mediaItemCount < 2
        ) {
            if (!isTransitioning) clearStandby(resetRetry = true)
            return
        }
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET || nextIndex !in 0 until player.mediaItemCount) {
            clearStandby(resetRetry = true)
            return
        }
        val nextItem = player.getMediaItemAt(nextIndex)
        updateRetryTarget(nextItem.mediaId, nextIndex)
        val hasPreparedTarget = preparedMediaId == nextItem.mediaId &&
            preparedTargetIndex == nextIndex
        if (shouldBlockAutoMixSecondaryPreparation(
                sameRetryTarget = retryTargetMediaId == nextItem.mediaId && retryTargetIndex == nextIndex,
                retryJobActive = retryJob?.isActive == true,
                retryAttempts = retryAttempts,
                maxRetryAttempts = MAX_STANDBY_RETRY_ATTEMPTS,
                isScheduledRetry = isScheduledRetry,
            )
            && !hasPreparedTarget
        ) {
            return
        }

        val outgoingId = player.currentMediaItem?.mediaId
        val planMatchesTarget = analyzedAttempt == (outgoingId to nextItem.mediaId)
        val incomingStartMs = smartPlan
            ?.takeIf { planMatchesTarget }
            ?.incomingStartMs
            ?.coerceAtLeast(0L)
            ?: 0L
        val standby = player.standbyDeck
        if (!force && preparedMediaId == nextItem.mediaId &&
            preparedTargetIndex == nextIndex && preparedIncomingStartMs == incomingStartMs &&
            standby.mediaItemCount == player.mediaItemCount && standby.playbackState != Player.STATE_IDLE
        ) {
            return
        }

        val targetChanged = preparedMediaId != nextItem.mediaId || preparedTargetIndex != nextIndex
        if (targetChanged) {
            analysisJob?.cancel()
            analysisJob = null
            smartPlan = null
            analyzedAttempt = null
        }
        preparationGeneration++
        val items = List(player.mediaItemCount, player::getMediaItemAt)
        standby.stop()
        standby.clearMediaItems()
        standby.setPauseAtEndOfMediaItems(false)
        standby.playWhenReady = false
        standby.volume = 0f
        standby.playbackParameters = PlaybackParameters.DEFAULT
        standby.repeatMode = player.repeatMode
        standby.playlistMetadata = player.playlistMetadata
        standby.trackSelectionParameters = player.trackSelectionParameters
        standby.setMediaItems(items, nextIndex, incomingStartMs)
        standby.setShuffleOrder(player.activeDeck.shuffleOrder)
        standby.shuffleModeEnabled = player.shuffleModeEnabled
        preparedMediaId = nextItem.mediaId
        preparedTargetIndex = nextIndex
        preparedIncomingStartMs = incomingStartMs
        standby.prepare()
    }

    private fun monitorPosition() {
        if (released || !configuration.enabled || isTransitioning || !player.isPlaying) return
        if (player.repeatMode == Player.REPEAT_MODE_ONE) return
        val duration = player.duration
        if (duration <= 0L || duration == C.TIME_UNSET) return
        val remaining = duration - player.currentPosition
        if (configuration.mode == AutoMixMode.Smart && remaining <= SMART_ANALYSIS_WINDOW_MS) {
            ensureSmartPlan(duration)
        }
        val plan = smartPlan
        val shouldBegin = if (configuration.mode == AutoMixMode.Smart && plan != null) {
            player.currentPosition >= plan.outgoingStartMs
        } else {
            remaining in 1..configuration.durationMs
        }
        if (shouldBegin && standbyReadyForTransition(plan)) {
            beginTransition(plan)
        }
    }

    private fun ensureSmartPlan(outgoingDurationMs: Long) {
        if (analysisJob != null || smartPlan != null || preparedTargetIndex < 0) return
        val outgoing = player.currentMediaItem ?: return
        val incoming = player.getMediaItemAt(preparedTargetIndex)
        val attempt = outgoing.mediaId to incoming.mediaId
        if (analyzedAttempt == attempt) return
        val generation = sourceGeneration
        val preparation = preparationGeneration
        analyzedAttempt = attempt
        analysisJob = scope.launch {
            var analysisAccepted = false
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
                if (generation == sourceGeneration && preparation == preparationGeneration &&
                    preparedMediaId == incoming.mediaId &&
                    player.currentMediaItem?.mediaId == outgoing.mediaId
                ) {
                    analysisAccepted = true
                    smartPlan = plan
                    if (plan != null && plan.incomingStartMs != preparedIncomingStartMs) {
                        prepareNext(force = true)
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Timber.tag(TAG).w(error, "BeatNet analysis failed; using fixed crossfade")
            } finally {
                if (generation == sourceGeneration) {
                    analysisJob = null
                    if (!analysisAccepted && preparation != preparationGeneration &&
                        analyzedAttempt == attempt &&
                        preparedMediaId == incoming.mediaId &&
                        player.currentMediaItem?.mediaId == outgoing.mediaId
                    ) {
                        analyzedAttempt = null
                    }
                }
            }
        }
    }

    private fun standbyReadyForTransition(plan: SmartAutoMixPlan?): Boolean {
        val targetId = preparedMediaId ?: return false
        val targetPosition = plan?.incomingStartMs ?: 0L
        val standby = player.standbyDeck
        return preparedTargetIndex == player.nextMediaItemIndex &&
            preparedIncomingStartMs == targetPosition &&
            isAutoMixStandbyReady(
                preparedMediaId = targetId,
                preparedTargetIndex = preparedTargetIndex,
                preparedPositionMs = preparedIncomingStartMs,
                currentMediaId = standby.currentMediaItem?.mediaId,
                currentMediaItemIndex = standby.currentMediaItemIndex,
                currentPositionMs = standby.currentPosition,
                playbackState = standby.playbackState,
                hasPlayerError = standby.playerError != null,
            )
    }

    private fun beginTransition(plan: SmartAutoMixPlan?) {
        if (activeTransition != null || transitionJob != null) return
        val targetId = preparedMediaId ?: return
        val targetIndex = preparedTargetIndex
        if (targetIndex != player.nextMediaItemIndex || !standbyReadyForTransition(plan)) return
        val outgoing = player.activeDeck
        val incoming = player.standbyDeck
        val outgoingId = outgoing.currentMediaItem?.mediaId ?: return
        val durationMs = (plan?.durationMs ?: configuration.durationMs).coerceAtLeast(1_000L)
        val wantsPlayback = player.playWhenReady
        val transition = ActiveTransition(
            outgoingDeck = outgoing,
            incomingDeck = incoming,
            outgoingMediaId = outgoingId,
            incomingMediaId = targetId,
            outgoingStartPositionMs = outgoing.currentPosition.coerceAtLeast(0L),
            incomingStartPositionMs = incoming.currentPosition.coerceAtLeast(0L),
            durationMs = durationMs,
            fadeCurve = configuration.fadeCurve,
            outgoingEndRate = plan?.outgoingEndRate ?: 1f,
            incomingStartRate = plan?.incomingStartRate ?: 1f,
            wantsPlayback = wantsPlayback,
        )
        activeTransition = transition
        outgoing.setPauseAtEndOfMediaItems(true)
        outgoing.volume = 1f
        outgoing.playbackParameters = PlaybackParameters.DEFAULT
        incoming.volume = 0f
        incoming.playbackParameters = PlaybackParameters(transition.incomingStartRate)
        incoming.playWhenReady = wantsPlayback
        transitionJob = scope.launch {
            while (isActive && activeTransition === transition) {
                val progress = autoMixTransitionProgress(
                    lastProgress = transition.lastProgress,
                    outgoingPositionMs = transition.outgoingDeck.currentPosition,
                    incomingPositionMs = transition.incomingDeck.currentPosition,
                    outgoingStartPositionMs = transition.outgoingStartPositionMs,
                    incomingStartPositionMs = transition.incomingStartPositionMs,
                    durationMs = transition.durationMs,
                    outgoingEndRate = transition.outgoingEndRate,
                    incomingStartRate = transition.incomingStartRate,
                )
                transition.lastProgress = progress
                val (outgoingGain, incomingGain) = autoMixGains(progress, transition.fadeCurve)
                transition.outgoingDeck.volume = outgoingGain
                transition.incomingDeck.volume = incomingGain
                transition.outgoingDeck.playbackParameters = PlaybackParameters(
                    autoMixTempoPlaybackRate(progress, 1f, transition.outgoingEndRate),
                )
                transition.incomingDeck.playbackParameters = PlaybackParameters(
                    autoMixTempoPlaybackRate(progress, transition.incomingStartRate, 1f),
                )
                if (progress >= 1f || transition.outgoingDeck.playbackState == Player.STATE_ENDED) {
                    finishTransition(transition.wantsPlayback)
                    return@launch
                }
                delay(ENVELOPE_INTERVAL_MS)
            }
        }
    }

    private fun finishTransition(wantsPlayback: Boolean) {
        val transition = activeTransition ?: return
        if (player.activeDeck !== transition.outgoingDeck ||
            player.standbyDeck !== transition.incomingDeck ||
            transition.incomingDeck.currentMediaItem?.mediaId != transition.incomingMediaId
        ) {
            cancelTransition(prepareAfterCancel = true)
            return
        }
        transitionJob?.cancel()
        transitionJob = null
        activeTransition = null
        transition.outgoingDeck.setPauseAtEndOfMediaItems(false)
        transition.incomingDeck.volume = 1f
        transition.incomingDeck.playbackParameters = PlaybackParameters.DEFAULT
        transition.incomingDeck.playWhenReady = wantsPlayback

        isPromotingDeck = true
        val oldActive = try {
            player.promoteStandby()
        } finally {
            isPromotingDeck = false
        }
        oldActive.stop()
        oldActive.clearMediaItems()
        oldActive.volume = 0f
        oldActive.playbackParameters = PlaybackParameters.DEFAULT
        oldActive.setPauseAtEndOfMediaItems(false)
        clearPreparedState(resetRetry = true)
        prepareNext(force = true)
    }

    private fun cancelTransition(prepareAfterCancel: Boolean) {
        val transition = activeTransition
        transitionJob?.cancel()
        transitionJob = null
        activeTransition = null
        if (transition != null) {
            transition.outgoingDeck.setPauseAtEndOfMediaItems(false)
            transition.outgoingDeck.volume = 1f
            transition.outgoingDeck.playbackParameters = PlaybackParameters.DEFAULT
            transition.outgoingDeck.playWhenReady = transition.wantsPlayback
            transition.incomingDeck.playWhenReady = false
        }
        clearStandby(resetRetry = false)
        if (prepareAfterCancel) prepareNext(force = true)
    }

    private fun clearStandby(resetRetry: Boolean) {
        if (isTransitioning) return
        preparationGeneration++
        val standby = player.standbyDeck
        standby.stop()
        standby.clearMediaItems()
        standby.playWhenReady = false
        standby.volume = 0f
        standby.playbackParameters = PlaybackParameters.DEFAULT
        standby.setPauseAtEndOfMediaItems(false)
        clearPreparedState(resetRetry)
    }

    private fun clearPreparedState(resetRetry: Boolean) {
        preparedMediaId = null
        preparedTargetIndex = C.INDEX_UNSET
        preparedIncomingStartMs = 0L
        if (resetRetry) resetRetryState()
    }

    private fun handleDeckError(deck: ExoPlayer, error: PlaybackException) {
        if (released || deck !== player.standbyDeck) return
        val targetId = preparedMediaId ?: return
        val targetIndex = preparedTargetIndex
        if (deck.currentMediaItem?.mediaId != targetId || deck.currentMediaItemIndex != targetIndex) return
        Timber.tag(TAG).w(error, "Standby deck failed for %s", targetId)
        if (isTransitioning) {
            cancelTransition(prepareAfterCancel = false)
        } else {
            clearStandby(resetRetry = false)
        }
        scheduleStandbyRetry(targetId, targetIndex)
    }

    private fun scheduleStandbyRetry(mediaId: String, targetIndex: Int) {
        updateRetryTarget(mediaId, targetIndex)
        if (!shouldRetryAutoMixSecondary(retryAttempts, MAX_STANDBY_RETRY_ATTEMPTS)) return
        retryAttempts++
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(STANDBY_RETRY_DELAY_MS)
            retryJob = null
            if (player.nextMediaItemIndex == targetIndex &&
                player.getMediaItemAtOrNull(targetIndex)?.mediaId == mediaId
            ) {
                prepareNext(force = true, isScheduledRetry = true)
            }
        }
    }

    private fun updateRetryTarget(mediaId: String, targetIndex: Int) {
        if (retryTargetMediaId == mediaId && retryTargetIndex == targetIndex) return
        resetRetryState()
        retryTargetMediaId = mediaId
        retryTargetIndex = targetIndex
    }

    private fun resetRetryState() {
        retryJob?.cancel()
        retryJob = null
        retryTargetMediaId = null
        retryTargetIndex = C.INDEX_UNSET
        retryAttempts = 0
    }

    private fun Player.getMediaItemAtOrNull(index: Int): MediaItem? =
        if (index in 0 until mediaItemCount) getMediaItemAt(index) else null

    companion object {
        private const val TAG = "AutoMix"
        private const val MONITOR_INTERVAL_MS = 40L
        private const val ENVELOPE_INTERVAL_MS = 20L
        private const val SMART_ANALYSIS_WINDOW_MS = 60_000L
        private const val MAX_STANDBY_RETRY_ATTEMPTS = 1
        private const val STANDBY_RETRY_DELAY_MS = 750L
    }
}

internal fun isAutoMixStandbyReady(
    preparedMediaId: String,
    preparedTargetIndex: Int,
    preparedPositionMs: Long,
    currentMediaId: String?,
    currentMediaItemIndex: Int,
    currentPositionMs: Long,
    playbackState: Int,
    hasPlayerError: Boolean,
): Boolean = !hasPlayerError && playbackState == Player.STATE_READY &&
    currentMediaId == preparedMediaId && currentMediaItemIndex == preparedTargetIndex &&
    abs(currentPositionMs - preparedPositionMs) <= 1_000L

internal fun autoMixTransitionProgress(
    lastProgress: Float,
    outgoingPositionMs: Long,
    incomingPositionMs: Long,
    outgoingStartPositionMs: Long,
    incomingStartPositionMs: Long,
    durationMs: Long,
    outgoingEndRate: Float,
    incomingStartRate: Float,
): Float {
    val outgoingProgress = autoMixTempoProgress(
        contentDurationMs = (outgoingPositionMs - outgoingStartPositionMs).coerceAtLeast(0L),
        wallClockDurationMs = durationMs,
        startRate = 1f,
        endRate = outgoingEndRate,
    )
    val incomingProgress = autoMixTempoProgress(
        contentDurationMs = (incomingPositionMs - incomingStartPositionMs).coerceAtLeast(0L),
        wallClockDurationMs = durationMs,
        startRate = incomingStartRate,
        endRate = 1f,
    )
    return max(lastProgress, min(outgoingProgress, incomingProgress)).coerceIn(0f, 1f)
}

internal fun autoMixTempoPlaybackRate(
    progress: Float,
    startRate: Float,
    endRate: Float,
): Float = max(startRate + (endRate - startRate) * autoMixSmoothstep(progress), 0.01f)

internal fun autoMixTempoContentDuration(
    wallClockDurationMs: Long,
    startRate: Float,
    endRate: Float,
    throughProgress: Float = 1f,
): Double {
    val progress = throughProgress.coerceIn(0f, 1f).toDouble()
    val start = max(startRate, 0.01f).toDouble()
    val end = max(endRate, 0.01f).toDouble()
    val smoothstepIntegral = progress.pow(3) - progress.pow(4) / 2.0
    return wallClockDurationMs.coerceAtLeast(0L) *
        (start * progress + (end - start) * smoothstepIntegral)
}

internal fun autoMixTempoProgress(
    contentDurationMs: Long,
    wallClockDurationMs: Long,
    startRate: Float,
    endRate: Float,
): Float {
    val elapsedContent = contentDurationMs.coerceAtLeast(0L).toDouble()
    val totalContent = autoMixTempoContentDuration(
        wallClockDurationMs,
        startRate,
        endRate,
    )
    if (totalContent <= 0.0 || elapsedContent >= totalContent) return 1f
    var lowerBound = 0f
    var upperBound = 1f
    repeat(18) {
        val midpoint = (lowerBound + upperBound) / 2f
        val consumed = autoMixTempoContentDuration(
            wallClockDurationMs,
            startRate,
            endRate,
            midpoint,
        )
        if (consumed < elapsedContent) lowerBound = midpoint else upperBound = midpoint
    }
    return (lowerBound + upperBound) / 2f
}

internal fun autoMixGains(
    rawProgress: Float,
    curve: AutoMixFadeCurve,
): Pair<Float, Float> {
    val progress = rawProgress.coerceIn(0f, 1f)
    return when (curve) {
        AutoMixFadeCurve.EqualPower -> {
            val smoothed = autoMixSmoothstep(progress)
            val angle = smoothed * (PI / 2.0)
            cos(angle).toFloat() to sin(angle).toFloat()
        }
        AutoMixFadeCurve.Smooth -> {
            val smoothed = autoMixSmoothstep(progress)
            (1f - smoothed) to smoothed
        }
        AutoMixFadeCurve.Linear -> (1f - progress) to progress
    }
}

internal fun autoMixSmoothstep(value: Float): Float {
    val clamped = value.coerceIn(0f, 1f)
    return clamped * clamped * (3f - 2f * clamped)
}

internal fun shouldRetryAutoMixSecondary(attempts: Int, maxAttempts: Int): Boolean =
    maxAttempts > 0 && attempts < maxAttempts

internal fun shouldBlockAutoMixSecondaryPreparation(
    sameRetryTarget: Boolean,
    retryJobActive: Boolean,
    retryAttempts: Int,
    maxRetryAttempts: Int,
    isScheduledRetry: Boolean,
): Boolean = sameRetryTarget && !isScheduledRetry &&
    (retryJobActive || retryAttempts >= maxRetryAttempts)

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
