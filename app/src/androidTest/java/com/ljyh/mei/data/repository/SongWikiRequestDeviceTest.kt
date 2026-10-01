package com.ljyh.mei.data.repository

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.data.model.melox.SongWikiMemoryKind
import com.ljyh.mei.data.network.api.MeloXDirectService
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

/** Actual Repository/platform parsing, synthetic transport only; no account requests. */
class SongWikiRequestDeviceTest {
    private var identity = SessionIdentity(17, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private val owner = sessions.snapshot()
    private val requests = mutableListOf<Pair<Map<String, Any>, SessionStamp?>>()
    private var reply: suspend () -> JsonObject = { json("""{"code":200,"data":{"blocks":[]}}""") }
    private val eapi = object : MeloXDirectService {
        override suspend fun post(path: String, body: Map<String, Any>, headers: Map<String, String>, expectedSession: SessionStamp?): JsonObject {
            assertEquals("/api/song/play/about/block/page", path)
            assertTrue(headers.isEmpty())
            requests += body to expectedSession
            return reply()
        }
        override suspend fun postPlaybackRaw(path: String, body: Map<String, Any>, headers: Map<String, String>): Response<ResponseBody> =
            error("Unrelated playback report")
    }
    private val weapi = object : MeloXDirectService {
        override suspend fun post(path: String, body: Map<String, Any>, headers: Map<String, String>, expectedSession: SessionStamp?): JsonObject =
            error("Wiki must retain the original EAPI route")
        override suspend fun postPlaybackRaw(path: String, body: Map<String, Any>, headers: Map<String, String>): Response<ResponseBody> =
            error("Unrelated playback report")
    }
    private val repository = MeloXRepository(eapi, weapi, InstrumentationRegistry.getInstrumentation().targetContext, sessions,
        CloudUploadCoordinator(eapi, weapi, sessions, object : CloudBinaryUploader {
            override suspend fun upload(file: CloudUploadFile, authorization: CloudUploadAuthorization,
                owner: SessionStamp, onProgress: (Long, Long) -> Unit) = error("Unrelated upload")
        }), CloudLibraryBackend(weapi, sessions))

    @Test fun originalWikiPayloadAndPersonalizedMemoryMappingCarryOneOwner() = runBlocking {
        reply = { json("""{"code":200,"data":{"blocks":[
            {"code":"SONG_PLAY_ABOUT_MUSIC_MEMORY","creatives":[{"resources":[
                {"resourceType":"FIRST_LISTEN","resourceExt":{"musicFirstListenDto":{"date":"2026-01-01"}}},
                {"resourceType":"TOTAL_PLAY","resourceExtInfo":{"musicTotalPlayDto":{"playCount":"12","duration":"34","text":"Fixture memory"}}}
            ]}]}
        ]}}""") }
        val wiki = repository.songWiki(10, owner)
        assertEquals(listOf(mapOf("songId" to 10L) to owner), requests)
        assertEquals(listOf(SongWikiMemoryKind.FirstListen, SongWikiMemoryKind.TotalPlay), wiki.memories.map { it.kind })
        assertEquals("2026-01-01", wiki.memories.first().date)
        assertEquals(12L, wiki.memories.last().playCount)
        assertEquals(34L, wiki.memories.last().durationMinutes)
        assertEquals("Fixture memory", wiki.memories.last().text)
    }

    @Test fun staleSessionAndInvalidSongNeverReachTheTransport() = runBlocking {
        sessions.invalidate()
        assertTrue(runCatching { repository.songWiki(10, owner) }.exceptionOrNull() is SessionChangedException)
        assertTrue(runCatching { repository.songWiki(0, sessions.snapshot()) }.isFailure)
        assertTrue(requests.isEmpty())
    }

    @Test fun anonymousReadRemainsAllowedAndRecoveryNeverDispatches() = runBlocking {
        identity = SessionIdentity(0, false, true)
        val guest = sessions.snapshot()
        assertTrue(repository.songWiki(10, guest).isEmpty)
        assertEquals(guest, requests.single().second)
        sessions.setRecoveryRequired(true)
        assertTrue(runCatching { repository.songWiki(10, guest) }.isFailure)
        assertEquals(1, requests.size)
    }

    @Test fun accountChangeAndReauthorizationRejectLateWikiResponses() = runBlocking {
        reply = { identity = SessionIdentity(18, true, false); sessions.invalidate(); json("""{"code":200}""") }
        assertTrue(runCatching { repository.songWiki(10, owner) }.exceptionOrNull() is SessionChangedException)
        val replacement = sessions.snapshot()
        reply = { sessions.invalidate(); json("""{"code":200}""") }
        assertTrue(runCatching { repository.songWiki(10, replacement) }.exceptionOrNull() is SessionChangedException)
        assertEquals(listOf(owner, replacement), requests.map { it.second })
    }

    @Test fun recoveryDuringTheResponseRejectsItsData() = runBlocking {
        reply = { sessions.setRecoveryRequired(true); json("""{"code":200}""") }
        assertTrue(runCatching { repository.songWiki(10, owner) }.isFailure)
        assertEquals(1, requests.size)
    }

    @Test fun businessFailureIsNotConvertedToAnEmptyWiki() = runBlocking {
        reply = { json("""{"code":403,"message":"Denied fixture"}""") }
        assertEquals("Denied fixture", runCatching { repository.songWiki(10, owner) }.exceptionOrNull()?.message)
    }

    @Test fun cancellationRejectsNonCooperativeLateTransportData() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val pending = CompletableDeferred<JsonObject>()
        reply = { started.complete(Unit); withContext(NonCancellable) { pending.await() } }
        val result = async { repository.songWiki(10, owner) }
        started.await()
        result.cancel()
        pending.complete(json("""{"code":200,"data":{"blocks":[]}}"""))
        result.join()
        assertTrue(result.isCancelled)
        assertEquals(1, requests.size)
    }

    private fun json(value: String): JsonObject = JsonParser.parseString(value).asJsonObject
}
