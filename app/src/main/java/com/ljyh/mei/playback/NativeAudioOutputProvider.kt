package com.ljyh.mei.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import java.nio.ByteBuffer

/** Native PCM output. Oboe keeps the requested API explicit instead of falling back to AudioTrack. */
@UnstableApi
class NativeAudioOutputProvider(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val useOpenSles: Boolean,
) : AudioOutputProvider {
    private var preferredDeviceId = 0

    override fun getFormatSupport(config: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport {
        val format = config.format
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW ||
            format.channelCount !in 1..2 || !Util.isEncodingLinearPcm(format.pcmEncoding) ||
            config.enableTunneling
        ) return AudioOutputProvider.FormatSupport.UNSUPPORTED
        val direct = format.pcmEncoding == C.ENCODING_PCM_16BIT ||
            format.pcmEncoding == C.ENCODING_PCM_FLOAT
        return AudioOutputProvider.FormatSupport.Builder()
            .setFormatSupportLevel(
                if (direct) AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY
                else AudioOutputProvider.FORMAT_SUPPORTED_WITH_TRANSCODING,
            ).build()
    }

    override fun getOutputConfig(config: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig {
        val format = config.format
        if (getFormatSupport(config).supportLevel == AudioOutputProvider.FORMAT_UNSUPPORTED ||
            format.sampleRate <= 0 ||
            format.pcmEncoding !in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)
        ) throw AudioOutputProvider.ConfigurationException("Unsupported native PCM format: $format")
        preferredDeviceId = config.preferredDevice?.id ?: 0
        val frameSize = Util.getPcmFrameSize(format.pcmEncoding, format.channelCount)
        return AudioOutputProvider.OutputConfig.Builder()
            .setEncoding(format.pcmEncoding)
            .setSampleRate(format.sampleRate)
            .setChannelMask(
                if (format.channelCount == 1) AudioFormat.CHANNEL_OUT_MONO
                else AudioFormat.CHANNEL_OUT_STEREO,
            )
            .setBufferSize((format.sampleRate / 4).coerceAtLeast(1) * frameSize)
            .setAudioAttributes(config.audioAttributes)
            .setAudioSessionId(C.AUDIO_SESSION_ID_UNSET)
            // Native outputs use Sonic in DefaultAudioSink for speed and pitch.
            .setUsePlaybackParameters(false)
            .build()
    }

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        try {
            return NativeAudioOutput(config, useOpenSles, preferredDeviceId) { preferredDeviceId = it }
        } catch (error: LinkageError) {
            throw AudioOutputProvider.InitializationException(error)
        } catch (error: IllegalStateException) {
            throw AudioOutputProvider.InitializationException(error)
        }
    }

    override fun addListener(listener: AudioOutputProvider.Listener) = Unit
    override fun removeListener(listener: AudioOutputProvider.Listener) = Unit
    override fun release() = Unit
}

@UnstableApi
private class NativeAudioOutput(
    private val config: AudioOutputProvider.OutputConfig,
    useOpenSles: Boolean,
    private var preferredDeviceId: Int,
    private val onPreferredDeviceChanged: (Int) -> Unit,
) : AudioOutput {
    private val listeners = mutableSetOf<AudioOutput.Listener>()
    private var handle = NativeAudioBridge.create(
        config.sampleRate,
        Integer.bitCount(config.channelMask),
        config.encoding == C.ENCODING_PCM_FLOAT,
        useOpenSles,
        preferredDeviceId,
        config.audioAttributes.usage,
        config.audioAttributes.contentType,
    )
    private var positionAdvancing = false
    private var underruns = 0
    private var routeChanged = false

    init {
        check(handle != 0L) { "Unable to open ${if (useOpenSles) "OpenSL ES" else "AAudio"} output" }
    }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        if (handle == 0L || routeChanged) throw AudioOutput.WriteException(-899, true)
        val result = NativeAudioBridge.write(handle, buffer, buffer.position(), buffer.remaining())
        if (result < 0) throw AudioOutput.WriteException(result, true)
        buffer.position(buffer.position() + result)
        notifyUnderruns()
        return !buffer.hasRemaining()
    }

    override fun play() {
        if (handle != 0L) NativeAudioBridge.play(handle)
    }

    override fun pause() {
        if (handle != 0L) NativeAudioBridge.pause(handle)
    }

    override fun flush() {
        if (handle != 0L) NativeAudioBridge.flush(handle)
        positionAdvancing = false
        underruns = 0
        routeChanged = false
    }

    override fun stop() {
        if (handle != 0L) NativeAudioBridge.drain(handle)
    }

    override fun release() {
        if (handle == 0L) return
        NativeAudioBridge.release(handle)
        handle = 0L
        listeners.toList().forEach(AudioOutput.Listener::onReleased)
        listeners.clear()
    }

    override fun setVolume(volume: Float) {
        if (handle != 0L) NativeAudioBridge.setVolume(handle, volume.coerceIn(0f, 1f))
    }

    override fun isOffloadedPlayback() = false
    override fun getAudioSessionId() = C.AUDIO_SESSION_ID_UNSET
    override fun getSampleRate() = config.sampleRate
    override fun getBufferSizeInFrames() =
        (config.bufferSize / Util.getPcmFrameSize(config.encoding, Integer.bitCount(config.channelMask))).toLong()

    override fun getPositionUs(): Long {
        val position = if (handle != 0L) NativeAudioBridge.positionFrames(handle) else 0L
        if (position > 0 && !positionAdvancing) {
            positionAdvancing = true
            listeners.toList().forEach { it.onPositionAdvancing(System.currentTimeMillis()) }
        }
        notifyUnderruns()
        // Round upwards so Media3's conversion back to frames does not leave one frame pending at EOS.
        return (position * 1_000_000L + config.sampleRate - 1) / config.sampleRate
    }

    private fun notifyUnderruns() {
        if (handle == 0L) return
        val count = NativeAudioBridge.underrunCount(handle)
        if (count > underruns) listeners.toList().forEach(AudioOutput.Listener::onUnderrun)
        underruns = count
    }

    override fun getPlaybackParameters() = PlaybackParameters.DEFAULT
    override fun setPlaybackParameters(playbackParams: PlaybackParameters) = Unit
    override fun isStalled() = routeChanged || (handle != 0L && NativeAudioBridge.error(handle) != 0)
    override fun addListener(listener: AudioOutput.Listener) { listeners += listener }
    override fun removeListener(listener: AudioOutput.Listener) { listeners -= listener }
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit
    override fun setOffloadEndOfStream() = Unit
    override fun attachAuxEffect(effectId: Int) = Unit
    override fun setAuxEffectSendLevel(level: Float) = Unit

    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) {
        val id = preferredDevice?.id ?: 0
        if (id == preferredDeviceId) return
        preferredDeviceId = id
        onPreferredDeviceChanged(id)
        // Media3 recreates a recoverable failed output. Already submitted PCM cannot be safely
        // migrated to another native stream without duplicating or dropping audible samples.
        routeChanged = true
    }
}

internal object NativeAudioBridge {
    init { System.loadLibrary("native_audio_output") }

    external fun create(
        sampleRate: Int, channelCount: Int, floatingPoint: Boolean, useOpenSles: Boolean,
        deviceId: Int, usage: Int, contentType: Int,
    ): Long
    external fun write(handle: Long, buffer: ByteBuffer, offset: Int, length: Int): Int
    external fun play(handle: Long)
    external fun pause(handle: Long)
    external fun flush(handle: Long)
    external fun drain(handle: Long)
    external fun release(handle: Long)
    external fun setVolume(handle: Long, volume: Float)
    external fun positionFrames(handle: Long): Long
    external fun underrunCount(handle: Long): Int
    external fun error(handle: Long): Int
}
