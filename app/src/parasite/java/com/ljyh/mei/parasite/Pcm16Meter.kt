package com.ljyh.mei.parasite

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** Aggregate-only diagnostic meter; never retains audio or advances the source buffer. */
internal class Pcm16Meter {
    private var samples = 0L
    private var nonzeroSamples = 0L
    private var peak = 0
    private var squareSum = 0.0

    fun record(source: ByteBuffer) {
        val buffer = source.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.remaining() % 2 == 0)
        var count = 0L
        var nonzero = 0L
        var maximum = 0
        var squares = 0.0
        while (buffer.hasRemaining()) {
            val value = buffer.short.toInt()
            count++
            if (value != 0) nonzero++
            maximum = maxOf(maximum, abs(value))
            squares += value.toDouble() * value
        }
        synchronized(this) {
            samples += count
            nonzeroSamples += nonzero
            peak = maxOf(peak, maximum)
            squareSum += squares
        }
    }

    @Synchronized
    fun snapshot() = Snapshot(samples, nonzeroSamples, peak, if (samples == 0L) 0.0 else sqrt(squareSum / samples) / 32768)

    internal data class Snapshot(val samples: Long, val nonzeroSamples: Long, val peak: Int, val rms: Double)
}
