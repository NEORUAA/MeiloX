package com.ljyh.mei.playback

import android.media.AudioFormat
import androidx.media3.common.Player
import com.ljyh.mei.constants.AutoMixFadeCurve
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class AutoMixDecisionTest {
    @Test
    fun failedMixRecoversOnlyOneDelayedInternalEndPauseOnTheSameEntry() {
        val recovery = AutoMixEndPauseRecovery()
        recovery.arm("song", "outgoing")
        assertTrue(recovery.consume("song", "outgoing", false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM))
        assertFalse(recovery.consume("song", "outgoing", false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM))
        recovery.arm("song", "outgoing")
        assertFalse(recovery.consume("song", "duplicate", false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM))
    }

    @Test
    fun userPlaybackIntentAndQueueOrSeekCancellationRevokeEndPauseRecovery() {
        val recovery = AutoMixEndPauseRecovery()
        for (playWhenReady in listOf(false, true)) {
            recovery.arm("song", "outgoing")
            assertFalse(recovery.consume("song", "outgoing", playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST))
            assertFalse(recovery.consume("song", "outgoing", false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM))
        }
        recovery.arm("song", "outgoing")
        recovery.cancel()
        assertFalse(recovery.consume("song", "outgoing", false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM))
        recovery.arm("song", "outgoing")
        assertFalse(recovery.consume("song", "outgoing", false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS))
        assertFalse(recovery.consume("song", "outgoing", false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM))
    }

    @Test
    fun promotionRequiresTheExactReadyIncomingEntry() {
        fun canPromote(
            mediaId: String = "song",
            entryId: String = "incoming",
            index: Int = 2,
            state: Int = Player.STATE_READY,
            failed: Boolean = false,
        ) = isAutoMixPromotionReady(
            expectedMediaId = "song",
            expectedEntryId = "incoming",
            expectedIndex = 2,
            currentMediaId = mediaId,
            currentEntryId = entryId,
            currentIndex = index,
            playbackState = state,
            hasPlayerError = failed,
        )

        assertTrue(canPromote())
        assertFalse(canPromote(mediaId = "other"))
        assertFalse(canPromote(entryId = "duplicate-song"))
        assertFalse(canPromote(index = 3))
        assertFalse(canPromote(state = Player.STATE_BUFFERING))
        assertFalse(canPromote(state = Player.STATE_ENDED))
        assertFalse(canPromote(failed = true))
    }

    @Test
    fun beatNetPcmBufferBoundsHighRateWindowAndIgnoresMorePcm() {
        val pcm = BeatNetPcmBuffer(durationMs = 32_000L)
        val samples = pcm.samples
        val source = ByteBuffer.allocate(1_920 * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(1_920) { putFloat(0.25f) }
        }
        repeat(3_200) { block ->
            pcm.append(
                buffer = source,
                offset = 0,
                size = source.capacity(),
                presentationTimeUs = block * 10_000L,
                channels = 1,
                encoding = AudioFormat.ENCODING_PCM_FLOAT,
                sampleRate = 192_000,
                requestedStartUs = 0L,
                requestedEndUs = 32_000_000L,
            )
        }
        pcm.finish()

        assertEquals(705_600, samples.size)
        assertEquals(samples.size, pcm.sampleCount)
        assertTrue(samples.all { it == 0.25f })

        pcm.append(
            buffer = source,
            offset = 0,
            size = source.capacity(),
            presentationTimeUs = 32_000_000L,
            channels = 1,
            encoding = AudioFormat.ENCODING_PCM_FLOAT,
            sampleRate = 192_000,
            requestedStartUs = 0L,
            requestedEndUs = 33_000_000L,
        )
        pcm.finish()
        assertTrue(samples === pcm.samples)
        assertEquals(samples.size, pcm.sampleCount)
        assertTrue(samples.all { it == 0.25f })
    }

    @Test
    fun beatNetPcmBufferPreservesLinearResamplingAcrossChunksAndPadsShortInput() {
        val frameCount = 53
        val source = ByteBuffer.allocate(frameCount * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frameCount) { putFloat(it / 64f) }
        }
        for (sampleRate in listOf(11_025, 22_050, 44_100, 48_000, 192_000)) {
            val whole = BeatNetPcmBuffer(durationMs = 10L)
            val chunked = BeatNetPcmBuffer(durationMs = 10L)
            whole.append(
                buffer = source,
                offset = 0,
                size = source.capacity(),
                presentationTimeUs = 0L,
                channels = 1,
                encoding = AudioFormat.ENCODING_PCM_FLOAT,
                sampleRate = sampleRate,
                requestedStartUs = 0L,
                requestedEndUs = 10_000L,
            )
            var frameOffset = 0
            for (chunkFrames in listOf(1, 3, 8, 1, 13, 2, 7, 18)) {
                chunked.append(
                    buffer = source,
                    offset = frameOffset * Float.SIZE_BYTES,
                    size = chunkFrames * Float.SIZE_BYTES,
                    presentationTimeUs = frameOffset * 1_000_000L / sampleRate,
                    channels = 1,
                    encoding = AudioFormat.ENCODING_PCM_FLOAT,
                    sampleRate = sampleRate,
                    requestedStartUs = 0L,
                    requestedEndUs = 10_000L,
                )
                frameOffset += chunkFrames
            }
            whole.finish()
            chunked.finish()

            val expectedCount = frameCount * BeatNetPcmBuffer.SAMPLE_RATE / sampleRate
            val expected = FloatArray(221) { index ->
                if (index < expectedCount) {
                    // A linear ramp remains linear between samples and holds its last value at EOF.
                    (index.toDouble() * sampleRate / BeatNetPcmBuffer.SAMPLE_RATE)
                        .coerceAtMost((frameCount - 1).toDouble()).toFloat() / 64f
                } else 0f
            }
            assertEquals("sample rate $sampleRate", expectedCount, chunked.sampleCount)
            assertArrayEquals("sample rate $sampleRate", expected, whole.samples, 0.000001f)
            assertArrayEquals("sample rate $sampleRate", whole.samples, chunked.samples, 0.000001f)
        }
    }

    @Test
    fun beatNetPcmBufferMixesStereoAndClipsSeekWindow() {
        val byteOffset = 7
        val frameCount = 400
        val source = ByteBuffer.allocate(byteOffset + frameCount * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            position(byteOffset)
            repeat(frameCount) { frame ->
                putShort(((frame % 10) * 1_024).toShort())
                putShort((-(frame % 7) * 1_024).toShort())
            }
        }
        val pcm = BeatNetPcmBuffer(durationMs = 6L)
        pcm.append(
            buffer = source,
            offset = byteOffset,
            size = frameCount * 4,
            presentationTimeUs = 1_000_000L,
            channels = 2,
            encoding = AudioFormat.ENCODING_PCM_16BIT,
            sampleRate = 22_050,
            requestedStartUs = 1_004_000L,
            requestedEndUs = 1_010_000L,
        )
        pcm.finish()

        assertEquals(132, pcm.sampleCount)
        val expected = FloatArray(133) { index ->
            if (index < 132) {
                val sourceFrame = index + 88
                ((sourceFrame % 10) - (sourceFrame % 7)) / 64f
            } else 0f
        }
        assertArrayEquals(expected, pcm.samples, 0f)
    }

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
