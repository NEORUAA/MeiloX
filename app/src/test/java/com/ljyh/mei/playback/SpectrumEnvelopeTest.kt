package com.ljyh.mei.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumEnvelopeTest {
    @Test
    fun silenceAndInvalidSamplesStayNeutral() {
        val envelope = SpectrumEnvelope()
        repeat(10) {
            assertEquals(PlaybackSpectrum.Zero, envelope.update(floatArrayOf(0f, Float.NaN, Float.POSITIVE_INFINITY), .033f))
        }
    }

    @Test
    fun attackIsSmoothedAndEveryBandDecaysInsteadOfFlashingOff() {
        val envelope = SpectrumEnvelope()
        val power = floatArrayOf(.1f, .1f, .1f)
        val first = envelope.update(power, .033f)
        assertTrue(first.low in .1f.. .5f)
        var peak = first
        repeat(20) { peak = envelope.update(power, .033f) }
        assertTrue(peak.low > .9f && peak.high > .9f)
        var tail = peak
        power.fill(0f)
        repeat(15) { tail = envelope.update(power, .033f) }
        assertTrue(tail.low > .1f && tail.low < peak.low)
        assertTrue(tail.high > tail.mid && tail.mid > tail.low)
        repeat(3_000) { tail = envelope.update(power, .033f) }
        assertEquals(PlaybackSpectrum.Zero, tail)
    }

    @Test
    fun amplitudesRemainBoundedAndIndependent() {
        val envelope = SpectrumEnvelope()
        var result = PlaybackSpectrum.Zero
        repeat(20) { result = envelope.update(floatArrayOf(100f, 0f, .0001f), .033f) }
        assertTrue(result.low in .99f..1f)
        assertEquals(0f, result.mid, 0f)
        assertTrue(result.high in .05f.. .4f)
    }

    @Test
    fun sustainedLoudBassDoesNotHoldTheBeatPulseOpen() {
        val envelope = SpectrumEnvelope()
        repeat(90) {
            val result = envelope.update(floatArrayOf(.1f, .1f, .1f), 1f / 30f)
            assertEquals(0f, result.pulse, 0f)
        }
    }

    @Test
    fun repeatedKicksKeepTheirTravelEvenWhenColorLevelsAreNearTheCeiling() {
        val envelope = SpectrumEnvelope()
        repeat(60) { envelope.update(floatArrayOf(.01f, .003f, .002f), 1f / 30f) }
        repeat(8) {
            var peak = 0f
            var tail = PlaybackSpectrum.Zero
            repeat(15) { frame ->
                tail = envelope.update(floatArrayOf(if (frame < 2) .1225f else .01f, .003f, .002f), 1f / 30f)
                peak = maxOf(peak, tail.pulse)
            }
            assertTrue("Each kick needs a distinct attack: $peak", peak > .65f)
            assertTrue("The pulse must recover before the next beat: ${tail.pulse}", tail.pulse < .15f)
        }
    }

    @Test
    fun beatResponseAdaptsToTrackLevelWithoutAmplifyingNearSilence() {
        fun kick(gain: Float): Float {
            val envelope = SpectrumEnvelope()
            val base = .01f * gain * gain
            repeat(60) { envelope.update(floatArrayOf(base, base * .3f, 0f), 1f / 30f) }
            return (0..2).maxOf {
                envelope.update(floatArrayOf(base * 9f, base * .3f, 0f), 1f / 30f).pulse
            }
        }
        assertEquals(kick(1f), kick(.25f), .02f)
        assertTrue(kick(.001f) < .001f)
    }
}
