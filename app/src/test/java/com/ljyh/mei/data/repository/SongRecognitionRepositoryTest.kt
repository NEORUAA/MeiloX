package com.ljyh.mei.data.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.data.network.api.AudioMatchService
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Tag

class SongRecognitionRepositoryTest {
    private var identity = SessionIdentity(17, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private val owner = sessions.snapshot()
    private val calls = mutableListOf<List<Any?>>()
    private var reply: () -> JsonObject = { json("""{"code":200,"data":{"result":[]}}""") }
    private val service = Proxy.newProxyInstance(AudioMatchService::class.java.classLoader,
        arrayOf(AudioMatchService::class.java)) { _, method, args ->
        check(method.name == "match")
        calls += args!!.dropLast(1)
        reply()
    } as AudioMatchService
    private val repository = SongRecognitionRepository(service, sessions)

    @Test fun fingerprintProtocolFieldsAreUnchangedAndOwnerIsOnlyALocalTag() = runBlocking {
        assertTrue(repository.match("fixture-fingerprint", 6, owner).isEmpty())
        assertEquals(listOf("0123456789abcdef", "shazam_v2", 6, "fixture-fingerprint", 1, 1, owner), calls.single())
        val method = AudioMatchService::class.java.methods.single { it.name == "match" }
        assertEquals("/api/music/audio/match", method.getAnnotation(GET::class.java)!!.value)
        assertEquals(listOf("sessionId", "algorithmCode", "duration", "rawdata", "times", "decrypt"),
            method.parameterAnnotations.flatMap { it.filterIsInstance<Query>().map(Query::value) })
        assertTrue(method.parameterAnnotations[6].any { it is Tag })
        assertFalse(method.parameterAnnotations[6].any { it is Query })
    }

    @Test fun actualRetrofitSerializationKeepsTheOwnerOutOfTheFingerprintQuery() = runBlocking {
        var captured: Request? = null
        val client = OkHttpClient()
        val retrofit = Retrofit.Builder().baseUrl("https://interface.music.163.com")
            .addConverterFactory(GsonConverterFactory.create())
            .callFactory { request ->
                captured = request
                object : Call by client.newCall(request) {
                    override fun execute(): Response = error("Unexpected blocking request")
                    override fun enqueue(responseCallback: Callback) {
                        responseCallback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                            .code(200).message("OK")
                            .body("""{"code":200,"data":{"result":[]}}""".toResponseBody("application/json".toMediaType())).build())
                    }
                }
            }.build()
        val actual = SongRecognitionRepository(retrofit.create(AudioMatchService::class.java), sessions)
        assertTrue(actual.match("fixture+/=", 6, owner).isEmpty())
        val request = captured!!
        assertEquals("GET", request.method)
        assertEquals("/api/music/audio/match", request.url.encodedPath)
        assertEquals(setOf("sessionId", "algorithmCode", "duration", "rawdata", "times", "decrypt"), request.url.queryParameterNames)
        assertEquals("0123456789abcdef", request.url.queryParameter("sessionId"))
        assertEquals("shazam_v2", request.url.queryParameter("algorithmCode"))
        assertEquals("fixture+/=", request.url.queryParameter("rawdata"))
        assertEquals(owner, request.tag(SessionStamp::class.java))
        assertNull(request.header("Cookie"))
    }

    @Test fun candidateMetadataAndLegacyAliasesKeepTheirOriginalMapping() = runBlocking {
        reply = { json("""{"code":200,"data":{"result":[
            {"startTime":"12","song":{"id":"7","name":"Track","dt":1000,"ar":[{"name":"Artist"}],
                "al":{"name":"Album","picUrl":"https://example.test/art"}}},
            {"song":{"id":7,"name":"Duplicate"}},
            {"song":{"id":8,"duration":2000,"artists":[{"name":"Legacy"}],"album":{"name":"Old album"}}},
            null,{"song":{}},{"notSong":{}}
        ]}}""") }
        val results = repository.match("fixture", 9, owner)
        assertEquals(listOf(7L, 8L), results.map { it.id })
        assertEquals("Track", results[0].name)
        assertEquals(listOf("Artist"), results[0].artists)
        assertEquals("Album", results[0].album)
        assertEquals("https://example.test/art", results[0].coverUrl)
        assertEquals(1000L, results[0].durationMs)
        assertEquals(12L, results[0].startTimeMs)
        assertEquals(listOf("Legacy"), results[1].artists)
        assertEquals(2000L, results[1].durationMs)
        assertEquals("Unknown song", results[1].name)
    }

    @Test fun staleOwnersAndInvalidFingerprintsNeverDispatch() = runBlocking {
        sessions.invalidate()
        assertTrue(runCatching { repository.match("fixture", 6, owner) }.exceptionOrNull() is SessionChangedException)
        assertTrue(runCatching { repository.match(" ", 6, sessions.snapshot()) }.isFailure)
        for (duration in listOf(0, 16)) assertTrue(runCatching { repository.match("fixture", duration, sessions.snapshot()) }.isFailure)
        assertTrue(calls.isEmpty())
    }

    @Test fun anonymousRecognitionRemainsAllowedButRecoveryDoesNotDispatch() = runBlocking {
        identity = SessionIdentity(0, false, true)
        val guest = sessions.snapshot()
        assertTrue(repository.match("fixture", 3, guest).isEmpty())
        assertEquals(guest, calls.single().last())
        sessions.setRecoveryRequired(true)
        assertTrue(runCatching { repository.match("fixture", 3, guest) }.isFailure)
        assertEquals(1, calls.size)
    }

    @Test fun accountChangeAndSameAccountReauthorizationRejectLateCandidates() = runBlocking {
        reply = { identity = SessionIdentity(18, true, false); sessions.invalidate(); json("""{"code":200}""") }
        assertTrue(runCatching { repository.match("fixture", 6, owner) }.exceptionOrNull() is SessionChangedException)
        val replacement = sessions.snapshot()
        reply = { sessions.invalidate(); json("""{"code":200}""") }
        assertTrue(runCatching { repository.match("fixture", 6, replacement) }.exceptionOrNull() is SessionChangedException)
        assertEquals(listOf(owner, replacement), calls.map { it.last() })
    }

    @Test fun recoveryDuringResponseAndBusinessRejectionRemainFailures() = runBlocking {
        reply = { sessions.setRecoveryRequired(true); json("""{"code":200}""") }
        assertTrue(runCatching { repository.match("fixture", 6, owner) }.isFailure)
        sessions.setRecoveryRequired(false)
        reply = { json("""{"code":403}""") }
        assertEquals("NetEase audio match failed (403)", runCatching { repository.match("fixture", 6, owner) }.exceptionOrNull()?.message)
    }

    @Test fun transportCancellationIsNotConvertedToNoMatch() = runBlocking {
        reply = { throw CancellationException("Retired recognition") }
        try { repository.match("fixture", 6, owner); fail("Cancellation swallowed") } catch (_: CancellationException) { }
    }

    private fun json(value: String): JsonObject = JsonParser.parseString(value).asJsonObject
}
