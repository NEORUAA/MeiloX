package com.ljyh.mei.playback

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Debug
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackBeatMeterTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun realPlaybackProducesBassWithoutMicrophonePermissionAndStopsAnalysisWhenHidden() = runBlocking {
        assertEquals("Run this regression with RECORD_AUDIO denied", PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO))
        val meter = PlaybackBeatMeter()
        val file = makeTone()
        val deck = createDeck(meter, file)
        try {
            // Output buffering can already contain several seconds when the UI becomes visible.
            // Wait for freshly analyzed frames to reach the playback head, not just the decoder.
            val levels = withTimeout(8_000) { meter.spectrum.take(150).toList() }
            assertTrue("Real output PCM must drive the background", levels.maxOf { it.low } > .05f)
            assertTrue(levels.maxOf { it.mid } > .05f && levels.maxOf { it.high } > .05f)
            assertEquals(0, field(meter, "epoch"))
            SystemClock.sleep(300)
            val outputs = field(meter, "outputs") as CopyOnWriteArrayList<*>
            assertFalse(outputs.isEmpty())
            val analyzer = field(outputs.first()!!, "analyzer")!!
            assertEquals(0, field(analyzer, "windowSize"))
            val cachedWindows = field(analyzer, "size")
            val writtenFrames = field(outputs.first()!!, "writtenFrames") as Long
            assertTrue((field(analyzer, "previousInput") as FloatArray).all { it == 0f })
            SystemClock.sleep(300)
            assertEquals("Hidden writes must not analyze or enqueue more windows", cachedWindows, field(analyzer, "size"))
            assertTrue((field(outputs.first()!!, "writtenFrames") as Long) > writtenFrames)
            instrumentation.runOnMainSync { assertTrue(deck.isPlaying) }
            val resumed = withTimeout(8_000) { meter.spectrum.take(150).toList() }
            assertTrue(resumed.maxOf { it.low } > .05f)
            instrumentation.runOnMainSync { deck.pause() }
            val paused = withTimeout(2_000) { meter.spectrum.take(5).toList() }
            assertTrue(paused.all { it == PlaybackSpectrum.Zero })
        } finally {
            instrumentation.runOnMainSync { deck.release() }
            file.delete()
        }
    }

    @Test
    fun dualDeckHandoffSeekAndTempoChangesKeepDeliveringBass() = runBlocking {
        val meter = PlaybackBeatMeter()
        val file = makeTone()
        val first = createDeck(meter, file)
        val second = createDeck(meter, file)
        val levels = mutableListOf<PlaybackSpectrum>()
        val observation = launch(start = CoroutineStart.UNDISPATCHED) { meter.spectrum.collect { levels += it } }
        try {
            instrumentation.runOnMainSync { second.volume = 0f }
            delay(4_000)
            assertTrue(levels.maxOf { it.low } > .05f)
            levels.clear()
            repeat(10) { step ->
                instrumentation.runOnMainSync {
                    first.volume = (9 - step) / 10f
                    second.volume = (step + 1) / 10f
                    second.setPlaybackSpeed(1f + step * .01f)
                }
                delay(30)
            }
            instrumentation.runOnMainSync {
                first.pause()
                second.seekTo(300)
            }
            delay(4_000)
            assertTrue("Incoming deck must continue driving bass after seek and tempo changes", levels.takeLast(10).maxOf { it.low } > .05f)
            instrumentation.runOnMainSync {
                assertTrue(second.isPlaying)
                assertEquals(null, second.playerError)
            }
        } finally {
            observation.cancelAndJoin()
            instrumentation.runOnMainSync { first.release(); second.release() }
            file.delete()
        }
    }

    @Test
    fun boundedPcmAnalysisCostOnTheDevice() {
        val frames = 4_800
        val pcm = ByteBuffer.allocateDirect(frames * 2 * 4).order(ByteOrder.nativeOrder())
        repeat(frames * 2) { pcm.putFloat((sin(it * .01) * .2).toFloat()) }
        pcm.flip()
        val analyzer = PcmSpectrumAnalyzer(48_000, 2, androidx.media3.common.C.ENCODING_PCM_FLOAT)
        repeat(20) { analyzer.consume(pcm, 0, pcm.limit(), 0); analyzer.reset() }
        val cpuStart = Debug.threadCpuTimeNanos()
        repeat(100) { analyzer.consume(pcm, 0, pcm.limit(), it * frames.toLong()) }
        val cpuMs = (Debug.threadCpuTimeNanos() - cpuStart) / 1_000_000.0
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "\nPCM three-band analysis: 10 s stereo 48 kHz float, CPU ${"%.2f".format(cpuMs)} ms\n")
        })
        assertTrue("Analysis must use less than 10% of this thread's real-time budget", cpuMs < 1_000)
        val envelope = SpectrumEnvelope()
        val power = floatArrayOf(.01f, .004f, .002f)
        repeat(100) { envelope.update(power, 1f / 30f) }
        val envelopeStart = Debug.threadCpuTimeNanos()
        repeat(300) {
            power[0] = if (it % 15 < 2) .12f else .01f
            envelope.update(power, 1f / 30f)
        }
        val envelopeMs = (Debug.threadCpuTimeNanos() - envelopeStart) / 1_000_000.0
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "\nSpectrum envelope: 10 s at 30 Hz, CPU ${"%.2f".format(envelopeMs)} ms\n")
        })
        assertTrue("UI envelope must remain cheap", envelopeMs < 100)
    }

    private fun createDeck(meter: PlaybackBeatMeter, file: File): ExoPlayer {
        lateinit var deck: ExoPlayer
        val ready = CountDownLatch(1)
        instrumentation.runOnMainSync {
            deck = ExoPlayer.Builder(context).setRenderersFactory(object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean) =
                    DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioOutputPlaybackParameters(true)
                        .setAudioOutputProvider(meter.wrap(AudioTrackAudioOutputProvider.Builder(context).build()))
                        .build()
            }).build()
            deck.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) ready.countDown()
                }
            })
            deck.repeatMode = Player.REPEAT_MODE_ONE
            deck.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
            deck.prepare()
            deck.play()
        }
        if (!ready.await(5, TimeUnit.SECONDS)) {
            instrumentation.runOnMainSync { deck.release() }
            throw AssertionError("Local PCM track did not become ready")
        }
        return deck
    }

    private fun makeTone(): File {
        val frames = 48_000 * 3
        val pcmBytes = frames * 2
        val wav = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + pcmBytes).put("WAVEfmt ".toByteArray())
        wav.putInt(16).putShort(1).putShort(1).putInt(48_000).putInt(96_000).putShort(2).putShort(16)
        wav.put("data".toByteArray()).putInt(pcmBytes)
        repeat(frames) { frame ->
            val phase = 2 * PI * frame / 48_000
            wav.putShort(((sin(phase * 100) + sin(phase * 1_000) + sin(phase * 10_000)) * 2_000).toInt().toShort())
        }
        return File.createTempFile("beat-meter-", ".wav", context.cacheDir).apply { writeBytes(wav.array()) }
    }

    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)

}
