package com.ljyh.mei.parasite

import com.ljyh.mei.data.session.SessionChangedException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.playback.DownloadWorker
import com.ljyh.mei.playback.DownloadWorkerFixture
import com.ljyh.mei.playback.DownloadWorkerScenario
import com.ljyh.mei.playback.SongDownloadInfo
import com.ljyh.mei.utils.DownloadQueue
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room and WorkManager coordination; delayed work cannot reach the official graph. */
@RunWith(AndroidJUnit4::class)
class DownloadQueueDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val song = SongDownloadInfo("1", "Synthetic", listOf("Test"), "Test", "", 0, "standard")

    private fun test(block: suspend (DownloadWorkerFixture, DownloadQueue, WorkManager) -> Unit) = runBlocking(Dispatchers.IO) {
        val fixture = DownloadWorkerFixture(context, DownloadWorkerScenario.SUCCESS)
        val manager = try { WorkManager.getInstance(context) } catch (_: IllegalStateException) {
            WorkManager.initialize(context, Configuration.Builder().build())
            WorkManager.getInstance(context)
        }
        val tag = "download-queue-test-${UUID.randomUUID()}"
        val queue = DownloadQueue(context, fixture.database, fixture.sessions, manager, "$tag-") { task ->
            OneTimeWorkRequestBuilder<DownloadWorker>().setId(UUID.fromString(task.requestId))
                .setInitialDelay(1, TimeUnit.DAYS).addTag(tag).build()
        }
        try { block(fixture, queue, manager) }
        finally {
            manager.cancelAllWorkByTag(tag).result.await()
            val work = manager.getWorkInfosByTag(tag).await()
            val db = WorkManagerImpl.getInstance(context).workDatabase
            db.runInTransaction { work.forEach { db.workSpecDao().delete(it.id.toString()) } }
            fixture.close()
        }
    }

    @Test fun pauseThenResumeUsesANewRequestAndPreservesMetadataWithoutOldGrant() = test { fixture, queue, manager ->
        val dao = fixture.database.downloadDao()
        queue.enqueue(listOf(song, song), "Original playlist", fixture.sessions.snapshot(), downloadPath = "Music/Original")
        val first = checkNotNull(dao.getBySongId("1"))
        dao.updateFileInfo("1", "https://expired.example.test/old", "old.mp3", "mp3")
        queue.pauseSong("1", first.requestId)
        assertEquals(DownloadStatus.PAUSED, dao.getBySongId("1")?.status)
        assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(UUID.fromString(first.requestId)).await()?.state)
        queue.recover()
        assertEquals(DownloadStatus.PAUSED, dao.getBySongId("1")?.status)
        queue.resumeSong("1", "Fallback", first.requestId, "Music/Fallback")
        val resumed = checkNotNull(dao.getBySongId("1"))
        assertNotEquals(first.requestId, resumed.requestId)
        assertEquals(DownloadStatus.PENDING, resumed.status)
        assertEquals("", resumed.url)
        assertEquals("", resumed.fileType)
        assertEquals("Original playlist", resumed.playlistName)
        assertEquals("Music/Original", resumed.downloadPath)
        assertEquals(first.quality, resumed.quality)
        assertEquals(first.ownerId, resumed.ownerId)
        assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(UUID.fromString(resumed.requestId)).await()?.state)
        assertEquals(0, fixture.grants.get())
    }

    @Test fun stalePauseResumeAndDeleteCannotMutateTheReplacement() = test { fixture, queue, manager ->
        val dao = fixture.database.downloadDao()
        queue.enqueue(listOf(song), "Test", fixture.sessions.snapshot())
        val old = checkNotNull(dao.getBySongId("1"))
        queue.enqueue(listOf(song), "Replacement", fixture.sessions.snapshot())
        val replacement = checkNotNull(dao.getBySongId("1"))
        queue.pauseSong("1", old.requestId)
        queue.resumeSong("1", "Stale", old.requestId)
        queue.deleteTask("1", old.requestId)
        assertEquals(replacement, dao.getBySongId("1"))
        assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(UUID.fromString(replacement.requestId)).await()?.state)
        // WorkManager REPLACE cancels and deletes the old WorkSpec, unlike pause.
        assertNull(manager.getWorkInfoById(UUID.fromString(old.requestId)).await())
        queue.deleteTask("1", replacement.requestId)
        assertNull(dao.getBySongId("1"))
        assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(UUID.fromString(replacement.requestId)).await()?.state)
    }

    @Test fun cloudSourceSurvivesPauseResumeAndMissingWorkRecoveryWithoutRebindingItsOwner() = test { fixture, queue, manager ->
        val source = com.ljyh.mei.data.model.SongSourceIdentity(999, 88, 17, 1)
        val dao = fixture.database.downloadDao()
        queue.enqueue(listOf(song.copy(sourceKey = source.key)), "Cloud", fixture.sessions.snapshot())
        val first = checkNotNull(dao.getBySongId("1"))
        assertEquals(source.key, first.sourceKey)
        queue.pauseSong("1", first.requestId)
        queue.resumeSong("1", "Cloud", first.requestId)
        val resumed = checkNotNull(dao.getBySongId("1"))
        assertEquals(source.key, resumed.sourceKey)
        val workDb = WorkManagerImpl.getInstance(context).workDatabase
        workDb.workSpecDao().delete(resumed.requestId)
        queue.recover()
        assertNotNull(manager.getWorkInfoById(UUID.fromString(resumed.requestId)).await())
        assertEquals(source.key, dao.getBySongId("1")?.sourceKey)
        fixture.account = 18
        assertTrue(runCatching { queue.enqueue(listOf(song.copy(sourceKey = source.key)), "Cloud", fixture.sessions.snapshot()) }
            .exceptionOrNull() is SessionChangedException)
        assertEquals(resumed, dao.getBySongId("1"))
        assertEquals(0, fixture.grants.get())
    }

    @Test fun recoveryRepairsOnlyActiveOwnedMissingRequestsAndIsIdempotent() = test { fixture, queue, manager ->
        val dao = fixture.database.downloadDao()
        val active = fixture.task(UUID.randomUUID()).copy(status = DownloadStatus.DOWNLOADING)
        val paused = fixture.task(UUID.randomUUID()).copy(songId = "2", status = DownloadStatus.PAUSED)
        val other = fixture.task(UUID.randomUUID()).copy(songId = "3", ownerId = 18)
        dao.insertAll(listOf(active, paused, other))
        queue.recover()
        queue.recover()
        assertEquals(active.requestId, dao.getBySongId("1")?.requestId)
        assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(UUID.fromString(active.requestId)).await()?.state)
        assertNull(manager.getWorkInfoById(UUID.fromString(paused.requestId)).await())
        assertNull(manager.getWorkInfoById(UUID.fromString(other.requestId)).await())
        assertEquals(DownloadStatus.PAUSED, dao.getBySongId("2")?.status)
        assertEquals(DownloadStatus.FAILED, dao.getBySongId("3")?.status)
    }

    @Test fun terminalWorkDoesNotLeaveAnActiveRowAndCannotResumeForAnotherAccount() = test { fixture, queue, manager ->
        val dao = fixture.database.downloadDao()
        queue.enqueue(listOf(song), "Test", fixture.sessions.snapshot())
        val task = checkNotNull(dao.getBySongId("1"))
        manager.cancelWorkById(UUID.fromString(task.requestId)).result.await()
        queue.recover()
        assertEquals(DownloadStatus.FAILED, dao.getBySongId("1")?.status)
        fixture.account = 18
        try { queue.resumeSong("1", "Test", task.requestId); fail("Another account must not resume the task") }
        catch (_: SessionChangedException) { }
        assertEquals(task.requestId, dao.getBySongId("1")?.requestId)
        assertEquals(0, fixture.grants.get())
    }

    @Test fun resumeDoesNotReplacePendingOrCompletedWork() = test { fixture, queue, _ ->
        val dao = fixture.database.downloadDao()
        queue.enqueue(listOf(song), "Test", fixture.sessions.snapshot())
        val task = checkNotNull(dao.getBySongId("1"))
        queue.resumeSong("1", "Test", task.requestId)
        assertEquals(task, dao.getBySongId("1"))
        dao.updateStatus("1", DownloadStatus.COMPLETED)
        queue.resumeSong("1", "Test", task.requestId)
        assertEquals(task.requestId, dao.getBySongId("1")?.requestId)
        assertEquals(DownloadStatus.COMPLETED, dao.getBySongId("1")?.status)
    }

    @Test fun cancelAllPausesAndDeleteAllRemovesEveryOwnedRow() = test { fixture, queue, manager ->
        val dao = fixture.database.downloadDao()
        queue.enqueue(listOf(song, song.copy(songId = "2")), "Test", fixture.sessions.snapshot())
        queue.cancelAll()
        val first = checkNotNull(dao.getBySongId("1"))
        val second = checkNotNull(dao.getBySongId("2"))
        for (task in listOf(first, second)) {
            assertEquals(DownloadStatus.PAUSED, task.status)
            assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(UUID.fromString(task.requestId)).await()?.state)
        }
        queue.deleteAll()
        assertNull(dao.getBySongId("1"))
        assertNull(dao.getBySongId("2"))
    }

    @Test fun failedSchedulingLeavesAnExplicitRetryableFailureWithoutAGrant() = test { fixture, _, manager ->
        val queue = DownloadQueue(context, fixture.database, fixture.sessions, manager, "refusal-${UUID.randomUUID()}-") {
            throw java.io.IOException("Synthetic scheduling refusal")
        }
        try { queue.enqueue(listOf(song), "Test", fixture.sessions.snapshot()); fail("Scheduling must fail") }
        catch (_: java.io.IOException) { }
        val task = checkNotNull(fixture.database.downloadDao().getBySongId("1"))
        assertEquals(DownloadStatus.FAILED, task.status)
        assertNull(manager.getWorkInfoById(UUID.fromString(task.requestId)).await())
        assertEquals(0, fixture.grants.get())
    }

    @Test fun corruptedCloudAffinityCannotPoisonRecoveryOfOtherPendingTasks() = test { fixture, queue, manager ->
        val invalid = fixture.task(UUID.randomUUID()).copy(sourceKey = "meilox-cloud-v1:1:999:88:18")
        val valid = fixture.task(UUID.randomUUID()).copy(songId = "2")
        fixture.database.downloadDao().insertAll(listOf(invalid, valid))
        queue.recover()
        assertEquals(DownloadStatus.FAILED, fixture.database.downloadDao().getBySongId("1")?.status)
        assertNull(manager.getWorkInfoById(UUID.fromString(invalid.requestId)).await())
        assertNotNull(manager.getWorkInfoById(UUID.fromString(valid.requestId)).await())
        assertEquals(0, fixture.grants.get())
    }
}
