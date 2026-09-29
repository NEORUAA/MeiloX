package com.ljyh.mei.standalone

import com.ljyh.mei.data.repository.CloudUploadAuthorization
import com.ljyh.mei.data.repository.CloudUploadFile
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStore
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StandaloneCloudBinaryUploaderTest {
    @get:Rule val temp = TemporaryFolder()
    private val sessions = SessionStore().apply { bind { SessionIdentity(7, true, false) } }
    private val owner = sessions.snapshot()
    private val authorization = CloudUploadAuthorization("bucket", "folder/file.mp3", "fixture-token")
    private val requests = mutableListOf<Request>()
    private val bytes = mutableListOf<String>()
    private var host = "https://nosup-hz1.127.net"
    private var lookup: String? = null
    private var receipt = """{"offset":3,"context":"fixture"}"""
    private var responseCode = 200
    private var onRequest: (Request) -> Unit = {}
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).addInterceptor { chain ->
            val request = chain.request()
            requests += request
            onRequest(request)
            request.body?.let { body -> bytes += Buffer().also { body.writeTo(it) }.readUtf8() }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(responseCode).message("synthetic")
                .body((if (request.method == "GET") lookup ?: """{"upload":["$host"]}""" else receipt).toResponseBody()).build()
        }.build()
    private val source = StandaloneCloudBinaryUploader(sessions, client)
    private fun file(): CloudUploadFile {
        val file = temp.newFile().apply { writeText("abc") }
        return CloudUploadFile(file, "Test.mp3", "mp3", "Test", 3, "900150983cd24fb0d6963f7d28e17f72", "Test", "Artist", "Album", "audio/mpeg")
    }

    @Test fun originalNosFlowSendsOnlyUploadAuthorizationAndExactBytes() = runBlocking {
        val progress = mutableListOf<Pair<Long, Long>>()
        source.upload(file(), authorization, owner) { sent, total -> progress += sent to total }
        assertEquals(listOf("GET", "POST"), requests.map { it.method })
        assertEquals("wanproxy.127.net", requests[0].url.host)
        assertEquals("bucket", requests[0].url.queryParameter("bucketname"))
        assertEquals("/bucket/folder%2Ffile.mp3", requests[1].url.encodedPath)
        assertEquals("fixture-token", requests[1].header("x-nos-token"))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", requests[1].header("Content-MD5"))
        assertEquals(listOf("abc"), bytes)
        assertEquals(listOf(3L to 3L), progress)
        assertTrue(requests.all { it.header("Cookie") == null && it.header("Authorization") == null })
    }

    @Test fun untrustedLookupDestinationsNeverReceiveTokenOrBytes() = runBlocking {
        for (candidate in listOf("http://nosup-hz1.127.net", "https://127.net.invalid", "https://example.com",
            "https://u:p@nosup-hz1.127.net", "https://nosup-hz1.127.net:444", "https://nosup-hz1.127.net/path",
            "https://nosup-hz1.127.net/?token=x", "https://nosup-hz1.127.net/#x")) {
            host = candidate
            assertTrue(runCatching { source.upload(file(), authorization, owner) { _, _ -> } }.isFailure)
        }
        assertTrue(requests.all { it.method == "GET" && it.header("x-nos-token") == null })
        assertTrue(bytes.isEmpty())
    }

    @Test fun lookupFailuresAndOversizeBodiesNeverTransfer() = runBlocking {
        for (body in listOf("not-json", "{}", """{"upload":[]}""", "x".repeat(65_537))) {
            lookup = body
            assertTrue(runCatching { source.upload(file(), authorization, owner) { _, _ -> } }.isFailure)
        }
        lookup = null
        responseCode = 302
        assertTrue(runCatching { source.upload(file(), authorization, owner) { _, _ -> } }.isFailure)
        assertTrue(requests.all { it.method == "GET" })
    }

    @Test fun staleOwnersAndInvalidatedLookupCannotUpload() = runBlocking {
        sessions.invalidate()
        assertTrue(runCatching { source.upload(file(), authorization, owner) { _, _ -> } }.isFailure)
        assertTrue(requests.isEmpty())
        onRequest = { sessions.invalidate() }
        assertTrue(runCatching { source.upload(file(), authorization, sessions.snapshot()) { _, _ -> } }.isFailure)
        assertEquals(1, requests.size)
    }

    @Test fun changedFilesAndInvalidatedProgressNeverReturnSuccess() = runBlocking {
        val changed = file().apply { file.writeText("xyz") }
        assertTrue(runCatching { source.upload(changed, authorization, owner) { _, _ -> } }.isFailure)
        assertTrue(runCatching { source.upload(file(), authorization, owner) { _, _ -> sessions.invalidate() } }.isFailure)
    }

    @Test fun httpFailureDoesNotRepeatTheTransfer() = runBlocking {
        onRequest = { if (it.method == "POST") responseCode = 503 }
        assertTrue(runCatching { source.upload(file(), authorization, owner) { _, _ -> } }.isFailure)
        assertEquals(1, requests.count { it.method == "POST" })
    }

    @Test fun incompleteOrMalformedReceiptsAreNotSuccessfulTransfers() = runBlocking {
        for (reply in listOf("{}", "not-json", """{"offset":null}""", """{"offset":2}""",
            """{"offset":4}""", """{"offset":"bad"}""", "x".repeat(65_537))) {
            receipt = reply
            val before = requests.size
            assertTrue(runCatching { source.upload(file(), authorization, owner) { _, _ -> } }.isFailure)
            assertEquals(2, requests.size - before)
        }
    }

    @Test fun cancellationClosesABlockedCall() = runBlocking {
        val entered = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val held = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { canceled.countDown() }
        }).addInterceptor {
            entered.countDown()
            check(canceled.await(5, TimeUnit.SECONDS)) { "Call was not canceled" }
            throw IOException("Canceled fixture")
        }.build()
        val input = file()
        val operation = async(Dispatchers.IO) { StandaloneCloudBinaryUploader(sessions, held).upload(input, authorization, owner) { _, _ -> } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        operation.cancelAndJoin()
        assertEquals(0L, canceled.count)
    }

    @Test fun sessionInvalidationClosesABlockedCall() = runBlocking {
        val entered = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val held = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { canceled.countDown() }
        }).addInterceptor {
            entered.countDown()
            check(canceled.await(5, TimeUnit.SECONDS)) { "Call was not canceled" }
            throw IOException("Canceled fixture")
        }.build()
        val input = file()
        val operation = async(Dispatchers.IO) { runCatching { StandaloneCloudBinaryUploader(sessions, held).upload(input, authorization, owner) { _, _ -> } } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        sessions.invalidate()
        assertTrue(operation.await().isFailure)
        assertEquals(0L, canceled.count)
    }
}
