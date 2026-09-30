package com.ljyh.mei.data.repository

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.data.model.Lyric
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.model.api.GetCloudLyric
import com.ljyh.mei.data.model.api.GetLyricV1
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SongLyricBackendTest {
    private var account = SessionIdentity(7, true, false)
    private val sessions = SessionStore().apply { bind { account } }
    private val owner = sessions.snapshot()
    private val source = SongSourceIdentity(999, 88, 7, 17)
    private val requests = mutableListOf<Pair<Any, SessionStamp>>()
    private var cloud = json("""{"code":200,"lrc":"[00:01.00]Cloud","krc":"native karaoke"}""")
    private var catalog = Gson().fromJson("""{"code":200,"lrc":{"lyric":"Catalog","version":4}}""", Lyric::class.java)
    private var after: () -> Unit = {}
    private val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, args ->
        requests += args[0] to (args[1] as SessionStamp)
        try { after() } catch (error: IOException) {
            // Suspend calls deliver checked transport failures through their continuation.
            @Suppress("UNCHECKED_CAST")
            (args.last() as Continuation<Any>).resumeWithException(error)
            return@newProxyInstance COROUTINE_SUSPENDED
        }
        when (method.name) {
            "getCloudLyric" -> cloud
            "getLyricV1" -> catalog
            else -> error("Unexpected lyric request")
        }
    } as ApiService
    private val backend = SongLyricBackend(api, sessions)
    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    @Test fun cloudAudioAndFileOwnerAreNotTheEntryOrAuthenticatedAccount() = runTest {
        val result = backend.lyrics(source.key, owner)
        assertEquals(listOf(GetCloudLyric(999, 88) to owner), requests)
        assertEquals("[00:01.00]Cloud", result.lrc?.lyric)
        assertEquals("native karaoke", result.klyric?.lyric)
        assertEquals(1, result.lrc?.version)
        assertEquals(1, result.klyric?.version)
        assertNull(result.yrc)
        assertNull(result.pureMusic)
    }

    @Test fun catalogKeepsItsExistingV1BodyAndExplicitSession() = runTest {
        assertSame(catalog, backend.lyrics("999", owner))
        assertEquals(listOf(GetLyricV1("999") to owner), requests)
    }

    @Test fun absentBlankAndNullCloudFieldsAreNotPureMusicOrRequestFailures() = runTest {
        for (response in listOf("""{"code":200}""", """{"code":200,"lrc":null,"krc":" "}""",
            """{"code":404}""")) {
            cloud = json(response)
            val result = backend.lyrics(source.key, owner)
            assertEquals(200, result.code)
            assertNull(result.lrc)
            assertNull(result.klyric)
            assertNull(result.pureMusic)
        }
        assertTrue(requests.all { it.first is GetCloudLyric })
    }

    @Test fun karaokeOnlyResponsesRetainTheirOriginalFormat() = runTest {
        cloud = json("""{"code":200,"krc":"native karaoke"}""")
        val result = backend.lyrics(source.key, owner)
        assertNull(result.lrc)
        assertEquals("native karaoke", result.klyric?.lyric)
        assertNull(result.yrc)
    }

    @Test fun malformedCatalogShapedOrDeniedCloudResponsesNeverRetryAsCatalog() = runTest {
        for (response in listOf("{}", """{"code":200.5}""", """{"code":301}""", """{"code":500}""",
            """{"code":200,"lrc":{"lyric":"Wrong envelope"}}""", """{"code":200,"lrc":true}""",
            """{"code":200,"krc":[]}""")) {
            cloud = json(response)
            assertTrue(runCatching { backend.lyrics(source.key, owner) }.exceptionOrNull() is IOException)
        }
        assertEquals(7, requests.size)
        assertTrue(requests.all { it.first is GetCloudLyric })
    }

    @Test fun badSourceAffinityAndRecoveryFailBeforeDispatch() = runTest {
        for (key in listOf(source.copy(accountId = 8).key, "999_88", "meilox-cloud-v1:17:999:0:7")) {
            assertTrue(runCatching { backend.lyrics(key, owner) }.isFailure)
        }
        sessions.setRecoveryRequired(true)
        assertTrue(runCatching { backend.lyrics(source.key, owner) }.exceptionOrNull() is SessionChangedException)
        assertTrue(requests.isEmpty())
    }

    @Test fun changedAccountAndSameAccountReauthorizationRejectLateResponses() = runTest {
        after = sessions::invalidate
        assertTrue(runCatching { backend.lyrics(source.key, owner) }.exceptionOrNull() is SessionChangedException)
        after = { account = SessionIdentity(8, true, false) }
        val replacement = sessions.snapshot()
        assertTrue(runCatching { backend.lyrics(source.key, replacement) }.exceptionOrNull() is SessionChangedException)
        assertEquals(2, requests.size)
    }

    @Test fun cancellationAndTransportFailuresNeverBecomeNoLyrics() = runTest {
        after = { throw CancellationException() }
        assertTrue(runCatching { backend.lyrics(source.key, owner) }.exceptionOrNull() is CancellationException)
        after = { throw IOException("Closed transport failure") }
        assertTrue(runCatching { backend.lyrics(source.key, owner) }.exceptionOrNull() is IOException)
    }

    @Test fun catalogBusinessErrorsAreNotAcceptedAsLyrics() = runTest {
        catalog = catalog.copy(code = 301)
        assertTrue(runCatching { backend.lyrics("999", owner) }.exceptionOrNull() is IOException)
    }
}
