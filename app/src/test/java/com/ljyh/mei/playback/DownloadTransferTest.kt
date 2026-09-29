package com.ljyh.mei.playback

import com.ljyh.mei.data.model.DownloadSource
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class DownloadTransferTest {
    private val source = DownloadSource(1, "https://media.example.test/song", "mp3", "standard", "standard",
        4, "8d777f385d3dfec8815d20f7496026dc", null)

    private class FakeCall(private val body: ResponseBody, private val cancelAction: () -> Unit = {}) :
        Call by OkHttpClient().newCall(Request.Builder().url("https://media.example.test/song").build()) {
        val canceled = AtomicBoolean()
        override fun request() = Request.Builder().url("https://media.example.test/song").build()
        override fun execute(): Response = Response.Builder().request(request()).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body(body).build()
        override fun cancel() { canceled.set(true); cancelAction() }
        override fun isCanceled() = canceled.get()
        override fun isExecuted() = true
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = error("Unused")
        override fun enqueue(responseCallback: Callback) = error("Unused")
    }

    @Test fun verifiesOriginalBytesBeforeReportingCompletion() = runBlocking {
        val file = File.createTempFile("meilox-transfer-test", ".mp3")
        try {
            val progress = mutableListOf<Int>()
            var checks = 0
            transferOfficialDownload({ FakeCall("data".toResponseBody()) }, source, file, { checks++ }, { progress += it })
            assertEquals("data", file.readText())
            assertTrue(checks >= 3)
            assertEquals(listOf(99), progress)
        } finally { file.delete() }
    }

    @Test fun rejectsCorruptionTruncationAndAnExpiredGrant() = runBlocking {
        val file = File.createTempFile("meilox-transfer-test", ".mp3")
        try {
            for (candidate in listOf(source.copy(md5 = "0".repeat(32)), source.copy(size = 5), source.copy(expiresAtMs = 1))) {
                assertTrue(runCatching {
                    transferOfficialDownload({ FakeCall("data".toResponseBody()) }, candidate, file, {}, {})
                }.exceptionOrNull() is IOException)
            }
        } finally { file.delete() }
    }

    @Test fun staleOwnerStopsBeforeOpeningTheMediaConnection() = runBlocking {
        val file = File.createTempFile("meilox-transfer-test", ".mp3")
        try {
            var opened = false
            assertTrue(runCatching {
                transferOfficialDownload({ opened = true; FakeCall("data".toResponseBody()) }, source, file,
                    { throw IOException("Session changed") }, {})
            }.isFailure)
            assertFalse(opened)
        } finally { file.delete() }
    }

    @Test fun cancellationClosesABlockedSocketWithoutWaitingForReadTimeout() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicBoolean()
        val blocked = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                throw IOException("Canceled socket")
            }
            override fun timeout() = Timeout.NONE
            override fun close() { closed.set(true) }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source() = blocked
        }
        val call = FakeCall(body) { release.countDown() }
        val file = File.createTempFile("meilox-transfer-test", ".mp3")
        try {
            val job = launch(Dispatchers.Default) { transferOfficialDownload({ call }, source, file, {}, {}) }
            assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(call.canceled.get())
            assertTrue(closed.get())
        } finally { release.countDown(); file.delete() }
    }
}
