package com.ljyh.mei.data.network.netease

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NeteaseClientLogClientTest {
    @Test
    fun postsEncryptedMultipartToNcblEndpointAndRequiresTheUploadedFileToBeAccepted() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val result = client { request ->
            requests += request
            val body = multipartBody(request)
            val fileName = Regex("filename=\"([^\"]+)\"")
                .find(body)
                ?.groupValues
                ?.get(1)
            assertTrue(body.contains("name=\"file\""))
            assertTrue(body.contains("Content-Type: multipart/form-data"))
            assertTrue(fileName.orEmpty().matches(Regex("flush_ua_[0-9]+_[0-9]+_[0-9]+")))
            okResponse(
                request,
                200,
                """{"code":200,"data":{"successfiles":["$fileName"]}}""",
            )
        }.submitStart(session(), eventTimeMs = 1_790_006_390_987L)

        assertTrue(result.fileAccepted)
        assertEquals(200, result.httpStatus)
        assertEquals(200, result.businessCode)
        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("clientlogsf.music.163.com", request.url.host)
        assertEquals("/api/clientlog/encrypt/upload", request.url.encodedPath)
        assertEquals("true", request.url.queryParameter("multiupload"))
        assertEquals("android", request.header("X-Os"))
        assertEquals("DEVICE123", request.header("X-DeviceId"))
        assertEquals("8.20.20.231215173437", request.header("User-Agent")
            ?.substringAfter("NeteaseMusic/")
            ?.substringBefore('('))
        assertTrue(request.header("Cookie").orEmpty().contains("deviceId=DEVICE123"))
        assertTrue(request.header("Cookie").orEmpty().contains("MUSIC_U=token-not-for-logs"))
        assertNull(request.header("Mconfig-Info"))
    }

    @Test
    fun doesNotTreatRateResponseOrUnlistedFileAsAccepted() = runBlocking {
        val missingFile = client { request ->
            okResponse(request, 200, """{"code":200,"data":{"successfiles":["other"]}}""")
        }.submitStart(session(), eventTimeMs = 1_790_006_390_987L)
        assertFalse(missingFile.fileAccepted)
        assertEquals(200, missingFile.httpStatus)
        assertEquals(200, missingFile.businessCode)

        val rateLimited = client { request ->
            okResponse(request, 200, """{"code":200,"data":{"rate":1,"successfiles":[]}}""")
        }.submitStart(session(), eventTimeMs = 1_790_006_390_987L)
        assertFalse(rateLimited.fileAccepted)
        assertEquals(200, rateLimited.businessCode)
    }

    @Test
    fun failsClosedForNonJsonWrongBusinessCodeNon2xxAndOversizedResponse() = runBlocking {
        val malformed = client { request -> okResponse(request, 200, "not-json") }
            .submitStart(session(), eventTimeMs = 1_790_006_390_987L)
        assertFalse(malformed.fileAccepted)
        assertEquals(200, malformed.httpStatus)
        assertNull(malformed.businessCode)

        val stringCode = client { request ->
            val name = fileName(request)
            okResponse(request, 200, """{"code":"200","data":{"successfiles":["$name"]}}""")
        }.submitStart(session(), eventTimeMs = 1_790_006_390_987L)
        assertFalse(stringCode.fileAccepted)
        assertNull(stringCode.businessCode)

        val httpError = client { request ->
            val name = fileName(request)
            okResponse(request, 503, """{"code":200,"data":{"successfiles":["$name"]}}""")
        }.submitStart(session(), eventTimeMs = 1_790_006_390_987L)
        assertFalse(httpError.fileAccepted)
        assertEquals(503, httpError.httpStatus)
        assertEquals(200, httpError.businessCode)

        val tooLarge = client { request ->
            val name = fileName(request)
            okResponse(
                request,
                200,
                """{"code":200,"data":{"successfiles":["$name"]},"padding":"${"x".repeat(NCBL_MAX_RESPONSE_BYTES)}"}""",
            )
        }.submitStart(session(), eventTimeMs = 1_790_006_390_987L)
        assertFalse(tooLarge.fileAccepted)
        assertEquals(200, tooLarge.httpStatus)
        assertNull(tooLarge.businessCode)
    }

    @Test
    fun reportsTransportFailureWithoutLeakingExceptionOrMarkingFileAccepted() = runBlocking {
        val result = client { throw IOException("private request detail") }
            .submitStart(session(), eventTimeMs = 1_790_006_390_987L)

        assertFalse(result.fileAccepted)
        assertNull(result.httpStatus)
        assertNull(result.businessCode)
    }

    @Test
    fun coroutineCancellationCancelsTheUnderlyingCall() = runBlocking {
        val intercepted = CountDownLatch(1)
        val continueRequest = CountDownLatch(1)
        var interceptedCall: Call? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                interceptedCall = chain.call()
                intercepted.countDown()
                continueRequest.await(2, TimeUnit.SECONDS)
                okResponse(chain.request(), 200, "{}")
            })
            .build()
        val logClient = NeteaseClientLogClient(
            client,
            NcblSessionContextProvider { _, _, _, _ -> session() },
        )

        val request = async(Dispatchers.IO) {
            logClient.submitStart(session(), eventTimeMs = 1_790_006_390_987L)
        }
        assertTrue(intercepted.await(2, TimeUnit.SECONDS))
        request.cancel()
        assertTrue(interceptedCall?.isCanceled() == true)
        continueRequest.countDown()
        request.join()
        assertTrue(request.isCancelled)
    }

    private fun client(respond: (Request) -> Response): NeteaseClientLogClient {
        val transport = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> respond(chain.request()) })
            .build()
        val provider = NcblSessionContextProvider { _, _, _, _ -> session() }
        return NeteaseClientLogClient(transport, provider)
    }

    private fun session() = NcblSessionContext(
        credentials = NcblCredentials("token-not-for-logs", "DEVICE123"),
        device = NcblDeviceInfo(
            deviceId = "DEVICE123",
            osVersion = "16",
            model = "Pixel 10 Pro",
            brand = "Google",
            processName = "com.neoruaa.meilox",
            buildType = "debug",
            pid = 42,
            buildId = "AP4A",
        ),
        profile = NcblClientProfile.Android,
        song = NcblSongInfo(123456L, "Track title", "Artist", 241_000L),
        source = "track",
        sourceId = "456",
        startedAtMs = 1_790_006_390_987L,
        buildVersion = "1790006390",
        sessionId = "test-session",
    )

    private fun okResponse(request: Request, code: Int, body: String) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    private fun multipartBody(request: Request): String = Buffer().apply {
        request.body!!.writeTo(this)
    }.readByteArray().toString(Charsets.ISO_8859_1)

    private fun fileName(request: Request): String =
        Regex("filename=\"([^\"]+)\"")
            .find(multipartBody(request))
            ?.groupValues
            ?.get(1)
            .orEmpty()
}
