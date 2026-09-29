package com.ljyh.mei.parasite

import android.content.Context
import android.os.Process
import androidx.room.Room
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.workDataOf
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.playback.DownloadWorker
import com.ljyh.mei.playback.DownloadWorkerFixture
import com.ljyh.mei.playback.DownloadWorkerScenario
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Explicit debug qualification; the official account, queue and transport are never rebound. */
internal object HostDownloadProbe {
    const val TAG = "meilox-download-qualification"
    private const val DATABASE = "download_worker_qualification"
    private const val PREFERENCES = "download_worker_qualification"

    fun fixture(context: Context, scenario: DownloadWorkerScenario): DownloadWorkerFixture {
        check(BuildConfig.PARASITE_WORK_PROBE)
        return DownloadWorkerFixture(context, scenario, Room.databaseBuilder(context, AppDatabase::class.java, DATABASE).build())
    }

    fun command(context: Context, operation: String, scenario: String?) {
        check(BuildConfig.PARASITE_WORK_PROBE)
        val manager = WorkManager.getInstance(context)
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        when (operation) {
            "download_enqueue" -> {
                val mode = DownloadWorkerScenario.valueOf(scenario ?: "SUCCESS")
                check(manager.getWorkInfosByTag(TAG).get(10, TimeUnit.SECONDS).isEmpty()) { "Clean the previous qualification first" }
                fixture(context, mode).use { fixture ->
                    check(runBlocking { fixture.database.downloadDao().getAll().first().isEmpty() })
                    val request = OneTimeWorkRequestBuilder<HostDownloadProbeWorker>()
                        .setInitialDelay(20, TimeUnit.SECONDS).addTag(TAG)
                        .setInputData(workDataOf(DownloadWorker.KEY_SONG_ID to "1", DownloadWorker.KEY_OWNER_ID to 17L, "scenario" to mode.name))
                        .build()
                    runBlocking { fixture.database.downloadDao().insert(fixture.task(request.id)) }
                    check(preferences.edit().clear().commit())
                    manager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request).result.get(10, TimeUnit.SECONDS)
                    HostRuntimeProbe.report("download_probe_enqueued scenario=${mode.name} initial_delay_seconds=20")
                }
            }
            "download_status" -> {
                val work = manager.getWorkInfosByTag(TAG).get(10, TimeUnit.SECONDS)
                    .groupingBy { it.state.name }.eachCount()
                fixture(context, DownloadWorkerScenario.SUCCESS).use { fixture ->
                    val tasks = runBlocking { fixture.database.downloadDao().getAll().first() }
                        .groupingBy { it.status.name }.eachCount()
                    val artifacts = fixture.database.downloadArtifactDao().all().groupingBy { it.phase }.eachCount()
                    HostRuntimeProbe.report("download_probe_status work=$work tasks=$tasks artifacts=$artifacts " +
                        "grants=${preferences.getInt("grants", 0)} transfers=${preferences.getInt("transfers", 0)} " +
                        "running=${preferences.getBoolean("running", false)} pid=${preferences.getInt("pid", 0)}")
                }
            }
            "download_cancel" -> {
                manager.cancelUniqueWork(TAG).result.get(10, TimeUnit.SECONDS)
                HostRuntimeProbe.report("download_probe_cancel_requested")
            }
            "download_cleanup" -> {
                val work = manager.getWorkInfosByTag(TAG).get(10, TimeUnit.SECONDS)
                check(work.all { it.state.isFinished })
                check(!preferences.getBoolean("running", false) || preferences.getInt("pid", 0) != Process.myPid())
                fixture(context, DownloadWorkerScenario.SUCCESS).use { fixture ->
                    runBlocking {
                        fixture.cleanMedia()
                        fixture.database.downloadDao().getAll().first().forEach { task ->
                            val id = java.util.UUID.fromString(task.requestId)
                            java.io.File(context.cacheDir, "download/$id.wav").delete()
                        }
                    }
                }
                check(context.deleteDatabase(DATABASE))
                val db = WorkManagerImpl.getInstance(context).workDatabase
                db.runInTransaction { work.forEach { db.workSpecDao().delete(it.id.toString()) } }
                check(preferences.edit().clear().commit())
                HostRuntimeProbe.report("download_probe_cleaned synthetic_media_and_records=true")
            }
            else -> error("Unknown download qualification command")
        }
    }

    fun started(context: Context) {
        check(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean("running", true).putInt("pid", Process.myPid()).commit())
        HostRuntimeProbe.report("download_probe_started host_process=${android.app.Application.getProcessName() == HostIdentity.PACKAGE}")
    }

    fun finished(context: Context, fixture: DownloadWorkerFixture) {
        check(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean("running", false).putInt("grants", fixture.grants.get()).putInt("transfers", fixture.transfers.get()).commit())
        HostRuntimeProbe.report("download_probe_finished scenario=${fixture.scenario} call_cancel_invoked=${fixture.canceled.get()}")
    }
}

class HostDownloadProbeWorker private constructor(
    context: Context, params: WorkerParameters, private val fixture: DownloadWorkerFixture,
) : DownloadWorker(context, params, fixture.environment) {
    constructor(context: Context, params: WorkerParameters) : this(context, params,
        HostDownloadProbe.fixture(context, DownloadWorkerScenario.valueOf(checkNotNull(params.inputData.getString("scenario")))))

    override suspend fun doWork(): Result {
        HostDownloadProbe.started(applicationContext)
        try { return super.doWork() }
        finally {
            HostDownloadProbe.finished(applicationContext, fixture)
            fixture.close()
        }
    }
}
