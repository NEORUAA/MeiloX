package com.ljyh.mei.standalone

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.StartStopToken
import androidx.work.impl.WorkDatabase
import androidx.work.impl.WorkLauncherImpl
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.close
import androidx.work.impl.model.WorkGenerationalId
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import androidx.work.workDataOf
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.data.model.room.DownloadTask
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.playback.DownloadWorker
import com.ljyh.mei.utils.DownloadQueue
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Private Room/v17 files and an unscheduled in-memory WorkDatabase; never transfers real media. */
@Suppress("RestrictedApi")
@RunWith(AndroidJUnit4::class)
class StandaloneDownloadRecoveryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun v17TasksRecoverTheirWorkInputsAndCompletedFilesWithoutFabricatingPublicationReceipts() = fixture(disk = true) { f ->
        val before = f.db.downloadDao().getAll().first().associateBy { it.songId }
        for (song in listOf("1", "2", "3", "4")) f.legacy(song)
        val completed = before.getValue(LegacyStandaloneDatabaseFixture.completedId)
        val oldSong = f.db.songDao().getSong(completed.songId).first()
        val bytes = f.media.readBytes()
        f.convert()
        f.queue.recover()
        for (song in listOf("1", "2")) {
            val task = f.db.downloadDao().getBySongId(song)!!
            assertEquals(17, task.ownerId)
            assertEquals("Playlist $song", task.playlistName)
            assertEquals("Music/Original/$song", task.downloadPath)
            assertEquals("", task.url)
            assertEquals(0, task.progress)
            assertEquals(before.getValue(song).createdAt, task.createdAt)
            assertEquals(WorkInfo.State.ENQUEUED, f.manager.getWorkInfoById(UUID.fromString(task.requestId)).await()?.state)
        }
        assertEquals(DownloadStatus.PAUSED, f.db.downloadDao().getBySongId("3")?.status)
        assertEquals(DownloadStatus.FAILED, f.db.downloadDao().getBySongId("4")?.status)
        assertEquals(completed, f.db.downloadDao().getBySongId(completed.songId))
        assertEquals(oldSong, f.db.songDao().getSong(completed.songId).first())
        assertArrayEquals(bytes, f.media.readBytes())
        assertTrue(f.db.downloadArtifactDao().all().isEmpty())
        val after = f.db.downloadDao().getAll().first()
        f.db.close()
        f.db = f.openDisk()
        f.convert()
        f.queue.recover()
        assertEquals(after, f.db.downloadDao().getAll().first())
        assertArrayEquals(bytes, f.media.readBytes())
    }

    @Test fun conversionWaitsForVerifiedSessionAndCannotAssignWorkToAnotherAccount() = fixture { f ->
        f.db.downloadDao().insert(f.task())
        f.legacy("1")
        f.sessions.setRecoveryRequired(true)
        f.convert()
        f.queue.recover()
        val converted = f.db.downloadDao().getBySongId("1")!!
        assertNull(f.manager.getWorkInfoById(UUID.fromString(converted.requestId)).await())
        assertEquals(DownloadStatus.PENDING, converted.status)
        f.sessions.setRecoveryRequired(false)
        f.account = 88
        f.queue.recover()
        assertEquals(DownloadStatus.FAILED, f.db.downloadDao().getBySongId("1")?.status)
        assertEquals(17L, f.db.downloadDao().getBySongId("1")?.ownerId)
        assertNull(f.manager.getWorkInfoById(UUID.fromString(converted.requestId)).await())
    }

    @Test fun conversionAndOrphanRepairAreIdempotentAfterVerifiedSameAccountRecovery() = fixture { f ->
        f.db.downloadDao().insert(f.task(DownloadStatus.DOWNLOADING))
        val old = f.legacy("1")
        f.convert()
        val converted = f.db.downloadDao().getBySongId("1")!!
        assertEquals(WorkInfo.State.CANCELLED, f.manager.getWorkInfoById(old).await()?.state)
        repeat(2) { f.convert(); f.queue.recover() }
        assertEquals(converted, f.db.downloadDao().getBySongId("1"))
        val work = f.manager.getWorkInfosForUniqueWork(f.prefix + "1").await().single()
        assertEquals(converted.requestId, work.id.toString())
        val input = f.manager.workDatabase.workSpecDao().getWorkSpec(work.id.toString())!!.input
        assertEquals("1", input.getString(DownloadWorker.KEY_SONG_ID))
        assertEquals(17, input.getLong(DownloadWorker.KEY_OWNER_ID, 0))
        assertFalse(LEGACY_SONG_IDS in input.keyValueMap)
    }

    @Test fun crashAfterRoomCommitCannotTurnOurCancellationIntoAUserPauseOrChangeAffinity() = fixture { f ->
        f.db.downloadDao().insert(f.task())
        val old = f.legacy("1")
        val failure = runCatching { f.convert(afterCommit = { throw IOException("Synthetic crash") }) }.exceptionOrNull()
        assertTrue(failure is IOException)
        val converted = f.db.downloadDao().getBySongId("1")!!
        assertEquals(DownloadStatus.PENDING, converted.status)
        assertEquals(WorkInfo.State.ENQUEUED, f.manager.getWorkInfoById(old).await()?.state)
        f.convert(affinity = 88)
        f.queue.recover()
        assertEquals(converted, f.db.downloadDao().getBySongId("1"))
        assertEquals(converted.requestId, f.manager.getWorkInfosForUniqueWork(f.prefix + "1").await().single().id.toString())
    }

    @Test fun canceledLegacyWorkRemainsPausedAndAnExplicitResumeKeepsTheOriginalDestination() = fixture { f ->
        f.db.downloadDao().insert(f.task())
        val old = f.legacy("1")
        f.manager.cancelWorkById(old).result.await()
        f.convert()
        f.queue.recover()
        val paused = f.db.downloadDao().getBySongId("1")!!
        assertEquals(DownloadStatus.PAUSED, paused.status)
        assertNull(f.manager.getWorkInfoById(UUID.fromString(paused.requestId)).await())
        f.queue.resumeSong("1", "Fallback", paused.requestId, "Music/Fallback")
        val resumed = f.db.downloadDao().getBySongId("1")!!
        assertNotEquals(paused.requestId, resumed.requestId)
        assertEquals("Playlist 1", resumed.playlistName)
        assertEquals("Music/Original/1", resumed.downloadPath)
        assertEquals("lossless", resumed.quality)
        assertEquals(17, resumed.ownerId)
    }

    @Test fun missingMalformedAndUnknownAffinityCannotDispatchWorkOrInventOwnership() = fixture { f ->
        f.db.downloadDao().insertAll(listOf(f.task(), f.task().copy(songId = "2"), f.task().copy(songId = "3")))
        f.legacy("2", songs = "[\"unexpected\"]")
        f.legacy("3")
        f.convert(affinity = 0)
        f.queue.recover()
        for (song in listOf("1", "2", "3")) {
            val task = f.db.downloadDao().getBySongId(song)!!
            assertEquals(0, task.ownerId)
            assertEquals(DownloadStatus.FAILED, task.status)
            assertEquals("", task.url)
            assertTrue(f.manager.getWorkInfosForUniqueWork(f.prefix + song).await().all { it.state.isFinished })
        }
        assertEquals("Music/Original/3", f.db.downloadDao().getBySongId("3")?.downloadPath)
    }

    @Test fun fixtureProcessorRetiresAnUnresolvableOldWorkerNameWithoutConstructingTheOldImplementation() = fixture { f ->
        val request = OneTimeWorkRequestBuilder<DownloadWorker>().addTag(f.tag).addTag("download")
            .setInputData(workDataOf(LEGACY_SONG_IDS to "[\"1\"]")).build()
        request.workSpec.workerClassName = "old.minified.DownloadWorkerAlias"
        f.manager.enqueueUniqueWork(f.prefix + "1", ExistingWorkPolicy.REPLACE, request).result.await()
        WorkLauncherImpl(f.manager.processor, f.manager.workTaskExecutor)
            .startWork(StartStopToken(WorkGenerationalId(request.id.toString(), request.workSpec.generation)))
        val result = withTimeout(15_000) {
            while (true) {
                val work = f.manager.getWorkInfoById(request.id).await()!!
                if (work.state.isFinished) return@withTimeout work
                delay(50)
            }
            error("Unreachable")
        }
        assertEquals(WorkInfo.State.FAILED, result.state)
        assertTrue(result.outputData.getBoolean("standalone_legacy_retired", false))
        assertTrue(f.db.downloadDao().getAll().first().isEmpty())
        assertTrue(f.db.downloadArtifactDao().all().isEmpty())
    }

    @Test fun legacyAffinitySurvivesAccountPreferenceChangesAndDataStoreReopen() = runBlocking(Dispatchers.IO) {
        val file = File(context.cacheDir, "legacy-affinity-${UUID.randomUUID()}.preferences_pb")
        suspend fun useStore(block: suspend (androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> Unit) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try { block(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })) }
            finally { scope.coroutineContext.job.cancelAndJoin() }
        }
        try {
            useStore { store ->
                store.edit { it[UserIdKey] = "17" }
                assertEquals(17, freezeLegacyDownloadAffinity(store))
                store.edit { it[UserIdKey] = "88" }
                assertEquals(17, freezeLegacyDownloadAffinity(store))
                store.edit { it.remove(UserIdKey) }
            }
            useStore { store -> assertEquals(17, freezeLegacyDownloadAffinity(store)) }
        } finally { assertTrue(!file.exists() || file.delete()) }
    }

    private fun fixture(disk: Boolean = false, block: suspend (Fixture) -> Unit) = runBlocking(Dispatchers.IO) {
        assertEquals("com.neoruaa.meilox.standalone.debug", context.packageName)
        val f = Fixture(disk)
        try { withTimeout(30_000) { block(f) } }
        finally {
            try {
                f.manager.cancelAllWorkByTag(f.tag).result.await()
                val works = f.manager.getWorkInfosByTag(f.tag).await()
                val workDb = f.manager.workDatabase
                workDb.runInTransaction { works.forEach { workDb.workSpecDao().delete(it.id.toString()) } }
                assertTrue(f.manager.getWorkInfosByTag(f.tag).await().isEmpty())
            } finally {
                try {
                    f.db.close()
                    if (disk) context.deleteDatabase(f.name)
                    assertTrue(!f.media.exists() || f.media.delete())
                } finally {
                    try { f.manager.close() } finally { f.executor.shutdown() }
                }
            }
        }
    }

    private inner class Fixture(disk: Boolean) {
        val tag = "legacy-download-${UUID.randomUUID()}"
        val prefix = "$tag-song-"
        val name = "$tag.db"
        val media = File(context.cacheDir, "$tag-media.fixture")
        val executor = Executors.newSingleThreadExecutor()
        private val configuration = Configuration.Builder()
            .setExecutor(executor)
            .setTaskExecutor(executor)
            // Skip ForceStopRunnable's persisted-database and system-job recovery.
            .setDefaultProcessName("${context.packageName}.offline.fixture")
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
                    val worker = StandaloneDownloadWorkerFactory().createWorker(appContext, workerClassName, workerParameters) ?: return null
                    val legacy = "download" in workerParameters.tags && LEGACY_SONG_IDS in workerParameters.inputData.keyValueMap
                    return if (legacy) StandaloneDownloadWorker(appContext, workerParameters, true, prepared = {}) else worker
                }
            }).build()
        private val taskExecutor = WorkManagerTaskExecutor(executor)
        val manager = WorkManagerImpl(
            context, configuration, taskExecutor,
            WorkDatabase.create(context, taskExecutor.serialTaskExecutor, configuration.clock, true),
            schedulersCreator = { _, _, _, _, _, _ -> emptyList() },
        )
        var account = 17L
        val sessions = SessionStore().apply { bind { SessionIdentity(account, true, false) } }
        var db: AppDatabase
        init {
            if (disk) {
                media.writeBytes(byteArrayOf(1, 2, 3, 4))
                SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { old ->
                    LegacyStandaloneDatabaseFixture.create(old)
                    LegacyStandaloneDatabaseFixture.populate(old, media)
                    for ((state, id) in listOf("PENDING" to "1", "DOWNLOADING" to "2", "PAUSED" to "3", "FAILED" to "4")) {
                        old.execSQL("UPDATE download_task SET songId=? WHERE status=?", arrayOf(id, state))
                    }
                }
                db = openDisk()
            } else db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        }
        fun openDisk() = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_17_18, AppDatabase.MIGRATION_18_19, AppDatabase.MIGRATION_19_20, AppDatabase.MIGRATION_20_21).build()
        val queue get() = DownloadQueue(context, db, sessions, manager, prefix) { task ->
            OneTimeWorkRequestBuilder<DownloadWorker>().setId(UUID.fromString(task.requestId))
                .setInitialDelay(1, TimeUnit.DAYS).addTag(tag).addTag("download")
                .setInputData(workDataOf(DownloadWorker.KEY_SONG_ID to task.songId, DownloadWorker.KEY_OWNER_ID to task.ownerId)).build()
        }
        fun task(state: DownloadStatus = DownloadStatus.PENDING) = DownloadTask("1",
            url = "https://expired.example.test/old", fileName = "old.flac", fileType = "flac",
            status = state, progress = 37, songTitle = "Fixture", songArtist = "Test", songAlbum = "Test", quality = "lossless",
            createdAt = 123, updatedAt = 456)
        suspend fun legacy(song: String, songs: String = "[\"$song\"]"): UUID {
            val work = OneTimeWorkRequestBuilder<DownloadWorker>().setInitialDelay(1, TimeUnit.DAYS)
                .addTag(tag).addTag("download").addTag(prefix + song)
                .setInputData(workDataOf(LEGACY_SONG_IDS to songs, LEGACY_PLAYLIST_NAME to "Playlist $song",
                    LEGACY_DOWNLOAD_PATH to "Music/Original/$song")).build()
            manager.enqueueUniqueWork(prefix + song, ExistingWorkPolicy.REPLACE, work).result.await()
            return work.id
        }
        suspend fun convert(affinity: Long = 17, afterCommit: suspend () -> Unit = {}) =
            StandaloneDownloadRecovery(db, manager, affinity, prefix, tag, afterCommit).convert()
    }
}
