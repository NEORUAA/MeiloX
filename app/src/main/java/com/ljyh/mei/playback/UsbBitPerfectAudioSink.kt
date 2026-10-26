package com.ljyh.mei.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

/** Writes decoded PCM unchanged, bypassing DefaultAudioSink's converters, effects and initial ramp. */
@UnstableApi
class UsbBitPerfectAudioSink(
    context: Context,
    private val controller: UsbAudioOutput,
    fallback: AudioSink,
) : ForwardingAudioSink(fallback) {
    private val provider = AudioTrackAudioOutputProvider.Builder(context).build()
    private var sinkListener: AudioSink.Listener? = null
    private var attributes = AudioAttributes.DEFAULT
    private var sessionId = C.AUDIO_SESSION_ID_UNSET
    private var virtualDeviceId = C.INDEX_UNSET
    private var playerId: PlayerId? = null
    private var playing = false
    private var requestedConfig: AudioSink.AudioSinkConfig? = null
    private var requestedSelection: UsbAudioOutput.Selection? = null
    private var activeConfig: AudioSink.AudioSinkConfig? = null
    private var switchPending = false
    private var direct = false
    private var output: AudioTrackAudioOutput? = null
    private var lease: UsbAudioOutput.Lease? = null
    private var pcmQueue: UsbPcmFrameQueue? = null
    private var remainingDelayFrames = 0
    private var currentInput: ByteBuffer? = null
    private var pendingPresentationTimeUs = 0L
    private var resumeFailure: AudioSink.InitializationException? = null
    private var writtenBytes = 0L
    private var submittedBytes = 0L
    private var startMediaTimeUs = C.TIME_UNSET
    private var needsDiscontinuity = false
    private var stopped = false
    private var lastFeedMs = 0L

    override fun setListener(listener: AudioSink.Listener) {
        sinkListener = listener
        super.setListener(listener)
    }

    override fun setPlayerId(playerId: PlayerId?) {
        this.playerId = playerId
        playerId?.let { output?.setPlayerId(it) }
        super.setPlayerId(playerId)
    }

    override fun setClock(clock: Clock) {
        provider.setClock(clock)
        super.setClock(clock)
    }

    override fun supportsFormat(format: Format): Boolean =
        directlySupportedPcm(format) || super.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int = if (directlySupportedPcm(format)) {
        AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
    } else super.getFormatSupport(format)

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = AudioOffloadSupport.DEFAULT_UNSUPPORTED

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val mapping = audioSinkConfig.outputChannelMapping
        val format = audioSinkConfig.format
        val identityMapping = mapping == null ||
            (mapping.length() == format.channelCount && (0 until mapping.length()).all { mapping.get(it) == it })
        val selection = if (identityMapping) controller.select(format) else {
            controller.unsupportedFormat()
            null
        }
        if (selection == null) super.configure(audioSinkConfig)
        requestedConfig = audioSinkConfig
        requestedSelection = selection
        switchPending = activeConfig != audioSinkConfig || direct != (selection != null)
        if (!switchPending) activeConfig = audioSinkConfig
    }

    override fun play() {
        playing = true
        if (direct) playDirect() else super.play()
    }

    override fun pause() {
        playing = false
        output?.pause()
        lease?.let(controller::suspend)
        super.pause()
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        throwResumeFailure()
        if (!applyPendingConfiguration()) return false
        if (!direct) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (output == null && !initializeDirect()) {
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        throwResumeFailure()
        val currentOutput = checkNotNull(output)
        val queue = checkNotNull(pcmQueue)
        if (currentInput == null) {
            if (!buffer.hasRemaining()) return true
            val format = checkNotNull(activeConfig).format
            val expected = if (startMediaTimeUs == C.TIME_UNSET) presentationTimeUs else
                startMediaTimeUs + bytesToDurationUs(submittedBytes, format)
            if (startMediaTimeUs == C.TIME_UNSET) startMediaTimeUs = presentationTimeUs.coerceAtLeast(0L)
            else if (needsDiscontinuity || abs(presentationTimeUs - expected) > 200_000L) {
                if (!needsDiscontinuity) {
                    sinkListener?.onAudioSinkError(AudioSink.UnexpectedDiscontinuityException(presentationTimeUs, expected))
                }
                startMediaTimeUs += presentationTimeUs - expected
                sinkListener?.onPositionDiscontinuity()
            }
            needsDiscontinuity = false
            currentInput = buffer
            val accepted = queue.queue(buffer)
            remainingDelayFrames = queue.remainingDelayFrames
            submittedBytes += accepted
            pendingPresentationTimeUs = startMediaTimeUs + bytesToDurationUs(writtenBytes, format)
        } else {
            check(currentInput === buffer) { "A partial audio write must reuse the input buffer" }
        }
        val pending = queue.pending
        if (pending != null) {
            val before = pending.remaining()
            try {
                currentOutput.write(pending, 1, pendingPresentationTimeUs)
            } catch (error: AudioOutput.WriteException) {
                controller.unavailable()
                val exception = AudioSink.WriteException(error.errorCode, checkNotNull(activeConfig).format, error.isRecoverable)
                sinkListener?.onAudioSinkError(exception)
                throw exception
            }
            writtenBytes += before - pending.remaining()
            lastFeedMs = SystemClock.elapsedRealtime()
            if (pending.hasRemaining()) return false
            queue.clearPending()
        }
        currentInput = null
        return true
    }

    override fun handleDiscontinuity() {
        if (direct) needsDiscontinuity = true else super.handleDiscontinuity()
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!direct) return super.getCurrentPositionUs(sourceEnded)
        if (startMediaTimeUs == C.TIME_UNSET) return AudioSink.CURRENT_POSITION_NOT_SET
        val currentOutput = output ?: return AudioSink.CURRENT_POSITION_NOT_SET
        return startMediaTimeUs + min(currentOutput.positionUs, bytesToDurationUs(writtenBytes, checkNotNull(activeConfig).format))
    }

    override fun playToEndOfStream() {
        if (!direct) {
            super.playToEndOfStream()
            return
        }
        stopDirect()
    }

    override fun hasPendingData(): Boolean {
        if (!direct) return super.hasPendingData()
        if (pcmQueue?.pending?.hasRemaining() == true) return true
        val currentOutput = output ?: return false
        val format = activeConfig?.format ?: return false
        val pending = currentOutput.positionUs < bytesToDurationUs(writtenBytes, format)
        if (!pending && stopped) lease?.let(controller::suspend)
        return pending
    }

    override fun isEnded(): Boolean = if (direct) stopped && !hasPendingData() else super.isEnded()

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        // Bit-perfect output never changes speed or pitch, including on its fallback path.
        super.setPlaybackParameters(PlaybackParameters.DEFAULT)
    }

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) = super.setSkipSilenceEnabled(false)
    override fun getSkipSilenceEnabled(): Boolean = false

    override fun setVolume(volume: Float) {
        if (!direct) super.setVolume(volume)
    }

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        if (attributes != audioAttributes) {
            attributes = audioAttributes
            if (direct) flush()
        }
        super.setAudioAttributes(audioAttributes)
    }

    override fun getAudioAttributes(): AudioAttributes = attributes

    override fun setAudioSessionId(audioSessionId: Int) {
        if (sessionId != audioSessionId) {
            sessionId = audioSessionId
            if (direct) flush()
        }
        super.setAudioSessionId(audioSessionId)
    }

    override fun setVirtualDeviceId(virtualDeviceId: Int) {
        if (this.virtualDeviceId != virtualDeviceId) {
            this.virtualDeviceId = virtualDeviceId
            if (direct) flush()
        }
        super.setVirtualDeviceId(virtualDeviceId)
    }

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {
        // The exact mixer selection owns the direct track's USB routing.
        super.setPreferredDevice(audioDeviceInfo)
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        // Applying an auxiliary effect would invalidate the bit-perfect contract.
    }

    override fun getAudioTrackBufferSizeUs(): Long = if (direct) {
        val currentOutput = output
        if (currentOutput == null) C.TIME_UNSET else currentOutput.bufferSizeInFrames * 1_000_000L / currentOutput.sampleRate
    } else super.getAudioTrackBufferSizeUs()

    override fun flush() {
        releaseDirect()
        resetTiming()
        super.flush()
    }

    override fun reset() {
        flush()
        requestedConfig = null
        activeConfig = null
        requestedSelection = null
        direct = false
        switchPending = false
        super.reset()
    }

    override fun release() {
        reset()
        provider.release()
        super.release()
    }

    private fun applyPendingConfiguration(): Boolean {
        if (!switchPending) return true
        if (activeConfig != null) {
            if (direct) {
                stopDirect()
                if (hasPendingData()) return false
            } else if (requestedSelection != null) {
                super.playToEndOfStream()
                if (super.hasPendingData()) return false
                super.flush()
            }
        }
        releaseDirect()
        resetTiming()
        activeConfig = requestedConfig
        remainingDelayFrames = activeConfig?.format?.encoderDelay?.coerceAtLeast(0) ?: 0
        direct = requestedSelection != null
        switchPending = false
        return true
    }

    private fun initializeDirect(): Boolean {
        val config = checkNotNull(activeConfig)
        if (Build.VERSION.SDK_INT < 34) return fallBack(config)
        val format = config.format
        val selection = requestedSelection ?: return fallBack(config)
        val nextLease = controller.prepare(selection, attributes) ?: return fallBack(config)
        var created: AudioTrackAudioOutput? = null
        try {
            val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, selection.mixer.format.channelMask, format.pcmEncoding)
            check(minBuffer > 0) { "Unsupported USB AudioTrack buffer size" }
            val frameSize = Util.getPcmFrameSize(format.pcmEncoding, format.channelCount)
            val bufferSize = (minBuffer.toLong() * 4).coerceAtMost(Int.MAX_VALUE.toLong() - frameSize).toInt()
                .let { it - it % frameSize }
            val outConfig = AudioOutputProvider.OutputConfig.Builder()
                .setEncoding(format.pcmEncoding)
                .setSampleRate(format.sampleRate)
                .setChannelMask(selection.mixer.format.channelMask)
                .setBufferSize(bufferSize)
                .setAudioAttributes(attributes)
                .setAudioSessionId(sessionId)
                .setVirtualDeviceId(virtualDeviceId)
                .setUsePlaybackParameters(false)
                .setIsOffload(false)
                .build()
            created = provider.getAudioOutput(outConfig)
            if (!controller.attach(nextLease, created.audioTrack)) {
                created.release()
                controller.detach(nextLease)
                return fallBack(config)
            }
            val trackConfig = AudioSink.AudioTrackConfig(format.pcmEncoding, format.sampleRate, outConfig.channelMask, false, false, outConfig.bufferSize)
            created.addListener(object : AudioOutput.Listener {
                override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) {
                    sinkListener?.onPositionAdvancing(playoutStartSystemTimeMs)
                }
                override fun onUnderrun() {
                    sinkListener?.onUnderrun(outConfig.bufferSize, bytesToDurationUs(outConfig.bufferSize.toLong(), format) / 1_000,
                        SystemClock.elapsedRealtime() - lastFeedMs)
                }
                override fun onReleased() { sinkListener?.onAudioTrackReleased(trackConfig) }
                override fun onOffloadDataRequest() = Unit
                override fun onOffloadPresentationEnded() = Unit
            })
            output = created
            lease = nextLease
            pcmQueue = UsbPcmFrameQueue(frameSize, remainingDelayFrames, format.encoderPadding)
            playerId?.let(created::setPlayerId)
            if (sessionId != created.audioSessionId) {
                sessionId = created.audioSessionId
                sinkListener?.onAudioSessionIdChanged(sessionId)
            }
            sinkListener?.onAudioTrackInitialized(trackConfig)
            if (playing) playDirect()
            return true
        } catch (error: Exception) {
            if (created != null && output === created) {
                releaseDirect()
            } else {
                created?.release()
                controller.detach(nextLease)
            }
            resetTiming()
            controller.unavailable()
            sinkListener?.onAudioSinkError(error)
            return fallBack(config)
        }
    }

    private fun fallBack(config: AudioSink.AudioSinkConfig): Boolean {
        direct = false
        try {
            super.configure(config)
            if (playing) super.play()
        } catch (error: AudioSink.ConfigurationException) {
            throw AudioSink.InitializationException("USB fallback configuration failed", AudioTrack.STATE_UNINITIALIZED, config.format, false, error)
        }
        return false
    }

    private fun playDirect() {
        val currentOutput = output ?: return
        val currentLease = lease ?: return
        try {
            check(controller.resume(currentLease)) { "USB bit-perfect mixer could not be reacquired" }
            currentOutput.play()
            controller.refresh(currentLease)
        } catch (error: Exception) {
            val format = checkNotNull(activeConfig).format
            releaseDirect()
            controller.unavailable()
            resumeFailure = AudioSink.InitializationException(
                "USB bit-perfect output could not resume", AudioTrack.STATE_UNINITIALIZED, format, true, error,
            )
        }
    }

    private fun stopDirect() {
        if (stopped) return
        // Encoder padding is held back byte-for-byte and discarded only at a stream boundary.
        pcmQueue?.discardPadding()
        output?.stop()
        lease?.let(controller::refresh)
        stopped = true
    }

    private fun releaseDirect() {
        lease?.let(controller::detach)
        lease = null
        output?.release()
        output = null
    }

    private fun resetTiming() {
        writtenBytes = 0L
        submittedBytes = 0L
        startMediaTimeUs = C.TIME_UNSET
        needsDiscontinuity = false
        stopped = false
        currentInput = null
        pcmQueue = null
        resumeFailure = null
    }

    private fun throwResumeFailure() {
        resumeFailure?.let { exception ->
            resumeFailure = null
            sinkListener?.onAudioSinkError(exception)
            throw exception
        }
    }

    private fun bytesToDurationUs(bytes: Long, format: Format): Long =
        (bytes / Util.getPcmFrameSize(format.pcmEncoding, format.channelCount)) * 1_000_000L / format.sampleRate

    private fun directlySupportedPcm(format: Format): Boolean = format.sampleMimeType == MimeTypes.AUDIO_RAW &&
        controller.select(format, reportStatus = false) != null
}

/** Frame trimming only copies bytes; retained samples are never scaled or converted. */
internal class UsbPcmFrameQueue(private val frameSize: Int, delayFrames: Int, paddingFrames: Int) {
    private var skipBytes = delayFrames.coerceAtLeast(0).toLong() * frameSize
    val remainingDelayFrames: Int get() = (skipBytes / frameSize).toInt()
    private val paddingBytes = Math.multiplyExact(paddingFrames.coerceAtLeast(0), frameSize)
    private var tail = ByteArray(0)
    var pending: ByteBuffer? = null
        private set

    fun queue(input: ByteBuffer): Int {
        check(pending == null)
        require(input.remaining() % frameSize == 0) { "PCM buffers must contain whole frames" }
        val skip = min(skipBytes, input.remaining().toLong()).toInt()
        input.position(input.position() + skip)
        skipBytes -= skip
        val accepted = input.remaining()
        if (paddingBytes == 0 && tail.isEmpty()) {
            pending = input.takeIf { it.hasRemaining() }
            return accepted
        }
        val data = ByteBuffer.allocateDirect(Math.addExact(tail.size, accepted)).order(ByteOrder.nativeOrder())
        data.put(tail).put(input).flip()
        val retained = min(paddingBytes, data.remaining())
        val emitLimit = data.limit() - retained
        tail = ByteArray(retained)
        data.position(emitLimit)
        data.get(tail)
        data.position(0).limit(emitLimit)
        pending = data.takeIf { it.hasRemaining() }
        return accepted
    }

    fun clearPending() { pending = null }
    fun discardPadding() { tail = ByteArray(0) }
}
