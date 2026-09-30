package com.ljyh.mei.standalone

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.api.GetSongUrlV1
import com.ljyh.mei.data.session.SessionCallFactory
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.di.NeteaseInterceptor
import com.ljyh.mei.di.RetrofitModule
import com.ljyh.mei.runtime.RuntimeBackendModule
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class StandaloneDownloadSourceBackendTest {
    private var identity = SessionIdentity(17, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private val owner = sessions.snapshot()
    private var now = 1_000_000L
    private val requests = mutableListOf<GetSongUrlV1>()
    private var respond: (GetSongUrlV1) -> StandalonePlayerSources = { request ->
        fixture(request.ids.let { JsonParser.parseString(it).asJsonArray.map { id -> id.asLong } })
    }
    private val api = object : StandaloneDownloadApi {
        override suspend fun sources(body: GetSongUrlV1, owner: SessionStamp): StandalonePlayerSources {
            assertEquals(this@StandaloneDownloadSourceBackendTest.owner, owner)
            requests += body.copy()
            return respond(body)
        }
    }

    private fun fixture(ids: List<Long> = listOf(1), edit: JsonObject.() -> Unit = {}): StandalonePlayerSources {
        val json = JsonParser.parseString("""{"code":200,"data":[${ids.joinToString(",") { id ->
            """{"id":$id,"code":200,"url":"https://media.example.test/$id.flac","type":"flac","level":"lossless","size":12345678901,"md5":"0123456789abcdef0123456789abcdef","expi":600}"""
        }}]}""").asJsonObject
        json.edit()
        return Gson().fromJson(json, StandalonePlayerSources::class.java)
    }

    private suspend fun resolve(ids: List<String> = listOf("1"), quality: MusicQuality = MusicQuality.LOSSLESS) =
        resolveStandaloneDownloadSources(api, sessions, ids, quality, owner) { now }

    @Test fun usesNumericPlayerBatchesAndReturnsRequestedOrderWithoutDuplicates() = runTest {
        respond = { fixture(listOf(1, 2)) }
        val result = resolve(listOf(" 2 ", "1", "2"))
        assertEquals("[2,1]", requests.single().ids)
        assertEquals("lossless", requests.single().level)
        assertEquals("flac", requests.single().encodeType)
        assertNull(requests.single().immerseType)
        assertEquals(listOf(2L, 1L), result.sources.map { it.id })
        assertEquals(12345678901L, result.sources.first().size)
        assertEquals(now + 600_000, result.sources.first().expiresAtMs)
        assertTrue(result.rejectedCodes.isEmpty())
    }

    @Test fun originalQualityFallbackResolvesOnlyMissingSongsAndRetainsServerQuality() = runTest {
        respond = { request ->
            if (request.level == "sky") fixture(listOf(1, 2)) {
                getAsJsonArray("data")[1].asJsonObject.apply { addProperty("code", -105); remove("url") }
            } else fixture(listOf(2))
        }
        val result = resolve(listOf("1", "2"), MusicQuality.SKY)
        assertEquals(listOf("[1,2]", "[2]"), requests.map { it.ids })
        assertEquals(listOf("sky", "jyeffect"), requests.map { it.level })
        assertEquals("c51", requests.first().immerseType)
        assertNull(requests.last().immerseType)
        assertEquals(listOf(1L, 2L), result.sources.map { it.id })
        assertTrue(result.sources.all { it.requestedLevel == "sky" && it.level == "lossless" })
    }

    @Test fun trialsEmptyUrlsAndExplicitDenialsRemainRejectedAfterTheOriginalFallbacks() = runTest {
        for (edit in listOf<JsonObject.() -> Unit>(
            { addProperty("code", -105); remove("url") },
            { addProperty("url", "") },
            { add("freeTrialInfo", JsonObject()) },
        )) {
            requests.clear()
            respond = { fixture { getAsJsonArray("data")[0].asJsonObject.edit() } }
            val result = resolve()
            assertTrue(result.sources.isEmpty())
            assertTrue(result.rejectedCodes.containsKey("1"))
            assertEquals(listOf("lossless", "exhigh", "standard"), requests.map { it.level })
        }
    }

    @Test fun malformedOrPartialArraysDoNotReturnPartialSuccess() = runTest {
        for (json in listOf(
            "{}", """{"code":301,"data":[]}""", """{"code":200}""",
            """{"code":200,"data":null}""", """{"code":200,"data":[]}""",
            """{"code":200,"data":[null]}""", """{"code":200,"data":[{"id":2}]}""",
            """{"code":200,"data":[{"id":1},{"id":1}]}""",
        )) {
            requests.clear()
            respond = { Gson().fromJson(json, StandalonePlayerSources::class.java) }
            assertTrue(runCatching { resolve() }.exceptionOrNull() is IOException)
            assertEquals(1, requests.size)
        }
    }

    @Test fun malformedMetadataAndUnknownAuthorizationNeverTriggerQualityFallback() = runTest {
        for (edit in listOf<JsonObject.() -> Unit>(
            { remove("code") }, { addProperty("code", 301) }, { addProperty("code", 500) },
            { addProperty("url", "file:///private") }, { addProperty("url", "https://user:secret@example.test/file") },
            { remove("size") }, { addProperty("size", 0) }, { remove("md5") }, { addProperty("md5", "invalid") },
            { remove("type") }, { addProperty("type", "../flac") },
            { addProperty("expi", 0) }, { addProperty("expi", -1) }, { addProperty("expi", Long.MAX_VALUE) },
        )) {
            requests.clear()
            respond = { fixture { getAsJsonArray("data")[0].asJsonObject.edit() } }
            assertTrue(runCatching { resolve() }.exceptionOrNull() is IOException)
            assertEquals(1, requests.size)
        }
    }

    @Test fun optionalExpiryAndLevelAreNotInvented() = runTest {
        respond = { fixture { getAsJsonArray("data")[0].asJsonObject.apply { remove("expi"); remove("level") } } }
        val source = resolve().sources.single()
        assertNull(source.expiresAtMs)
        assertEquals("", source.level)
        assertEquals("lossless", source.requestedLevel)
    }

    @Test fun delayedResponsesCannotExtendTheOriginalSourceLifetime() = runTest {
        respond = { now += 600_000; fixture() }
        assertTrue(runCatching { resolve() }.exceptionOrNull() is IOException)
        assertEquals(1, requests.size)
    }

    @Test fun sourcesCannotExpireWhileLaterFallbacksAreBeingResolved() = runTest {
        respond = { request ->
            if (request.level == "lossless") fixture(listOf(1, 2)) {
                getAsJsonArray("data")[0].asJsonObject.addProperty("expi", 1)
                getAsJsonArray("data")[1].asJsonObject.addProperty("url", "")
            } else {
                now += 1_000
                fixture(listOf(2))
            }
        }
        assertTrue(runCatching { resolve(listOf("1", "2")) }.exceptionOrNull() is IOException)
    }

    @Test fun invalidIdsGuestsRecoveryAndStaleOwnersCannotDispatch() = runTest {
        for (id in listOf("", "0", "-1", "1_17", "1,2", "9223372036854775808")) {
            assertTrue(runCatching { resolve(listOf("1", id)) }.isFailure)
        }
        assertTrue(resolve(emptyList()).sources.isEmpty())
        sessions.setRecoveryRequired(true)
        assertTrue(runCatching { resolve() }.exceptionOrNull() is SessionChangedException)
        sessions.setRecoveryRequired(false)
        identity = SessionIdentity(0, false, true)
        assertTrue(runCatching { resolveStandaloneDownloadSources(api, sessions, listOf("1"), MusicQuality.STANDARD, sessions.snapshot()) }.exceptionOrNull() is SessionChangedException)
        identity = owner.identity
        sessions.invalidate()
        assertTrue(runCatching { resolve() }.exceptionOrNull() is SessionChangedException)
        assertTrue(requests.isEmpty())
    }

    @Test fun lateResponsesCannotEscapeAccountRenewalReplacementOrRecovery() = runTest {
        for (change in 0..2) {
            var current = SessionIdentity(17, true, false)
            val local = SessionStore().apply { bind { current } }
            val stamp = local.snapshot()
            val replacementApi = object : StandaloneDownloadApi {
                override suspend fun sources(body: GetSongUrlV1, owner: SessionStamp): StandalonePlayerSources {
                    when (change) {
                        0 -> local.invalidate()
                        1 -> current = SessionIdentity(88, true, false)
                        2 -> local.setRecoveryRequired(true)
                    }
                    return fixture()
                }
            }
            assertTrue(runCatching { resolveStandaloneDownloadSources(replacementApi, local, listOf("1"), MusicQuality.STANDARD, stamp) }.exceptionOrNull() is SessionChangedException)
        }
    }

    @Test fun transportFailuresDoNotFallBackOrReuseEarlierSources() = runTest {
        resolve()
        requests.clear()
        respond = { throw IOException("Offline") }
        assertTrue(runCatching { resolve() }.exceptionOrNull() is IOException)
        assertEquals(1, requests.size)
    }

    @Test fun cancellationRemainsCancellationWithoutAnotherQualityRequest() = runTest {
        val task = async {
            val job = currentCoroutineContext().job
            respond = { job.cancel(); fixture() }
            resolve()
        }
        assertTrue(runCatching { task.await() }.exceptionOrNull() is CancellationException)
        assertEquals(1, requests.size)
    }

    @Test fun largeBatchesAreBoundedAndKeepAllRequestedIdentities() = runTest {
        val ids = (1..201).map(Int::toString)
        val result = resolve(ids, MusicQuality.STANDARD)
        assertEquals(listOf(200, 1), requests.map { JsonParser.parseString(it.ids).asJsonArray.size() })
        assertEquals(ids, result.sources.map { it.id.toString() })
    }

    @Test fun selectedBackendSignsOnlyPlayerRequestsWithItsOwnCapturedCookie() = runBlocking {
        val store = StandaloneSessionStore(object : StandaloneAccountPersistence {
            override suspend fun read() = StoredAccount("", 0)
            override suspend fun write(account: StoredAccount) = Unit
        })
        store.initialize()
        store.commitLogin(store.beginLogin(), StoredAccount("fixture-cookie", 17))
        val originals = mutableListOf<Request>()
        val bodies = mutableListOf<JsonObject>()
        val signed = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            originals += chain.request()
            bodies += JsonParser.parseString(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).asJsonObject
            chain.proceed(chain.request())
        }.addInterceptor(NeteaseInterceptor { "fixture-device" }).addInterceptor { chain ->
            signed += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("Synthetic").body(Gson().toJson(fixture()).toResponseBody()).build()
        }.build()
        val calls = SessionCallFactory(store, client) { request, stamp ->
            request.newBuilder().tag(StandaloneCredentials::class.java, store.credentials(stamp)).build()
        }
        val backend = RuntimeBackendModule.downloadSources(StandaloneDownloadSourceBackend(RetrofitModule.provideRetrofit(calls), store))
        val stamp = store.snapshot()
        assertEquals(1L, backend.resolve(listOf("1"), MusicQuality.STANDARD, stamp).sources.single().id)
        assertEquals("/api/song/enhance/player/url/v1", originals.single().url.encodedPath)
        assertEquals(stamp, originals.single().tag(SessionStamp::class.java))
        assertEquals(JsonParser.parseString("""{"ids":"[1]","level":"standard","encodeType":"flac"}"""), bodies.single())
        assertTrue(signed.single().header("Cookie").orEmpty().contains("MUSIC_U=fixture-cookie"))
        store.invalidate()
        assertTrue(runCatching { backend.resolve(listOf("1"), MusicQuality.STANDARD, stamp) }.exceptionOrNull() is SessionChangedException)
        assertEquals(1, originals.size)
    }
}
