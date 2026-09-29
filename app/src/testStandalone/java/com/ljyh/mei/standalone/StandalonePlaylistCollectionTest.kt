package com.ljyh.mei.standalone

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.constants.checkToken
import com.ljyh.mei.data.session.SessionCallFactory
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.di.NeteaseInterceptor
import com.ljyh.mei.di.RetrofitModule
import com.ljyh.mei.runtime.RuntimeBackendModule
import com.ljyh.mei.utils.encrypt.decryptEApi
import java.io.IOException
import java.util.HexFormat
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class StandalonePlaylistCollectionTest {
    @Test fun bothActionsRetainOriginalEapiRoutesPayloadsAndSigningProfile() = runBlocking {
        val wire = fixture()
        val owner = wire.sessions.snapshot()
        for (collected in listOf(true, false)) {
            assertEquals(200, wire.backend.setCollected(10, collected, owner).code)
            val request = wire.requests.last()
            val action = if (collected) "subscribe" else "unsubscribe"
            assertEquals("/eapi/playlist/$action", request.url.encodedPath)
            assertEquals("POST", request.method)
            assertEquals(owner, request.tag(SessionStamp::class.java))
            assertTrue(request.headers.names().none { it.startsWith("X-Netease-", true) })
            assertTrue(request.header("Cookie").orEmpty().contains("MUSIC_U=fixture-cookie"))
            assertTrue(request.header("Cookie").orEmpty().contains("X-antiCheatToken=$checkToken"))
            val (path, body) = wire.signedFields(request)
            assertEquals("/api/playlist/$action", path)
            assertTrue(body["id"].asJsonPrimitive.isNumber)
            assertEquals(10L, body["id"].asLong)
            assertEquals(collected, body.has("checkToken"))
            if (collected) assertEquals(checkToken, body["checkToken"].asString)
            assertFalse(body.has("owner"))
            assertFalse(body.has("expectedSession"))
            val header = body.getAsJsonObject("header")
            assertEquals("fixture-cookie", header["MUSIC_U"].asString)
            assertEquals(checkToken, header["X-antiCheatToken"].asString)
        }
    }

    @Test fun rejectedBusinessCodesRemainAvailableToTheSharedRepository() = runBlocking {
        val wire = fixture()
        for (code in listOf(301, 500, 506)) for (collected in listOf(true, false)) {
            wire.code = code
            assertEquals(code, wire.backend.setCollected(10, collected, wire.sessions.snapshot()).code)
        }
    }

    @Test fun obsoleteOwnersCannotReachTheWireAndNewOwnersUseTheirOwnCookie() = runBlocking {
        val wire = fixture()
        val oldOwner = wire.sessions.snapshot()
        wire.backend.setCollected(10, true, oldOwner)
        wire.sessions.commitLogin(wire.sessions.beginLogin(), StoredAccount("next-cookie", 8))
        for (collected in listOf(true, false)) {
            assertTrue(runCatching { wire.backend.setCollected(10, collected, oldOwner) }.exceptionOrNull() is SessionChangedException)
        }
        assertEquals(1, wire.requests.size)
        wire.backend.setCollected(10, false, wire.sessions.snapshot())
        val bodies = wire.requests.map { wire.signedFields(it).second }
        assertEquals(listOf("fixture-cookie", "next-cookie"), bodies.map { it.getAsJsonObject("header")["MUSIC_U"].asString })
    }

    @Test fun changedSessionWhileResponseArrivesCannotReturnSuccess() = runBlocking {
        val wire = fixture()
        wire.afterRequest = wire.sessions::invalidate
        val failure = runCatching { wire.backend.setCollected(10, true, wire.sessions.snapshot()) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(1, wire.requests.size)
    }

    private suspend fun fixture(): Wire {
        val sessions = StandaloneSessionStore(object : StandaloneAccountPersistence {
            override suspend fun read() = StoredAccount("", 0)
            override suspend fun write(account: StoredAccount) = Unit
        })
        sessions.initialize()
        sessions.commitLogin(sessions.beginLogin(), StoredAccount("fixture-cookie", 7))
        return Wire(sessions)
    }

    private class Wire(val sessions: StandaloneSessionStore) {
        val requests = mutableListOf<Request>()
        var code = 200
        var afterRequest: () -> Unit = {}
        private val client = OkHttpClient.Builder()
            .addInterceptor(NeteaseInterceptor { "fixture-device" })
            .addInterceptor { chain ->
                requests += chain.request()
                afterRequest()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("synthetic").body("{\"code\":$code}".toResponseBody()).build()
            }.build()
        private val calls = SessionCallFactory(sessions, client) { request, owner ->
            request.newBuilder().tag(StandaloneCredentials::class.java, sessions.credentials(owner)).build()
        }
        val backend = RuntimeBackendModule.playlistCollections(
            StandalonePlaylistCollectionBackend(RetrofitModule.provideRetrofit(calls)),
        )

        fun signedFields(request: Request): Pair<String, JsonObject> {
            val plaintext = decryptEApi(HexFormat.of().parseHex((request.body as FormBody).value(0)))
            val fields = plaintext.split("-36cd479b6b5-")
            return fields[0] to JsonParser.parseString(fields[1]).asJsonObject
        }
    }
}
