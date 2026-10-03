package com.ljyh.mei.playback

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/** Normalized, smoothed output energy; unrelated to microphone input. */
data class PlaybackSpectrum(val low: Float, val mid: Float, val high: Float, val pulse: Float = 0f) {
    companion object {
        val Zero = PlaybackSpectrum(0f, 0f, 0f)
    }
}

/** Runs at UI sampling cadence, keeping logarithms and envelope work off the audio thread. */
internal class SpectrumEnvelope {
    private val history = FloatArray(12)
    private val peaks = FloatArray(3)
    private val current = FloatArray(3)
    private val decayRates = floatArrayOf(ln(.98f) * 120f, ln(.99f) * 120f, ln(.999f) * 120f)
    private var index = 0
    private var bassReference = 0f
    private var referenceReady = false
    private var pulse = 0f

    fun update(power: FloatArray, seconds: Float): PlaybackSpectrum {
        val dt = seconds.coerceIn(0f, .25f)
        val retention = exp(-83.17766f * dt) // Native backdrop's half-life of 1/120 second.
        for (band in 0..2) {
            val energy = power[band]
            val level = if (energy.isFinite() && energy > 0f) {
                ((10f * log10(energy) + 55f) / 47f).coerceIn(0f, 1f)
            } else 0f
            history[index * 3 + band] = level * level * level * (level * (level * 6f - 15f) + 10f)
            var weighted = 0f
            for (age in 0..3) {
                weighted += history[((index + 1 + age) % 4) * 3 + band] * ((age + 1) * .1f)
            }
            peaks[band] = maxOf(peaks[band] * exp(decayRates[band] * dt), weighted)
            current[band] = peaks[band] + (current[band] - peaks[band]) * retention
            if (current[band] < .0001f) current[band] = 0f
        }
        index = (index + 1) % 4
        // Absolute dB levels stay near the ceiling on mastered music. Detect low-frequency
        // rises relative to the recent RMS instead, before the long color-envelope decay.
        val bassPower = power[0] * .9f + power[1] * .1f
        val bass = if (bassPower.isFinite() && bassPower > 0f) sqrt(bassPower) else 0f
        if (!referenceReady && bass > .004f) {
            bassReference = bass
            referenceReady = true
        }
        bassReference += (bass - bassReference) * (1f - exp(-dt / .6f))
        val rise = ((bass - bassReference * 1.08f) / maxOf(bassReference * .8f, .012f)).coerceIn(0f, 1f)
        val audible = ((bass - .004f) / .016f).coerceIn(0f, 1f)
        val targetPulse = rise * audible
        val responseSeconds = if (targetPulse > pulse) .03f else .16f
        pulse += (targetPulse - pulse) * (1f - exp(-dt / responseSeconds))
        if (pulse < .0001f) pulse = 0f
        return PlaybackSpectrum(current[0], current[1], current[2], pulse)
    }
}
