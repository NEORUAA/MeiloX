package com.ljyh.mei.data.repository

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.data.model.melox.ListenTogetherStatus
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

/** Real account reads with substitute transports; no real messages, contacts or rooms. */
class AccountRequestFallbackDeviceTest {
    private val standalone = BuildConfig.FLAVOR == "standalone"

    @Test fun successfulAccountReadsKeepTheirBodiesAndPrimaryTransport() = runBlocking {
        val f = Fixture()
        val reads = f.reads()
        reads.forEach { read ->
            when (val result = read.execute()) {
                is List<*> -> assertTrue(result.isEmpty())
                is ListenTogetherStatus -> {
                    assertTrue(result.isInRoom)
                    assertEquals("READY", result.status)
                }
                else -> fail("Unexpected account read result")
            }
        }
        assertEquals(reads.map { Request(it.path, it.body, f.owner) }, f.weapi.requests)
        assertTrue(f.eapi.requests.isEmpty())
    }

    @Test fun accountTransportFailuresFallBackOnlyInStandaloneWithTheSameOwnerAndBody() = runBlocking {
        val f = Fixture()
        val failure = IOException("Synthetic account transport failure")
        f.weapi.reply = { throw failure }
        val reads = f.reads()
        reads.forEach { read ->
            val result = runCatching { read.execute() }
            if (standalone) assertTrue(result.isSuccess) else assertSame(failure, result.exceptionOrNull())
        }
        val expected = reads.map { Request(it.path, it.body, f.owner) }
        assertEquals(expected, f.weapi.requests)
        assertEquals(if (standalone) expected else emptyList<Request>(), f.eapi.requests)
    }

    @Test fun accountBusinessFailuresFallBackOnlyInStandaloneWithTheSameOwnerAndBody() = runBlocking {
        val f = Fixture()
        f.weapi.reply = { json("""{"code":403,"message":"Account fixture denied"}""") }
        val reads = f.reads()
        reads.forEach { read ->
            val result = runCatching { read.execute() }
            if (standalone) assertTrue(result.isSuccess)
            else assertEquals("Account fixture denied", result.exceptionOrNull()?.message)
        }
        val expected = reads.map { Request(it.path, it.body, f.owner) }
        assertEquals(expected, f.weapi.requests)
        assertEquals(if (standalone) expected else emptyList<Request>(), f.eapi.requests)
    }

    @Test fun fallbackFailureIsTerminalWithoutAnotherPrimaryAttempt() = runBlocking {
        val f = Fixture()
        val primary = IOException("Synthetic primary failure")
        val fallback = IOException("Synthetic fallback failure")
        f.weapi.reply = { throw primary }
        f.eapi.reply = { throw fallback }
        assertSame(if (standalone) fallback else primary,
            runCatching { f.repository.privateConversations(f.owner) }.exceptionOrNull())
        assertEquals(1, f.weapi.requests.size)
        assertEquals(if (standalone) 1 else 0, f.eapi.requests.size)
    }

    @Test fun staleAccountReadsNeverStartEitherAttempt() = runBlocking {
        val f = Fixture()
        f.sessions.invalidate()
        f.reads().forEach { read ->
            assertTrue(runCatching { read.execute() }.exceptionOrNull() is SessionChangedException)
        }
        assertTrue(f.weapi.requests.isEmpty())
        assertTrue(f.eapi.requests.isEmpty())
    }

    @Test fun accountReplacementAndReauthorizationRejectLateFailuresWithoutFallback() = runBlocking {
        for (replacement in listOf(false, true)) {
            for (transportFailure in listOf(false, true)) {
                val f = Fixture()
                f.weapi.reply = {
                    if (replacement) f.identity = SessionIdentity(18, true, false)
                    f.sessions.invalidate()
                    if (transportFailure) throw IOException("Synthetic late failure")
                    json("""{"code":403,"message":"Synthetic late denial"}""")
                }
                val result = runCatching { f.repository.privateConversations(f.owner) }
                if (standalone || !transportFailure) {
                    assertTrue(result.exceptionOrNull() is SessionChangedException)
                } else {
                    assertEquals("Synthetic late failure", result.exceptionOrNull()?.message)
                }
                assertEquals(listOf(f.owner), f.weapi.requests.map { it.owner })
                assertTrue(f.eapi.requests.isEmpty())
            }
        }
    }

    @Test fun recoveryRejectsAccountReadsBeforeEitherAttempt() = runBlocking {
        val f = Fixture()
        f.sessions.setRecoveryRequired(true)
        f.reads().forEach { read -> assertTrue(runCatching { read.execute() }.isFailure) }
        assertTrue(f.weapi.requests.isEmpty())
        assertTrue(f.eapi.requests.isEmpty())
    }

    @Test fun directCancellationNeverFallsBack() = runBlocking {
        val f = Fixture()
        val failure = CancellationException("Synthetic account cancellation")
        f.weapi.reply = { throw failure }
        f.reads().forEach { read ->
            assertSame(failure, runCatching { read.execute() }.exceptionOrNull())
        }
        assertEquals(4, f.weapi.requests.size)
        assertTrue(f.eapi.requests.isEmpty())
    }

    @Test fun canceledNonCooperatingPrimaryResponseNeverFallsBack() = runBlocking {
        val f = Fixture()
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        f.weapi.reply = {
            started.complete(Unit)
            withContext(NonCancellable) { response.await() }
            json("""{"code":403}""")
        }
        val pending = async { f.repository.privateConversations(f.owner) }
        started.await()
        pending.cancel()
        response.complete(Unit)
        assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
        pending.join()
        assertEquals(1, f.weapi.requests.size)
        assertTrue(f.eapi.requests.isEmpty())
    }

    @Test fun explicitEapiAccountWritesRemainSingleAttempt() = runBlocking {
        val f = Fixture()
        val failure = IOException("Synthetic EAPI failure")
        f.eapi.reply = { throw failure }
        assertSame(failure,
            runCatching { f.repository.sendPrivateText(f.owner, "Fixture text", listOf(9)) }.exceptionOrNull())
        assertEquals(Request("/api/msg/private/send",
            mapOf("type" to "text", "msg" to "Fixture text", "userIds" to "[9]"), f.owner), f.eapi.requests.single())
        assertTrue(f.weapi.requests.isEmpty())
    }

    private data class Read(val path: String, val body: Map<String, Any>, val execute: suspend () -> Any)
    private data class Request(val path: String, val body: Map<String, Any>, val owner: SessionStamp?)

    private class Transport : MeloXDirectService {
        val requests = mutableListOf<Request>()
        var reply: suspend () -> JsonObject = { json("""{"code":200,"msgs":[],"follow":[],"more":false,
            "data":{"inRoom":true,"status":"READY"}}""") }
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
            }), CloudLibraryBackend(weapi, sessions, eapi))

        fun reads() = listOf(
            Read("/api/msg/private/users", mapOf("offset" to 3, "limit" to 7, "total" to "true")) {
                repository.privateConversations(owner, offset = 3, limit = 7)
            },
            Read("/api/msg/private/history", mapOf("userId" to 9L, "time" to 123L, "limit" to 7, "total" to "true")) {
                repository.privateMessages(owner, 9, before = 123, limit = 7)
            },
            Read("/api/user/getfollows/17", mapOf("offset" to 0, "limit" to 7, "order" to true)) {
                repository.messageContacts(owner, pageSize = 7, maximumCount = 7)
            },
            Read("/api/listen/together/status/get", emptyMap()) { repository.listenTogetherStatus(owner) },
        )
    }

    companion object {
        private fun json(value: String): JsonObject = JsonParser.parseString(value).asJsonObject
    }
}
