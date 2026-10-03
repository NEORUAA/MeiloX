package com.ljyh.mei.playback

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ljyh.mei.MainActivity
import com.ljyh.mei.R
import java.util.Date

class SleepTimerNotification(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.more_action_sleep_timer),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setSound(null, null) },
        )
        manager.cancel(NOTIFICATION_ID)
    }

    fun isEnabled(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED && manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE

    fun update(state: SleepTimerState) {
        if (state == SleepTimerState.Off || !isEnabled()) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        val openApp = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            context,
            NOTIFICATION_ID,
            Intent(context, MusicService::class.java).setAction(MusicService.ACTION_CANCEL_SLEEP_TIMER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sleep_timer)
            .setContentTitle(context.getString(R.string.sleep_timer_active))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(0, context.getString(R.string.sleep_timer_cancel), cancel)

        if (state is SleepTimerState.Countdown) {
            val remaining = (state.deadlineElapsedRealtimeMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            val endTime = System.currentTimeMillis() + remaining
            builder
                .setContentText(context.getString(
                    R.string.sleep_timer_stops_at,
                    DateFormat.getTimeFormat(context).format(Date(endTime)),
                ))
                .setWhen(endTime)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setTimeoutAfter(remaining.coerceAtLeast(1L))
        } else {
            builder
                .setContentText(context.getString(R.string.sleep_timer_end_of_track))
                .setShowWhen(false)
        }
        // Permission can be revoked between the check and posting the notification.
        try {
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            manager.cancel(NOTIFICATION_ID)
        }
    }

    companion object {
        const val CHANNEL_ID = "sleep_timer"
        private const val NOTIFICATION_ID = 1002
    }
}
