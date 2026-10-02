package com.ljyh.mei.parasite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.workDataOf
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.di.AppGraph
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

/** Explicit debug commands: closed work substitutes or read-only cloud checks. */
internal class HostWorkProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.PARASITE_WORK_PROBE || intent.action != ACTION) return
        val pending = goAsync()
        val command = intent.getStringExtra("operation")
        val hold = intent.getBooleanExtra("hold", false)
        Thread({
            val report = HostRuntimeProbe.report
            var broadcastFinished = false
            try {
                val owner = AppGraph.component.context()
                val manager = WorkManager.getInstance(owner)
                val preferences = owner.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                when (command) {
                    "log_share_closed" -> verifyLogSharing(owner)
                    "log_share_grant_closed" -> {
                        // Release the ordered trigger before waiting for another broadcast to this UID.
                        pending.finish()
                        broadcastFinished = true
                        verifyLogSharing(owner, checkNotNull(intent.getStringExtra("nonce")))
                    }
                    "cloud_upload_sdk_closed" -> HostCloudUploadProbe.run(owner)
                    "cloud_history_closed" -> HostCloudHistoryProbe.closed(owner)
                    "cloud_history_read" -> HostCloudHistoryProbe.read(owner)
                    "cloud_playback_closed" -> HostCloudPlaybackProbe.run()
                    "cloud_playlist_closed" -> HostCloudPlaylistProbe.run()
                    "cloud_favorite_read" -> kotlinx.coroutines.runBlocking {
                        val snapshot = com.ljyh.mei.playback.PlaybackPersistence(owner).load()
                        val key = checkNotNull(snapshot?.items?.singleOrNull()?.sourceKey)
                        val source = com.ljyh.mei.data.model.SongSourceIdentity.fromKey(key)
                        check(source.isCloud)
                        val session = AppGraph.component.sessions().snapshot()
                        source.requireAccount(session.identity)
                        AppGraph.component.songFavorites().isLiked(source, session)
                        AppGraph.component.sessions().requireCurrent(session)
                        report("cloud_favorite_read_passed source_owned=true session_unchanged=true no_mutation=true")
                    }
                    "cloud_lyric_read" -> kotlinx.coroutines.runBlocking {
                        val snapshot = com.ljyh.mei.playback.PlaybackPersistence(owner).load()
                        val key = checkNotNull(snapshot?.items?.singleOrNull()?.sourceKey)
                        check(com.ljyh.mei.data.model.SongSourceIdentity.fromKey(key).isCloud)
                        val session = AppGraph.component.sessions().snapshot()
                        val result = AppGraph.component.songLyrics().lyrics(key, session)
                        report("cloud_lyric_read_passed code=${result.code} lrc_present=${!result.lrc?.lyric.isNullOrBlank()} " +
                            "karaoke_present=${!result.klyric?.lyric.isNullOrBlank()} no_download_grant=true")
                    }
                    "foreground_enqueue", "foreground_bind", "foreground_status", "foreground_cancel_first",
                    "foreground_cancel", "foreground_cleanup" -> HostForegroundProbe.command(owner, command)
                    "download_enqueue", "download_enqueue_pair", "download_status", "download_cancel", "download_cleanup",
                    "download_queue_enqueue", "download_queue_pause", "download_queue_resume", "download_queue_recover", "download_queue_delete",
                    "download_release_first", "download_release_second", "download_cancel_first" -> {
                        HostDownloadProbe.command(owner, command, intent.getStringExtra("scenario"))
                    }
                    "publication" -> {
                        check(com.ljyh.mei.playback.DownloadPublicationProbe.run(owner))
                        report("work_probe_publication_passed synthetic_only=true media_cleaned=true")
                    }
                    "enqueue" -> {
                        val request = OneTimeWorkRequestBuilder<HostWorkProbeWorker>()
                            .setInitialDelay(30, TimeUnit.SECONDS)
                            .setInputData(workDataOf("nonce" to UUID.randomUUID().toString(), "hold" to hold))
                            .addTag(TAG).build()
                        manager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request).result.get(10, TimeUnit.SECONDS)
                        report("work_probe_enqueued hold=$hold initial_delay_seconds=30")
                    }
                    "cancel" -> {
                        manager.cancelUniqueWork(TAG).result.get(10, TimeUnit.SECONDS)
                        report("work_probe_cancel_requested")
                    }
                    "status" -> {
                        val states = manager.getWorkInfosByTag(TAG).get(10, TimeUnit.SECONDS)
                            .groupingBy { it.state.name }.eachCount()
                        report("work_probe_status states=$states started_pid=${preferences.getInt("started_pid", 0)} " +
                            "completed_pid=${preferences.getInt("completed_pid", 0)} canceled=${preferences.getBoolean("canceled", false)}")
                    }
                    "cleanup" -> {
                        manager.cancelUniqueWork(TAG).result.get(10, TimeUnit.SECONDS)
                        val work = manager.getWorkInfosByTag(TAG).get(10, TimeUnit.SECONDS)
                        check(work.all { it.state.isFinished })
                        val database = WorkManagerImpl.getInstance(owner).workDatabase
                        database.runInTransaction { work.forEach { database.workSpecDao().delete(it.id.toString()) } }
                        check(preferences.edit().clear().commit())
                        report("work_probe_cleaned records=${work.size}")
                    }
                    else -> report("work_probe_unknown_command")
                }
            } catch (error: Exception) {
                report("work_probe_failed type=${error.javaClass.simpleName}")
                if (command == "cloud_favorite_read") error.stackTrace.take(8).forEach { frame ->
                    report("cloud_favorite_probe_frame=${frame.className}.${frame.methodName}:${frame.lineNumber}")
                }
            } finally { if (!broadcastFinished) pending.finish() }
        }, "MeiloX-work-probe").start()
    }

    private fun verifyLogSharing(owner: Context, fixtureNonce: String? = null) {
        val module = owner as ModuleContext
        val host = module.baseContext
        if (fixtureNonce != null) check(UUID.fromString(fixtureNonce).toString() == fixtureNonce)
        val provider = host.classLoader.loadClass("androidx.core.content.FileProvider")
            .getMethod("getUriForFile", Context::class.java, String::class.java, java.io.File::class.java)
        val originals = mutableListOf<java.io.File>()
        val copies = mutableListOf<java.io.File>()
        val uris = arrayListOf<android.net.Uri>()
        var acknowledgment: BroadcastReceiver? = null
        var verified = false
        try {
            for (folder in listOf("app_logs", "crash_logs")) {
                val source = java.io.File(owner.filesDir, "$folder/log-share-fixture-${UUID.randomUUID()}.txt")
                source.parentFile!!.mkdirs()
                originals += source
                val text = "Synthetic host log"
                source.writeText(text)
                val legacyFailure = runCatching { provider.invoke(null, host, "${host.packageName}.fileprovider", source) }.exceptionOrNull()
                check((legacyFailure as? java.lang.reflect.InvocationTargetException)?.cause is IllegalArgumentException)
                val uri = AppGraph.component.runtime().logShareUri(owner, source)
                uris += uri
                check(uri.authority == "${HostIdentity.PACKAGE}.fileprovider")
                check(uri.path.orEmpty().startsWith("/cache_apk/${ModuleStorage.NAMESPACE}_log_share/"))
                val path = uri.pathSegments.drop(1).joinToString(java.io.File.separator)
                val staged = java.io.File(host.cacheDir, "apk/$path")
                copies += staged
                check(staged.isFile && staged.name == source.name)
                check(owner.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } == text)
                check(source.readText() == text)
                owner.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
                    check(it.moveToFirst() && it.getString(0) == source.name)
                }
            }
            if (fixtureNonce != null) {
                val replied = java.util.concurrent.CountDownLatch(1)
                val passed = java.util.concurrent.atomic.AtomicBoolean()
                acknowledgment = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (intent.getStringExtra("nonce") == fixtureNonce) {
                            passed.set(intent.getBooleanExtra("passed", false))
                            replied.countDown()
                        }
                    }
                }.also { host.registerReceiver(it, android.content.IntentFilter(LOG_SHARE_ACK), Context.RECEIVER_EXPORTED) }
                uris.forEach { host.grantUriPermission(BuildConfig.APPLICATION_ID, it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                host.sendBroadcast(Intent(LOG_SHARE_FIXTURE).setPackage(BuildConfig.APPLICATION_ID)
                    .putExtra("nonce", fixtureNonce).putExtra("hostUid", Process.myUid()).putParcelableArrayListExtra("uris", uris))
                check(replied.await(10, TimeUnit.SECONDS) && passed.get())
            }
            verified = true
            HostRuntimeProbe.report("log_share_probe_passed originals=2 legacy_private_path_rejected=true host_provider_readback=true cross_uid_readback=${fixtureNonce != null} synthetic_only=true")
        } finally {
            uris.forEach { host.revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            acknowledgment?.let { host.unregisterReceiver(it) }
            originals.forEach { it.delete() }
            copies.forEach { file -> file.delete(); file.parentFile?.delete() }
            check(originals.none { it.exists() } && copies.none { it.exists() })
            HostRuntimeProbe.report("log_share_probe_cleaned fixtures_only=true")
            if (fixtureNonce != null) host.sendBroadcast(Intent(LOG_SHARE_CLEANED).setPackage(BuildConfig.APPLICATION_ID)
                .putExtra("nonce", fixtureNonce).putExtra("passed", verified))
        }
    }

    companion object {
        const val CARRIER = "com.netease.cloudmusic.receiver.AlarmAlertBroadcastReceiver"
        const val ACTION = "com.neoruaa.meilox.parasite.WORK_PROBE"
        const val TAG = "meilox-work-qualification"
        const val PREFERENCES = "work_qualification"
        const val LOG_SHARE_FIXTURE = "com.neoruaa.meilox.parasite.LOG_SHARE_FIXTURE"
        const val LOG_SHARE_ACK = "com.neoruaa.meilox.parasite.LOG_SHARE_ACK"
        const val LOG_SHARE_CLEANED = "com.neoruaa.meilox.parasite.LOG_SHARE_CLEANED"
    }
}

class HostWorkProbeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!BuildConfig.PARASITE_WORK_PROBE) return Result.failure()
        val nonce = inputData.getString("nonce") ?: return Result.failure()
        if (runCatching { UUID.fromString(nonce).toString() != nonce }.getOrDefault(true)) return Result.failure()
        val preferences = applicationContext.getSharedPreferences(HostWorkProbeReceiver.PREFERENCES, Context.MODE_PRIVATE)
        check(preferences.edit().clear().putInt("started_pid", Process.myPid()).commit())
        HostRuntimeProbe.report("work_probe_started host_process=${android.app.Application.getProcessName() == HostIdentity.PACKAGE}")
        var completed = false
        try {
            delay(if (inputData.getBoolean("hold", false)) 120_000 else 1_000)
            check(preferences.edit().putInt("completed_pid", Process.myPid()).commit())
            completed = true
            HostRuntimeProbe.report("work_probe_completed")
            return Result.success()
        } finally {
            if (!completed) {
                preferences.edit().putBoolean("canceled", true).commit()
                HostRuntimeProbe.report("work_probe_canceled")
            }
        }
    }
}
