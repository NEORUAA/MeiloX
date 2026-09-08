package com.ljyh.mei.playback

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.ljyh.mei.constants.AutoMixFadeCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPerformanceTest {
    @Test
    fun packed24BitSamplesUseThreeBytesAndSignExtension() {
        val buffer = ByteBuffer.wrap(byteArrayOf(0, 0, 0x80.toByte(), 0xff.toByte(), 0xff.toByte(), 0x7f, 0, 0, 0))
            .order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(-1f, buffer.readAnalysisPcmSample(AudioFormat.ENCODING_PCM_24BIT_PACKED), 0f)
        assertEquals(1f, buffer.readAnalysisPcmSample(AudioFormat.ENCODING_PCM_24BIT_PACKED), .000001f)
        assertEquals(0f, buffer.readAnalysisPcmSample(AudioFormat.ENCODING_PCM_24BIT_PACKED), 0f)
        assertEquals(9, buffer.position())
    }

    @Test
    fun pcm32AndFloatSamplesAreDecodedWithout16BitReinterpretation() {
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(Int.MIN_VALUE).putInt(Int.MAX_VALUE).putFloat(Float.NaN).putFloat(.25f).flip()
        assertEquals(-1f, buffer.readAnalysisPcmSample(AudioFormat.ENCODING_PCM_32BIT), 0f)
        assertEquals(1f, buffer.readAnalysisPcmSample(AudioFormat.ENCODING_PCM_32BIT), .000001f)
        assertEquals(0f, buffer.readAnalysisPcmSample(AudioFormat.ENCODING_PCM_FLOAT), 0f)
        assertEquals(.25f, buffer.readAnalysisPcmSample(AudioFormat.ENCODING_PCM_FLOAT), 0f)
    }

    @Test
    fun equalPowerFadePreservesCombinedPower() {
        repeat(1001) { step ->
            val (outgoing, incoming) = autoMixGains(step / 1000f, AutoMixFadeCurve.EqualPower)
            assertEquals(1f, outgoing * outgoing + incoming * incoming, .000001f)
        }
    }

    @Test
    fun fadeKeepsEndpointsAndBoundedGains() {
        AutoMixFadeCurve.entries.forEach { curve ->
            assertEquals(1f to 0f, autoMixGains(0f, curve))
            val end = autoMixGains(1f, curve)
            assertEquals(0f, end.first, 0.000001f)
            assertEquals(1f, end.second, 0.000001f)
            repeat(1001) { step ->
                val (outgoing, incoming) = autoMixGains(step / 1000f, curve)
                assertTrue(outgoing in 0f..1f && incoming in 0f..1f)
            }
        }
    }

    @Test
    fun equalPowerMidpointDoesNotIntroduceVolumeDip() {
        val gains = autoMixGains(.5f, AutoMixFadeCurve.EqualPower)
        assertEquals(.70710677f, gains.first, .00001f)
        assertEquals(.70710677f, gains.second, .00001f)
    }

    @Test
    fun monitorRetainsPrecisionNearTransitionsAndSleepsElsewhere() {
        assertEquals(1000L, autoMixMonitorInterval(120000L, true, false))
        assertEquals(250L, autoMixMonitorInterval(60000L, true, false))
        assertEquals(40L, autoMixMonitorInterval(32000L, true, false))
        assertEquals(1000L, autoMixMonitorInterval(2000L, false, false))
        assertEquals(1000L, autoMixMonitorInterval(2000L, true, true))
    }

    private val queue = PlaybackSnapshot(
        savedAtEpochMs = 100L,
        items = listOf(PlaybackItemSnapshot("1"), PlaybackItemSnapshot("2")),
        shuffleOrder = listOf(1, 0),
    )
    private val checkpoint = PlaybackCheckpoint(100L, 200L, 1, 12345L, 2, true, true)

    @Test
    fun progressRestoreRetainsQueueAndExactShufflePermutation() {
        val restored = queue.withCheckpoint(checkpoint)
        assertSame(queue.items, restored.items)
        assertSame(queue.shuffleOrder, restored.shuffleOrder)
        assertEquals(1, restored.currentIndex)
        assertEquals(12345L, restored.positionMs)
        assertEquals(200L, restored.savedAtEpochMs)
        assertTrue(restored.shuffleModeEnabled)
        assertTrue(restored.playWhenReady)
    }

    @Test
    fun checkpointFromAnotherQueueCannotSeekTheRestoredQueue() {
        assertSame(queue, queue.withCheckpoint(checkpoint.copy(queueSavedAtEpochMs = 99L)))
    }

    @Test
    fun legacySnapshotWithoutCheckpointRestoresUnchanged() {
        assertSame(queue, queue.withCheckpoint(null))
    }

    @Test
    fun invalidCheckpointDoesNotDiscardTheValidQueue() {
        listOf(
            checkpoint.copy(currentIndex = -1), checkpoint.copy(currentIndex = 2),
            checkpoint.copy(positionMs = -1), checkpoint.copy(repeatMode = 3),
        ).forEach { assertSame(queue, queue.withCheckpoint(it)) }
    }
}
