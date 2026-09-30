package com.ljyh.mei.data.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.data.network.api.MeloXDirectService
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class CloudLibraryBackendTest {
    private var identity = SessionIdentity(7, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private val owner = sessions.snapshot()
    private val calls = mutableListOf<Triple<String, Map<String, Any>, SessionStamp?>>()
    private var reply: suspend (Map<String, Any>) -> JsonObject = { page(emptyList()) }
    private val api = object : MeloXDirectService {
        override suspend fun post(path: String, body: Map<String, Any>, headers: Map<String, String>, expectedSession: SessionStamp?): JsonObject {
            assertTrue(headers.isEmpty())
            calls += Triple(path, body, expectedSession)
            return reply(body)
        }
        override suspend fun postPlaybackRaw(path: String, body: Map<String, Any>, headers: Map<String, String>): Response<ResponseBody> = error("Unexpected raw request")
    }
    private val backend = CloudLibraryBackend(api, sessions)

    @Test fun completeSnapshotUsesAllPagesUnderOneOwner() = runTest {
        reply = { body -> when (body["offset"]) {
            0 -> page((1L..200L).toList(), 201)
            200 -> page(listOf(201), 201, false)
            else -> error("Unexpected page")
        } }
        val result = backend.songs(owner)
        assertEquals((1L..201L).toList(), result.songs.map { it.id })
        assertEquals(201, result.count)
        assertFalse(result.hasMore)
        assertEquals(20L, result.usedSize)
        assertEquals(100L, result.maxSize)
        assertEquals(listOf(0, 200), calls.map { it.second["offset"] })
        assertTrue(calls.all { it.first == "/api/v1/cloud/get" && it.second["limit"] == 200 && it.third == owner })
    }

    @Test fun shortPagesAdvanceByRawRowsAndCountCanSupplyContinuation() = runTest {
        reply = { body -> page(listOf((body.getValue("offset") as Int).toLong() + 1), 3) }
        assertEquals(listOf(1L, 2L, 3L), backend.songs(owner).songs.map { it.id })
        assertEquals(listOf(0, 1, 2), calls.map { it.second["offset"] })
    }

    @Test fun displayFallbacksNeverReplaceCloudDeletionIdentity() = runTest {
        reply = { json("""{"code":200,"count":1,"size":"20","maxSize":"100","data":[{
            "songId":"17","songName":" ","artist":null,"fileSize":"50","bitrate":320000,
            "simpleSong":{"id":999,"name":"Track","duration":1000,"artists":[{"name":"Artist"},null],
            "album":{"name":"Album","picUrl":"https://example.test/cover"}}}]}""") }
        val song = backend.songs(owner).songs.single()
        assertEquals(17L, song.id)
        assertEquals("Track", song.name)
        assertEquals("Artist", song.artist)
        assertEquals("Album", song.album)
        assertEquals(1000L, song.durationMs)
        assertEquals(50L, song.fileSize)
        assertEquals(320000, song.bitrate)
        assertEquals("https://example.test/cover", song.coverUrl)
        assertEquals(com.ljyh.mei.data.model.SongSourceIdentity(999, 7, 7, 17), song.source)
    }

    @Test fun explicitCloudOwnersDoNotBecomeTheAuthenticatedAccount() = runTest {
        reply = { json("""{"code":200,"count":1,"size":0,"maxSize":0,"data":[{
            "songId":17,"userId":88,"simpleSong":{"id":999,"pc":{"uid":88}}}]}""") }
        val song = backend.songs(owner).songs.single()
        assertEquals(com.ljyh.mei.data.model.SongSourceIdentity(999, 88, 7, 17), song.source)
    }

    @Test fun missingAudioAndInvalidOrConflictingOwnersCannotBecomeCatalogSources() = runTest {
        for (row in listOf(
            """{"songId":17}""", """{"songId":17,"simpleSong":{"id":0}}""",
            """{"songId":17,"userId":0,"simpleSong":{"id":999}}""",
            """{"songId":17,"simpleSong":{"id":999,"pc":{"uid":-1}}}""",
            """{"songId":17,"simpleSong":{"id":999,"pc":[]}}""",
            """{"songId":17,"simpleSong":{"id":999,"pc":88}}""",
            """{"songId":17,"userId":7,"simpleSong":{"id":999,"pc":{"uid":88}}}""",
        )) {
            reply = { json("""{"code":200,"count":1,"size":0,"maxSize":0,"data":[$row]}""") }
            assertTrue(runCatching { backend.songs(owner) }.isFailure)
        }
    }

    @Test fun malformedEnvelopesCannotBecomeEmptySuccess() = runTest {
        val valid = page(emptyList())
        for ((key, value) in listOf(
            "code" to "null", "code" to "200.5", "code" to "301", "count" to "null",
            "count" to "-1", "count" to "2147483648", "count" to "0.2", "data" to "{}",
            "data" to "null", "size" to "-1", "maxSize" to "null", "hasMore" to "\"false\"",
        )) {
            reply = { valid.deepCopy().apply { add(key, JsonParser.parseString(value)) } }
            assertTrue("$key=$value", runCatching { backend.songs(owner) }.isFailure)
        }
        for (key in listOf("code", "count", "data", "size", "maxSize")) {
            reply = { valid.deepCopy().apply { remove(key) } }
            assertTrue(key, runCatching { backend.songs(owner) }.isFailure)
        }
    }

    @Test fun invalidRowsAreRejectedInsteadOfSilentlyDroppingPrivateSongs() = runTest {
        for (row in listOf("null", "{}", """{"songId":0}""", """{"songId":-1}""",
            """{"simpleSong":{"id":999}}""", """{"songId":1.5}""")) {
            reply = { json("""{"code":200,"count":1,"size":0,"maxSize":0,"data":[$row]}""") }
            assertTrue(row, runCatching { backend.songs(owner) }.isFailure)
        }
    }

    @Test fun inconsistentContinuationCannotSilentlyTruncateTheLibrary() = runTest {
        for (response in listOf(page(emptyList(), 1, true), page(listOf(1), 2, false),
            page(listOf(1), 1, true), page(listOf(1, 2), 1), page((1L..201L).toList(), 201))) {
            val before = calls.size
            reply = { response }
            assertTrue(runCatching { backend.songs(owner) }.isFailure)
            assertEquals(before + 1, calls.size)
        }
    }

    @Test fun duplicatesAndChangedCountsAbortWithoutRetryOrPartialSnapshot() = runTest {
        for (second in listOf(page(listOf(1), 2), page(listOf(2), 3), page(emptyList(), 2, false))) {
            calls.clear()
            reply = { if (it["offset"] == 0) page(listOf(1), 2) else second }
            assertTrue(runCatching { backend.songs(owner) }.isFailure)
            assertEquals(2, calls.size)
        }
    }

    @Test fun deleteUsesOneExplicitCloudIdAndRequiresAnAcceptedBusinessCode() = runTest {
        reply = { json("""{"code":200}""") }
        backend.delete(owner, 17)
        assertEquals(Triple("/api/cloud/del", mapOf("songIds" to listOf(17L)), owner), calls.single())
        for (response in listOf("{}", """{"code":500}""", """{"code":200.5}""")) {
            reply = { json(response) }
            val before = calls.size
            assertTrue(runCatching { backend.delete(owner, 17) }.isFailure)
            assertEquals(before + 1, calls.size)
        }
        val before = calls.size
        for (id in listOf(0L, -1L)) assertTrue(runCatching { backend.delete(owner, id) }.isFailure)
        assertEquals(before, calls.size)
    }

    @Test fun guestsStaleOwnersAndRecoveryNeverDispatch() = runTest {
        for (guest in listOf(SessionIdentity(0, false, true), SessionIdentity(7, true, true), SessionIdentity(0, true, false))) {
            identity = guest
            assertTrue(runCatching { backend.songs(sessions.snapshot()) }.isFailure)
            assertTrue(runCatching { backend.delete(sessions.snapshot(), 17) }.isFailure)
        }
        identity = owner.identity
        sessions.setRecoveryRequired(true)
        assertTrue(runCatching { backend.songs(owner) }.isFailure)
        assertTrue(runCatching { backend.delete(owner, 17) }.isFailure)
        sessions.setRecoveryRequired(false)
        sessions.invalidate()
        assertTrue(runCatching { backend.songs(owner) }.exceptionOrNull() is SessionChangedException)
        assertTrue(runCatching { backend.delete(owner, 17) }.exceptionOrNull() is SessionChangedException)
        assertTrue(calls.isEmpty())
    }

    @Test fun invalidationAfterFirstPageStopsPagingAndRejectsLateDeletion() = runTest {
        reply = { sessions.invalidate(); page(listOf(1), 2) }
        assertTrue(runCatching { backend.songs(owner) }.exceptionOrNull() is SessionChangedException)
        assertEquals(1, calls.size)
        reply = { sessions.invalidate(); json("""{"code":200}""") }
        assertTrue(runCatching { backend.delete(sessions.snapshot(), 17) }.exceptionOrNull() is SessionChangedException)
        assertEquals(2, calls.size)
    }

    @Test fun canceledNonCooperativePageDoesNotContinueOrPublish() = runTest {
        val late = CompletableDeferred<JsonObject>()
        reply = { withContext(NonCancellable) { late.await() } }
        val result = async { runCatching { backend.songs(owner) } }
        runCurrent()
        result.cancel()
        late.complete(page(listOf(1), 2))
        runCurrent()
        assertTrue(result.isCancelled)
        assertEquals(1, calls.size)
        reply = { throw CancellationException("Canceled request") }
        assertTrue(runCatching { backend.delete(owner, 17) }.exceptionOrNull() is CancellationException)
    }

    private fun json(text: String) = JsonParser.parseString(text).asJsonObject
    private fun page(ids: List<Long>, count: Int = ids.size, more: Boolean? = null): JsonObject = json(
        """{"code":200,"count":$count,"size":20,"maxSize":100,"data":[${ids.joinToString { "{\"songId\":$it,\"simpleSong\":{\"id\":$it}}" }}]}"""
    ).apply { more?.let { addProperty("hasMore", it) } }
}
