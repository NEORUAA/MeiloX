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
import com.ljyh.mei.playback.DownloadNotifications
import com.ljyh.mei.playback.SongDownloadInfo
import com.ljyh.mei.utils.DownloadQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Explicit debug qualification; the official account, queue and transport are never rebound. */
internal object HostDownloadProbe {
    const val TAG = "meilox-download-qualification"
    private const val DATABASE = "download_worker_qualification"
    private const val PREFERENCES = "download_worker_qualification"
    private val running = java.util.concurrent.ConcurrentHashMap<String, DownloadWorkerFixture>()

    fun fixture(context: Context, scenario: DownloadWorkerScenario, songId: String = "1", captureNotifications: Boolean = true): DownloadWorkerFixture {
        check(BuildConfig.PARASITE_WORK_PROBE)
        return DownloadWorkerFixture(context, scenario, Room.databaseBuilder(context, AppDatabase::class.java, DATABASE).build(),
            songId, captureNotifications)
    }

    fun command(context: Context, operation: String, scenario: String?) {
        check(BuildConfig.PARASITE_WORK_PROBE)
        val manager = WorkManager.getInstance(context)
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        when (operation) {
            "download_queue_enqueue", "download_queue_pause", "download_queue_resume", "download_queue_recover", "download_queue_delete" -> {
                fixture(context, DownloadWorkerScenario.HOLD).use { fixture ->
                    val queue = DownloadQueue(context, fixture.database, fixture.sessions, manager, "$TAG-queue-") { task ->
                        OneTimeWorkRequestBuilder<HostDownloadProbeWorker>().setId(java.util.UUID.fromString(task.requestId))
                            .setInitialDelay(20, TimeUnit.SECONDS).addTag(TAG).addTag("$TAG-${task.songId}")
                            .setInputData(workDataOf(DownloadWorker.KEY_SONG_ID to task.songId,
                                DownloadWorker.KEY_OWNER_ID to task.ownerId, "scenario" to "HOLD")).build()
                    }
                    runBlocking {
                        val before = fixture.database.downloadDao().getBySongId("1")
                        when (operation) {
                            "download_queue_enqueue" -> {
                                check(manager.getWorkInfosByTag(TAG).get(10, TimeUnit.SECONDS).isEmpty() && before == null)
                                check(preferences.edit().clear().commit())
                                queue.enqueue(listOf(SongDownloadInfo("1", "MeiloX synthetic qualification", listOf("Test"), "Test", "", 0, "standard")),
                                    "MeiloX Test/${java.util.UUID.randomUUID()}", fixture.sessions.snapshot())
                            }
                            "download_queue_pause" -> queue.pauseSong("1", checkNotNull(before).requestId)
                            "download_queue_resume" -> queue.resumeSong("1", "MeiloX Test", checkNotNull(before).requestId)
                            "download_queue_recover" -> queue.recover()
                            "download_queue_delete" -> queue.deleteTask("1", checkNotNull(before).requestId)
                        }
                        val after = fixture.database.downloadDao().getBySongId("1")
                        HostRuntimeProbe.report("download_queue_result operation=$operation status=${after?.status} request_changed=" +
                            "${before != null && after != null && before.requestId != after.requestId} grants=${fixture.grants.get()}")
                    }
                }
            }
            "download_enqueue", "download_enqueue_pair" -> {
                val mode = DownloadWorkerScenario.valueOf(scenario ?: if (operation == "download_enqueue_pair") "HOLD" else "SUCCESS")
                check(manager.getWorkInfosByTag(TAG).get(10, TimeUnit.SECONDS).isEmpty()) { "Clean the previous qualification first" }
                fixture(context, mode).use { fixture ->
                    check(runBlocking { fixture.database.downloadDao().getAll().first().isEmpty() })
                    val requests = (1..if (operation == "download_enqueue_pair") 2 else 1).map { index ->
                        val songId = index.toString()
                        OneTimeWorkRequestBuilder<HostDownloadProbeWorker>()
                            .setInitialDelay(20, TimeUnit.SECONDS).addTag(TAG).addTag("$TAG-$songId")
                            .setInputData(workDataOf(DownloadWorker.KEY_SONG_ID to songId, DownloadWorker.KEY_OWNER_ID to 17L, "scenario" to mode.name))
                            .build().also { request -> runBlocking {
                                fixture.database.downloadDao().insert(fixture.task(request.id).copy(songId = songId))
                            } }
                    }
                    check(preferences.edit().clear().commit())
                    manager.beginUniqueWork(TAG, ExistingWorkPolicy.KEEP, requests).enqueue().result.get(10, TimeUnit.SECONDS)
                    HostRuntimeProbe.report("download_probe_enqueued scenario=${mode.name} count=${requests.size} initial_delay_seconds=20")
                }
            }
            "download_status" -> {
                val work = manager.getWorkInfosByTag(TAG).get(10, TimeUnit.SECONDS)
                    .groupingBy { it.state.name }.eachCount()
                fixture(context, DownloadWorkerScenario.SUCCESS).use { fixture ->
                    val rows = runBlocking { fixture.database.downloadDao().getAll().first() }
                    val tasks = rows.groupingBy { it.status.name }.eachCount()
                    val artifacts = fixture.database.downloadArtifactDao().all().groupingBy { it.phase }.eachCount()
                    val grants = rows.sumOf { running[it.songId]?.grants?.get() ?: preferences.getInt("${it.songId}.grants", 0) }
                    val transfers = rows.sumOf { running[it.songId]?.transfers?.get() ?: preferences.getInt("${it.songId}.transfers", 0) }
                    HostRuntimeProbe.report("download_probe_status work=$work tasks=$tasks artifacts=$artifacts " +
                        "grants=$grants transfers=$transfers running=${running.size}")
                }
            }
            "download_release_first", "download_release_second" -> {
                val song = if (operation == "download_release_first") "1" else "2"
                checkNotNull(running[song]).completeTransfer()
                HostRuntimeProbe.report("download_probe_release_requested song=$song")
            }
            "download_cancel_first" -> {
                manager.cancelAllWorkByTag("$TAG-1").result.get(10, TimeUnit.SECONDS)
                HostRuntimeProbe.report("download_probe_first_cancel_requested")
            }
            "download_cancel" -> {
                manager.cancelAllWorkByTag(TAG).result.get(10, TimeUnit.SECONDS)
                HostRuntimeProbe.report("download_probe_cancel_requested")
            }
            "download_cleanup" -> {
                val work = manager.getWorkInfosByTag(TAG).get(10, TimeUnit.SECONDS)
                check(work.all { it.state.isFinished })
                check(running.isEmpty())
                fixture(context, DownloadWorkerScenario.SUCCESS).use { fixture ->
                    runBlocking {
                        fixture.database.downloadDao().getAll().first().forEach { task ->
                            fixture.database.songDao().updatePath(task.songId, null)
                            val id = java.util.UUID.fromString(task.requestId)
                            java.io.File(context.cacheDir, "download/$id.wav").delete()
                        }
                        fixture.publication.recover()
                        check(fixture.database.downloadArtifactDao().all().isEmpty())
                    }
                }
                DownloadNotifications.qualification.dismissProbeCompletion(context)
                check(context.deleteDatabase(DATABASE))
                val db = WorkManagerImpl.getInstance(context).workDatabase
                db.runInTransaction { work.forEach { db.workSpecDao().delete(it.id.toString()) } }
                check(preferences.edit().clear().commit())
                HostRuntimeProbe.report("download_probe_cleaned synthetic_media_and_records=true")
            }
            else -> error("Unknown download qualification command")
        }
    }

    fun started(context: Context, fixture: DownloadWorkerFixture) {
        check(running.putIfAbsent(fixture.songId, fixture) == null)
        check(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putInt("${fixture.songId}.pid", Process.myPid()).commit())
        HostRuntimeProbe.report("download_probe_started song=${fixture.songId} host_process=${android.app.Application.getProcessName() == HostIdentity.PACKAGE}")
    }

    fun finished(context: Context, fixture: DownloadWorkerFixture) {
        check(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putInt("${fixture.songId}.grants", fixture.grants.get()).putInt("${fixture.songId}.transfers", fixture.transfers.get()).commit())
        running.remove(fixture.songId, fixture)
        HostRuntimeProbe.report("download_probe_finished song=${fixture.songId} scenario=${fixture.scenario} call_cancel_invoked=${fixture.canceled.get()}")
    }
}

class HostDownloadProbeWorker private constructor(
    context: Context, params: WorkerParameters, private val fixture: DownloadWorkerFixture,
) : DownloadWorker(context, params, fixture.environment) {
    constructor(context: Context, params: WorkerParameters) : this(context, params,
        HostDownloadProbe.fixture(context, DownloadWorkerScenario.valueOf(checkNotNull(params.inputData.getString("scenario"))),
            checkNotNull(params.inputData.getString(KEY_SONG_ID)), captureNotifications = false))

    override suspend fun doWork(): Result {
        HostDownloadProbe.started(applicationContext, fixture)
        try { return super.doWork() }
        finally {
            try { HostDownloadProbe.finished(applicationContext, fixture) }
            finally { fixture.close() }
        }
    }
}
