package com.ljyh.mei.playback

import android.media.MediaMetadata
import android.media.session.MediaController
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import androidx.media3.session.MediaSession
import androidx.media3.session.PlatformLyricsSessionAccess
import timber.log.Timber

/** Overlays lyrics on the host session and reapplies them after artwork/metadata updates. */
internal class ColorOsLyricsPublisher(session: MediaSession) {
    private val platformSession = PlatformLyricsSessionAccess.get(session)
    private val controller = platformSession.controller
    private var mediaId: String? = null
    private var payload: String? = null
    private var lastPublished: String? = null
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
    }

    init {
        controller.registerCallback(callback, Handler(Looper.getMainLooper()))
    }

    fun setLyrics(mediaId: String?, payload: String?) {
        this.mediaId = mediaId
        this.payload = payload
        publish()
    }

    private fun publish() {
        try {
            val original = controller.metadata ?: return
            val currentPayload = original.getString("lyricInfo")
            val desired = payload.takeIf {
                mediaId != null && original.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) == mediaId
            }
            if (currentPayload == desired) return
            // Remove only data written by this publisher, never another metadata owner's field.
            if (desired == null && (lastPublished == null || currentPayload != lastPublished)) return
            val candidate = MediaMetadata.Builder(original).putString("lyricInfo", desired).build()
            val parcel = Parcel.obtain()
            val fits = try {
                candidate.writeToParcel(parcel, 0)
                parcel.dataSize() <= 512 * 1024
            } finally {
                parcel.recycle()
            }
            if (desired != null && !fits) return
            platformSession.setMetadata(candidate)
            lastPublished = desired
        } catch (error: Exception) {
            Timber.w(error, "ColorOS lyric metadata publication failed")
        }
    }

    fun release() {
        controller.unregisterCallback(callback)
        setLyrics(null, null)
    }
}
