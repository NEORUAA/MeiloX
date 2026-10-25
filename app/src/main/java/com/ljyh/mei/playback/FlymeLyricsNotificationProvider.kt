package com.ljyh.mei.playback

import android.app.Notification
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/** Adds Flyme's ticker contract to the existing media notification, including artwork callbacks. */
internal class FlymeLyricsNotificationProvider(
    private val delegate: MediaNotification.Provider,
    private val refreshNotification: () -> Unit,
) : MediaNotification.Provider by delegate {
    private var lyric: String? = null
    private var tickerOnlyUpdate = false

    fun setLyric(text: String?) {
        val next = text?.replace('\r', ' ')?.replace('\n', ' ')?.take(5_120)
            ?.takeIf(String::isNotBlank)
        if (next == lyric) return
        lyric = next
        tickerOnlyUpdate = true
        try {
            refreshNotification()
        } finally {
            tickerOnlyUpdate = false
        }
    }

    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification = delegate.createNotification(
        mediaSession,
        mediaButtonPreferences,
        actionFactory,
    ) { updated ->
        // A completed artwork request must refresh artwork as well as the current lyric.
        decorate(updated, tickerOnly = false)
        onNotificationChangedCallback.onNotificationChanged(updated)
    }.also { decorate(it, tickerOnlyUpdate) }

    private fun decorate(notification: MediaNotification, tickerOnly: Boolean) {
        notification.notification.applyFlymeLyric(lyric, tickerOnly)
    }
}

internal data class FlymeTickerFlags(val alwaysShow: Int, val onlyUpdate: Int) {
    companion object {
        val system: FlymeTickerFlags by lazy {
            // Retain the protocol on other ROMs after the user has explicitly enabled it.
            FlymeTickerFlags(
                readFlag("FLAG_ALWAYS_SHOW_TICKER", 0x01000000),
                readFlag("FLAG_ONLY_UPDATE_TICKER", 0x02000000),
            )
        }

        private fun readFlag(name: String, fallback: Int): Int = try {
            Notification::class.java.getDeclaredField(name).apply { isAccessible = true }
                .getInt(null).takeIf { it > 0 } ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }
}

internal fun Notification.applyFlymeLyric(
    lyric: String?,
    tickerOnly: Boolean,
    flymeFlags: FlymeTickerFlags = FlymeTickerFlags.system,
) {
    flags = flags and flymeFlags.alwaysShow.inv() and flymeFlags.onlyUpdate.inv()
    tickerText = lyric
    if (lyric != null) {
        flags = flags or Notification.FLAG_NO_CLEAR or flymeFlags.alwaysShow
        if (tickerOnly) flags = flags or flymeFlags.onlyUpdate
        extras.putBoolean("ticker_icon_switch", false)
        // Reuse the media notification icon rather than shipping a second ticker icon.
        extras.putInt("ticker_icon", smallIcon?.resId ?: android.R.drawable.ic_media_play)
    } else {
        extras.remove("ticker_icon_switch")
        extras.remove("ticker_icon")
    }
}
