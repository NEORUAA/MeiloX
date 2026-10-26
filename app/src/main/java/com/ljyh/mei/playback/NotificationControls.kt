package com.ljyh.mei.playback

import android.content.Context
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaNotification
import androidx.media3.session.SessionCommand
import com.google.common.collect.ImmutableList
import com.ljyh.mei.R
import com.ljyh.mei.MainActivity
import com.ljyh.mei.constants.NotificationAction

internal const val NOTIFICATION_LEFT_COMMAND = "com.neoruaa.meilox.notification.LEFT"
internal const val NOTIFICATION_RIGHT_COMMAND = "com.neoruaa.meilox.notification.RIGHT"
internal const val FLOATING_LYRICS_BUTTON = "floatingLyrics"
internal const val FLOATING_LYRICS_ENABLED = "floatingLyricsEnabled"
internal const val ENABLE_FLOATING_LYRICS = "com.neoruaa.meilox.floatingLyrics.ENABLE"
internal const val DISABLE_FLOATING_LYRICS = "com.neoruaa.meilox.floatingLyrics.DISABLE"

private val useHyperOsNotificationIcons: Boolean by lazy {
    val versionName = if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) {
        try {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, "ro.mi.os.version.name", "") as? String ?: ""
        } catch (_: Exception) {
            ""
        }
    } else ""
    usesHyperOsNotificationIcons(Build.MANUFACTURER, versionName)
}

internal fun usesHyperOsNotificationIcons(manufacturer: String, versionName: String): Boolean =
    manufacturer.equals("Xiaomi", ignoreCase = true) && versionName.isNotEmpty()

/** Matches the reference clients' separate 40dp resources for HyperOS custom actions. */
internal fun hyperOsNotificationIcon(icon: Int): Int = when (icon) {
    R.drawable.ic_notification_sf_shuffle -> R.drawable.ic_notification_hyperos_sf_shuffle
    R.drawable.ic_notification_sf_repeat -> R.drawable.ic_notification_hyperos_sf_repeat
    R.drawable.ic_notification_sf_repeat_1 -> R.drawable.ic_notification_hyperos_sf_repeat_1
    R.drawable.ic_notification_sf_heart -> R.drawable.ic_notification_hyperos_sf_heart
    R.drawable.ic_notification_sf_heart_fill -> R.drawable.ic_notification_hyperos_sf_heart_fill
    R.drawable.ic_notification_sf_text_rectangle -> R.drawable.ic_notification_hyperos_sf_text_rectangle
    R.drawable.ic_notification_sf_text_rectangle_fill -> R.drawable.ic_notification_hyperos_sf_text_rectangle_fill
    else -> icon
}

internal fun floatingLyricsIntent(context: Context, enabled: Boolean): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(if (enabled) ENABLE_FLOATING_LYRICS else DISABLE_FLOATING_LYRICS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)

internal fun floatingLyricsPendingIntent(context: Context, enabled: Boolean): PendingIntent {
    val options = ActivityOptions.makeBasic().apply {
        if (Build.VERSION.SDK_INT >= 35) {
            setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
    }
    return PendingIntent.getActivity(
        context,
        if (enabled) 704 else 705,
        floatingLyricsIntent(context, enabled),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        options.toBundle(),
    )
}

internal fun notificationButton(
    context: Context,
    action: NotificationAction,
    left: Boolean,
    repeatMode: Int,
    shuffle: Boolean,
    liked: Boolean,
    enabled: Boolean,
    floatingLyricsEnabled: Boolean = false,
    useHyperOsIcons: Boolean = useHyperOsNotificationIcons,
): CommandButton? {
    val icon: Int
    val label: Int
    when (action) {
        NotificationAction.None -> return null
        NotificationAction.PlayMode -> when {
            shuffle -> {
                icon = R.drawable.ic_notification_sf_shuffle
                label = R.string.notification_mode_shuffle
            }
            repeatMode == Player.REPEAT_MODE_ONE -> {
                icon = R.drawable.ic_notification_sf_repeat_1
                label = R.string.notification_mode_repeat_one
            }
            else -> {
                icon = R.drawable.ic_notification_sf_repeat
                label = if (repeatMode == Player.REPEAT_MODE_ALL) {
                    R.string.notification_mode_repeat_all
                } else {
                    R.string.notification_mode_sequential
                }
            }
        }
        NotificationAction.Like -> {
            icon = if (liked) R.drawable.ic_notification_sf_heart_fill else R.drawable.ic_notification_sf_heart
            label = if (liked) R.string.notification_remove_like else R.string.notification_add_like
        }
        NotificationAction.FloatingLyrics -> {
            icon = if (floatingLyricsEnabled) R.drawable.ic_notification_sf_text_rectangle_fill
                else R.drawable.ic_notification_sf_text_rectangle
            label = if (floatingLyricsEnabled) R.string.notification_disable_floating_lyrics
                else R.string.notification_enable_floating_lyrics
        }
    }
    // ICON_UNDEFINED keeps controllers from substituting a predefined icon for the SF glyph.
    return CommandButton.Builder(CommandButton.ICON_UNDEFINED)
        .setCustomIconResId(if (useHyperOsIcons) hyperOsNotificationIcon(icon) else icon)
        .setDisplayName(context.getString(label))
        .setSessionCommand(SessionCommand(
            if (left) NOTIFICATION_LEFT_COMMAND else NOTIFICATION_RIGHT_COMMAND,
            Bundle.EMPTY,
        ))
        .setSlots(
            if (left) CommandButton.SLOT_BACK_SECONDARY else CommandButton.SLOT_FORWARD_SECONDARY,
            CommandButton.SLOT_OVERFLOW,
        )
        .setEnabled(enabled)
        .setExtras(Bundle().apply {
            if (action == NotificationAction.FloatingLyrics) {
                putBoolean(FLOATING_LYRICS_BUTTON, true)
                putBoolean(FLOATING_LYRICS_ENABLED, floatingLyricsEnabled)
            }
        })
        .build()
}

/** Keeps side actions on either side of transport controls in notification-based system UIs. */
internal class PlaybackNotificationProvider(
    private val context: Context,
    notificationId: Int,
    channelId: String,
    private val songTitle: () -> CharSequence?,
) :
    DefaultMediaNotificationProvider(context, { notificationId }, channelId, R.string.app_name_en) {
    override fun getNotificationContentTitle(metadata: MediaMetadata): CharSequence? =
        songTitle() ?: super.getNotificationContentTitle(metadata)

    override fun addNotificationActions(
        mediaSession: MediaSession,
        mediaButtons: ImmutableList<CommandButton>,
        builder: NotificationCompat.Builder,
        actionFactory: MediaNotification.ActionFactory,
    ): IntArray = super.addNotificationActions(mediaSession, mediaButtons, builder,
        object : MediaNotification.ActionFactory by actionFactory {
            override fun createCustomActionFromCustomCommandButton(
                mediaSession: MediaSession,
                customCommandButton: CommandButton,
            ): NotificationCompat.Action {
                if (!customCommandButton.extras.getBoolean(FLOATING_LYRICS_BUTTON)) {
                    return actionFactory.createCustomActionFromCustomCommandButton(mediaSession, customCommandButton)
                }
                // Launch directly from the notification, without a service notification trampoline.
                return NotificationCompat.Action.Builder(
                    IconCompat.createWithResource(context, customCommandButton.iconResId),
                    customCommandButton.displayName,
                    floatingLyricsPendingIntent(context, !customCommandButton.extras.getBoolean(FLOATING_LYRICS_ENABLED)),
                ).build()
            }
        },
    )

    override fun getMediaButtons(
        session: MediaSession,
        playerCommands: Player.Commands,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        showPauseButton: Boolean,
    ): ImmutableList<CommandButton> {
        val buttons = super.getMediaButtons(session, playerCommands, mediaButtonPreferences, showPauseButton)
        val left = buttons.filter { it.sessionCommand?.customAction == NOTIFICATION_LEFT_COMMAND }
        val right = buttons.filter { it.sessionCommand?.customAction == NOTIFICATION_RIGHT_COMMAND }
        return ImmutableList.copyOf(left + buttons.filter { it !in left && it !in right } + right)
    }
}
