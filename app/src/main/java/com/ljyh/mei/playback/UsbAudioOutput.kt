package com.ljyh.mei.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UsbAudioStatus { Inactive, WaitingForDevice, UnsupportedDevice, UnsupportedFormat, Active, Unavailable }

/** Owns the app's mixer preference. A retired track can never clear a newer track's lease. */
@UnstableApi
class UsbAudioOutput(context: Context, private val onDevicesChanged: () -> Unit) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mutableStatus = MutableStateFlow(UsbAudioStatus.Inactive)
    val status: StateFlow<UsbAudioStatus> = mutableStatus.asStateFlow()
    private var enabled = false
    private var released = false
    private var lease: Lease? = null
    private val mixerListener = if (Build.VERSION.SDK_INT >= 34) MixerListener() else null
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = devicesChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = devicesChanged()
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        if (Build.VERSION.SDK_INT >= 34) {
            mixerListener?.let { listener ->
                runCatching { audioManager.addOnPreferredMixerAttributesChangedListener(context.mainExecutor, listener) }
            }
        }
    }

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (released || enabled == value) return
        enabled = value
        if (!value) {
            if (Build.VERSION.SDK_INT >= 34) lease?.let(::relinquish)
            mutableStatus.value = UsbAudioStatus.Inactive
        } else {
            updateAvailability()
        }
    }

    @Synchronized
    internal fun select(format: Format, reportStatus: Boolean = true): Selection? {
        if (!enabled || released) return null
        if (Build.VERSION.SDK_INT < 34) {
            if (reportStatus) mutableStatus.value = UsbAudioStatus.Unavailable
            return null
        }
        val devices = usbDevices()
        if (devices.isEmpty()) {
            if (reportStatus) mutableStatus.value = UsbAudioStatus.WaitingForDevice
            return null
        }
        val supported = devices.map { device ->
            device to runCatching { audioManager.getSupportedMixerAttributes(device) }.getOrDefault(emptyList())
                .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
        }
        if (supported.all { it.second.isEmpty() }) {
            if (reportStatus) mutableStatus.value = UsbAudioStatus.UnsupportedDevice
            return null
        }
        val channelMask = runCatching { Util.getAudioTrackChannelConfig(format) }.getOrDefault(0)
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW || channelMask == 0 || format.sampleRate <= 0) {
            if (reportStatus) mutableStatus.value = UsbAudioStatus.UnsupportedFormat
            return null
        }
        val currentIds = runCatching {
            audioManager.getAudioDevicesForAttributes(AudioAttributes.DEFAULT.platformAudioAttributes).map { it.id }
        }.getOrDefault(emptyList())
        val selection = supported.sortedBy { if (it.first.id in currentIds) 0 else 1 }.firstNotNullOfOrNull { (device, mixers) ->
            mixers.firstOrNull { attributes ->
                val candidate = attributes.format
                candidate.encoding == format.pcmEncoding && candidate.sampleRate == format.sampleRate &&
                    candidate.channelMask == channelMask
            }?.let { Selection(device, it) }
        }
        if (reportStatus) {
            mutableStatus.value = if (selection == null) UsbAudioStatus.UnsupportedFormat else UsbAudioStatus.Inactive
        }
        return selection
    }

    @Synchronized
    internal fun prepare(selection: Selection, attributes: AudioAttributes): Lease? {
        if (!enabled || released || Build.VERSION.SDK_INT < 34) return null
        lease?.let(::relinquish)
        val next = Lease(selection, attributes.platformAudioAttributes)
        lease = next
        if (!applyMixer(next)) {
            lease = null
            mutableStatus.value = UsbAudioStatus.Unavailable
            return null
        }
        return next
    }

    @Synchronized
    internal fun attach(owner: Lease, track: AudioTrack): Boolean {
        if (Build.VERSION.SDK_INT < 34 || lease !== owner || !enabled || released) return false
        owner.track = track
        if (!track.setPreferredDevice(owner.selection.device)) {
            relinquish(owner)
            mutableStatus.value = UsbAudioStatus.Unavailable
            return false
        }
        val listener = AudioRouting.OnRoutingChangedListener { synchronized(this) { validate(owner) } }
        owner.routingListener = listener
        track.addOnRoutingChangedListener(listener, handler)
        validate(owner)
        return true
    }

    @Synchronized
    internal fun resume(owner: Lease): Boolean {
        if (!enabled || released || Build.VERSION.SDK_INT < 34 || (lease != null && lease !== owner)) return false
        lease = owner
        if (!applyMixer(owner)) {
            mutableStatus.value = UsbAudioStatus.Unavailable
            return false
        }
        validate(owner)
        return true
    }

    @Synchronized
    internal fun suspend(owner: Lease) {
        if (Build.VERSION.SDK_INT >= 34 && lease === owner) relinquish(owner)
    }

    @Synchronized
    internal fun detach(owner: Lease) {
        owner.routingListener?.let { listener -> runCatching { owner.track?.removeOnRoutingChangedListener(listener) } }
        owner.routingListener = null
        if (Build.VERSION.SDK_INT >= 34 && lease === owner) relinquish(owner)
        owner.track = null
    }

    @Synchronized
    internal fun unavailable() {
        if (enabled && !released) mutableStatus.value = UsbAudioStatus.Unavailable
    }

    @Synchronized
    internal fun unsupportedFormat() {
        if (enabled && !released) mutableStatus.value = UsbAudioStatus.UnsupportedFormat
    }

    @Synchronized
    internal fun refresh(owner: Lease) = validate(owner)

    @Synchronized
    fun release() {
        if (released) return
        lease?.let(::detach)
        enabled = false
        released = true
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        if (Build.VERSION.SDK_INT >= 34) {
            mixerListener?.let { listener ->
                runCatching { audioManager.removeOnPreferredMixerAttributesChangedListener(listener) }
            }
        }
        mutableStatus.value = UsbAudioStatus.Inactive
    }

    private fun usbDevices(): List<AudioDeviceInfo> = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .filter { it.isSink && it.type in USB_TYPES }

    private fun devicesChanged() {
        synchronized(this) {
            if (!enabled || released) return
            updateAvailability()
        }
        onDevicesChanged()
    }

    private fun updateAvailability() {
        if (Build.VERSION.SDK_INT < 34) {
            mutableStatus.value = UsbAudioStatus.Unavailable
            return
        }
        val devices = usbDevices()
        mutableStatus.value = when {
            devices.isEmpty() -> UsbAudioStatus.WaitingForDevice
            devices.none { device ->
                runCatching { audioManager.getSupportedMixerAttributes(device) }.getOrDefault(emptyList())
                    .any { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            } -> UsbAudioStatus.UnsupportedDevice
            else -> UsbAudioStatus.Inactive
        }
        lease?.let(::validate)
    }

    @RequiresApi(34)
    private fun applyMixer(owner: Lease): Boolean = runCatching {
        audioManager.setPreferredMixerAttributes(owner.attributes, owner.selection.device, owner.selection.mixer)
    }.getOrDefault(false)

    @RequiresApi(34)
    private fun relinquish(owner: Lease) {
        if (lease !== owner) return
        lease = null
        runCatching { audioManager.clearPreferredMixerAttributes(owner.attributes, owner.selection.device) }
        if (enabled && !released) mutableStatus.value = UsbAudioStatus.Inactive
    }

    private fun validate(owner: Lease) {
        if (Build.VERSION.SDK_INT < 34 || lease !== owner || !enabled || released) return
        if (usbDevices().none { it.id == owner.selection.device.id }) {
            mutableStatus.value = UsbAudioStatus.WaitingForDevice
            return
        }
        val correctMixer = runCatching {
            audioManager.getPreferredMixerAttributes(owner.attributes, owner.selection.device) == owner.selection.mixer
        }.getOrDefault(false)
        mutableStatus.value = when {
            !correctMixer -> UsbAudioStatus.Unavailable
            owner.track?.routedDevice?.id == owner.selection.device.id &&
                owner.track?.playState == AudioTrack.PLAYSTATE_PLAYING -> UsbAudioStatus.Active
            else -> UsbAudioStatus.Inactive
        }
    }

    @RequiresApi(34)
    private inner class MixerListener : AudioManager.OnPreferredMixerAttributesChangedListener {
        override fun onPreferredMixerAttributesChanged(
            attributes: android.media.AudioAttributes,
            device: AudioDeviceInfo,
            mixerAttributes: AudioMixerAttributes?,
        ) {
            synchronized(this@UsbAudioOutput) {
                lease?.takeIf { it.selection.device.id == device.id && it.attributes == attributes }?.let(::validate)
            }
        }
    }

    internal data class Selection(val device: AudioDeviceInfo, val mixer: AudioMixerAttributes)
    internal class Lease(val selection: Selection, val attributes: android.media.AudioAttributes) {
        var track: AudioTrack? = null
        var routingListener: AudioRouting.OnRoutingChangedListener? = null
    }

    private companion object {
        val USB_TYPES = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY)
    }
}
