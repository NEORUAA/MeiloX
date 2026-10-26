package com.ljyh.mei.playback

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Debug
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
import org.junit.Assume.assumeTrue
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
    fun playbackPositionKeepsFrameCadenceWithDynamicSchedulingDisabled() {
        val file = makeTone(durationSeconds = 12)
        try {
            // Media3 1.11 enables dynamic scheduling by default. Compare its cadence without
            // requiring it to stall on every device or audio output configuration.
            val baseline = createDeck(PlaybackBeatMeter(), file, dynamicSchedulingEnabled = true, initialVolume = 0f)
            val baselineCadence = try {
                SystemClock.sleep(1_000)
                samplePositionCadence(baseline)
            } finally {
                instrumentation.runOnMainSync { baseline.release() }
            }
            val fixed = createDeck(PlaybackBeatMeter(), file, initialVolume = 0f)
            try {
                SystemClock.sleep(1_000)
                val fixedCadence = samplePositionCadence(fixed)
                instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "\nPlayback position cadence: baseline=$baselineCadence; fixed=$fixedCadence\n")
                })
                assertTrue("Position must advance on most frame samples: $fixedCadence",
                    fixedCadence.updates >= fixedCadence.samples / 2)
                assertTrue("Position updates must stay frequent for word-level lyrics: $fixedCadence",
                    fixedCadence.p90UpdateGapMs <= 80)
                assertTrue("Position must not intermittently stall: $fixedCadence",
                    fixedCadence.longestUpdateGapMs <= 200)
                assertTrue("Playback position must track elapsed time: $fixedCadence",
                    fixedCadence.advanceMs in (fixedCadence.elapsedMs * 7 / 10)..(fixedCadence.elapsedMs * 13 / 10))

                var pausedPosition = 0L
                instrumentation.runOnMainSync {
                    fixed.pause()
                    pausedPosition = fixed.currentPosition
                }
                SystemClock.sleep(300)
                instrumentation.runOnMainSync {
                    assertEquals("Paused position must remain stable", pausedPosition.toDouble(), fixed.currentPosition.toDouble(), 20.0)
                    fixed.seekTo(2_000)
                    fixed.setPlaybackSpeed(2f)
                }
                SystemClock.sleep(200)
                instrumentation.runOnMainSync {
                    assertEquals("Paused seek must update position", 2_000.0, fixed.currentPosition.toDouble(), 50.0)
                    fixed.play()
                }
                SystemClock.sleep(300)
                var speedStart = 0L
                instrumentation.runOnMainSync { speedStart = fixed.currentPosition }
                SystemClock.sleep(500)
                instrumentation.runOnMainSync {
                    assertTrue("Position must follow double-speed playback", fixed.currentPosition - speedStart in 650L..1_400L)
                    assertTrue(fixed.isPlaying)
                    assertEquals(null, fixed.playerError)
                }
            } finally {
                instrumentation.runOnMainSync { fixed.release() }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun usbBitPerfectWithoutADacKeepsPlayingThroughTheSystemOutput() {
        val manager = context.getSystemService(AudioManager::class.java)
        assumeTrue(manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none {
            it.type in listOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY)
        })
        val file = makeTone(durationSeconds = 4)
        val usb = UsbAudioOutput(context) {}
        usb.setEnabled(true)
        val deck = onMain {
            ExoPlayer.Builder(context).setRenderersFactory(object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ) = UsbBitPerfectAudioSink(context, usb, DefaultAudioSink.Builder(context).build())
            }).build().apply {
                volume = .1f
                setMediaItem(outputItem(file, "usb-fallback"))
                prepare()
                play()
            }
        }
        try {
            waitForOutput(deck, "Missing USB hardware must leave ordinary playback available") { it.currentPosition >= 300 }
            val expected = if (Build.VERSION.SDK_INT >= 34) UsbAudioStatus.WaitingForDevice else UsbAudioStatus.Unavailable
            assertEquals(expected, usb.status.value)
            onMain { deck.seekTo(3_000) }
            waitForOutput(deck, "USB fallback must drain normally") { it.playbackState == Player.STATE_ENDED }
            assertEquals(expected, usb.status.value)
        } finally {
            onMain { deck.release() }
            usb.release()
            file.delete()
        }
    }

    @Test
    fun nativeEnginesPlayPauseSeekApplySonicTempoAndDrainToTheEnd() {
        val file = makeTone(durationSeconds = 10, sampleRate = 44_100, channels = 2)
        try {
            for (openSles in listOf(false, true)) {
                val name = if (openSles) "OpenSL ES" else "AAudio"
                val provider = ProbedAudioProvider(NativeAudioOutputProvider(context, openSles), native = true)
                val deck = createOutputDeck(listOf(outputItem(file, "native")), provider)
                try {
                    waitForOutput(deck, "$name must start real native playback") { it.currentPosition >= 300 }
                    provider.assertNativeOutputs(name)
                    onMain { deck.pause() }
                    SystemClock.sleep(150)
                    val paused = onMain { deck.currentPosition }
                    SystemClock.sleep(300)
                    onMain {
                        assertFalse(deck.playWhenReady)
                        assertEquals("$name must hold the paused playback head", paused.toDouble(), deck.currentPosition.toDouble(), 40.0)
                        deck.seekTo(2_000)
                        deck.setPlaybackSpeed(1.05f)
                    }
                    waitForOutput(deck, "$name must prepare a paused seek") { it.playbackState == Player.STATE_READY }
                    onMain {
                        assertEquals(2_000.0, deck.currentPosition.toDouble(), 50.0)
                        assertFalse(deck.playWhenReady)
                        assertEquals(1.05f, deck.playbackParameters.speed, .0001f)
                        deck.play()
                    }
                    waitForOutput(deck, "$name must resume from the seek") { it.currentPosition >= 2_300 }

                    // The final isolated two seconds verify Sonic changes the emitted PCM count,
                    // rather than merely changing ExoPlayer's reported playback parameters.
                    onMain { deck.seekTo(8_000) }
                    waitForOutput(deck, "$name must drain its final PCM without hanging", timeoutMs = 6_000) {
                        it.playbackState == Player.STATE_ENDED
                    }
                    provider.assertNativeOutputs(name)
                    val finalOutput = provider.outputs.last()
                    assertFalse("Native speed adjustment must run through Sonic", finalOutput.config.usePlaybackParameters)
                    val expectedFrames = finalOutput.config.sampleRate * 2 / 1.05
                    assertEquals("$name must render the tempo-adjusted final two seconds", expectedFrames,
                        finalOutput.writtenFrames.toDouble(), 1_500.0)
                    onMain {
                        assertEquals(10_000.0, deck.currentPosition.toDouble(), 80.0)
                        assertEquals(null, deck.playerError)
                    }
                } finally {
                    onMain { deck.release() }
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun modeSinkSwitchesRealOutputsWithoutChangingTheQueueOrPlaybackIntent() {
        val file = makeTone(durationSeconds = 8)
        val items = List(3) { outputItem(file, "queue-$it") }
        val initial = ProbedAudioProvider(AudioTrackAudioOutputProvider.Builder(context).build(), native = false)
        val sinkCreations = AtomicInteger()
        val deck = createOutputDeck(items, initial, startIndex = 1, startPositionMs = 1_000, sinkCreations = sinkCreations)
        try {
            waitForOutput(deck, "AudioTrack must start before changing modes") { it.currentPosition >= 1_200 }
            onMain { deck.pause() }
            SystemClock.sleep(150)
            val queue = onMain { outputQueue(deck) }

            for (playing in listOf(false, true)) {
                if (playing) {
                    val start = onMain { deck.currentPosition.also { deck.play() } }
                    waitForOutput(deck, "The retained queue must resume") { it.currentPosition >= start + 150 }
                }
                for (mode in listOf("AAudio", "OpenSL ES", "AudioTrack")) {
                    val provider = when (mode) {
                        "AAudio" -> ProbedAudioProvider(NativeAudioOutputProvider(context, false), native = true)
                        "OpenSL ES" -> ProbedAudioProvider(NativeAudioOutputProvider(context, true), native = true)
                        else -> ProbedAudioProvider(AudioTrackAudioOutputProvider.Builder(context).build(), native = false)
                    }
                    val before = onMain {
                        val position = deck.currentPosition
                        val indexToRestore = deck.currentMediaItemIndex
                        val resumePlayback = deck.playWhenReady
                        deck.stop()
                        for (index in 0 until deck.rendererCount) {
                            if (deck.getRendererType(index) == C.TRACK_TYPE_AUDIO) {
                                deck.createMessage(deck.getRenderer(index))
                                    .setType(Renderer.MSG_SET_AUDIO_OUTPUT_PROVIDER)
                                    .setPayload(provider)
                                    .send()
                            }
                        }
                        deck.seekTo(indexToRestore, position)
                        deck.prepare()
                        deck.playWhenReady = resumePlayback
                        position
                    }
                    waitForOutput(deck, "$mode must initialize after a live mode change") {
                        it.playbackState == Player.STATE_READY && provider.outputs.isNotEmpty()
                    }
                    if (provider.native) provider.assertNativeOutputs(mode)
                    else assertTrue("AudioTrack must use its platform output", provider.outputs.all { it.audioTrack })
                    onMain {
                        assertEquals("Switching to $mode must retain every queue entry", queue, outputQueue(deck))
                        assertEquals("Switching to $mode must retain the selected entry", 1, deck.currentMediaItemIndex)
                        assertEquals("Switching to $mode must retain playback intent", playing, deck.playWhenReady)
                        assertEquals("Switching to $mode must retain playback position", before.toDouble(),
                            deck.currentPosition.toDouble(), if (playing) 600.0 else 50.0)
                    }
                    if (playing) {
                        waitForOutput(deck, "$mode must continue advancing after switching") { it.currentPosition >= before + 150 }
                    } else {
                        SystemClock.sleep(150)
                        onMain { assertEquals(before.toDouble(), deck.currentPosition.toDouble(), 50.0) }
                    }
                }
            }
            assertEquals("Each provider change must rebuild the sink's processing configuration", 7, sinkCreations.get())
            onMain { deck.seekTo(2, 7_300) }
            waitForOutput(deck, "The final queue item must finish after all output changes", timeoutMs = 5_000) {
                it.playbackState == Player.STATE_ENDED
            }
            onMain {
                assertEquals(queue, outputQueue(deck))
                assertEquals(2, deck.currentMediaItemIndex)
                assertTrue(deck.playWhenReady)
            }
        } finally {
            onMain { deck.release() }
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

    private data class PositionCadence(
        val samples: Int,
        val updates: Int,
        val p90UpdateGapMs: Long,
        val longestUpdateGapMs: Long,
        val advanceMs: Long,
        val elapsedMs: Long,
    )

    private fun samplePositionCadence(deck: ExoPlayer): PositionCadence {
        val startedAt = SystemClock.elapsedRealtime()
        var lastUpdateAt = startedAt
        var firstPosition = 0L
        var previousPosition = 0L
        var samples = 0
        val updateGaps = mutableListOf<Long>()
        while (SystemClock.elapsedRealtime() - startedAt < 2_500) {
            var position = 0L
            instrumentation.runOnMainSync { position = deck.currentPosition }
            val sampledAt = SystemClock.elapsedRealtime()
            if (samples == 0) {
                firstPosition = position
                lastUpdateAt = sampledAt
            } else if (position != previousPosition) {
                updateGaps += sampledAt - lastUpdateAt
                lastUpdateAt = sampledAt
            }
            previousPosition = position
            samples++
            SystemClock.sleep(16)
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        val sortedGaps = updateGaps.sorted()
        return PositionCadence(
            samples, updateGaps.size,
            sortedGaps.getOrElse(sortedGaps.lastIndex * 9 / 10) { elapsedMs },
            maxOf(updateGaps.maxOrNull() ?: 0, SystemClock.elapsedRealtime() - lastUpdateAt),
            previousPosition - firstPosition, elapsedMs,
        )
    }

    private fun createDeck(
        meter: PlaybackBeatMeter,
        file: File,
        dynamicSchedulingEnabled: Boolean = false,
        initialVolume: Float = 1f,
    ): ExoPlayer {
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
            }).experimentalSetDynamicSchedulingEnabled(dynamicSchedulingEnabled).build()
            deck.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) ready.countDown()
                }
            })
            deck.volume = initialVolume
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

    private class OutputProbe(
        val config: AudioOutputProvider.OutputConfig,
        val audioTrack: Boolean,
        val implementation: String,
    ) {
        val writtenBytes = AtomicLong()
        val writtenFrames: Long
            get() = writtenBytes.get() /
                ((if (config.encoding == C.ENCODING_PCM_FLOAT) 4 else 2) * Integer.bitCount(config.channelMask))
    }

    private class ProbedAudioProvider(provider: AudioOutputProvider, val native: Boolean) :
        ForwardingAudioOutputProvider(provider) {
        val outputs = CopyOnWriteArrayList<OutputProbe>()

        override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
            val output = super.getAudioOutput(config)
            val probe = OutputProbe(config, output is AudioTrackAudioOutput, output.javaClass.simpleName)
            outputs += probe
            return object : ForwardingAudioOutput(output) {
                override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
                    val start = buffer.position()
                    return super.write(buffer, encodedAccessUnitCount, presentationTimeUs).also {
                        probe.writtenBytes.addAndGet((buffer.position() - start).toLong())
                    }
                }
            }
        }

        fun assertNativeOutputs(name: String) {
            assertTrue("$name must initialize an actual native stream", outputs.isNotEmpty())
            assertTrue("$name must never fall back to AudioTrack", outputs.all {
                !it.audioTrack && it.implementation == "NativeAudioOutput"
            })
        }
    }

    private fun createOutputDeck(
        items: List<MediaItem>,
        provider: ProbedAudioProvider,
        startIndex: Int = 0,
        startPositionMs: Long = 0,
        sinkCreations: AtomicInteger = AtomicInteger(),
    ): ExoPlayer = onMain {
        ExoPlayer.Builder(context)
            .setRenderersFactory(object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean) =
                    ModeAudioSink(provider) { output ->
                        sinkCreations.incrementAndGet()
                        DefaultAudioSink.Builder(context)
                            .setEnableFloatOutput(enableFloatOutput)
                            .setEnableAudioOutputPlaybackParameters(true)
                            .setAudioOutputProvider(output)
                            .build()
                    }
            })
            .experimentalSetDynamicSchedulingEnabled(false)
            .build().apply {
                volume = 0f
                setMediaItems(items, startIndex, startPositionMs)
                prepare()
                play()
            }
    }

    private fun outputItem(file: File, id: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri(file.toURI().toString())
        .build()
        .withQueueEntryId()

    private fun outputQueue(deck: ExoPlayer): List<Pair<String, String?>> =
        List(deck.mediaItemCount) { deck.getMediaItemAt(it).let { item -> item.mediaId to item.queueEntryId } }

    private fun waitForOutput(
        deck: ExoPlayer,
        message: String,
        timeoutMs: Long = 5_000,
        predicate: (ExoPlayer) -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain {
                    assertEquals(message, null, deck.playerError)
                    predicate(deck)
                }) return
            SystemClock.sleep(20)
        }
        onMain {
            assertTrue("$message (state=${deck.playbackState}, position=${deck.currentPosition}, error=${deck.playerError})", predicate(deck))
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun makeTone(durationSeconds: Int = 3, sampleRate: Int = 48_000, channels: Int = 1): File {
        val frames = sampleRate * durationSeconds
        val pcmBytes = frames * 2 * channels
        val wav = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + pcmBytes).put("WAVEfmt ".toByteArray())
        wav.putInt(16).putShort(1).putShort(channels.toShort()).putInt(sampleRate)
            .putInt(sampleRate * 2 * channels).putShort((2 * channels).toShort()).putShort(16)
        wav.put("data".toByteArray()).putInt(pcmBytes)
        repeat(frames) { frame ->
            val phase = 2 * PI * frame / sampleRate
            repeat(channels) {
                wav.putShort(((sin(phase * 100) + sin(phase * 1_000) + sin(phase * 10_000)) * 2_000).toInt().toShort())
            }
        }
        return File.createTempFile("beat-meter-", ".wav", context.cacheDir).apply { writeBytes(wav.array()) }
    }

    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)

}
