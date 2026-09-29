package com.ljyh.mei.parasite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.workDataOf
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.R
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

/** Isolated lifecycle qualification, with no account access, network or user media. */
internal object HostForegroundProbe {
    const val TAG = "meilox-foreground-qualification"
    const val CHANNEL = "meilox_foreground_qualification"
    private const val DESCRIPTOR = "org.chromium.wow.extension.usage.WowIPCServer"
    @Volatile private var binder: IBinder? = null
    private var connection: ServiceConnection? = null

    fun command(context: Context, operation: String) {
        check(BuildConfig.PARASITE_WORK_PROBE)
        val report = HostRuntimeProbe.report
        val manager = WorkManager.getInstance(context)
        when (operation) {
            "foreground_enqueue" -> {
                check(manager.getWorkInfosByTag(TAG).get(10, TimeUnit.SECONDS).isEmpty())
                val work = (0..1).map { slot -> OneTimeWorkRequestBuilder<HostForegroundProbeWorker>()
                    .setInputData(workDataOf("slot" to slot)).addTag(TAG).addTag("$TAG-$slot")
                    .setInitialDelay(20, TimeUnit.SECONDS).build() }
                manager.enqueue(work).result.get(10, TimeUnit.SECONDS)
                report("foreground_probe_enqueued count=2 initial_delay_seconds=20")
            }
            "foreground_bind" -> {
                check(connection == null)
                val client = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        check(service.interfaceDescriptor == DESCRIPTOR)
                        binder = service
                        report("foreground_probe_bound official_binder=true alive=${service.pingBinder()}")
                    }
                    override fun onServiceDisconnected(name: ComponentName) { binder = null }
                }
                check(context.bindService(Intent().setClassName(HostIdentity.PACKAGE, HostWorkForegroundPolicy.CARRIER), client, Context.BIND_AUTO_CREATE))
                connection = client
            }
            "foreground_status" -> {
                val work = manager.getWorkInfosByTag(TAG).get(10, TimeUnit.SECONDS).groupingBy { it.state.name }.eachCount()
                val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications
                val owned = notifications.filter { it.id in HostWorkForegroundPolicy.MIN_NOTIFICATION_ID..HostWorkForegroundPolicy.MAX_NOTIFICATION_ID }
                report("foreground_probe_status work=$work notification_count=${owned.size} foreground_count=" +
                    "${owned.count { it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 }} " +
                    "music_notification=${notifications.any { it.id == 888 }} official_binder_alive=${binder?.pingBinder() == true}")
            }
            "foreground_cancel_first" -> {
                manager.cancelAllWorkByTag("$TAG-0").result.get(10, TimeUnit.SECONDS)
                report("foreground_probe_first_cancel_requested")
            }
            "foreground_cancel" -> {
                manager.cancelAllWorkByTag(TAG).result.get(10, TimeUnit.SECONDS)
                report("foreground_probe_cancel_requested")
            }
            "foreground_cleanup" -> {
                val work = manager.getWorkInfosByTag(TAG).get(10, TimeUnit.SECONDS)
                check(work.all { it.state.isFinished })
                check(context.getSystemService(NotificationManager::class.java).activeNotifications.none {
                    it.id in HostWorkForegroundPolicy.MIN_NOTIFICATION_ID..HostWorkForegroundPolicy.MIN_NOTIFICATION_ID + 1 })
                connection?.let { context.unbindService(it) }
                connection = null
                binder = null
                val database = WorkManagerImpl.getInstance(context).workDatabase
                database.runInTransaction { work.forEach { database.workSpecDao().delete(it.id.toString()) } }
                context.getSystemService(NotificationManager::class.java).deleteNotificationChannel(CHANNEL)
                report("foreground_probe_cleaned records=${work.size} original_binding_released=true")
            }
            else -> error("Unknown foreground qualification command")
        }
    }
}

class HostForegroundProbeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        check(BuildConfig.PARASITE_WORK_PROBE)
        val slot = inputData.getInt("slot", -1)
        check(slot in 0..1)
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(HostForegroundProbe.CHANNEL, "MeiloX qualification", NotificationManager.IMPORTANCE_LOW))
        fun info(progress: Int) = ForegroundInfo(HostWorkForegroundPolicy.MIN_NOTIFICATION_ID + slot,
            NotificationCompat.Builder(applicationContext, HostForegroundProbe.CHANNEL)
                .setSmallIcon(R.drawable.baseline_download_24).setContentTitle("MeiloX synthetic worker ${slot + 1}")
                .setContentText("No account, network or media access").setOngoing(true)
                .setOnlyAlertOnce(true).setProgress(12, progress, false).build())
        setForeground(info(0))
        HostRuntimeProbe.report("foreground_probe_started slot=$slot")
        try {
            repeat(12) { minute ->
                delay(60_000)
                setForeground(info(minute + 1))
            }
            HostRuntimeProbe.report("foreground_probe_completed slot=$slot duration_minutes=12")
            return Result.success()
        } finally { HostRuntimeProbe.report("foreground_probe_exited slot=$slot") }
    }
}
