package com.ljyh.mei.standalone

import com.google.gson.JsonParser
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.di.NeteaseInterceptor
import com.ljyh.mei.utils.encrypt.decryptEApi
import java.io.IOException
import java.util.HexFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Real candidate verification and signing; the terminal interceptor never opens a socket. */
class StandaloneAccountTransportTest {
    @Test fun successfulPrimaryVerificationDoesNotRequestTheAlternative() = runBlocking {
        val f = Fixture()
        val account = f.transport.verify("candidate-cookie", f.owner)
        assertEquals(22, account.userId)
        assertEquals("Verified", account.nickname)
        assertEquals("https://fixture.invalid/avatar.png", account.avatarUrl)
        assertEquals(listOf(PRIMARY), f.sent.map { it.url.encodedPath })
        assertCandidateRequests(f)
        assertEquals(0, f.disk.writes)
        assertEquals(f.owner, f.sessions.snapshot())
    }

    @Test fun rejectedHttpBusinessAndTransportPrimaryUseOneOriginalAlternative() = runBlocking {
        for (failure in listOf("http", "business", "transport", "json")) {
            val f = Fixture()
            f.reply = { request ->
                if (request.url.encodedPath == PRIMARY) when (failure) {
                    "http" -> 503 to "synthetic"
                    "business" -> 200 to """{"code":403}"""
                    "json" -> 200 to "invalid JSON"
                    else -> throw IOException("Synthetic transport failure")
                } else 200 to profile()
            }
            assertEquals(22, f.transport.verify("candidate-cookie", f.owner).userId)
            assertEquals(listOf(PRIMARY, ALTERNATIVE), f.sent.map { it.url.encodedPath })
            assertCandidateRequests(f)
            assertEquals(0, f.disk.writes)
            assertEquals(f.owner, f.sessions.snapshot())
        }
    }

    @Test fun failedAlternativeIsTerminalAndDoesNotReplaceTheCurrentAccount() = runBlocking {
        val f = Fixture()
        f.reply = { request -> 200 to if (request.url.encodedPath == PRIMARY) """{"code":403}""" else """{"code":500}""" }
        val controller = StandaloneAccountController(f.sessions, f.transport::verify)
        assertFalse(controller.login("candidate-cookie"))
        assertEquals(listOf(PRIMARY, ALTERNATIVE), f.sent.map { it.url.encodedPath })
        assertEquals("old-cookie", f.sessions.credentials(f.owner).musicU)
        assertEquals(0, f.disk.writes)
        f.reply = { 200 to profile() }
        assertTrue(controller.login("candidate-cookie"))
        assertEquals(1, f.disk.writes)
        assertEquals(22, f.sessions.snapshot().identity.userId)
    }

    @Test fun acceptedPrimaryWithMissingOrInvalidProfileDoesNotRetry() = runBlocking {
        for (body in listOf("""{"code":200}""", """{"code":200,"profile":{"userId":0}}""")) {
            val f = Fixture()
            f.reply = { 200 to body }
            assertTrue(runCatching { f.transport.verify("candidate-cookie", f.owner) }.isFailure)
            assertEquals(listOf(PRIMARY), f.sent.map { it.url.encodedPath })
            assertEquals(0, f.disk.writes)
        }
    }

    @Test fun originalSuccessCodesAndOptionalProfileDefaultsAreRetained() = runBlocking {
        for (code in listOf("\"code\":207,", "", "\"code\":null,")) {
            val f = Fixture()
            f.reply = { 200 to """{$code"profile":{"userId":"22","nickname":null,"avatarUrl":null}}""" }
            val account = f.transport.verify("candidate-cookie", f.owner)
            assertEquals(22, account.userId)
            assertEquals("NetEase user", account.nickname)
            assertNull(account.avatarUrl)
            assertEquals(listOf(PRIMARY), f.sent.map { it.url.encodedPath })
            assertCandidateRequests(f)
        }
    }

    @Test fun successfulResponseAfterRecoveryChangedCannotPublishAnAccount() = runBlocking {
        val f = Fixture()
        f.reply = { f.sessions.setRecoveryRequired(true); 200 to profile() }
        val controller = StandaloneAccountController(f.sessions, f.transport::verify)
        assertFalse(controller.login("candidate-cookie"))
        assertEquals(listOf(PRIMARY), f.sent.map { it.url.encodedPath })
        assertEquals(0, f.disk.writes)
        assertEquals("old-cookie", f.sessions.credentials(f.owner).musicU)
    }

    @Test fun reauthorizationReplacementAndRecoveryDuringFailureCannotReachTheAlternative() = runBlocking {
        for (change in listOf("reauthorization", "replacement", "recovery")) {
            val f = Fixture()
            f.reply = {
                when (change) {
                    "reauthorization" -> f.sessions.invalidate()
                    "replacement" -> runBlocking {
                        f.sessions.commitLogin(f.sessions.beginLogin(), StoredAccount("new-cookie", 33))
                    }
                    else -> f.sessions.setRecoveryRequired(true)
                }
                throw IOException("Synthetic stale failure")
            }
            assertTrue(runCatching { f.transport.verify("candidate-cookie", f.owner) }.exceptionOrNull() is SessionChangedException)
            assertEquals(listOf(PRIMARY), f.sent.map { it.url.encodedPath })
            assertEquals(if (change == "replacement") 1 else 0, f.disk.writes)
        }
    }

    @Test fun pendingRecoveryCanUseTheAlternativeWithoutPublishingSavedIdentityFirst() = runBlocking {
        val f = Fixture(recovery = true)
        assertTrue(f.sessions.recoveryRequired.value)
        assertFalse(f.owner.identity.authenticated)
        f.reply = { request -> 200 to if (request.url.encodedPath == PRIMARY) """{"code":403}""" else profile() }
        val controller = StandaloneAccountController(f.sessions, f.transport::verify)
        assertTrue(controller.login("candidate-cookie"))
        assertEquals(listOf(PRIMARY, ALTERNATIVE), f.sent.map { it.url.encodedPath })
        assertCandidateRequests(f)
        assertFalse(f.sessions.recoveryRequired.value)
        assertEquals(22, f.sessions.snapshot().identity.userId)
        assertEquals(1, f.disk.writes)
    }

    @Test fun canceledNonCooperativePrimaryCannotRetryOrPersistAfterItFinishes() = runBlocking {
        withTimeout(5_000) {
            val f = Fixture()
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val terminal = CompletableDeferred<Unit>()
            f.reply = {
                started.complete(Unit)
                runBlocking { finish.await() }
                terminal.complete(Unit)
                200 to """{"code":403}"""
            }
            val controller = StandaloneAccountController(f.sessions, f.transport::verify)
            val pending = async(Dispatchers.Default) { controller.login("candidate-cookie") }
            started.await()
            pending.cancel()
            finish.complete(Unit)
            terminal.await()
            pending.join()
            assertTrue(pending.isCancelled)
            assertEquals(listOf(PRIMARY), f.sent.map { it.url.encodedPath })
            assertEquals(0, f.disk.writes)
            assertEquals(f.owner, f.sessions.snapshot())
        }
    }

    @Test fun staleOwnerAndMalformedCandidateNeverReachTransport() = runBlocking {
        val f = Fixture()
        f.sessions.invalidate()
        assertTrue(runCatching { f.transport.verify("candidate-cookie", f.owner) }.exceptionOrNull() is SessionChangedException)
        assertTrue(runCatching { f.transport.verify("MUSIC_U=value", f.sessions.snapshot()) }.isFailure)
        assertTrue(f.sent.isEmpty())
        assertEquals(0, f.disk.writes)
    }

    private fun assertCandidateRequests(f: Fixture) {
        for (request in f.sent) {
            assertEquals("interface.music.163.com", request.url.host)
            assertEquals(f.owner, request.tag(SessionStamp::class.java))
            assertNull(request.header("X-Netease-Crypto"))
            assertFalse(request.header("Cookie").orEmpty().contains("old-cookie"))
            val fields = JsonParser.parseString(decryptEApi(HexFormat.of().parseHex((request.body as FormBody).value(0)))
                .split("-36cd479b6b5-")[1]).asJsonObject
            assertEquals("candidate-cookie", fields.getAsJsonObject("header").get("MUSIC_U").asString)
            fields.remove("header")
            fields.remove("e_r")
            assertEquals(JsonParser.parseString("{}"), fields)
        }
    }

    private class Fixture(recovery: Boolean = false) {
        val disk = MemoryPersistence()
        val sessions = StandaloneSessionStore(disk).also { store -> runBlocking {
            store.initialize()
            if (!recovery) store.commitLogin(store.beginLogin(), disk.account)
            disk.writes = 0
        } }
        val owner = sessions.snapshot()
        val sent = java.util.concurrent.CopyOnWriteArrayList<Request>()
        var reply: (Request) -> Pair<Int, String> = { 200 to profile() }
        private val client = OkHttpClient.Builder().addInterceptor(NeteaseInterceptor { "fixture-device" })
            .addInterceptor { chain ->
                val request = chain.request()
                sent += request
                val (code, body) = reply(request)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(code).message("synthetic").body(body.toResponseBody()).build()
            }.build()
        val transport = StandaloneTransport(sessions, client)
    }

    private class MemoryPersistence : StandaloneAccountPersistence {
        var account = StoredAccount("old-cookie", 11)
        var writes = 0
        override suspend fun read() = account
        override suspend fun write(account: StoredAccount) { this.account = account; writes++ }
    }

    companion object {
        private const val PRIMARY = "/eapi/w/nuser/account/get"
        private const val ALTERNATIVE = "/eapi/nuser/account/get"
        private fun profile() = """{"code":200,"profile":{"userId":22,"nickname":"Verified","avatarUrl":"https://fixture.invalid/avatar.png"}}"""
    }
}
