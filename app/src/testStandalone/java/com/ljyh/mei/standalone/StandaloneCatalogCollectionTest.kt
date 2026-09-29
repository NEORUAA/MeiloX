package com.ljyh.mei.standalone

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.data.session.SessionCallFactory
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.di.NeteaseInterceptor
import com.ljyh.mei.di.RetrofitModule
import com.ljyh.mei.runtime.RuntimeBackendModule
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class StandaloneCatalogCollectionTest {
    @Test fun albumStateUsesEveryRequiredPageAndTheCurrentCookie() = runBlocking {
        for (id in listOf(4L, 9L)) {
            val wire = fixture()
            val owner = wire.sessions.snapshot()
            wire.response = { _, body ->
                when (body["offset"].asInt) {
                    0 -> albums(true, 1, 2)
                    2 -> albums(true, 2, 3)
                    4 -> albums(false, 4)
                    else -> error("Unexpected offset")
                }
            }
            assertEquals(id == 4L, wire.backend.albumCollected(id, owner))
            assertEquals(listOf(0, 2, 4), wire.bodies.map { it["offset"].asInt })
            wire.originals.forEach {
                assertEquals("/api/album/sublist", it.url.encodedPath)
                assertEquals(owner, it.tag(SessionStamp::class.java))
            }
            wire.bodies.forEach {
                assertEquals("100", it["limit"].asString)
                assertEquals("true", it["total"].asString)
                assertEquals(setOf("offset", "limit", "total"), it.keySet())
            }
            wire.signed.forEach { assertTrue(it.header("Cookie").orEmpty().contains("MUSIC_U=fixture-cookie")) }
        }
    }

    @Test fun matchingAlbumStopsWithoutLoadingUnneededPages() = runBlocking {
        val wire = fixture()
        wire.response = { _, _ -> albums(true, 10) }
        assertTrue(wire.backend.albumCollected(10, wire.sessions.snapshot()))
        assertEquals(1, wire.originals.size)
    }

    @Test fun emptyFinalAlbumListIsFalseButNonterminalEmptyOrRepeatedPagesFail() = runBlocking {
        val wire = fixture()
        wire.response = { _, _ -> albums(false) }
        assertFalse(wire.backend.albumCollected(10, wire.sessions.snapshot()))
        for (page in listOf(albums(true), albums(true, 1, 2))) {
            val broken = fixture()
            broken.response = { _, _ -> page }
            assertTrue(runCatching { broken.backend.albumCollected(10, broken.sessions.snapshot()) }.isFailure)
            assertTrue(broken.originals.size <= 2)
        }
    }

    @Test fun malformedAlbumListsCursorsAndBusinessFailuresAreNotFalse() = runBlocking {
        for (json in listOf(
            """{"code":301}""", """{"code":200,"hasMore":false}""",
            """{"code":200,"data":[],"hasMore":null}""", """{"code":200,"data":[]}""",
            """{"code":200,"data":[],"hasMore":"false"}""",
            """{"code":200,"data":[null],"hasMore":false}""",
            """{"code":200,"data":[{}],"hasMore":false}""",
            albums(false, 0), albums(false, 1, 1),
        )) {
            val wire = fixture()
            wire.response = { _, _ -> json }
            assertTrue(runCatching { wire.backend.albumCollected(10, wire.sessions.snapshot()) }.isFailure)
        }
    }

    @Test fun artistStateUsesArtistIdentityAndFlagNotTheLinkedUser() = runBlocking {
        for (followed in listOf(true, false)) {
            val wire = fixture()
            wire.response = { _, _ -> """{"code":200,"artist":{"id":10,"followed":$followed},"user":{"followed":${!followed}}}""" }
            assertEquals(followed, wire.backend.artistFollowed(10, wire.sessions.snapshot()))
            assertEquals("/api/v1/artist/10", wire.originals.single().url.encodedPath)
            assertEquals(JsonParser.parseString("""{"limit":50,"offset":0,"total":true}"""), wire.bodies.single())
        }
    }

    @Test fun missingOrMalformedArtistFlagsAndMismatchedIdentitiesFail() = runBlocking {
        for (json in listOf(
            """{"code":500}""", """{"code":200}""",
            """{"code":200,"artist":{"id":10}}""",
            """{"code":200,"artist":{"id":11,"followed":true}}""",
            """{"code":200,"artist":{"id":10,"followed":null}}""",
            """{"code":200,"artist":{"id":10,"followed":"false"}}""",
            """{"code":200,"data":{"user":{"followed":true}}}""",
        )) {
            val wire = fixture()
            wire.response = { _, _ -> json }
            assertTrue(runCatching { wire.backend.artistFollowed(10, wire.sessions.snapshot()) }.isFailure)
        }
    }

    @Test fun artistWritesKeepOriginalWeapiRoutesAndBothIdentityFields() = runBlocking {
        val wire = fixture()
        val owner = wire.sessions.snapshot()
        for (followed in listOf(true, false)) {
            assertEquals(200, wire.backend.setArtistFollowed(10, followed, owner).code)
            val request = wire.originals.last()
            assertEquals("music.163.com", request.url.host)
            assertEquals("/weapi/artist/${if (followed) "sub" else "unsub"}", request.url.encodedPath)
            assertEquals(owner, request.tag(SessionStamp::class.java))
            val body = wire.bodies.last()
            assertEquals(setOf("artistId", "artistIds"), body.keySet())
            assertTrue(body["artistId"].asJsonPrimitive.isNumber)
            assertEquals(10L, body["artistId"].asLong)
            assertEquals("[10]", body["artistIds"].asString)
            val signed = wire.signed.last()
            val form = signed.body as FormBody
            assertEquals(listOf("params", "encSecKey"), (0 until form.size).map(form::name))
            assertTrue(signed.header("Cookie").orEmpty().contains("MUSIC_U=fixture-cookie"))
        }
    }

    @Test fun rejectedOrUncertainWritesAreNotRetriedThroughAnotherProtocol() = runBlocking {
        val wire = fixture()
        wire.response = { _, _ -> """{"code":301}""" }
        assertEquals(301, wire.backend.setArtistFollowed(10, true, wire.sessions.snapshot()).code)
        assertEquals(1, wire.originals.size)
        wire.response = { _, _ -> throw IOException("Unknown result") }
        assertTrue(runCatching { wire.backend.setArtistFollowed(10, false, wire.sessions.snapshot()) }.exceptionOrNull() is IOException)
        assertEquals(2, wire.originals.size)
    }

    @Test fun staleSessionsCannotDispatchAnyCollectionOperation() = runBlocking {
        val wire = fixture()
        val owner = wire.sessions.snapshot()
        wire.sessions.invalidate()
        val calls: List<suspend () -> Any> = listOf(
            { wire.backend.albumCollected(10, owner) }, { wire.backend.artistFollowed(10, owner) },
            { wire.backend.setArtistFollowed(10, true, owner) }, { wire.backend.setArtistFollowed(10, false, owner) },
        )
        calls.forEach { assertTrue(runCatching { it() }.exceptionOrNull() is SessionChangedException) }
        assertTrue(wire.originals.isEmpty())
    }

    @Test fun accountChangesDuringAlbumPaginationDoNotLoadUnderTheNextAccount() = runBlocking {
        val wire = fixture()
        wire.response = { _, body ->
            if (body["offset"].asInt == 0) albums(true, 1)
            else { wire.sessions.invalidate(); albums(false, 10) }
        }
        assertTrue(runCatching { wire.backend.albumCollected(10, wire.sessions.snapshot()) }.exceptionOrNull() is IOException)
        assertEquals(2, wire.originals.size)
    }

    private fun albums(more: Boolean, vararg ids: Long) =
        """{"code":200,"hasMore":$more,"data":[${ids.joinToString(",") { "{\"id\":$it}" }}]}"""

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
        val originals = mutableListOf<Request>()
        val bodies = mutableListOf<JsonObject>()
        val signed = mutableListOf<Request>()
        var response: (Request, JsonObject) -> String = { _, _ -> """{"code":200}""" }
        private val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                originals += chain.request()
                bodies += JsonParser.parseString(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).asJsonObject
                chain.proceed(chain.request())
            }
            .addInterceptor(NeteaseInterceptor { "fixture-device" })
            .addInterceptor { chain ->
                signed += chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("synthetic").body(response(originals.last(), bodies.last()).toResponseBody()).build()
            }.build()
        private val calls = SessionCallFactory(sessions, client) { request, owner ->
            request.newBuilder().tag(StandaloneCredentials::class.java, sessions.credentials(owner)).build()
        }
        val backend = RuntimeBackendModule.catalogCollections(StandaloneCatalogCollectionBackend(
            RetrofitModule.provideRetrofit(calls), RetrofitModule.provideWeApiRetrofit(calls), sessions,
        ))
    }
}
