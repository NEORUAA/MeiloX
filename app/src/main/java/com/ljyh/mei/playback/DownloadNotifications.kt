package com.ljyh.mei.playback

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.ljyh.mei.MainActivity
import com.ljyh.mei.R
import com.ljyh.mei.BuildConfig
import timber.log.Timber

/** One visible progress notification; WorkManager still owns each worker's foreground lifetime. */
internal class DownloadNotifications private constructor(val progressId: Int, private val completionId: Int, private val channel: String) {
    companion object {
        const val PROGRESS_ID = 0x4D59FFFF
        private const val LAST_REQUEST = "meilox_download_last_request"
        private const val TOTAL = "meilox_download_total"
        val production = DownloadNotifications(PROGRESS_ID, DownloadWorker.NOTIFICATION_ID, DownloadWorker.CHANNEL_ID)
        val qualification by lazy {
            check(BuildConfig.DEBUG)
            DownloadNotifications(PROGRESS_ID - 1, PROGRESS_ID - 2, "download_qualification_channel")
        }

    }
    private val state = DownloadNotificationState()
    private var publishedRevision = -1L

    @Synchronized fun fence(): Long = state.revision

    @Synchronized fun begin(context: Context, requestId: String, account: Long, generation: Long): DownloadNotificationState.Lease {
        context.getSystemService(NotificationManager::class.java).cancel(completionId)
        return state.begin(requestId, account, generation)
    }

    @Synchronized fun progress(context: Context, lease: DownloadNotificationState.Lease, title: String, progress: Int): ForegroundInfo {
        check(state.update(lease, title, progress)) { "Download notification ownership changed" }
        return ForegroundInfo(progressId, build(context, checkNotNull(state.snapshot())))
    }

    @Synchronized fun finish(lease: DownloadNotificationState.Lease?, outcome: DownloadNotificationState.Outcome?, title: String = "") {
        if (lease != null) state.finish(lease, outcome, title)
    }

    @Synchronized fun active(): Boolean = (state.snapshot()?.active ?: 0) > 0

    @Synchronized fun refreshProgress(context: Context): Boolean {
        val snapshot = state.snapshot()?.takeIf { it.active > 0 } ?: return false
        context.getSystemService(NotificationManager::class.java).notify(progressId, build(context, snapshot))
        return true
    }

    @Synchronized fun current(context: Context, fallback: Notification): Notification =
        state.snapshot()?.let { build(context, it) } ?: fallback

    @Synchronized fun publishCompletion(context: Context) {
        val snapshot = state.snapshot()?.takeIf { it.active == 0 && it.revision != publishedRevision } ?: return
        post(context, snapshot)
        publishedRevision = snapshot.revision
    }

    @Synchronized fun publishPreflightFailure(context: Context, fence: Long, requestId: String, title: String) {
        if (state.revision != fence || active()) return
        post(context, DownloadNotificationState.Snapshot(fence, title, 0, 0, 1, requestId))
    }

    @Synchronized fun dismissProbeCompletion(context: Context) {
        check(BuildConfig.DEBUG && this === qualification && !active())
        val manager = context.getSystemService(NotificationManager::class.java)
        check(manager.activeNotifications.none { it.id == progressId })
        manager.cancel(completionId)
        manager.deleteNotificationChannel(channel)
    }

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            android.app.NotificationChannel(channel, "音乐下载", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "歌曲下载进度通知" })
    }

    private fun post(context: Context, snapshot: DownloadNotificationState.Snapshot) {
        try {
            createChannel(context)
            context.getSystemService(NotificationManager::class.java).notify(completionId, build(context, snapshot))
        } catch (_: SecurityException) { Timber.w("Download notification permission unavailable") }
    }

    private fun build(context: Context, snapshot: DownloadNotificationState.Snapshot): Notification {
        val intent = Intent(context, MainActivity::class.java)
            .apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, channel)
            .setContentTitle(snapshot.title).setContentText("Mei 音乐下载")
            .setSmallIcon(R.drawable.baseline_download_24).setOngoing(snapshot.active > 0)
            .setOnlyAlertOnce(true).setProgress(100, snapshot.progress, false)
            .setContentIntent(pendingIntent).setPriority(NotificationCompat.PRIORITY_LOW)
            .addExtras(Bundle().apply {
                putString(LAST_REQUEST, snapshot.lastRequestId)
                putInt(TOTAL, snapshot.total)
            }).build()
    }
}
