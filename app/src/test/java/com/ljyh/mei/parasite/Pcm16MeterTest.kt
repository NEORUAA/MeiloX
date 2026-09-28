package com.ljyh.mei.parasite

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class Pcm16MeterTest {
    @Test fun measuresExtremesWithoutMutatingInput() {
        val input = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(42).putShort(0).putShort(32767).putShort(-32768).putShort(7)
        input.position(2)
        input.limit(8)
        val meter = Pcm16Meter()
        meter.record(input.asReadOnlyBuffer())
        val result = meter.snapshot()
        assertEquals(2, input.position())
        assertEquals(8, input.limit())
        assertEquals(3L, result.samples)
        assertEquals(2L, result.nonzeroSamples)
        assertEquals(32768, result.peak)
        assertEquals(0.816484122, result.rms, 0.000000001)
    }

    @Test fun accumulatesSilenceAndEmptyBuffers() {
        val meter = Pcm16Meter()
        meter.record(ByteBuffer.allocate(8))
        meter.record(ByteBuffer.allocate(4))
        meter.record(ByteBuffer.allocate(0))
        assertEquals(Pcm16Meter.Snapshot(6, 0, 0, 0.0), meter.snapshot())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIncompleteSamples() {
        Pcm16Meter().record(ByteBuffer.allocate(1))
    }
}
