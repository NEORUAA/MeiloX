package com.ljyh.mei.playback

import androidx.media3.common.C
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.exp

/** Read-only three-band energy, indexed by output frames rather than decoder timestamps. */
internal class PcmSpectrumAnalyzer(
    private val sampleRate: Int,
    private val channelCount: Int,
    private val encoding: Int,
) {
    private val bytesPerSample = bytesPerSample(encoding)
    init {
        require(sampleRate > 0 && channelCount > 0 && bytesPerSample > 0)
    }
    val bytesPerFrame = bytesPerSample * channelCount
    private val highPassDecay = exp(-2.0 * PI * 30.0 / sampleRate).toFloat()
    private val lowPassBlend = (1.0 - exp(-2.0 * PI * minOf(300.0, sampleRate * .45) / sampleRate)).toFloat()
    private val midPassBlend = (1.0 - exp(-2.0 * PI * minOf(3_500.0, sampleRate * .45) / sampleRate)).toFloat()
    private val previousInput = FloatArray(channelCount)
    private val highPass = FloatArray(channelCount)
    private val lowPass = FloatArray(channelCount)
    private val midPass = FloatArray(channelCount)
    private val windowFrames = (sampleRate / 50).coerceAtLeast(1)
    private var windowSize = 0
    private val windowPower = DoubleArray(3)

    // Covers five seconds of queued audio without allocating on the playback thread.
    private val times = LongArray(256)
    private val powers = FloatArray(times.size * 3)
    private var head = 0
    private var size = 0
    private val currentPower = FloatArray(3)
    private var currentTimeUs = Long.MIN_VALUE

    /** Only the bytes accepted by AudioOutput are analyzed; positions and samples are untouched. */
    fun consume(buffer: ByteBuffer, start: Int, end: Int, firstFrame: Long) {
        var offset = start
        var frame = firstFrame
        while (offset + bytesPerFrame <= end) {
            var lowPower = 0f
            var midPower = 0f
            var highPower = 0f
            for (channel in 0 until channelCount) {
                val input = readSample(buffer, offset)
                offset += bytesPerSample
                val high = highPassDecay * (highPass[channel] + input - previousInput[channel])
                previousInput[channel] = input
                highPass[channel] = high
                lowPass[channel] += lowPassBlend * (high - lowPass[channel])
                midPass[channel] += midPassBlend * (high - midPass[channel])
                val low = lowPass[channel]
                val mid = midPass[channel] - low
                val treble = high - midPass[channel]
                // Keep channel energies separate so opposite-phase stereo does not cancel.
                lowPower += low * low
                midPower += mid * mid
                highPower += treble * treble
            }
            frame++
            windowPower[0] += lowPower
            windowPower[1] += midPower
            windowPower[2] += highPower
            if (++windowSize == windowFrames) {
                if (size == times.size) {
                    head = (head + 1) % times.size
                    size--
                }
                val tail = (head + size) % times.size
                times[tail] = frame * 1_000_000L / sampleRate
                val divisor = windowSize.toDouble() * channelCount
                for (band in 0..2) powers[tail * 3 + band] = (windowPower[band] / divisor).toFloat()
                size++
                windowPower.fill(0.0)
                windowSize = 0
            }
        }
    }

    /** Copies into a caller-owned scratch buffer, without allocating on the playback thread. */
    fun powerAt(positionUs: Long, destination: FloatArray) {
        while (size > 0 && times[head] <= positionUs) {
            currentTimeUs = times[head]
            for (band in 0..2) currentPower[band] = powers[head * 3 + band]
            head = (head + 1) % times.size
            size--
        }
        // Never show future buffered samples, or retain a beat after the output has drained.
        if (currentTimeUs != Long.MIN_VALUE && positionUs - currentTimeUs in 0..100_000L) {
            currentPower.copyInto(destination)
        } else {
            destination.fill(0f)
        }
    }

    fun suspendAnalysis() {
        previousInput.fill(0f)
        highPass.fill(0f)
        lowPass.fill(0f)
        midPass.fill(0f)
        windowSize = 0
        windowPower.fill(0.0)
    }

    fun reset() {
        suspendAnalysis()
        head = 0
        size = 0
        currentPower.fill(0f)
        currentTimeUs = Long.MIN_VALUE
    }

    private fun readSample(buffer: ByteBuffer, offset: Int): Float = when (encoding) {
        C.ENCODING_PCM_8BIT -> ((buffer.get(offset).toInt() and 0xff) - 128) / 128f
        C.ENCODING_PCM_16BIT -> buffer.getShort(offset) / 32768f
        C.ENCODING_PCM_24BIT -> ((buffer.get(offset).toInt() and 0xff) or
            ((buffer.get(offset + 1).toInt() and 0xff) shl 8) or
            (buffer.get(offset + 2).toInt() shl 16)) / 8388608f
        C.ENCODING_PCM_32BIT -> buffer.getInt(offset) / 2147483648f
        C.ENCODING_PCM_FLOAT -> buffer.getFloat(offset).let { if (it.isFinite()) it.coerceIn(-1f, 1f) else 0f }
        else -> 0f
    }

    companion object {
        fun bytesPerSample(encoding: Int): Int = when (encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }
    }
}
