package com.ljyh.mei.data.repository

import com.ljyh.mei.source.platform.prepareCloudUploadFile
import com.ljyh.mei.source.platform.executeCloudUploadRequest

import android.net.Uri
import android.os.Bundle
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.test.mock.MockContentResolver
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import com.ljyh.mei.source.shared.OnlineCloudPage as CloudMusicPage
import com.ljyh.mei.source.shared.OnlineCloudSong as CloudSong
import com.ljyh.mei.ui.screen.cloud.CloudMusicViewModel
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudUploadInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val authority = "${instrumentation.context.packageName}.cloud-upload-fixture"
    // A real Android provider transport runs in the instrumentation process. Resolving an
    // exported provider in the separate test APK can invoke an OEM autostart confirmation.
    private val resolver by lazy {
        val provider = CloudUploadFixtureProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = this@CloudUploadInstrumentedTest.authority })
        MockContentResolver(context).apply { addProvider(authority, provider) }
    }
    private val fixtureContext by lazy {
        object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver() = resolver
        }
    }

    @Test
    fun refreshPreservesPendingCloudUploadAndDeletionWithoutAccountWrites() = runBlocking {
        val deletion = CompletableDeferred<Unit>()
        val upload = CompletableDeferred<Unit>()
        val deleteCalls = AtomicInteger()
        val uploadCalls = AtomicInteger()
        val reads = AtomicInteger()
        lateinit var progress: (Long, Long) -> Unit
        val song = CloudSong("1", "Fixture song", "Artist", "Album", null, 1_000, 42, 320, 0)
        val model = withContext(Dispatchers.Main) {
            CloudMusicViewModel(
                loadPage = {
                    reads.incrementAndGet()
                    CloudMusicPage(listOf(song), 1, 42, 99, false)
                },
                deleteSong = {
                    deleteCalls.incrementAndGet()
                    deletion.await()
                },
                uploadSong = { _, _ -> error("This state regression must not invoke a repository upload") },
            )
        }
        try {
            withTimeout(5_000) { model.state.first { !it.isLoading } }
            withContext(Dispatchers.Main) {
                model.delete(song)
                model.delete(song)
                model.upload { callback ->
                    uploadCalls.incrementAndGet()
                    progress = callback
                    callback(40, 100)
                    upload.await()
                }
                model.upload { error("The duplicate upload must be ignored") }
                model.refresh()
            }
            val refreshed = withTimeout(5_000) { model.state.first { !it.isLoading } }
            assertEquals(1, deleteCalls.get())
            assertEquals(1, uploadCalls.get())
            assertTrue(reads.get() >= 2)
            assertTrue(refreshed.isUploading)
            assertEquals(0.4f, refreshed.uploadProgress)
            assertEquals(setOf("1"), refreshed.deletingIds)

            withContext(Dispatchers.IO) { progress(70, 100) }
            assertEquals(0.7f, model.state.value.uploadProgress)
            deletion.complete(Unit)
            upload.complete(Unit)
            val completed = withTimeout(5_000) {
                model.state.first { !it.isLoading && !it.isUploading && it.deletingIds.isEmpty() }
            }
            assertFalse(completed.isUploading)
            assertTrue(completed.deletingIds.isEmpty())
            assertEquals(1f, completed.uploadProgress)
            withContext(Dispatchers.IO) { progress(20, 100) }
            assertEquals(1f, model.state.value.uploadProgress)
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                model.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }
    }

    @Test
    fun snapshotUsesActualBytesOnceAndSurvivesProviderMutation() = runBlocking {
        for (mode in listOf("normal", "slice")) {
            fixture(mode).use { source ->
                val before = snapshots()
                val prepared = prepareCloudUploadFile(fixtureContext, source.uri)
                try {
                    assertEquals(source.created.getLong("size"), prepared.size)
                    assertEquals(source.created.getString("md5"), prepared.md5)
                    assertEquals("Fixture song.WAV", prepared.filename)
                    assertEquals("wav", prepared.extension)
                    val bytes = prepared.file.readBytes()
                    assertEquals(prepared.size, bytes.size.toLong())
                    source.call("mutate")
                    assertArrayEquals(bytes, prepared.file.readBytes())
                    val status = source.call("status")
                    assertEquals(1, status.getInt("queries"))
                    assertEquals(1, status.getInt("opens"))
                    assertEquals(0, status.getInt("types"))
                    assertTrue(status.getBoolean("query_signal"))
                    assertTrue(status.getBoolean("open_signal"))
                } finally {
                    prepared.close()
                }
                assertEquals(before, snapshots())
            }
        }
    }

    @Test
    fun failedPreparationRemovesItsSnapshotAndPreservesSource() = runBlocking {
        for (mode in listOf("empty", "deny_query", "deny_open", "null_open")) {
            fixture(mode).use { source ->
                val before = snapshots()
                try {
                    prepareCloudUploadFile(fixtureContext, source.uri).close()
                    fail("Preparation must reject $mode")
                } catch (_: Exception) {
                    // The fixture deliberately denies access or returns no bytes.
                }
                assertEquals(before, snapshots())
                assertTrue(source.call("status").getBoolean("source_unchanged"))
            }
        }
    }

    @Test
    fun cancellationStopsProviderQueryOpenAndPendingPipeRead() = runBlocking {
        for (mode in listOf("block_query", "block_open", "stall_read")) {
            fixture(mode).use { source ->
                val before = snapshots()
                supervisorScope {
                    val preparation = async(Dispatchers.IO) { prepareCloudUploadFile(fixtureContext, source.uri) }
                    try {
                        await {
                            val status = source.call("status")
                            when (mode) {
                                "block_query" -> status.getInt("queries") > 0
                                "block_open" -> status.getInt("opens") > 0
                                else -> status.getInt("opens") > 0 && snapshots() != before
                            }
                        }
                        withTimeout(3_000) { preparation.cancelAndJoin() }
                        assertTrue(preparation.isCancelled)
                        val status = source.call("status")
                        if (mode != "stall_read") assertTrue(status.getBoolean("canceled"))
                        assertTrue(status.getBoolean("source_unchanged"))
                        assertEquals(before, snapshots())
                    } finally {
                        source.call("release")
                        preparation.cancelAndJoin()
                    }
                }
            }
        }
    }

    @Test
    fun cancellationClosesBlockedHttpResponseRead() = runBlocking {
        HttpFixture(replyHeaders = true).use { server ->
            val failed = CountDownLatch(1)
            val client = client(failed)
            val transfer = async(Dispatchers.IO) {
                executeCloudUploadRequest(client, Request.Builder().url(server.url).build())
            }
            try {
                awaitLatch(server.receivedHeaders)
                withTimeout(3_000) { transfer.cancelAndJoin() }
                awaitLatch(failed)
                awaitLatch(server.peerClosed)
                assertTrue(transfer.isCancelled)
            } finally {
                transfer.cancelAndJoin()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test
    fun cancellationClosesBlockedHttpRequestWrite() = runBlocking {
        HttpFixture(replyHeaders = false).use { server ->
            val failed = CountDownLatch(1)
            val client = client(failed)
            val written = AtomicLong()
            val total = 512L * 1024 * 1024
            val body = object : RequestBody() {
                override fun contentType(): MediaType? = null
                override fun contentLength() = total
                override fun writeTo(sink: BufferedSink) {
                    val buffer = ByteArray(64 * 1024)
                    while (written.get() < total) {
                        sink.write(buffer)
                        sink.flush()
                        written.addAndGet(buffer.size.toLong())
                    }
                }
            }
            val transfer = async(Dispatchers.IO) {
                executeCloudUploadRequest(client, Request.Builder().url(server.url).post(body).build())
            }
            try {
                awaitLatch(server.receivedHeaders)
                await { written.get() > 0 }
                // The server deliberately stops consuming bytes after the headers.
                withTimeout(3_000) {
                    do {
                        val previous = written.get()
                        delay(100)
                    } while (written.get() != previous)
                }
                assertFalse(written.get() == total)
                withTimeout(3_000) { transfer.cancelAndJoin() }
                awaitLatch(failed)
                assertTrue(transfer.isCancelled)
            } finally {
                transfer.cancelAndJoin()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    private fun client(failed: CountDownLatch) = OkHttpClient.Builder()
        .eventListener(object : EventListener() {
            override fun callFailed(call: Call, ioe: IOException) { failed.countDown() }
        })
        .retryOnConnectionFailure(false)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun snapshots(): Set<String> = File(context.cacheDir, "cloud-uploads")
        .listFiles().orEmpty().mapTo(mutableSetOf()) { it.name }

    private suspend fun await(predicate: () -> Boolean) = withTimeout(3_000) {
        while (!predicate()) delay(20)
    }

    private suspend fun awaitLatch(latch: CountDownLatch) = withContext(Dispatchers.IO) {
        assertTrue("Fixture event timed out", latch.await(3, TimeUnit.SECONDS))
    }

    private fun fixture(mode: String): ProviderFixture {
        val id = UUID.randomUUID().toString()
        val uri = Uri.parse("content://$authority/$id")
        val created = checkNotNull(resolver.call(uri, "create", id, Bundle().apply { putString("mode", mode) }))
        return ProviderFixture(uri, created)
    }

    private inner class ProviderFixture(val uri: Uri, val created: Bundle) : Closeable {
        fun call(method: String): Bundle = checkNotNull(resolver.call(uri, method, uri.lastPathSegment, null))
        override fun close() { call("delete") }
    }

    private class HttpFixture(private val replyHeaders: Boolean) : Closeable {
        private val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}/fixture"
        val receivedHeaders = CountDownLatch(1)
        val peerClosed = CountDownLatch(1)
        private val release = CountDownLatch(1)
        @Volatile private var socket: Socket? = null
        private val worker = thread(name = "Cloud-upload-HTTP-fixture", isDaemon = true) {
            try {
                server.accept().use { accepted ->
                    socket = accepted
                    accepted.receiveBufferSize = 1_024
                    val input = accepted.getInputStream()
                    var ending = 0
                    while (ending != 0x0d0a0d0a) {
                        val byte = input.read()
                        if (byte < 0) throw IOException("Request headers ended early")
                        ending = (ending shl 8) or byte
                    }
                    if (replyHeaders) {
                        accepted.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 16\r\n\r\n".toByteArray())
                            flush()
                        }
                    }
                    receivedHeaders.countDown()
                    if (replyHeaders) {
                        try { while (input.read() >= 0) Unit } finally { peerClosed.countDown() }
                    } else {
                        release.await(10, TimeUnit.SECONDS)
                    }
                }
            } catch (_: IOException) {
                peerClosed.countDown()
            }
        }

        override fun close() {
            release.countDown()
            socket?.close()
            server.close()
            worker.join(1_000)
        }
    }
}
