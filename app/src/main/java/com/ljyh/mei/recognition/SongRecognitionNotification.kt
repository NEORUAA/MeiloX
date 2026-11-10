package com.ljyh.mei.recognition

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ljyh.mei.MainActivity
import com.ljyh.mei.R

class SongRecognitionNotification(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        manager.createNotificationChannel(
            NotificationChannel(ACTIVE_CHANNEL, context.getString(R.string.recognition_active), NotificationManager.IMPORTANCE_LOW)
                .apply { setSound(null, null) },
        )
        manager.createNotificationChannel(
            resultChannel(RESULT_CHANNEL, R.string.recognition_result_notifications),
        )
        manager.createNotificationChannel(resultChannel(PANEL_CHANNEL, R.string.recognition_panel_channel))
    }

    private fun resultChannel(id: String, name: Int) =
        NotificationChannel(id, context.getString(name), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 180)
        }

    fun ongoing(): Notification = builder(ACTIVE_CHANNEL)
        .setContentTitle(context.getString(R.string.recognition_active))
        .setOngoing(true)
        .setSilent(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .addAction(
            0,
            context.getString(R.string.recognition_stop),
            PendingIntent.getService(
                context, ACTIVE_ID, SongRecognitionService.stopMicrophoneIntent(context), PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    fun show(outcome: RecognitionOutcome) {
        val (title, message) = outcomeText(outcome)
        post(RESULT_ID, builder(RESULT_CHANNEL)
            .setContentTitle(context.getString(title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build())
    }

    fun panel(state: SongRecognitionUiState, token: String, providerId: String, alert: Boolean = false): Notification {
        val outcome = state.lastOutcome
        val phase = when (state.phase) {
            RecognitionPhase.Starting -> R.string.recognition_active
            RecognitionPhase.Listening -> R.string.recognition_listening
            RecognitionPhase.Fingerprinting -> R.string.recognition_fingerprinting
            RecognitionPhase.Matching -> R.string.recognition_matching
            else -> R.string.recognition_panel_ready
        }
        val status = context.getString(phase)
        val (title, message) = outcome?.let(::outcomeText) ?: (R.string.song_recognition to status)
        val details = if (outcome != null && state.isWorking) "$message\n$status" else message
        val close = serviceAction(SongRecognitionService.closeIntent(context, token))
        val action = when {
            state.isWorking -> R.string.recognition_stop to SongRecognitionService.stopIntent(context, token)
            outcome != null -> R.string.recognition_panel_retry to SongRecognitionService.runIntent(context, token)
            else -> R.string.recognition_panel_start to SongRecognitionService.runIntent(context, token)
        }
        return builder(PANEL_CHANNEL)
            .setContentTitle(context.getString(title))
            .setContentText(details)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setContentIntent(openRecognition(providerId))
            .setOngoing(true)
            .setSilent(!alert)
            .setOnlyAlertOnce(!alert)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setDeleteIntent(close)
            .addAction(0, context.getString(action.first), serviceAction(action.second))
            .addAction(0, context.getString(R.string.recognition_panel_close), close)
            .build()
    }

    fun updatePanel(state: SongRecognitionUiState, token: String, providerId: String, alert: Boolean) {
        post(PANEL_ID, panel(state, token, providerId, alert))
    }

    fun showPanelExpired(@androidx.annotation.StringRes reason: Int, providerId: String) {
        val message = context.getString(reason)
        post(PANEL_ID, builder(PANEL_CHANNEL)
            .setContentTitle(context.getString(R.string.recognition_failed))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(null)
            .addAction(0, context.getString(R.string.recognition_panel_reauthorize), openRecognition(providerId))
            .build())
    }

    private fun outcomeText(outcome: RecognitionOutcome): Pair<Int, String> = when (outcome) {
        is RecognitionOutcome.Matched -> R.string.recognition_results to
            (listOf(outcome.song.name) + outcome.song.artists).joinToString(" · ")
        is RecognitionOutcome.NoMatch -> R.string.recognition_no_match to context.getString(
            if (outcome.silentPlayback) R.string.recognition_no_playback_audio else R.string.recognition_no_match_detail,
        )
        is RecognitionOutcome.Failed -> R.string.recognition_failed to outcome.message
    }

    private fun openRecognition(providerId: String): PendingIntent = PendingIntent.getActivity(
        context,
        PANEL_ID,
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_SONG_RECOGNITION)
            .setIdentifier(providerId)
            .putExtra(MainActivity.EXTRA_RECOGNITION_PROVIDER, providerId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun serviceAction(intent: Intent): PendingIntent =
        PendingIntent.getService(context, PANEL_ID, intent, PendingIntent.FLAG_IMMUTABLE)

    @SuppressLint("MissingPermission")
    private fun post(id: Int, notification: Notification) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED || !manager.areNotificationsEnabled()
        ) return
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // Notification permission can be revoked after the check.
        }
    }

    private fun builder(channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_song_recognition)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                ACTIVE_ID,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )

    companion object {
        const val ACTIVE_ID = 1003
        const val PANEL_ID = 1005
        private const val RESULT_ID = 1004
        private const val ACTIVE_CHANNEL = "song_recognition_active"
        private const val RESULT_CHANNEL = "song_recognition_results"
        private const val PANEL_CHANNEL = "song_recognition_playback_panel"
    }
}
