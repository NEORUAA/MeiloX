package com.ljyh.mei.playback

import android.media.AudioDeviceInfo
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

/** Rebuilds the PCM pipeline when an output mode needs a different processor configuration. */
@UnstableApi
internal class ModeAudioSink(
    initialProvider: AudioOutputProvider,
    private val createSink: (AudioOutputProvider) -> AudioSink,
) : AudioSink {
    private var provider = initialProvider
    private var sink = createSink(initialProvider)
    private var listener: AudioSink.Listener? = null
    private var playerId: PlayerId? = null
    private var clock: Clock? = null
    private var audioAttributes: AudioAttributes? = null
    private var audioSessionId: Int? = null
    private var auxEffectInfo: AuxEffectInfo? = null
    private var preferredDevice: AudioDeviceInfo? = null
    private var virtualDeviceId: Int? = null
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var skipSilenceEnabled = false
    private var volume = 1f
    private var outputStreamOffsetUs = 0L
    private var tunneling = false
    private var offloadMode = AudioSink.OFFLOAD_MODE_DISABLED
    private var offloadPadding: Pair<Int, Int>? = null
    private var pendingOffloadPadding = false
    private var configuration: AudioSink.AudioSinkConfig? = null
    private var playing = false

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        sink.setListener(listener)
    }

    override fun setPlayerId(playerId: PlayerId?) {
        this.playerId = playerId
        sink.setPlayerId(playerId)
    }

    override fun setClock(clock: Clock) {
        this.clock = clock
        sink.setClock(clock)
    }

    override fun supportsFormat(format: Format): Boolean = sink.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int = sink.getFormatSupport(format)

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        sink.getFormatOffloadSupport(format)

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        sink.getCurrentPositionUs(sourceEnded)

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        sink.configure(audioSinkConfig)
        configuration = audioSinkConfig
    }

    override fun play() {
        playing = true
        sink.play()
    }

    override fun handleDiscontinuity() = sink.handleDiscontinuity()

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val handled = sink.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (pendingOffloadPadding &&
            sink.getCurrentPositionUs(false) != AudioSink.CURRENT_POSITION_NOT_SET
        ) {
            offloadPadding?.let { (delay, padding) -> sink.setOffloadDelayPadding(delay, padding) }
            pendingOffloadPadding = false
        }
        return handled
    }

    override fun playToEndOfStream() = sink.playToEndOfStream()

    override fun isEnded(): Boolean = sink.isEnded

    override fun hasPendingData(): Boolean = sink.hasPendingData()

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = playbackParameters
        sink.setPlaybackParameters(playbackParameters)
    }

    override fun getPlaybackParameters(): PlaybackParameters = sink.playbackParameters

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        this.skipSilenceEnabled = skipSilenceEnabled
        sink.setSkipSilenceEnabled(skipSilenceEnabled)
    }

    override fun getSkipSilenceEnabled(): Boolean = sink.skipSilenceEnabled

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        this.audioAttributes = audioAttributes
        sink.setAudioAttributes(audioAttributes)
    }

    override fun getAudioAttributes(): AudioAttributes? = sink.audioAttributes

    override fun getAudioCapabilities(): AudioCapabilities? = sink.audioCapabilities

    override fun setAudioSessionId(audioSessionId: Int) {
        this.audioSessionId = audioSessionId
        sink.setAudioSessionId(audioSessionId)
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        this.auxEffectInfo = auxEffectInfo
        sink.setAuxEffectInfo(auxEffectInfo)
    }

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {
        preferredDevice = audioDeviceInfo
        sink.setPreferredDevice(audioDeviceInfo)
    }

    override fun setVirtualDeviceId(virtualDeviceId: Int) {
        this.virtualDeviceId = virtualDeviceId
        sink.setVirtualDeviceId(virtualDeviceId)
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        this.outputStreamOffsetUs = outputStreamOffsetUs
        sink.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun getAudioTrackBufferSizeUs(): Long = sink.audioTrackBufferSizeUs

    override fun enableTunnelingV21() {
        sink.enableTunnelingV21()
        tunneling = true
    }

    override fun disableTunneling() {
        sink.disableTunneling()
        tunneling = false
    }

    override fun setOffloadMode(offloadMode: Int) {
        this.offloadMode = offloadMode
        sink.setOffloadMode(offloadMode)
    }

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {
        offloadPadding = delayInFrames to paddingInFrames
        pendingOffloadPadding = true
        sink.setOffloadDelayPadding(delayInFrames, paddingInFrames)
    }

    override fun setAudioOutputProvider(audioOutputProvider: AudioOutputProvider) {
        if (provider === audioOutputProvider) return
        // A provider owns output resources and must be released before the new mode claims them.
        sink.reset()
        sink.release()
        provider = audioOutputProvider
        sink = createSink(audioOutputProvider)
        listener?.let(sink::setListener)
        sink.setPlayerId(playerId)
        clock?.let(sink::setClock)
        audioAttributes?.let(sink::setAudioAttributes)
        audioSessionId?.let(sink::setAudioSessionId)
        auxEffectInfo?.let(sink::setAuxEffectInfo)
        sink.setPreferredDevice(preferredDevice)
        virtualDeviceId?.let(sink::setVirtualDeviceId)
        sink.setOutputStreamOffsetUs(outputStreamOffsetUs)
        sink.setOffloadMode(offloadMode)
        if (tunneling) sink.enableTunnelingV21()
        sink.setPlaybackParameters(playbackParameters)
        sink.setSkipSilenceEnabled(skipSilenceEnabled)
        sink.setVolume(volume)
        configuration?.let(sink::configure)
        pendingOffloadPadding = offloadPadding != null
        if (playing) sink.play()
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
        sink.setVolume(volume)
    }

    override fun pause() {
        playing = false
        sink.pause()
    }

    override fun flush() {
        sink.flush()
        pendingOffloadPadding = offloadPadding != null
    }

    override fun reset() {
        sink.reset()
        configuration = null
        playing = false
        offloadPadding = null
        pendingOffloadPadding = false
    }

    override fun release() = sink.release()
}
