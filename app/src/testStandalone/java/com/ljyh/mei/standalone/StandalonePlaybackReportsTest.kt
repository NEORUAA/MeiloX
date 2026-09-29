package com.ljyh.mei.standalone

import com.google.gson.JsonParser
import com.ljyh.mei.data.network.netease.NcblClientProfile
import com.ljyh.mei.data.network.netease.NcblCredentials
import com.ljyh.mei.data.network.netease.NcblDeviceInfo
import com.ljyh.mei.data.network.netease.NcblSessionContext
import com.ljyh.mei.data.network.netease.NcblSessionContextProvider
import com.ljyh.mei.data.network.netease.NcblSongInfo
import com.ljyh.mei.data.network.netease.NeteaseClientLogClient
import com.ljyh.mei.data.session.SessionCallFactory
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.di.NETEASE_EAPI_PROFILE_HEADER
import com.ljyh.mei.di.PLAYBACK_HISTORY_PROFILE
import com.ljyh.mei.playback.PlaybackReportDetails
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class StandalonePlaybackReportsTest {
    @Test fun startAndEndUseOriginalWeblogFieldsAndOneCapturedNcblContext() = runBlocking {
        val fixture = Fixture()
        fixture.submit("startplay")
        fixture.submit("play")

        assertEquals(2, fixture.webRequests.size)
        assertEquals(2, fixture.ncblRequests.size)
        val context = fixture.contexts.single()
        assertEquals(NcblSongInfo(123, "Original title", "Original artist", 240_000), context.song)
        assertEquals(1_000_123L, context.startedAtMs)
        assertEquals("list", context.source)
        assertEquals("456", context.sourceId)
        assertEquals(fixture.owner, context.owner)
        for (request in fixture.webRequests) {
            assertEquals(PLAYBACK_HISTORY_PROFILE, request.header(NETEASE_EAPI_PROFILE_HEADER))
            assertEquals("eapi", request.header("X-Netease-Crypto"))
            assertEquals(fixture.owner, request.tag(SessionStamp::class.java))
        }
        val end = weblogEvent(fixture.webRequests.last())
        assertEquals("play", end.get("action").asString)
        val fields = end.getAsJsonObject("json")
        assertEquals(1000L, fields.get("startlogtime").asLong)
        assertEquals(45L, fields.get("time").asLong)
        assertFalse(fields.has("title"))
        assertFalse(fields.has("durationMs"))
        assertTrue(fixture.ncblRequests.all { it.tag(SessionStamp::class.java) == fixture.owner })
        assertEquals(1, fixture.ncblRequests.map { it.header("Cookie") }.distinct().size)
        assertEquals(4, fixture.reports.count { it.endsWith("accepted=true") })
    }

    @Test fun failedWeblogDoesNotSuppressNcblStartOrEnd() = runBlocking {
        val fixture = Fixture(webResponse = { throw IOException("private server text") })
        fixture.submit("startplay")
        fixture.submit("play")
        assertEquals(2, fixture.ncblRequests.size)
        assertEquals(2, fixture.reports.count { it == "standalone_playback_report channel=ncbl accepted=true" })
        assertEquals(2, fixture.reports.count { it.endsWith("failed=IOException") })
        assertTrue(fixture.reports.none { it.contains("private server text") })
    }

    @Test fun failedNcblStartStillKeepsOriginalContextForEnd() = runBlocking {
        var uploads = 0
        val fixture = Fixture(ncblResponse = { request ->
            if (uploads++ == 0) response(request, """{"code":200,"data":{"successfiles":[]}}""")
            else acceptedUpload(request)
        })
        fixture.submit("startplay")
        fixture.submit("play")
        assertEquals(1, fixture.contexts.size)
        assertEquals(2, fixture.webRequests.size)
        assertEquals(2, fixture.ncblRequests.size)
        assertTrue(fixture.reports.contains("standalone_playback_report channel=ncbl accepted=false"))
        assertTrue(fixture.reports.contains("standalone_playback_report channel=ncbl accepted=true"))
    }

    @Test fun sameSongStartsInTheSameSecondKeepDistinctContexts() = runBlocking {
        val fixture = Fixture()
        val first = fixture.details
        val second = first.copy(startedAtMs = first.startedAtMs + 1, title = "New metadata")
        fixture.submit("startplay", first)
        fixture.submit("startplay", second)
        fixture.submit("play", first)
        fixture.submit("play", second)
        assertEquals(listOf(first.startedAtMs, second.startedAtMs), fixture.contexts.map { it.startedAtMs })
        val devices = fixture.ncblRequests.map { it.header("X-DeviceId") }
        assertNotEquals(devices[0], devices[1])
        assertEquals(devices[0], devices[2])
        assertEquals(devices[1], devices[3])
    }

    @Test fun reauthorizationRejectsOldEndAndDoesNotReuseItForNewGeneration() = runBlocking {
        val fixture = Fixture()
        fixture.submit("startplay")
        fixture.sessions.invalidate()
        try {
            fixture.submit("play")
            fail("Old owner must be rejected")
        } catch (_: SessionChangedException) { }
        fixture.submit("play", owner = fixture.sessions.snapshot())
        assertEquals(1, fixture.ncblRequests.size)
        fixture.submit("startplay", owner = fixture.sessions.snapshot())
        assertEquals(2, fixture.contexts.size)
        assertNotEquals(fixture.contexts[0].owner, fixture.contexts[1].owner)
    }

    @Test fun invalidationDuringContextCreationCannotPublishOrUploadOldCredentials() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = Fixture(beforeContext = { entered.complete(Unit); release.await() })
        val request = async { fixture.submit("startplay") }
        entered.await()
        fixture.sessions.invalidate()
        release.complete(Unit)
        request.await()
        assertTrue(fixture.ncblRequests.isEmpty())
        fixture.submit("play", owner = fixture.sessions.snapshot())
        assertTrue(fixture.ncblRequests.isEmpty())
    }

    @Test fun cancellationCancelsBothTransportsAndDropsStartContext() = runBlocking {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val calls = CopyOnWriteArrayList<Call>()
        val fixture = Fixture(beforeCall = { call ->
            calls += call
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        })
        val request = async(Dispatchers.IO) { fixture.submit("startplay") }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            request.cancelAndJoin()
            assertEquals(2, calls.size)
            assertTrue(calls.all { it.isCanceled() })
        } finally {
            release.countDown()
            request.cancelAndJoin()
        }
        fixture.submit("play")
        assertEquals(1, fixture.ncblRequests.size)
    }

    private class Fixture(
        webResponse: (Request) -> Response = { response(it, """{"code":200}""") },
        ncblResponse: (Request) -> Response = ::acceptedUpload,
        beforeContext: suspend () -> Unit = {},
        private val beforeCall: (Call) -> Unit = {},
    ) {
        val sessions = SessionStore().apply { bind { SessionIdentity(7, true, false) } }
        val owner = sessions.snapshot()
        val details = PlaybackReportDetails(1_000_123, "Original title", "Original artist", 240_000)
        val webRequests = CopyOnWriteArrayList<Request>()
        val ncblRequests = CopyOnWriteArrayList<Request>()
        val contexts = CopyOnWriteArrayList<NcblSessionContext>()
        val reports = CopyOnWriteArrayList<String>()
        private fun transport(requests: MutableList<Request>, respond: (Request) -> Response): Call.Factory =
            SessionCallFactory(sessions, OkHttpClient.Builder().addInterceptor { chain ->
                requests += chain.request()
                beforeCall(chain.call())
                respond(chain.request())
            }.build())
        val sink = StandalonePlaybackReports(
            sessions,
            transport(webRequests, webResponse),
            NeteaseClientLogClient(
                transport(ncblRequests, ncblResponse),
                NcblSessionContextProvider { song, source, sourceId, startedAtMs, owner ->
                    beforeContext()
                    context(song, source, sourceId, startedAtMs, owner).also { contexts += it }
                },
            ),
            reports::add,
        )

        suspend fun submit(action: String, details: PlaybackReportDetails = this.details, owner: SessionStamp = this.owner) {
            val fields = mapOf<String, Any>(
                "type" to "song", "id" to 123L, "source" to "list", "sourceId" to "456",
                "startlogtime" to details.startedAtMs / 1_000, "logtime" to details.startedAtMs + 50_000,
                "time" to if (action == "play") 45L else 0L,
            ) + if (action == "play") mapOf("end" to "ui") else emptyMap()
            sink.submit(action, fields, owner, details)
        }
    }

    private companion object {
        fun context(song: NcblSongInfo, source: String, sourceId: Long, startedAtMs: Long, owner: SessionStamp) =
            NcblSessionContext(
                owner, NcblCredentials("test-token", "device-$startedAtMs"),
                NcblDeviceInfo("device-$startedAtMs", "16", "Pixel", "Google", "test", "debug", 42, "test"),
                NcblClientProfile.Android, song, source, sourceId.toString(), startedAtMs,
                (startedAtMs / 1_000).toString(), "session-$startedAtMs",
            )

        fun response(request: Request, json: String) = Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(200).message("test")
            .body(json.toResponseBody("application/json".toMediaType())).build()

        fun acceptedUpload(request: Request): Response {
            val disposition = (request.body as MultipartBody).part(0).headers!!.get("Content-Disposition")!!
            val fileName = disposition.substringAfter("filename=\"").substringBefore('"')
            return response(request, """{"code":200,"data":{"successfiles":["$fileName"]}}""")
        }

        fun weblogEvent(request: Request) = JsonParser.parseString(
            JsonParser.parseString(Buffer().apply { request.body!!.writeTo(this) }.readUtf8())
                .asJsonObject.get("logs").asString,
        ).asJsonArray[0].asJsonObject
    }
}
