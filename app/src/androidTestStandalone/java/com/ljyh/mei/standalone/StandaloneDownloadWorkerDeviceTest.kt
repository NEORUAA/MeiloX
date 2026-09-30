package com.ljyh.mei.standalone

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import androidx.work.workDataOf
import com.google.common.util.concurrent.Futures
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.ljyh.mei.data.model.room.DownloadArtifact
import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.data.model.room.DownloadTask
import com.ljyh.mei.data.session.SessionCallFactory
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.di.NeteaseInterceptor
import com.ljyh.mei.di.RetrofitModule
import com.ljyh.mei.playback.AndroidDownloadMediaStore
import com.ljyh.mei.playback.DownloadPublication
import com.ljyh.mei.playback.DownloadWorker
import com.ljyh.mei.playback.DownloadWorkerEnvironment
import com.ljyh.mei.runtime.RuntimeBackendModule
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real worker/publication with private Room, fake credentials and closed media/network substitutes. */
@RunWith(AndroidJUnit4::class)
class StandaloneDownloadWorkerDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private enum class Scenario { SUCCESS, DENIED, TRIAL, CORRUPT, TRUNCATED, INVALIDATE }

    @Test fun completeWorkerUsesThePlayerBackendPublishesAndDoesNotResolveCompletedWorkAgain() =
        test(Scenario.SUCCESS) { fixture, id ->
            assertEquals(ListenableWorker.Result.success(), worker(fixture, id).doWork())
            assertEquals(DownloadStatus.COMPLETED, fixture.db.downloadDao().getBySongId("1")?.status)
            assertEquals("", fixture.db.downloadDao().getBySongId("1")?.url)
            assertEquals(100, fixture.db.downloadDao().getBySongId("1")?.progress)
            assertNotNull(fixture.db.songDao().getSong("1").first()?.path)
            assertEquals(DownloadArtifact.PUBLISHED, fixture.db.downloadArtifactDao().all().single().phase)
            assertFalse(fixture.temp(id).exists())
            assertEquals(ListenableWorker.Result.success(), worker(fixture, id).doWork())
            assertEquals(1, fixture.reads.get())
            assertEquals(1, fixture.transfers.get())
        }

    @Test fun cloudWorkerKeepsItsOwnerTupleUnderTheOriginalPlayerDownloadPolicy() = test(Scenario.SUCCESS) { fixture, id ->
        val source = com.ljyh.mei.data.model.SongSourceIdentity(999, 88, 17, 1)
        fixture.audioId = 999
        val dao = fixture.db.downloadDao()
        dao.insert(requireNotNull(dao.getBySongId("1")).copy(sourceKey = source.key))
        assertEquals(ListenableWorker.Result.success(), worker(fixture, id).doWork())
        assertEquals(listOf("[\"999_88\"]"), fixture.sourceIds)
        assertEquals(DownloadStatus.COMPLETED, dao.getBySongId("1")?.status)
        assertEquals(source.key, dao.getBySongId("1")?.sourceKey)
        assertNotNull(fixture.db.songDao().getSong("1").first()?.path)
        assertEquals(1, fixture.reads.get())
        assertEquals(1, fixture.transfers.get())
    }

    @Test fun denialAndTrialSourcesCannotTransferOrPublish() = runBlocking(Dispatchers.IO) {
        for (scenario in listOf(Scenario.DENIED, Scenario.TRIAL)) {
            withFixture(scenario) { fixture, id ->
                assertEquals(ListenableWorker.Result.failure(), worker(fixture, id).doWork())
                assertEquals(DownloadStatus.FAILED, fixture.db.downloadDao().getBySongId("1")?.status)
                assertEquals(1, fixture.reads.get())
                assertEquals(0, fixture.transfers.get())
                assertTrue(fixture.db.downloadArtifactDao().all().isEmpty())
                assertFalse(fixture.temp(id).exists())
            }
        }
    }

    @Test fun corruptionAndTruncationCannotBecomeCompletedDownloads() = runBlocking(Dispatchers.IO) {
        for (scenario in listOf(Scenario.CORRUPT, Scenario.TRUNCATED)) {
            withFixture(scenario) { fixture, id ->
                assertEquals(ListenableWorker.Result.failure(), worker(fixture, id).doWork())
                assertEquals(DownloadStatus.FAILED, fixture.db.downloadDao().getBySongId("1")?.status)
                assertNull(fixture.db.songDao().getSong("1").first())
                assertTrue(fixture.db.downloadArtifactDao().all().isEmpty())
                assertFalse(fixture.temp(id).exists())
            }
        }
    }

    @Test fun changedAccountCannotResolveOrTransferThePreviousAccountsTask() = test(Scenario.SUCCESS) { fixture, id ->
        fixture.sessions.commitLogin(fixture.sessions.beginLogin(), StoredAccount("other-fixture-cookie", 18))
        assertEquals(ListenableWorker.Result.failure(), worker(fixture, id).doWork())
        assertEquals(0, fixture.reads.get())
        assertEquals(0, fixture.transfers.get())
        assertTrue(fixture.db.downloadArtifactDao().all().isEmpty())
    }

    @Test fun sessionInvalidationCancelsTheTransferAndCannotPublish() = test(Scenario.INVALIDATE) { fixture, id ->
        assertTrue(runCatching { worker(fixture, id).doWork() }.exceptionOrNull() is CancellationException)
        assertTrue(fixture.canceled.get())
        assertEquals(DownloadStatus.FAILED, fixture.db.downloadDao().getBySongId("1")?.status)
        assertTrue(fixture.db.downloadArtifactDao().all().isEmpty())
        assertNull(fixture.db.songDao().getSong("1").first())
        assertFalse(fixture.temp(id).exists())
    }

    @Test fun startupPreparationAndCookieRecoveryCannotFailOrDispatchAPendingTask() = test(Scenario.SUCCESS) { fixture, id ->
        coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val environments = AtomicInteger()
            fixture.sessions.setRecoveryRequired(true)
            val guarded = StandaloneDownloadWorker(context, parameters(id), false,
                prepared = { entered.complete(Unit); gate.await() },
                environment = { environments.incrementAndGet(); fixture.environment })
            val job = async { guarded.doWork() }
            entered.await()
            assertEquals(0, environments.get())
            assertEquals(0, fixture.reads.get())
            gate.complete(Unit)
            assertEquals(ListenableWorker.Result.retry(), job.await())
            assertEquals(DownloadStatus.PENDING, fixture.db.downloadDao().getBySongId("1")?.status)
            assertEquals(0, fixture.transfers.get())
            fixture.sessions.setRecoveryRequired(false)
            val resumed = StandaloneDownloadWorker(context, parameters(id), false, prepared = {}, environment = { fixture.environment })
            assertEquals(ListenableWorker.Result.success(), resumed.doWork())
            assertEquals(1, fixture.reads.get())
            assertEquals(1, fixture.transfers.get())
        }
    }

    @Test fun cancelingBeforePreparationCannotConstructAnEnvironmentOrChangeTheTask() = test(Scenario.SUCCESS) { fixture, id ->
        coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val guarded = StandaloneDownloadWorker(context, parameters(id), false,
                prepared = { entered.complete(Unit); gate.await() }, environment = { error("Must not construct a graph before preparation") })
            val job = async { guarded.doWork() }
            entered.await()
            job.cancelAndJoin()
            assertEquals(DownloadStatus.PENDING, fixture.db.downloadDao().getBySongId("1")?.status)
            assertEquals(0, fixture.reads.get())
            assertEquals(0, fixture.transfers.get())
        }
    }

    private fun worker(fixture: Fixture, id: UUID) = DownloadWorker(context, parameters(id), fixture.environment)

    private fun parameters(id: UUID): WorkerParameters {
        val executor = Executor { it.run() }
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = null
        }
        return WorkerParameters(id,
            workDataOf(DownloadWorker.KEY_SONG_ID to "1", DownloadWorker.KEY_OWNER_ID to 17L), emptyList(),
            WorkerParameters.RuntimeExtras(), 0, 0, executor, Dispatchers.Default,
            WorkManagerTaskExecutor(executor), factory,
            { _, _, _ -> Futures.immediateFuture(null) }, { _, _, _ -> Futures.immediateFuture(null) },
        )
    }

    private fun test(scenario: Scenario, block: suspend (Fixture, UUID) -> Unit) = runBlocking(Dispatchers.IO) {
        withFixture(scenario, block)
    }

    private suspend fun withFixture(scenario: Scenario, block: suspend (Fixture, UUID) -> Unit) {
        assertEquals("com.neoruaa.meilox.standalone.debug", context.packageName)
        val fixture = Fixture(scenario)
        val id = UUID.randomUUID()
        try {
            fixture.sessions.initialize()
            fixture.sessions.commitLogin(fixture.sessions.beginLogin(), StoredAccount("fixture-cookie", 17))
            fixture.db.downloadDao().insert(DownloadTask(
                "1", requestId = id.toString(), ownerId = 17, quality = "standard",
                songTitle = "MeiloX standalone fixture", songArtist = "Test", songAlbum = "Test",
                playlistName = "MeiloX Qualification $id", downloadPath = "Music",
            ))
            withTimeout(15_000) { block(fixture, id) }
        } finally {
            try {
                fixture.db.songDao().updatePath("1", null)
                fixture.publication.recover()
                assertTrue("Synthetic publication was not cleaned", fixture.db.downloadArtifactDao().all().isEmpty())
                assertFalse("Synthetic temporary media was not cleaned", fixture.temp(id).exists())
            } finally {
                fixture.db.close()
            }
        }
    }

    private inner class Fixture(private val scenario: Scenario) {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val sessions = StandaloneSessionStore(object : StandaloneAccountPersistence {
            override suspend fun read() = StoredAccount("", 0)
            override suspend fun write(account: StoredAccount) = Unit
        })
        val reads = AtomicInteger()
        var audioId = 1L
        val sourceIds = mutableListOf<String>()
        val transfers = AtomicInteger()
        val canceled = AtomicBoolean()
        val publication = DownloadPublication(db, AndroidDownloadMediaStore(context), context.packageName)
        private val original = ByteBuffer.allocate(44 + 32000).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(32036).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(32000).array()
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            check(chain.request().url.encodedPath == "/api/song/enhance/player/url/v1")
            val buffer = okio.Buffer().also { chain.request().body!!.writeTo(it) }
            sourceIds += com.google.gson.JsonParser.parseString(buffer.readUtf8()).asJsonObject.get("ids").asString
            reads.incrementAndGet()
            chain.proceed(chain.request())
        }.addInterceptor(NeteaseInterceptor { "fixture-device" }).addInterceptor { chain ->
            check(chain.request().header("Cookie").orEmpty().contains("MUSIC_U=fixture-cookie"))
            val row = JsonObject().apply {
                addProperty("id", audioId)
                addProperty("code", if (scenario == Scenario.DENIED) -105 else 200)
                addProperty("url", ADDRESS)
                addProperty("type", "wav")
                addProperty("level", "standard")
                addProperty("size", original.size)
                addProperty("md5", MessageDigest.getInstance("MD5").digest(original).joinToString("") { "%02x".format(it) })
                addProperty("expi", 600)
                if (scenario == Scenario.TRIAL) add("freeTrialInfo", JsonObject())
            }
            val body = JsonObject().apply { addProperty("code", 200); add("data", JsonArray().apply { add(row) }) }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("Synthetic").body(body.toString().toResponseBody()).build()
        }.build()
        private val calls = SessionCallFactory(sessions, client) { request, stamp ->
            request.newBuilder().tag(StandaloneCredentials::class.java, sessions.credentials(stamp)).build()
        }
        val environment = DownloadWorkerEnvironment(db, sessions,
            RuntimeBackendModule.downloadSources(StandaloneDownloadSourceBackend(RetrofitModule.provideRetrofit(calls), sessions)),
            client = { request -> mediaCall(request) }, lyric = { _, _ -> null }, cover = { null },
            publication = publication, notification = { _, _, _ -> },
        )
        fun temp(id: UUID) = File(context.cacheDir, "download/$id.wav")

        private fun mediaCall(request: Request): Call {
            check(request.url.toString() == ADDRESS)
            transfers.incrementAndGet()
            val bytes = when (scenario) {
                Scenario.CORRUPT -> original.copyOf().apply { this[lastIndex] = 9 }
                Scenario.TRUNCATED -> original.copyOf(original.size - 1)
                else -> original
            }
            return object : Call by OkHttpClient().newCall(request) {
                override fun execute(): Response {
                    if (scenario == Scenario.INVALIDATE) sessions.invalidate()
                    return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                        .message("Synthetic").body(bytes.toResponseBody()).build()
                }
                override fun cancel() { canceled.set(true) }
            }
        }
    }

    companion object {
        private const val ADDRESS = "https://media.example.test/standalone-qualification.wav"
    }
}
