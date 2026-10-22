package com.ljyh.mei.data.repository

import com.google.gson.JsonParser
import com.ljyh.mei.data.network.NETEASE_EXPECTED_SESSION_HEADER
import com.ljyh.mei.data.network.QQMusicUApiService
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.neteaseSessionFingerprint
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.EApiService
import com.ljyh.mei.data.network.api.WeApiService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class PlayerRepositoryTest {
    private val expectedSession = "a".repeat(64)

    @Test
    fun unknownLikeIsReadBeforeTogglingAndOnlySuccessfulWritesChangeSharedState() = runBlocking {
        var responseCode = 200
        val fixture = Fixture { request ->
            if (request.url.encodedPath.contains("song/like")) {
                """{"code":200,"ids":[123]}"""
            } else {
                """{"code":$responseCode,"songs":[],"playlistId":1}"""
            }
        }

        assertEquals(Resource.Success(false), fixture.repository.toggleLike("123", expectedSession))
        assertEquals(mapOf("123" to false), fixture.repository.songLikes.value)
        assertEquals(2, fixture.requests.size)
        fixture.requests.forEach { assertEquals(expectedSession, it.header(NETEASE_EXPECTED_SESSION_HEADER)) }
        assertFalse(fixture.body(1)["like"].asBoolean)

        responseCode = 301
        assertTrue(fixture.repository.toggleLike("123", expectedSession) is Resource.Error)
        assertEquals(mapOf("123" to false), fixture.repository.songLikes.value)
        assertEquals(3, fixture.requests.size)
    }

    @Test
    fun rejectedStatusReadsNeverInventAnUnlikedSongOrReachTheWriteEndpoint() = runBlocking {
        val fixture = Fixture { """{"code":301,"ids":[]}""" }

        assertTrue(fixture.repository.checkSongLike(123, expectedSession) is Resource.Error)
        assertTrue(fixture.repository.toggleLike("123", expectedSession) is Resource.Error)
        assertTrue(fixture.repository.songLikes.value.isEmpty())
        assertEquals(2, fixture.requests.size)
        assertTrue(fixture.requests.all { it.url.encodedPath.contains("song/like") })
    }

    @Test
    fun rapidTogglesWaitForTheConfirmedResultAndUseOppositeTargets() = runBlocking {
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CountDownLatch(1)
        var writes = 0
        val fixture = Fixture { request ->
            if (request.url.encodedPath.contains("song/like")) {
                """{"code":200,"ids":[]}"""
            } else {
                if (++writes == 1) {
                    writeStarted.complete(Unit)
                    check(releaseWrite.await(5, TimeUnit.SECONDS))
                }
                """{"code":200,"songs":[],"playlistId":1}"""
            }
        }
        fixture.repository.checkSongLike(123, expectedSession)
        val first = async { fixture.repository.toggleLike("123", expectedSession) }
        writeStarted.await()
        try {
            val second = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.toggleLike("123", expectedSession) }
            assertEquals(2, fixture.requests.size)
            releaseWrite.countDown()
            assertEquals(Resource.Success(true), first.await())
            assertEquals(Resource.Success(false), second.await())
            assertEquals(listOf(true, false), listOf(1, 2).map { fixture.body(it)["like"].asBoolean })
            assertEquals(mapOf("123" to false), fixture.repository.songLikes.value)
        } finally {
            releaseWrite.countDown()
        }
    }

    @Test
    fun accountResetRejectsOldReadsAndQueuedActionsWithoutRepopulatingState() = runBlocking {
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CountDownLatch(1)
        val fixture = Fixture {
            readStarted.complete(Unit)
            check(releaseRead.await(5, TimeUnit.SECONDS))
            """{"code":200,"ids":[123]}"""
        }
        val read = async { fixture.repository.checkSongLike(123, expectedSession) }
        readStarted.await()
        try {
            val queuedToggle = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.toggleLike("123", expectedSession) }
            assertEquals(0L, fixture.repository.songLikeGeneration.value)
            fixture.repository.resetLikeState()
            assertEquals(1L, fixture.repository.songLikeGeneration.value)
            releaseRead.countDown()

            assertTrue(read.await() is Resource.Error)
            assertTrue(queuedToggle.await() is Resource.Error)
            assertTrue(fixture.repository.songLikes.value.isEmpty())
            assertEquals(1, fixture.requests.size)
        } finally {
            releaseRead.countDown()
        }
    }

    @Test
    fun accountResetAlsoDiscardsAnAlreadyRunningWriteResult() = runBlocking {
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CountDownLatch(1)
        val fixture = Fixture { request ->
            if (request.url.encodedPath.contains("song/like")) {
                """{"code":200,"ids":[]}"""
            } else {
                writeStarted.complete(Unit)
                check(releaseWrite.await(5, TimeUnit.SECONDS))
                """{"code":200,"songs":[],"playlistId":1}"""
            }
        }
        fixture.repository.checkSongLike(123, expectedSession)
        val toggle = async { fixture.repository.toggleLike("123", expectedSession) }
        writeStarted.await()
        try {
            assertEquals(0L, fixture.repository.songLikeGeneration.value)
            fixture.repository.resetLikeState()
            assertEquals(1L, fixture.repository.songLikeGeneration.value)
            releaseWrite.countDown()

            assertTrue(toggle.await() is Resource.Error)
            assertTrue(fixture.repository.songLikes.value.isEmpty())
        } finally {
            releaseWrite.countDown()
        }
    }

    @Test
    fun batchChecksOnlyUnknownPositiveIdsAndCachesBothResults() = runBlocking {
        val fixture = Fixture { """{"code":200,"ids":[1,101,201,999]}""" }

        assertEquals(
            Resource.Success(Unit),
            fixture.repository.ensureSongLikes((1L..201L).toSet() + setOf(0L, -1L), expectedSession),
        )
        val batches = fixture.requests.indices.map { index ->
            JsonParser.parseString(fixture.body(index)["trackIds"].asString).asJsonArray.map { it.asLong }
        }
        assertEquals(listOf(100, 100, 1), batches.map { it.size })
        assertEquals((1L..201L).toList(), batches.flatten())
        assertEquals(201, fixture.repository.songLikes.value.size)
        assertEquals(setOf("1", "101", "201"), fixture.repository.songLikes.value.filterValues { it }.keys)
        assertEquals(false, fixture.repository.songLikes.value["2"])

        assertEquals(Resource.Success(Unit), fixture.repository.ensureSongLikes(setOf(2L, 201L, 202L), expectedSession))
        assertEquals(4, fixture.requests.size)
        assertEquals(listOf(202L), JsonParser.parseString(fixture.body(3)["trackIds"].asString).asJsonArray.map { it.asLong })
        assertEquals(false, fixture.repository.songLikes.value["202"])
        assertEquals(0L, fixture.repository.songLikeGeneration.value)
        fixture.requests.forEach { assertEquals(expectedSession, it.header(NETEASE_EXPECTED_SESSION_HEADER)) }
    }

    @Test
    fun rejectedBatchKeepsKnownStateAndLeavesMissingIdsAvailableForRetry() = runBlocking {
        var responseCode = 200
        val fixture = Fixture { """{"code":$responseCode,"ids":[1]}""" }
        fixture.repository.ensureSongLikes(setOf(1L), expectedSession)

        responseCode = 301
        assertTrue(fixture.repository.ensureSongLikes(setOf(1L, 2L, 3L), expectedSession) is Resource.Error)
        assertEquals(mapOf("1" to true), fixture.repository.songLikes.value)

        responseCode = 200
        assertEquals(Resource.Success(Unit), fixture.repository.ensureSongLikes(setOf(2L, 3L), expectedSession))
        assertEquals(mapOf("1" to true, "2" to false, "3" to false), fixture.repository.songLikes.value)
        assertEquals(3, fixture.requests.size)
        assertEquals(listOf(2L, 3L), JsonParser.parseString(fixture.body(2)["trackIds"].asString).asJsonArray.map { it.asLong })
    }

    @Test
    fun accountResetRejectsInFlightAndQueuedBatches() = runBlocking {
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CountDownLatch(1)
        val fixture = Fixture {
            readStarted.complete(Unit)
            check(releaseRead.await(5, TimeUnit.SECONDS))
            """{"code":200,"ids":[123]}"""
        }
        val read = async { fixture.repository.ensureSongLikes(setOf(123L, 456L), expectedSession) }
        readStarted.await()
        try {
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.repository.ensureSongLikes(setOf(789L), expectedSession)
            }
            assertEquals(0L, fixture.repository.songLikeGeneration.value)
            fixture.repository.resetLikeState()
            assertEquals(1L, fixture.repository.songLikeGeneration.value)
            releaseRead.countDown()

            assertTrue(read.await() is Resource.Error)
            assertTrue(queued.await() is Resource.Error)
            assertTrue(fixture.repository.songLikes.value.isEmpty())
            assertEquals(1, fixture.requests.size)
        } finally {
            releaseRead.countDown()
        }
    }

    @Test
    fun cancelledBatchDoesNotPublishAndReleasesTheLikeMutex() = runBlocking {
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CountDownLatch(1)
        val fixture = Fixture {
            readStarted.complete(Unit)
            check(releaseRead.await(5, TimeUnit.SECONDS))
            """{"code":200,"ids":[123]}"""
        }
        val read = async { fixture.repository.ensureSongLikes(setOf(123L), expectedSession) }
        readStarted.await()
        try {
            read.cancel()
            releaseRead.countDown()
            read.join()
            assertTrue(read.isCancelled)
            assertTrue(fixture.repository.songLikes.value.isEmpty())

            assertEquals(Resource.Success(Unit), fixture.repository.ensureSongLikes(setOf(456L), expectedSession))
            assertEquals(mapOf("456" to false), fixture.repository.songLikes.value)
        } finally {
            releaseRead.countDown()
        }
    }

    @Test
    fun playlistMutationsRefreshCachedLikesWithoutStartingPlayback() = runBlocking {
        var serverLiked = false
        var mutationCode = 200
        val fixture = Fixture { request ->
            if (request.url.encodedPath == "/api/song/like/check") {
                """{"code":200,"ids":[${if (serverLiked) "123" else ""}]}"""
            } else {
                val body = JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject
                if (mutationCode == 200 && body["pid"].asString == "42") {
                    serverLiked = body["op"].asString == "add"
                }
                """{"code":$mutationCode,"count":1,"cloudCount":0,"trackIds":"123"}"""
            }
        }
        val session = neteaseSessionFingerprint("test-session")
        val playlists = fixture.playlistRepository()
        fixture.repository.ensureSongLikes(setOf(123L), session)
        assertEquals(false, fixture.repository.songLikes.value["123"])

        assertEquals(200, (playlists.manipulateTrack("add", "42", "123") as Resource.Success).data.code)
        assertEquals(true, fixture.repository.songLikes.value["123"])
        assertEquals(200, (playlists.manipulateTrack("del", "42", "123") as Resource.Success).data.code)
        assertEquals(false, fixture.repository.songLikes.value["123"])
        assertEquals(200, (playlists.manipulateTrack("add", "99", "123") as Resource.Success).data.code)
        assertEquals(false, fixture.repository.songLikes.value["123"])

        serverLiked = true
        mutationCode = 502
        assertEquals(502, (playlists.manipulateTrack("add", "42", "123") as Resource.Success).data.code)
        assertEquals(true, fixture.repository.songLikes.value["123"])
        fixture.requests.forEach { assertEquals(session, it.header(NETEASE_EXPECTED_SESSION_HEADER)) }
    }

    @Test
    fun failedPlaylistMutationDoesNotRefreshOrInventLikes() = runBlocking {
        val fixture = Fixture { request ->
            if (request.url.encodedPath == "/api/song/like/check") """{"code":200,"ids":[]}"""
            else """{"code":301,"count":0,"cloudCount":0,"trackIds":"123"}"""
        }
        fixture.repository.ensureSongLikes(setOf(123L), neteaseSessionFingerprint("test-session"))

        assertEquals(301, (fixture.playlistRepository().manipulateTrack("add", "42", "123") as Resource.Success).data.code)
        assertEquals(mapOf("123" to false), fixture.repository.songLikes.value)
        assertEquals(0L, fixture.repository.songLikeGeneration.value)
        assertEquals(2, fixture.requests.size)
    }

    @Test
    fun failedLikeRefreshPreservesSuccessfulMutationAndMakesOnlyAffectedIdsRetryable() = runBlocking {
        var readCode = 200
        var serverLiked = false
        val fixture = Fixture { request ->
            if (request.url.encodedPath == "/api/song/like/check") {
                """{"code":$readCode,"ids":[${if (serverLiked) "123" else ""}]}"""
            } else {
                serverLiked = true
                """{"code":200,"count":1,"cloudCount":0,"trackIds":"123"}"""
            }
        }
        val session = neteaseSessionFingerprint("test-session")
        fixture.repository.ensureSongLikes(setOf(123L, 456L), session)
        readCode = 301

        assertEquals(200, (fixture.playlistRepository().manipulateTrack("add", "42", "123") as Resource.Success).data.code)
        assertEquals(mapOf("456" to false), fixture.repository.songLikes.value)
        assertEquals(1L, fixture.repository.songLikeGeneration.value)

        readCode = 200
        assertEquals(Resource.Success(Unit), fixture.repository.ensureSongLikes(setOf(123L), session))
        assertEquals(mapOf("123" to true, "456" to false), fixture.repository.songLikes.value)
        fixture.repository.resetLikeState()
        assertEquals(2L, fixture.repository.songLikeGeneration.value)
    }

    @Test
    fun confirmedPlaylistMutationFinishesLikeRefreshAfterCallerCancellation() = runBlocking {
        var serverLiked = false
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CountDownLatch(1)
        val fixture = Fixture { request ->
            if (request.url.encodedPath == "/api/song/like/check") {
                if (serverLiked) {
                    refreshStarted.complete(Unit)
                    check(releaseRefresh.await(5, TimeUnit.SECONDS))
                }
                """{"code":200,"ids":[${if (serverLiked) "123" else ""}]}"""
            } else {
                serverLiked = true
                """{"code":200,"count":1,"cloudCount":0,"trackIds":"123"}"""
            }
        }
        val session = neteaseSessionFingerprint("test-session")
        fixture.repository.ensureSongLikes(setOf(123L), session)
        assertEquals(false, fixture.repository.songLikes.value["123"])

        val write = async { fixture.playlistRepository().manipulateTrack("add", "42", "123") }
        refreshStarted.await()
        try {
            write.cancel()
            releaseRefresh.countDown()
            write.join()

            assertTrue(write.isCancelled)
            assertEquals(true, fixture.repository.songLikes.value["123"])
            assertEquals(Resource.Success(Unit), fixture.repository.ensureSongLikes(setOf(123L), session))
            assertEquals(3, fixture.requests.size)
        } finally {
            releaseRefresh.countDown()
        }
    }

    @Test
    fun accountSwitchDuringPlaylistMutationRejectsTheOldResultBeforeRefreshingLikes() = runBlocking {
        var account = "test-session"
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CountDownLatch(1)
        val fixture = Fixture { request ->
            if (request.url.encodedPath == "/api/song/like/check") {
                """{"code":200,"ids":[]}"""
            } else {
                writeStarted.complete(Unit)
                check(releaseWrite.await(5, TimeUnit.SECONDS))
                """{"code":200,"count":1,"cloudCount":0,"trackIds":"123"}"""
            }
        }
        val session = neteaseSessionFingerprint(account)
        fixture.repository.ensureSongLikes(setOf(123L), session)
        val playlists = fixture.playlistRepository { account }
        val write = async { playlists.manipulateTrack("add", "42", "123") }
        writeStarted.await()
        try {
            account = "replacement-session"
            fixture.repository.resetLikeState()
            releaseWrite.countDown()

            assertTrue(write.await() is Resource.Error)
            assertTrue(fixture.repository.songLikes.value.isEmpty())
            assertEquals(2, fixture.requests.size)
            assertEquals(session, fixture.requests.last().header(NETEASE_EXPECTED_SESSION_HEADER))
        } finally {
            releaseWrite.countDown()
        }
    }

    private class Fixture(response: (Request) -> String) {
        val requests = CopyOnWriteArrayList<Request>()
        private val retrofit = Retrofit.Builder()
            .baseUrl("https://example.test/")
            .client(OkHttpClient.Builder().addInterceptor { chain ->
                requests += chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("synthetic")
                    .body(response(chain.request()).toResponseBody("application/json".toMediaType()))
                    .build()
            }.build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val repository = PlayerRepository(
            retrofit.create(QQMusicUApiService::class.java),
            retrofit.create(ApiService::class.java),
            retrofit.create(WeApiService::class.java),
        )

        fun playlistRepository(account: () -> String = { "test-session" }) = PlaylistRepository(
            retrofit.create(ApiService::class.java),
            retrofit.create(WeApiService::class.java),
            retrofit.create(EApiService::class.java),
            freshCheckToken = { "unused" },
            subscriptionAccount = account,
            onTracksChanged = { ids, session -> repository.refreshSongLikes(ids, session) },
        )

        fun body(index: Int) = JsonParser.parseString(
            Buffer().also { requests[index].body!!.writeTo(it) }.readUtf8(),
        ).asJsonObject
    }
}
