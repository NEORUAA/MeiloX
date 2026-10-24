package com.ljyh.mei.playback

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import androidx.media3.session.MediaSession
import androidx.media3.session.PlatformLyricsSessionAccess
import com.ljyh.mei.R
import timber.log.Timber

internal const val COLOR_OS_TRANSLATION_ACTION =
    "io.github.andrealtb.lockscreenlyrics.action.TOGGLE_TRANSLATION"
private const val TRANSLATION_ACTION_OWNER = "com.ljyh.mei.playback.ColorOsLyricsPublisher"
private const val LYRIC_INFO = "lyricInfo"
private const val MAX_METADATA_BYTES = 512 * 1024

/** Overlays platform metadata without replacing the host session or its transport controls. */
internal class ColorOsLyricsPublisher(
    context: Context,
    session: MediaSession,
    private val translationActionEnabled: Boolean = Build.MANUFACTURER.let { manufacturer ->
        listOf("OPPO", "OnePlus", "realme", "OPlus").any { manufacturer.equals(it, ignoreCase = true) }
    },
) {
    private val externalPlayer = session.player as? ExternalMediaPlayer
    private val platformSession = PlatformLyricsSessionAccess.get(session)
    private val controller = platformSession.controller
    private val translationAction = PlaybackState.CustomAction.Builder(
        COLOR_OS_TRANSLATION_ACTION,
        context.getString(R.string.lyrics_translation),
        context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_menu_manage,
    ).setExtras(Bundle().apply { putBoolean(TRANSLATION_ACTION_OWNER, true) }).build()
    private var mediaId: String? = null
    private var payload: String? = null
    private var hasTranslation = false
    private var lastPublished: String? = null
    private var externalMediaId: String? = null
    private var externalLyric: String? = null
    private var lastExternalMediaId: String? = null
    private var lastExternalTitle: String? = null
    private var originalTitle: String? = null
    private var released = false
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            publishMetadata()
            publishPlaybackState()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) = publishPlaybackState()
    }

    init {
        controller.registerCallback(callback, Handler(Looper.getMainLooper()))
    }

    fun setLyrics(mediaId: String?, payload: String?, hasTranslation: Boolean = false) {
        if (released) return
        this.mediaId = mediaId
        this.payload = payload
        this.hasTranslation = hasTranslation
        publishMetadata()
        publishPlaybackState()
    }

    fun setExternalLyrics(mediaId: String?, lyric: String?) {
        if (released) return
        if (externalPlayer != null) {
            externalPlayer.setExternalLyrics(mediaId, lyric)
            return
        }
        externalMediaId = mediaId
        externalLyric = lyric?.takeIf { it.isNotBlank() }
        publishMetadata()
    }

    private fun currentPayload(metadata: MediaMetadata): String? = payload.takeIf {
        mediaId != null && metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) == mediaId
    }

    private fun publishMetadata() {
        if (released) return
        try {
            val original = controller.metadata ?: return
            val current = original.getString(LYRIC_INFO)
            val desired = currentPayload(original)
            // Remove only data written by this publisher, never another metadata owner's field.
            var updateLyrics = current != desired &&
                (desired != null || (lastPublished != null && current == lastPublished))
            val currentId = original.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            val currentTitle = original.getString(MediaMetadata.METADATA_KEY_TITLE)
            val ownsTitle = currentId != null && currentId == lastExternalMediaId && currentTitle == lastExternalTitle
            val lyric = externalLyric.takeIf { currentId != null && currentId == externalMediaId }
            if (lyric != null && !ownsTitle) originalTitle = currentTitle
            val title = lyric ?: if (ownsTitle) originalTitle else currentTitle
            val artwork = original.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: original.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            if (artwork != null) externalPlayer?.setArtwork(currentId, artwork)
            val addArtwork = artwork != null && original.getBitmap(MediaMetadata.METADATA_KEY_ART) == null
            val updateTitle = currentTitle != title
            if (!updateLyrics && !updateTitle && !addArtwork) return
            fun buildCandidate(writeLyrics: Boolean) = copyMetadata(original, writeLyrics).apply {
                if (writeLyrics && desired != null) putString(LYRIC_INFO, desired)
                if (updateTitle) putString(MediaMetadata.METADATA_KEY_TITLE, title)
                // Match the official client's three bitmap fields for legacy external controllers.
                if (addArtwork) putBitmap(MediaMetadata.METADATA_KEY_ART, artwork)
            }.build()
            var candidate = buildCandidate(updateLyrics)
            val parcel = Parcel.obtain()
            val fits = try {
                candidate.writeToParcel(parcel, 0)
                parcel.dataSize() <= MAX_METADATA_BYTES
            } finally {
                parcel.recycle()
            }
            if (updateLyrics && desired != null && !fits) {
                // An oversized sharing payload must not suppress external titles or artwork.
                if (!updateTitle && !addArtwork) return
                updateLyrics = false
                candidate = buildCandidate(false)
            }
            platformSession.setMetadata(candidate)
            if (updateLyrics) lastPublished = desired
            lastExternalMediaId = currentId.takeIf { lyric != null }
            lastExternalTitle = lyric
        } catch (error: Exception) {
            Timber.w(error, "ColorOS lyric metadata publication failed")
        }
    }

    private fun publishPlaybackState() {
        if (released) return
        try {
            val original = controller.playbackState ?: return
            val metadata = controller.metadata
            val showTranslation = translationActionEnabled && hasTranslation && metadata != null &&
                currentPayload(metadata)?.let { it == metadata.getString(LYRIC_INFO) } == true
            val actions = original.customActions.orEmpty()
            val owned = actions.filter {
                it.action == COLOR_OS_TRANSLATION_ACTION && it.extras?.getBoolean(TRANSLATION_ACTION_OWNER) == true
            }
            if (showTranslation && actions.any { it.action == COLOR_OS_TRANSLATION_ACTION }) return
            if (!showTranslation && owned.isEmpty()) return
            val builder = PlaybackState.Builder()
                .setState(original.state, original.position, original.playbackSpeed, original.lastPositionUpdateTime)
                .setBufferedPosition(original.bufferedPosition)
                .setActions(original.actions)
                .setErrorMessage(original.errorMessage)
                .setActiveQueueItemId(original.activeQueueItemId)
                .setExtras(original.extras)
            actions.filterNot { it in owned }.forEach(builder::addCustomAction)
            if (showTranslation) builder.addCustomAction(translationAction)
            platformSession.setPlaybackState(builder.build())
        } catch (error: Exception) {
            Timber.w(error, "ColorOS translation action publication failed")
        }
    }

    fun release() {
        if (released) return
        controller.unregisterCallback(callback)
        setExternalLyrics(null, null)
        setLyrics(null, null)
        released = true
    }

    @Suppress("DEPRECATION")
    private fun copyMetadata(original: MediaMetadata, stripLyrics: Boolean): MediaMetadata.Builder {
        val parcel = Parcel.obtain()
        val values = try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            requireNotNull(parcel.readBundle(MediaMetadata::class.java.classLoader))
        } finally {
            parcel.recycle()
        }
        // A fresh typed builder avoids ColorOS losing lazily parcelled bitmap fields.
        // Unknown keys still retain their original public MediaMetadata value types.
        return MediaMetadata.Builder().apply {
            values.keySet().forEach { key ->
                if (stripLyrics && key == LYRIC_INFO) return@forEach
                when (val value = values.get(key)) {
                    is Bitmap -> putBitmap(key, value)
                    is Rating -> putRating(key, value)
                    is Long -> putLong(key, value)
                    is CharSequence -> putText(key, value)
                    null -> when (key) {
                        MediaMetadata.METADATA_KEY_ART,
                        MediaMetadata.METADATA_KEY_ALBUM_ART,
                        MediaMetadata.METADATA_KEY_DISPLAY_ICON -> putBitmap(key, null)
                        MediaMetadata.METADATA_KEY_RATING,
                        MediaMetadata.METADATA_KEY_USER_RATING -> putRating(key, null)
                        else -> putText(key, null)
                    }
                    // Leave unusual vendor metadata untouched instead of dropping its fields.
                    else -> error("Unsupported media metadata value type")
                }
            }
        }
    }
}
