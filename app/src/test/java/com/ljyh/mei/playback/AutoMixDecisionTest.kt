package com.ljyh.mei.playback

import androidx.media3.common.Player
import com.ljyh.mei.constants.AutoMixFadeCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class AutoMixDecisionTest {
    @Test
    fun audioAnimationStartsAtCurrentVolumeAndIsIdempotentAtTarget() {
        val range = audioVolumeAnimationRange(0.37f, 1f)
        assertEquals(0.37f, range.first, 0.0001f)
        assertEquals(1f, range.second, 0.0001f)
        assertTrue(audioVolumeAnimationNeeded(range.first, range.second))
        assertFalse(audioVolumeAnimationNeeded(1f, 1f))
    }

    @Test
    fun secondaryRetryIsBounded() {
        assertTrue(shouldRetryAutoMixSecondary(attempts = 0, maxAttempts = 1))
        assertFalse(shouldRetryAutoMixSecondary(attempts = 1, maxAttempts = 1))
        assertFalse(shouldRetryAutoMixSecondary(attempts = 0, maxAttempts = 0))
    }

    @Test
    fun exhaustedRetryBlocksOrdinaryReprepareButAllowsTheScheduledAttempt() {
        assertTrue(
            shouldBlockAutoMixSecondaryPreparation(
                sameRetryTarget = true,
                retryJobActive = false,
                retryAttempts = 1,
                maxRetryAttempts = 1,
                isScheduledRetry = false,
            ),
        )
        assertFalse(
            shouldBlockAutoMixSecondaryPreparation(
                sameRetryTarget = true,
                retryJobActive = false,
                retryAttempts = 1,
                maxRetryAttempts = 1,
                isScheduledRetry = true,
            ),
        )
        assertFalse(
            shouldBlockAutoMixSecondaryPreparation(
                sameRetryTarget = false,
                retryJobActive = false,
                retryAttempts = 1,
                maxRetryAttempts = 1,
                isScheduledRetry = false,
            ),
        )
    }

    @Test
    fun standbyReadinessRequiresThePreparedIdentityIndexAndPosition() {
        val ready = isAutoMixStandbyReady(
            preparedMediaId = "incoming",
            preparedTargetIndex = 4,
            preparedPositionMs = 3_200L,
            currentMediaId = "incoming",
            currentMediaItemIndex = 4,
            currentPositionMs = 3_250L,
            playbackState = Player.STATE_READY,
            hasPlayerError = false,
        )
        assertTrue(ready)
        assertFalse(
            isAutoMixStandbyReady(
                preparedMediaId = "incoming",
                preparedTargetIndex = 4,
                preparedPositionMs = 0L,
                currentMediaId = "stale",
                currentMediaItemIndex = 4,
                currentPositionMs = 0L,
                playbackState = Player.STATE_READY,
                hasPlayerError = false,
            ),
        )
        assertFalse(
            isAutoMixStandbyReady(
                preparedMediaId = "incoming",
                preparedTargetIndex = 4,
                preparedPositionMs = 3_200L,
                currentMediaId = "incoming",
                currentMediaItemIndex = 3,
                currentPositionMs = 3_200L,
                playbackState = Player.STATE_READY,
                hasPlayerError = false,
            ),
        )
        assertFalse(
            isAutoMixStandbyReady(
                preparedMediaId = "incoming",
                preparedTargetIndex = 4,
                preparedPositionMs = 3_200L,
                currentMediaId = "incoming",
                currentMediaItemIndex = 4,
                currentPositionMs = 3_200L,
                playbackState = Player.STATE_BUFFERING,
                hasPlayerError = false,
            ),
        )
    }

    @Test
    fun transitionProgressUsesBothDeckPositionsAndNeverMovesBackward() {
        val waitingForIncoming = autoMixTransitionProgress(
            lastProgress = 0f,
            outgoingPositionMs = 4_000L,
            incomingPositionMs = 0L,
            outgoingStartPositionMs = 0L,
            incomingStartPositionMs = 0L,
            durationMs = 8_000L,
            outgoingEndRate = 1f,
            incomingStartRate = 1f,
        )
        assertEquals(0f, waitingForIncoming, 0.0001f)

        val bothAdvanced = autoMixTransitionProgress(
            lastProgress = waitingForIncoming,
            outgoingPositionMs = 4_000L,
            incomingPositionMs = 4_000L,
            outgoingStartPositionMs = 0L,
            incomingStartPositionMs = 0L,
            durationMs = 8_000L,
            outgoingEndRate = 1f,
            incomingStartRate = 1f,
        )
        assertEquals(0.5f, bothAdvanced, 0.001f)

        val monotonic = autoMixTransitionProgress(
            lastProgress = 0.7f,
            outgoingPositionMs = 2_000L,
            incomingPositionMs = 2_000L,
            outgoingStartPositionMs = 0L,
            incomingStartPositionMs = 0L,
            durationMs = 8_000L,
            outgoingEndRate = 1f,
            incomingStartRate = 1f,
        )
        assertEquals(0.7f, monotonic, 0.0001f)
    }

    @Test
    fun tempoAndFadeEnvelopesMatchMeloXShape() {
        assertEquals(
            7_680.0,
            autoMixTempoContentDuration(
                wallClockDurationMs = 8_000L,
                startRate = 0.92f,
                endRate = 1f,
            ),
            0.01,
        )
        assertEquals(
            0.5f,
            autoMixTempoProgress(
                contentDurationMs = 4_000L,
                wallClockDurationMs = 8_000L,
                startRate = 1f,
                endRate = 1f,
            ),
            0.001f,
        )
        val (outgoing, incoming) = autoMixGains(0.5f, AutoMixFadeCurve.EqualPower)
        val midpointGain = (sqrt(2.0) / 2.0).toFloat()
        assertEquals(midpointGain, outgoing, 0.0001f)
        assertEquals(midpointGain, incoming, 0.0001f)
    }
}
