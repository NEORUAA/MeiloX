package com.ljyh.mei.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Shares the audible three-band energy of both AutoMix decks with visible player backgrounds. */
@UnstableApi
class PlaybackBeatMeter {
    private val outputs = CopyOnWriteArrayList<BeatAudioOutput>()
    private var observers = 0
    private var nextEpoch = 0
    @Volatile private var epoch = 0

    // Collection is owned by the visible UI: no service timer or background analysis job.
    val spectrum: Flow<PlaybackSpectrum> = flow {
        beginObservation()
        val power = FloatArray(3)
        val envelope = SpectrumEnvelope()
        var previousNs = System.nanoTime()
        try {
            while (true) {
                val now = System.nanoTime()
                currentPower(power)
                emit(envelope.update(power, (now - previousNs) / 1e9f))
                previousNs = now
                delay(33L)
            }
        } finally {
            endObservation()
        }
    }

    fun wrap(provider: AudioOutputProvider): AudioOutputProvider =
        object : ForwardingAudioOutputProvider(provider) {
            override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
                val output = super.getAudioOutput(config)
                // Never change format support, offload, tunneling, or the chosen output format.
                if (config.isOffload || config.isTunneling || config.sampleRate <= 0 ||
                    PcmSpectrumAnalyzer.bytesPerSample(config.encoding) == 0 || config.channelMask == 0
                ) return output
                return BeatAudioOutput(output, config).also(outputs::add)
            }
        }

    @Synchronized
    private fun beginObservation() {
        if (observers++ == 0) epoch = ++nextEpoch
    }

    @Synchronized
    private fun endObservation() {
        if (--observers == 0) epoch = 0
    }

    private fun currentPower(destination: FloatArray) {
        destination.fill(0f)
        val activeEpoch = epoch
        val now = System.nanoTime()
        for (output in outputs) output.accumulatePower(activeEpoch, now, destination)
    }

    private inner class BeatAudioOutput(
        output: AudioOutput,
        config: AudioOutputProvider.OutputConfig,
    ) : ForwardingAudioOutput(output) {
        private val analyzer = PcmSpectrumAnalyzer(config.sampleRate, Integer.bitCount(config.channelMask), config.encoding)
        private var writtenFrames = 0L
        private var analysisEpoch = 0
        private var analyzing = false
        @Volatile private var playing = false
        @Volatile private var gain = 1f
        private val scratchPower = FloatArray(3)
        @Volatile private var powerRevision = 0
        @Volatile private var lowAndMidPower = 0L
        @Volatile private var highPower = 0f
        @Volatile private var publishedEpoch = 0
        @Volatile private var publishedAtNs = 0L

        override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
            val start = buffer.position()
            val handled = super.write(buffer, encodedAccessUnitCount, presentationTimeUs)
            val end = buffer.position()
            if (updateDemand()) analyzer.consume(buffer, start, end, writtenFrames)
            // Advance even when hidden so newly analyzed samples still match the output clock.
            writtenFrames += (end - start) / analyzer.bytesPerFrame
            return handled
        }

        override fun getPositionUs(): Long {
            val positionUs = super.getPositionUs()
            // Reuse Media3's normal output-position query, without querying AudioTrack from UI.
            if (updateDemand()) {
                analyzer.powerAt(positionUs, scratchPower)
                // One playback-thread writer, bounded lock-free reads from the UI.
                powerRevision++
                lowAndMidPower = (scratchPower[0].toRawBits().toLong() and 0xffffffffL) or
                    (scratchPower[1].toRawBits().toLong() shl 32)
                highPower = scratchPower[2]
                publishedEpoch = analysisEpoch
                publishedAtNs = System.nanoTime()
                powerRevision++
            }
            return positionUs
        }

        private fun updateDemand(): Boolean {
            val requestedEpoch = epoch
            val requested = requestedEpoch != 0 && playing && gain > 0f
            if (analysisEpoch != requestedEpoch || analyzing != requested) {
                // Already analyzed queued audio is still valid after a brief UI closure. Keep
                // its timestamps, but never process hidden audio or reuse filter state over a gap.
                analyzer.suspendAnalysis()
                publishedEpoch = 0
                analysisEpoch = requestedEpoch
                analyzing = requested
            }
            return requested
        }

        fun accumulatePower(activeEpoch: Int, nowNs: Long, destination: FloatArray) {
            if (activeEpoch == 0 || !playing) return
            repeat(3) {
                val revision = powerRevision
                if (revision and 1 != 0) return@repeat
                val packed = lowAndMidPower
                val high = highPower
                val validEpoch = publishedEpoch == activeEpoch
                val age = nowNs - publishedAtNs
                if (revision != powerRevision) return@repeat
                if (validEpoch && age in 0..150_000_000L) {
                    val volume = gain
                    val weight = volume * volume
                    destination[0] += Float.fromBits(packed.toInt()) * weight
                    destination[1] += Float.fromBits((packed ushr 32).toInt()) * weight
                    destination[2] += high * weight
                }
                return
            }
        }

        override fun setVolume(volume: Float) {
            super.setVolume(volume)
            // Includes each deck's crossfade and audio-focus attenuation.
            gain = volume.coerceIn(0f, 1f)
        }

        override fun play() {
            super.play()
            playing = true
        }

        override fun pause() {
            super.pause()
            playing = false
        }

        override fun flush() {
            super.flush()
            writtenFrames = 0L
            analyzer.reset()
            publishedEpoch = 0
        }

        override fun release() {
            playing = false
            outputs.remove(this)
            super.release()
        }
    }
}
