package com.ljyh.mei.parasite

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import androidx.work.workDataOf
import com.google.common.util.concurrent.Futures
import com.ljyh.mei.data.model.room.DownloadArtifact
import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.playback.DownloadWorker
import com.ljyh.mei.playback.DownloadWorkerFixture
import com.ljyh.mei.playback.DownloadWorkerScenario
import com.ljyh.mei.playback.DownloadNotifications
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadWorkerDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val factory = object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = null
    }
    private fun worker(fixture: DownloadWorkerFixture, id: UUID, foreground: (ForegroundInfo) -> Unit = {}): DownloadWorker {
        val executor = Executor { it.run() }
        val parameters = WorkerParameters(id,
            workDataOf(DownloadWorker.KEY_SONG_ID to "1", DownloadWorker.KEY_OWNER_ID to 17L), emptyList(),
            WorkerParameters.RuntimeExtras(), 0, 0, executor, Dispatchers.Default,
            WorkManagerTaskExecutor(executor), factory,
            { _, _, _ -> Futures.immediateFuture(null) }, { _, _, info ->
                try { foreground(info); Futures.immediateFuture(null) }
                catch (error: Exception) { Futures.immediateFailedFuture<Void>(error) }
            },
        )
        return DownloadWorker(context, parameters, fixture.environment)
    }
    private fun test(scenario: DownloadWorkerScenario, captureNotifications: Boolean = true,
        block: suspend (DownloadWorkerFixture, UUID) -> Unit) = runBlocking(Dispatchers.IO) {
        val fixture = DownloadWorkerFixture(context, scenario, captureNotifications = captureNotifications)
        val id = UUID.randomUUID()
        try {
            fixture.database.downloadDao().insert(fixture.task(id))
            withTimeout(15_000) { block(fixture, id) }
        } finally {
            try {
                fixture.cleanMedia()
                if (!captureNotifications) DownloadNotifications.qualification.dismissProbeCompletion(context)
            } finally { fixture.close() }
        }
    }

    @Test fun foregroundRegistrationPrecedesTheGrantAndProgressUsesOneReservedId() =
        test(DownloadWorkerScenario.SUCCESS, captureNotifications = false) { fixture, id ->
            val grants = mutableListOf<Int>()
            val notifications = mutableListOf<ForegroundInfo>()
            val result = worker(fixture, id) { info -> grants += fixture.grants.get(); notifications += info }.doWork()
            assertEquals(ListenableWorker.Result.success(), result)
            assertTrue(notifications.size > 1)
            assertEquals(0, grants.first())
            assertEquals(setOf(DownloadNotifications.qualification.progressId), notifications.map { it.notificationId }.toSet())
            assertTrue(notifications.all { it.foregroundServiceType == 0 })
            assertFalse(DownloadNotifications.qualification.active())
        }

    @Test fun refusedForegroundRegistrationCannotConsumeADownloadGrant() =
        test(DownloadWorkerScenario.SUCCESS, captureNotifications = false) { fixture, id ->
            val result = worker(fixture, id) { throw java.io.IOException("Synthetic foreground refusal") }.doWork()
            assertEquals(ListenableWorker.Result.failure(), result)
            assertEquals(0, fixture.grants.get())
            assertEquals(0, fixture.transfers.get())
            assertEquals(DownloadStatus.FAILED, fixture.database.downloadDao().getBySongId("1")?.status)
            assertFalse(DownloadNotifications.qualification.active())
        }

    @Test fun completeWorkerDownloadsPublishesAndDoesNotAcquireAnotherGrantForCompletedWork() = test(DownloadWorkerScenario.SUCCESS) { fixture, id ->
        assertEquals(ListenableWorker.Result.success(), worker(fixture, id).doWork())
        val task = fixture.database.downloadDao().getBySongId("1")!!
        assertEquals(DownloadStatus.COMPLETED, task.status)
        assertEquals(100, task.progress)
        assertEquals("", task.url)
        assertNotNull(fixture.database.songDao().getSong("1").first()?.path)
        assertEquals(DownloadArtifact.PUBLISHED, fixture.database.downloadArtifactDao().all().single().phase)
        assertFalse(fixture.hasTemporaryFile(id))
        assertTrue(fixture.notifications.last().first.contains("完成"))
        assertEquals(ListenableWorker.Result.success(), worker(fixture, id).doWork())
        assertEquals(1, fixture.grants.get())
        assertEquals(1, fixture.transfers.get())
    }

    @Test fun officialDenialNeverOpensMediaOrPublishesAFile() = test(DownloadWorkerScenario.DENIED) { fixture, id ->
        assertEquals(ListenableWorker.Result.failure(), worker(fixture, id).doWork())
        assertEquals(DownloadStatus.FAILED, fixture.database.downloadDao().getBySongId("1")?.status)
        assertEquals(1, fixture.grants.get())
        assertEquals(0, fixture.transfers.get())
        assertTrue(fixture.database.downloadArtifactDao().all().isEmpty())
        assertFalse(fixture.hasTemporaryFile(id))
        assertTrue(fixture.notifications.last().first.contains("授权"))
    }

    @Test fun corruptionCannotBecomeACompletedDownload() = test(DownloadWorkerScenario.CORRUPT) { fixture, id ->
        assertEquals(ListenableWorker.Result.failure(), worker(fixture, id).doWork())
        assertEquals(DownloadStatus.FAILED, fixture.database.downloadDao().getBySongId("1")?.status)
        assertTrue(fixture.database.downloadArtifactDao().all().isEmpty())
        assertFalse(fixture.hasTemporaryFile(id))
    }

    @Test fun truncationCannotBecomeACompletedDownload() = test(DownloadWorkerScenario.TRUNCATED) { fixture, id ->
        assertEquals(ListenableWorker.Result.failure(), worker(fixture, id).doWork())
        assertEquals(DownloadStatus.FAILED, fixture.database.downloadDao().getBySongId("1")?.status)
        assertNull(fixture.database.songDao().getSong("1").first())
        assertFalse(fixture.hasTemporaryFile(id))
    }

    @Test fun anotherAccountCannotRequestAGrant() = test(DownloadWorkerScenario.SUCCESS) { fixture, id ->
        fixture.account = 18
        assertEquals(ListenableWorker.Result.failure(), worker(fixture, id).doWork())
        assertEquals(0, fixture.grants.get())
        assertEquals(0, fixture.transfers.get())
        assertEquals(DownloadStatus.FAILED, fixture.database.downloadDao().getBySongId("1")?.status)
    }

    @Test fun cancellationReleasesABlockedReadAndPreservesPausedState() = test(DownloadWorkerScenario.HOLD) { fixture, id ->
        kotlinx.coroutines.coroutineScope {
            val job = async(Dispatchers.Default) { worker(fixture, id).doWork() }
            assertTrue(fixture.readBlocked.await(3, TimeUnit.SECONDS))
            assertEquals(1, fixture.database.downloadDao().updateOwnedProgress("1", id.toString(), 17, DownloadStatus.PAUSED, 12, 1))
            withTimeout(3_000) { job.cancelAndJoin() }
            assertTrue(fixture.canceled.get())
            assertTrue(fixture.closed.get())
            assertEquals(DownloadStatus.PAUSED, fixture.database.downloadDao().getBySongId("1")?.status)
            assertFalse(fixture.hasTemporaryFile(id))
        }
    }

    @Test fun sessionInvalidationCancelsTheWholeWorker() = test(DownloadWorkerScenario.INVALIDATE) { fixture, id ->
        val result = runCatching { worker(fixture, id).doWork() }
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertTrue(fixture.canceled.get())
        assertTrue(fixture.closed.get())
        assertEquals(DownloadStatus.FAILED, fixture.database.downloadDao().getBySongId("1")?.status)
        assertFalse(fixture.hasTemporaryFile(id))
        assertNull(fixture.database.songDao().getSong("1").first())
    }

    @Test fun replacedRequestCannotPublishIntoItsReplacement() = test(DownloadWorkerScenario.HOLD) { fixture, id ->
        kotlinx.coroutines.coroutineScope {
            val job = async(Dispatchers.Default) { worker(fixture, id).doWork() }
            assertTrue(fixture.readBlocked.await(3, TimeUnit.SECONDS))
            val replacement = fixture.task(UUID.randomUUID())
            fixture.database.downloadDao().insert(replacement)
            withTimeout(3_000) { job.cancelAndJoin() }
            assertEquals(replacement, fixture.database.downloadDao().getBySongId("1"))
            assertTrue(fixture.database.downloadArtifactDao().all().isEmpty())
            assertFalse(fixture.hasTemporaryFile(id))
        }
    }
}
