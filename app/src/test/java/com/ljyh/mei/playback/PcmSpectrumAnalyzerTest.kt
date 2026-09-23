package com.ljyh.mei.playback

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmSpectrumAnalyzerTest {
    @Test
    fun bassIsStrongerThanTrebleAndDcIsRejected() {
        val bass = measure(100.0)
        assertTrue(bass > 0.005f)
        assertTrue(measure(4_000.0) < bass / 100f)
        val analyzer = PcmSpectrumAnalyzer(48_000, 1, C.ENCODING_PCM_FLOAT)
        val dc = ByteBuffer.allocateDirect(48_000 * 4).order(ByteOrder.nativeOrder())
        repeat(48_000) { dc.putFloat(0.5f) }
        dc.flip()
        analyzer.consume(dc, 0, dc.limit(), 0)
        assertTrue(analyzer.bassAt(1_000_000) < 0.000001f)
    }

    @Test
    fun pcmEncodingsAndSampleRatesProduceComparableEnergy() {
        val reference = measure(100.0)
        for (sampleRate in listOf(44_100, 48_000, 96_000, 192_000)) {
            for (encoding in listOf(C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT,
                C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)) {
                assertEquals("$sampleRate / $encoding", reference, measure(100.0, sampleRate, encoding), reference * .06f)
            }
        }
    }

    @Test
    fun oppositePhaseStereoRetainsBassEnergy() {
        assertEquals(measure(100.0), measure(100.0, channels = 2, oppositePhase = true), 0.00001f)
    }

    @Test
    fun analysisDoesNotMutateAudioOrBufferState() {
        val buffer = tone(48_000, C.ENCODING_PCM_16BIT, 2, 100.0)
        val bytes = ByteArray(buffer.remaining()) { buffer.get(it) }
        val originalLimit = buffer.limit()
        buffer.position(24)
        val analyzer = PcmSpectrumAnalyzer(48_000, 2, C.ENCODING_PCM_16BIT)
        analyzer.consume(buffer, 24, buffer.limit(), 6)
        assertEquals(24, buffer.position())
        assertEquals(originalLimit, buffer.limit())
        assertTrue(bytes.indices.all { bytes[it] == buffer.get(it) })
    }

    @Test
    fun bufferedAudioDoesNotAppearBeforeItsPlayoutTimeAndExpiresAfterDrain() {
        val analyzer = PcmSpectrumAnalyzer(48_000, 1, C.ENCODING_PCM_FLOAT)
        val buffer = tone(48_000, C.ENCODING_PCM_FLOAT, 1, 100.0)
        analyzer.consume(buffer, 0, buffer.limit(), 48_000)
        assertEquals(0f, analyzer.bassAt(1_000_000), 0f)
        assertTrue(analyzer.bassAt(1_500_000) > 0f)
        assertEquals(0f, analyzer.bassAt(2_200_000), 0f)
    }

    @Test
    fun partialWritesAreEquivalentToAWholeBuffer() {
        val whole = PcmSpectrumAnalyzer(48_000, 2, C.ENCODING_PCM_16BIT)
        val partial = PcmSpectrumAnalyzer(48_000, 2, C.ENCODING_PCM_16BIT)
        val buffer = tone(48_000, C.ENCODING_PCM_16BIT, 2, 100.0)
        whole.consume(buffer, 0, buffer.limit(), 0)
        var offset = 0
        while (offset < buffer.limit()) {
            val end = minOf(buffer.limit(), offset + 137 * 4)
            partial.consume(buffer, offset, end, offset / 4L)
            offset = end
        }
        assertEquals(whole.bassAt(1_000_000), partial.bassAt(1_000_000), 0f)
    }

    @Test
    fun brieflySuspendingAnalysisRetainsOnlyTheAlreadyAnalyzedPlaybackWindows() {
        val analyzer = PcmSpectrumAnalyzer(48_000, 1, C.ENCODING_PCM_FLOAT)
        val buffer = tone(48_000, C.ENCODING_PCM_FLOAT, 1, 100.0)
        analyzer.consume(buffer, 0, buffer.limit(), 0)
        assertTrue(analyzer.bassAt(500_000) > 0f)
        analyzer.suspendAnalysis()
        assertTrue(analyzer.bassAt(800_000) > 0f)
        assertEquals(0f, analyzer.bassAt(1_500_000), 0f)
    }

    @Test
    fun resetDropsOldTrackAndNonFiniteFloatSamplesStayFinite() {
        val analyzer = PcmSpectrumAnalyzer(48_000, 1, C.ENCODING_PCM_FLOAT)
        val buffer = tone(48_000, C.ENCODING_PCM_FLOAT, 1, 100.0)
        analyzer.consume(buffer, 0, buffer.limit(), 0)
        assertTrue(analyzer.bassAt(500_000) > 0f)
        analyzer.reset()
        assertEquals(0f, analyzer.bassAt(1_000_000), 0f)
        for (index in 0 until buffer.limit() step 4) buffer.putFloat(index, Float.NaN)
        analyzer.consume(buffer, 0, buffer.limit(), 0)
        assertEquals(0f, analyzer.bassAt(1_000_000), 0f)
    }

    @Test
    fun allThreeBandsRespondToTheirOwnFrequencyRange() {
        for ((frequency, expectedBand) in listOf(100.0 to 0, 1_000.0 to 1, 10_000.0 to 2)) {
            val analyzer = PcmSpectrumAnalyzer(48_000, 2, C.ENCODING_PCM_FLOAT)
            val buffer = tone(48_000, C.ENCODING_PCM_FLOAT, 2, frequency, oppositePhase = true)
            analyzer.consume(buffer, 0, buffer.limit(), 0)
            val power = FloatArray(3)
            analyzer.powerAt(1_000_000, power)
            assertTrue("$frequency Hz: ${power.toList()}", power[expectedBand] > .01f)
            for (band in 0..2) if (band != expectedBand) {
                assertTrue("$frequency Hz leaked into band $band: ${power.toList()}", power[expectedBand] > power[band] * 3f)
            }
            analyzer.reset()
            analyzer.powerAt(1_000_000, power)
            assertTrue(power.all { it == 0f })
        }
    }

    private fun PcmSpectrumAnalyzer.bassAt(positionUs: Long): Float =
        FloatArray(3).also { powerAt(positionUs, it) }[0]

    private fun measure(
        frequency: Double,
        sampleRate: Int = 48_000,
        encoding: Int = C.ENCODING_PCM_FLOAT,
        channels: Int = 1,
        oppositePhase: Boolean = false,
    ): Float {
        val analyzer = PcmSpectrumAnalyzer(sampleRate, channels, encoding)
        val buffer = tone(sampleRate, encoding, channels, frequency, oppositePhase)
        analyzer.consume(buffer, 0, buffer.limit(), 0)
        return analyzer.bassAt(1_000_000)
    }

    private fun tone(sampleRate: Int, encoding: Int, channels: Int, frequency: Double, oppositePhase: Boolean = false): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(sampleRate * channels * PcmSpectrumAnalyzer.bytesPerSample(encoding))
            .order(ByteOrder.nativeOrder())
        repeat(sampleRate) { frame ->
            repeat(channels) { channel ->
                val sample = (sin(2 * PI * frame * frequency / sampleRate) * .3 *
                    if (oppositePhase && channel == 1) -1 else 1).toFloat()
                when (encoding) {
                    C.ENCODING_PCM_8BIT -> buffer.put((sample * 128 + 128).toInt().toByte())
                    C.ENCODING_PCM_16BIT -> buffer.putShort((sample * 32767).toInt().toShort())
                    C.ENCODING_PCM_24BIT -> {
                        val value = (sample * 8388607).toInt()
                        buffer.put(value.toByte()).put((value shr 8).toByte()).put((value shr 16).toByte())
                    }
                    C.ENCODING_PCM_32BIT -> buffer.putInt((sample * 2147483647).toInt())
                    C.ENCODING_PCM_FLOAT -> buffer.putFloat(sample)
                }
            }
        }
        return buffer.flip() as ByteBuffer
    }
}
