package com.ljyh.mei.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class PlaybackBeatMeterTest {
    @Test
    fun partialOutputWritesRemainBitExactAndUseThePlayoutClock() = runBlocking {
        val meter = PlaybackBeatMeter()
        val observation = launch(start = CoroutineStart.UNDISPATCHED) { meter.spectrum.collect() }
        try {
            val sink = FakeOutput(maxWriteBytes = 512)
            val output = meter.wrap(sink.provider).getAudioOutput(pcmConfig())
            output.play()
            val buffer = tone()
            val original = ByteArray(buffer.limit()) { buffer.get(it) }
            while (buffer.hasRemaining()) output.write(buffer, 1, 5_000_000L)
            assertEquals(original.size, buffer.position())
            assertTrue(original.indices.all { original[it] == buffer.get(it) })
            assertEquals(0L, output.positionUs)
            assertEquals(0f, power(meter), 0f)
            sink.positionUs = 500_000
            assertEquals(500_000L, output.positionUs)
            assertTrue(power(meter) > 0f)
            output.flush()
            assertEquals(0f, power(meter), 0f)
            output.release()
        } finally {
            observation.cancelAndJoin()
        }
    }

    @Test
    fun bothAutoMixDecksContributeAccordingToTheirOutputGain() = runBlocking {
        val meter = PlaybackBeatMeter()
        val observation = launch(start = CoroutineStart.UNDISPATCHED) { meter.spectrum.collect() }
        try {
            val first = FakeOutput(positionUs = 500_000)
            val second = FakeOutput(positionUs = 500_000)
            val a = meter.wrap(first.provider).getAudioOutput(pcmConfig())
            val b = meter.wrap(second.provider).getAudioOutput(pcmConfig())
            a.play(); b.play()
            a.write(tone(), 1, 0)
            b.write(tone(), 1, 90_000_000)
            a.positionUs; b.positionUs
            val both = FloatArray(3) { power(meter, it) }
            assertTrue(both.all { it > 0f })
            a.setVolume(.5f)
            b.setVolume(0f)
            for (band in 0..2) assertEquals(both[band] / 8f, power(meter, band), 0.000001f)
            a.pause()
            assertEquals(0f, power(meter), 0f)
            a.release(); b.release()
            assertEquals(0f, power(meter), 0f)
        } finally {
            observation.cancelAndJoin()
        }
    }

    @Test
    fun hiddenPlaybackSkipsAnalysisAndResumeDoesNotPublishOldSamples() = runBlocking {
        val meter = PlaybackBeatMeter()
        val sink = FakeOutput(positionUs = 500_000)
        val output = meter.wrap(sink.provider).getAudioOutput(pcmConfig())
        output.play()
        output.write(tone(), 1, 0)
        output.positionUs
        assertEquals(0f, power(meter), 0f)
        var observation = launch(start = CoroutineStart.UNDISPATCHED) { meter.spectrum.collect() }
        try {
            output.write(tone(), 1, 1_000_000)
            output.positionUs
            assertEquals(0f, power(meter), 0f)
            sink.positionUs = 1_500_000
            output.positionUs
            assertTrue(power(meter) > 0f)
            observation.cancelAndJoin()
            assertEquals(0f, power(meter), 0f)
            sink.positionUs = 3_000_000
            observation = launch(start = CoroutineStart.UNDISPATCHED) { meter.spectrum.collect() }
            assertEquals(0f, power(meter), 0f)
            output.positionUs
            assertEquals(0f, power(meter), 0f)
        } finally {
            observation.cancelAndJoin()
            output.release()
        }
    }

    @Test
    fun disposingOnePlayerBackgroundDoesNotDisableAnotherObserver() = runBlocking {
        val meter = PlaybackBeatMeter()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { meter.spectrum.collect() }
        val second = launch(start = CoroutineStart.UNDISPATCHED) { meter.spectrum.collect() }
        val sink = FakeOutput(positionUs = 500_000)
        val output = meter.wrap(sink.provider).getAudioOutput(pcmConfig())
        try {
            output.play()
            output.write(tone(), 1, 0)
            output.positionUs
            first.cancelAndJoin()
            assertTrue(power(meter) > 0f)
            second.cancelAndJoin()
            assertEquals(0f, power(meter), 0f)
        } finally {
            first.cancelAndJoin(); second.cancelAndJoin()
            output.release()
        }
    }

    @Test
    fun offloadPassthroughAndTunnelingAreDelegatedWithoutWrappingOrChangingCapabilities() {
        val meter = PlaybackBeatMeter()
        val sink = FakeOutput()
        val provider = meter.wrap(sink.provider)
        val format = AudioOutputProvider.FormatConfig.Builder(Format.Builder().build()).build()
        assertSame(AudioOutputProvider.FormatSupport.UNSUPPORTED, provider.getFormatSupport(format))
        for (config in listOf(
            pcmConfig().buildUpon().setIsOffload(true).build(),
            pcmConfig().buildUpon().setIsTunneling(true).build(),
            pcmConfig().buildUpon().setEncoding(C.ENCODING_AC3).build(),
        )) {
            assertSame(sink.output, provider.getAudioOutput(config))
        }
    }

    private fun power(meter: PlaybackBeatMeter, band: Int = 0): Float = PlaybackBeatMeter::class.java
        .getDeclaredMethod("currentPower", FloatArray::class.java).let { method ->
            val result = FloatArray(3)
            method.isAccessible = true
            method.invoke(meter, result)
            result[band]
        }

    private fun pcmConfig() = AudioOutputProvider.OutputConfig.Builder()
        .setEncoding(C.ENCODING_PCM_16BIT).setSampleRate(48_000).setChannelMask(4).build()

    private fun tone(): ByteBuffer = ByteBuffer.allocateDirect(48_000 * 2)
        .order(ByteOrder.nativeOrder()).apply {
            repeat(48_000) { putShort((sin(2 * PI * it * 100 / 48_000) * 10_000).toInt().toShort()) }
            flip()
        }

    private class FakeOutput(var positionUs: Long = 0, private val maxWriteBytes: Int = Int.MAX_VALUE) {
        val output = Proxy.newProxyInstance(AudioOutput::class.java.classLoader, arrayOf(AudioOutput::class.java)) { _, method, args ->
            when (method.name) {
                "write" -> {
                    val buffer = args[0] as ByteBuffer
                    buffer.position(buffer.position() + minOf(buffer.remaining(), maxWriteBytes))
                    !buffer.hasRemaining()
                }
                "getPositionUs" -> positionUs
                "flush" -> { positionUs = 0; null }
                else -> null
            }
        } as AudioOutput
        val provider = Proxy.newProxyInstance(AudioOutputProvider::class.java.classLoader, arrayOf(AudioOutputProvider::class.java)) { _, method, _ ->
            when (method.name) {
                "getAudioOutput" -> output
                "getFormatSupport" -> AudioOutputProvider.FormatSupport.UNSUPPORTED
                else -> null
            }
        } as AudioOutputProvider
    }
}
