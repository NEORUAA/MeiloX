package com.ljyh.mei.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.ljyh.mei.constants.EqualizerBandGainsKey
import com.ljyh.mei.constants.EqualizerEnabledKey
import com.ljyh.mei.constants.EqualizerPreampKey
import com.ljyh.mei.constants.EqualizerPresetKey
import com.ljyh.mei.utils.dataStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class EqualizerBand(val centerFrequency: Double, val title: String) {
    Hz31(31.25, "31 Hz"), Hz62(62.5, "62 Hz"), Hz125(125.0, "125 Hz"),
    Hz250(250.0, "250 Hz"), Hz500(500.0, "500 Hz"), Khz1(1_000.0, "1 kHz"),
    Khz2(2_000.0, "2 kHz"), Khz4(4_000.0, "4 kHz"), Khz8(8_000.0, "8 kHz"),
    Khz16(16_000.0, "16 kHz"),
}

enum class EqualizerPreset(val preamp: Float, val gains: List<Float>) {
    Flat(0f, listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    Acoustic(-4f, listOf(4f, 3f, 2f, 1f, 0f, 1f, 2f, 3f, 4f, 3f)),
    BassBoost(-6f, listOf(7f, 6f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f)),
    Classical(-4f, listOf(5f, 4f, 3f, 0f, -2f, -2f, 0f, 3f, 4f, 5f)),
    Dance(-6f, listOf(6f, 5f, 2f, 0f, 0f, -3f, -4f, -4f, 0f, 0f)),
    Electronic(-5f, listOf(5f, 4f, 1f, 0f, -2f, 2f, 1f, 2f, 5f, 6f)),
    HipHop(-5f, listOf(6f, 5f, 2f, 3f, -1f, -1f, 2f, -1f, 2f, 3f)),
    Jazz(-4f, listOf(4f, 3f, 2f, 2f, -2f, -2f, 0f, 2f, 3f, 4f)),
    Pop(-5f, listOf(-1f, 2f, 4f, 5f, 2f, -2f, -2f, -2f, -1f, -1f)),
    Rock(-5f, listOf(5f, 3f, -1f, -3f, -2f, 1f, 3f, 5f, 5f, 5f)),
    SpokenWord(-3f, listOf(-6f, -5f, -3f, 0f, 3f, 5f, 5f, 3f, 0f, -2f)),
    TrebleBoost(-6f, listOf(0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f, 7f)),
    Vocal(-4f, listOf(-2f, -3f, -3f, 1f, 4f, 5f, 4f, 2f, 0f, -2f)),
    Custom(0f, emptyList()),
}

data class EqualizerConfiguration(
    val enabled: Boolean = false,
    val preamp: Float = 0f,
    val gains: List<Float> = EqualizerPreset.Flat.gains,
)

class EqualizerConfigurationState(
    context: Context,
    scope: CoroutineScope,
) {
    private val current = AtomicReference(EqualizerConfiguration())
    private val observation: Job = scope.launch {
        context.dataStore.data.collectLatest { preferences ->
            val preset = preferences[EqualizerPresetKey]
                ?.let { runCatching { EqualizerPreset.valueOf(it) }.getOrNull() }
                ?: EqualizerPreset.Flat
            val storedGains = preferences[EqualizerBandGainsKey]
                ?.split(',')
                ?.mapNotNull(String::toFloatOrNull)
                ?.takeIf { it.size == EqualizerBand.entries.size }
            current.set(
                EqualizerConfiguration(
                    enabled = preferences[EqualizerEnabledKey] ?: false,
                    preamp = (preferences[EqualizerPreampKey] ?: preset.preamp).coerceIn(-12f, 6f),
                    gains = (storedGains ?: preset.gains.takeIf { it.isNotEmpty() } ?: EqualizerPreset.Flat.gains)
                        .map { it.coerceIn(-12f, 12f) },
                ),
            )
        }
    }

    fun snapshot(): EqualizerConfiguration = current.get()
    fun release() = observation.cancel()
}

private data class BiquadCoefficients(
    val b0: Float,
    val b1: Float,
    val b2: Float,
    val a1: Float,
    val a2: Float,
) {
    companion object {
        val Passthrough = BiquadCoefficients(1f, 0f, 0f, 0f, 0f)

        fun peaking(centerFrequency: Double, gain: Float, sampleRate: Int): BiquadCoefficients {
            if (sampleRate <= 0 || centerFrequency <= 0 || centerFrequency >= sampleRate * .49 || abs(gain) < .0001f) {
                return Passthrough
            }
            val amplitude = 10.0.pow(gain / 40.0)
            val angularFrequency = 2.0 * PI * centerFrequency / sampleRate
            val alpha = sin(angularFrequency) / (2.0 * 1.4)
            val cosine = cos(angularFrequency)
            val a0 = 1.0 + alpha / amplitude
            return BiquadCoefficients(
                b0 = ((1.0 + alpha * amplitude) / a0).toFloat(),
                b1 = ((-2.0 * cosine) / a0).toFloat(),
                b2 = ((1.0 - alpha * amplitude) / a0).toFloat(),
                a1 = ((-2.0 * cosine) / a0).toFloat(),
                a2 = ((1.0 - alpha / amplitude) / a0).toFloat(),
            )
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TenBandEqualizerProcessor(
    private val state: EqualizerConfigurationState,
) : BaseAudioProcessor() {
    private companion object {
        const val BYPASS_EPSILON = 0.0001f
        const val BAND_COUNT = 10
    }

    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID
    private var appliedConfiguration: EqualizerConfiguration? = null
    private var bypassed = true
    private var preampMultiplier = 1f
    private val b0 = FloatArray(BAND_COUNT)
    private val b1 = FloatArray(BAND_COUNT)
    private val b2 = FloatArray(BAND_COUNT)
    private val a1 = FloatArray(BAND_COUNT)
    private val a2 = FloatArray(BAND_COUNT)
    private var firstDelays = FloatArray(0)
    private var secondDelays = FloatArray(0)
    private var channelCursor = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding !in setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        firstDelays = FloatArray(channelCount * EqualizerBand.entries.size)
        secondDelays = FloatArray(channelCount * EqualizerBand.entries.size)
        channelCursor = 0
        appliedConfiguration = null
        bypassed = true
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Media3 can queue EMPTY_BUFFER before any PCM arrives. The base processor
        // initially uses that same buffer, so a bulk copy would copy it into itself.
        if (!inputBuffer.hasRemaining()) return

        val output = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder())
        inputBuffer.order(ByteOrder.nativeOrder())
        refreshConfiguration()
        if (bypassed) {
            output.put(inputBuffer)
            output.flip()
            return
        }
        when (encoding) {
            C.ENCODING_PCM_FLOAT -> while (inputBuffer.remaining() >= Float.SIZE_BYTES) {
                output.putFloat(process(inputBuffer.float, channelCursor))
                channelCursor = (channelCursor + 1) % channelCount
            }
            C.ENCODING_PCM_16BIT -> while (inputBuffer.remaining() >= Short.SIZE_BYTES) {
                val sample = inputBuffer.short / Short.MAX_VALUE.toFloat()
                val rendered = (process(sample, channelCursor).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                output.putShort(rendered)
                channelCursor = (channelCursor + 1) % channelCount
            }
        }
        output.flip()
    }

    override fun onFlush() {
        firstDelays.fill(0f)
        secondDelays.fill(0f)
        channelCursor = 0
    }

    override fun onReset() {
        sampleRate = 0
        channelCount = 0
        encoding = C.ENCODING_INVALID
        appliedConfiguration = null
        bypassed = true
        preampMultiplier = 1f
        firstDelays = FloatArray(0)
        secondDelays = FloatArray(0)
        channelCursor = 0
    }

    private fun refreshConfiguration() {
        val configuration = state.snapshot()
        if (configuration == appliedConfiguration) return
        appliedConfiguration = configuration
        var configurationBypassed = !configuration.enabled || abs(configuration.preamp) < BYPASS_EPSILON
        var band = 0
        while (configuration.enabled && band < BAND_COUNT && configurationBypassed) {
            if (abs(configuration.gains.getOrElse(band) { 0f }) >= BYPASS_EPSILON) {
                configurationBypassed = false
            }
            band++
        }

        bypassed = configurationBypassed
        preampMultiplier = if (bypassed) 1f else 10f.pow(configuration.preamp / 20f)

        band = 0
        while (band < BAND_COUNT) {
            val coefficient = if (bypassed) {
                BiquadCoefficients.Passthrough
            } else {
                BiquadCoefficients.peaking(
                    EqualizerBand.entries[band].centerFrequency,
                    configuration.gains.getOrElse(band) { 0f },
                    sampleRate,
                )
            }
            b0[band] = coefficient.b0
            b1[band] = coefficient.b1
            b2[band] = coefficient.b2
            a1[band] = coefficient.a1
            a2[band] = coefficient.a2
            band++
        }

        firstDelays.fill(0f)
        secondDelays.fill(0f)
    }

    private fun process(input: Float, channel: Int): Float {
        var sample = input * preampMultiplier
        var band = 0
        while (band < BAND_COUNT) {
            val delayIndex = band * channelCount + channel
            val output = b0[band] * sample + firstDelays[delayIndex]
            firstDelays[delayIndex] = b1[band] * sample - a1[band] * output + secondDelays[delayIndex]
            secondDelays[delayIndex] = b2[band] * sample - a2[band] * output
            sample = output
            band++
        }
        return sample
    }
}
