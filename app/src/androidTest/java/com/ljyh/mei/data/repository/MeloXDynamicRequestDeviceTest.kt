package com.ljyh.mei.data.repository

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.data.network.api.MeloXDirectService
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

/** Real Repository with substitute services; no real server, contact or upload operation. */
class MeloXDynamicRequestDeviceTest {
    private val standalone = BuildConfig.FLAVOR == "standalone"

    @Test fun successfulGenericReadPreservesBodyAndOwnerWithoutRetry() = runBlocking {
        val f = Fixture()
        assertTrue(f.repository.podcasts(f.owner, 5, 2, 20).isEmpty())
        assertEquals(Request("/api/djradio/hot", mapOf<String, Any>("cateId" to 5L, "offset" to 2, "limit" to 20), f.owner),
            f.weapi.requests.single())
        assertTrue(f.eapi.requests.isEmpty())
    }

    @Test fun genericBusinessFailureUsesOnlyTheStandaloneAlternativeAndSameOwner() = runBlocking {
        val f = Fixture()
        f.weapi.reply = { json("""{"code":403}""") }
        val result = runCatching { f.read() }
        if (standalone) assertTrue(result.getOrThrow().isEmpty()) else assertTrue(result.isFailure)
        assertEquals(if (standalone) f.weapi.requests else emptyList<Request>(), f.eapi.requests)
    }

    @Test fun genericTransportAndAlternativeFailuresDoNotLoop() = runBlocking {
        val f = Fixture()
        val primary = IOException("Synthetic primary failure")
        val alternative = IOException("Synthetic alternative failure")
        f.weapi.reply = { throw primary }
        f.eapi.reply = { throw alternative }
        assertSame(if (standalone) alternative else primary, runCatching { f.read() }.exceptionOrNull())
        assertEquals(1, f.weapi.requests.size)
        assertEquals(if (standalone) 1 else 0, f.eapi.requests.size)
        f.weapi.reply = { f.success() }
        assertTrue(f.read().isEmpty())
        assertEquals(2, f.weapi.requests.size)
    }

    @Test fun accountReplacementAndReauthorizationRejectLateFailureWithoutRetry() = runBlocking {
        for (replacement in listOf(false, true)) {
            val f = Fixture()
            f.weapi.reply = {
                if (replacement) f.identity = SessionIdentity(18, true, false)
                f.sessions.invalidate()
                json("""{"code":403}""")
            }
            assertTrue(runCatching { f.read() }.exceptionOrNull() is SessionChangedException)
            assertEquals(listOf(f.owner), f.weapi.requests.map { it.owner })
            assertTrue(f.eapi.requests.isEmpty())
        }
    }

    @Test fun recoveryDuringResponseRejectsItAndDoesNotReachTheAlternative() = runBlocking {
        val f = Fixture()
        f.weapi.reply = { f.sessions.setRecoveryRequired(true); json("""{"code":403}""") }
        assertTrue(runCatching { f.read() }.isFailure)
        assertTrue(f.eapi.requests.isEmpty())
        f.sessions.setRecoveryRequired(false)
        f.weapi.reply = { f.success() }
        assertTrue(f.read().isEmpty())
    }

    @Test fun staleAndRecoveringOwnersNeverStartEitherAttempt() = runBlocking {
        val f = Fixture()
        f.sessions.invalidate()
        assertTrue(runCatching { f.read() }.exceptionOrNull() is SessionChangedException)
        f.sessions.setRecoveryRequired(true)
        assertTrue(runCatching { f.repository.podcasts(f.sessions.snapshot(), 5, 2, 20) }.isFailure)
        assertTrue(f.weapi.requests.isEmpty())
        assertTrue(f.eapi.requests.isEmpty())
    }

    @Test fun directCancellationDoesNotRetry() = runBlocking {
        val f = Fixture()
        val canceled = CancellationException("Synthetic cancellation")
        f.weapi.reply = { throw canceled }
        assertSame(canceled, runCatching { f.read() }.exceptionOrNull())
        assertTrue(f.eapi.requests.isEmpty())
    }

    @Test fun canceledNonCooperatingResponseDoesNotRetryOrSucceed() = runBlocking {
        val f = Fixture()
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        f.weapi.reply = {
            started.complete(Unit)
            withContext(NonCancellable) { response.await() }
            json("""{"code":403}""")
        }
        val pending = async { f.read() }
        started.await()
        pending.cancel()
        response.complete(Unit)
        assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
        pending.join()
        assertTrue(f.eapi.requests.isEmpty())
    }

    @Test fun explicitWeapiAndEapiCallsDoNotAcquireGenericRetrySemantics() = runBlocking {
        val f = Fixture()
        f.weapi.reply = { json("""{"code":403}""") }
        assertTrue(runCatching { f.repository.listenTogetherStatus(f.owner) }.isFailure)
        assertTrue(f.eapi.requests.isEmpty())
        f.eapi.reply = { json("""{"code":403}""") }
        assertTrue(runCatching { f.repository.songWiki(11, f.owner) }.isFailure)
        assertEquals(1, f.weapi.requests.size)
        assertEquals(1, f.eapi.requests.size)
    }

    private data class Request(val path: String, val body: Map<String, Any>, val owner: SessionStamp?)

    private class Transport : MeloXDirectService {
        val requests = mutableListOf<Request>()
        var reply: suspend () -> JsonObject = { json("""{"code":200,"djRadios":[]}""") }
        override suspend fun post(path: String, body: Map<String, Any>, headers: Map<String, String>,
            expectedSession: SessionStamp?): JsonObject {
            assertTrue(headers.isEmpty())
            requests += Request(path, body, expectedSession)
            return reply()
        }
        override suspend fun postPlaybackRaw(path: String, body: Map<String, Any>, headers: Map<String, String>):
            Response<ResponseBody> = error("Unexpected playback report")
    }

    private class Fixture {
        var identity = SessionIdentity(17, true, false)
        val sessions = SessionStore().apply { bind { identity } }
        val owner = sessions.snapshot()
        val weapi = Transport()
        val eapi = Transport()
        val repository = MeloXRepository(eapi, weapi, InstrumentationRegistry.getInstrumentation().targetContext, sessions,
            CloudUploadCoordinator(eapi, weapi, sessions, object : CloudBinaryUploader {
                override suspend fun upload(file: CloudUploadFile, authorization: CloudUploadAuthorization,
                    owner: SessionStamp, onProgress: (Long, Long) -> Unit) = error("Unexpected upload")
            }), CloudLibraryBackend(weapi, sessions))
        suspend fun read() = repository.podcasts(owner, 5, 2, 20)
        fun success() = json("""{"code":200,"djRadios":[]}""")
    }

    companion object {
        private fun json(value: String): JsonObject = JsonParser.parseString(value).asJsonObject
    }
}
