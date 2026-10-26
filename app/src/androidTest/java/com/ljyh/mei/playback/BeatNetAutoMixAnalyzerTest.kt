package com.ljyh.mei.playback

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BeatNetAutoMixAnalyzerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun highRateStereoDecodeKeepsOnlyTheAnalysisSampleCount() = runBlocking {
        withFixture { file ->
            val samples = AndroidAudioDecoder(context).decode(Uri.fromFile(file), 0L, WINDOW_MS)

            assertEquals(705_600, samples.size)
            assertTrue("Decoded pulses must survive downsampling", samples.any { abs(it) > 0.25f })
            assertTrue("Decoded PCM must contain finite, normalized samples", samples.all { it.isFinite() && abs(it) <= 1f })
        }
    }

    @Test
    fun highRatePairRunsTailSeekAndNativeInferenceWithoutStartingPlayback() = runBlocking {
        withFixture { file ->
            val analyzer = BeatNetAutoMixAnalyzer(context)
            try {
                val uri = Uri.fromFile(file)
                val pair = withTimeout(120_000) {
                    analyzer.analyzePair(
                        outgoingId = "beatnet-high-rate-outgoing",
                        outgoingSource = ResolvedMediaSource(uri, actualQuality = "", cacheKey = null),
                        outgoingDurationMs = FIXTURE_DURATION_MS,
                        incomingId = "beatnet-high-rate-incoming",
                        incomingSource = ResolvedMediaSource(uri, actualQuality = "", cacheKey = null),
                    )
                }

                assertAnalysis(pair.outgoing, FIXTURE_DURATION_MS - WINDOW_MS)
                assertAnalysis(pair.incoming, 0L)
            } finally {
                analyzer.close()
            }
        }
    }

    @Test
    fun networkAnalysisReusesPlaybackCache() = runBlocking {
        verifyNetworkAnalysisUsesCache(fullyCached = true)
    }

    @Test
    fun networkAnalysisFetchesOnlyMissingPlaybackRanges() = runBlocking {
        verifyNetworkAnalysisUsesCache(fullyCached = false)
    }

    @Test
    fun concurrentAnalysisDoesNotDownloadAnInFlightPlaybackRangeAgain() {
        val bytes = ByteArray(256 * 1024) { (it * 31 + 7).toByte() }
        HttpFixture(bytes).use { server ->
            val factory = CacheManager.getCacheDataSourceFactory(context)
            val cache = checkNotNull(factory.cache)
            val key = "concurrent-analysis-${System.nanoTime()}"
            val source = ResolvedMediaSource(
                uri = server.uri, actualQuality = "lossless", cacheKey = key,
                contentLength = bytes.size.toLong(),
            )
            val playback = factory.createDataSource()
            val executor = Executors.newSingleThreadExecutor()
            try {
                playback.open(DataSpec.Builder().setUri(server.uri).setKey(key)
                    .setLength(bytes.size.toLong()).build())
                val started = CountDownLatch(1)
                val analysis = executor.submit<ByteArray> {
                    CacheMediaDataSource(source, cache, { CacheManager.getCacheDataSourceFactory(context, it) }) {}.use { reader ->
                        started.countDown()
                        ByteArray(4_096).also {
                            assertEquals(it.size, reader.readAt(0, it, 0, it.size))
                        }
                    }
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                // Give a second reader time to contend while playback still owns the cache hole.
                server.awaitRequests(2, 300)
                val buffer = ByteArray(8_192)
                while (playback.read(buffer, 0, buffer.size) != C.RESULT_END_OF_INPUT) Unit
                playback.close()
                assertArrayEquals(bytes.copyOfRange(0, 4_096), analysis.get(5, TimeUnit.SECONDS))
                android.util.Log.i("BeatNetCacheTest",
                    "concurrent bytes=${server.bytesServed} expected=${bytes.size} requests=${server.requestCount}")
                assertEquals("Concurrent analysis must reuse the bytes being cached by playback",
                    bytes.size.toLong(), server.bytesServed)
            } finally {
                playback.close()
                executor.shutdownNow()
                cache.removeResource(key)
            }
        }
    }

    @Test
    fun decoderCancellationDoesNotWaitForAnOpenPlaybackCacheHole() = runBlocking {
        withFixture { file ->
            withNetworkSource(file.readBytes()) { server, factory, source ->
                val playback = factory.createDataSource()
                try {
                    playback.open(DataSpec.Builder().setUri(source.uri).setKey(source.cacheKey)
                        .setLength(file.length()).build())
                    val waiting = CountDownLatch(1)
                    val cache = checkNotNull(factory.cache)
                    val observed = object : Cache by cache {
                        override fun startReadWrite(key: String, position: Long, length: Long): CacheSpan {
                            waiting.countDown()
                            return cache.startReadWrite(key, position, length)
                        }

                        override fun startReadWriteNonBlocking(key: String, position: Long, length: Long): CacheSpan? {
                            waiting.countDown()
                            return cache.startReadWriteNonBlocking(key, position, length)
                        }
                    }
                    val analysis = async(Dispatchers.IO) {
                        AndroidAudioDecoder(context, observed, ::httpFactory).decode(source, 0L, WINDOW_MS)
                    }
                    try {
                        assertTrue("The real extractor must reach the held cache range",
                            waiting.await(5, TimeUnit.SECONDS))
                        withTimeout(2_000) { analysis.cancelAndJoin() }
                        assertEquals("Cancelled analysis must not bypass the active playback writer",
                            1, server.requestCount)
                    } finally {
                        analysis.cancel()
                    }
                } finally {
                    playback.close()
                }
            }
        }
    }

    @Test
    fun analysisCanReadCommittedPlaybackFragmentsBeforeTheWriterCloses() {
        val bytes = ByteArray(2 * 1024 * 1024) { (it * 31 + 7).toByte() }
        HttpFixture(bytes).use { server ->
            val factory = CacheManager.getCacheDataSourceFactory(context)
            val cache = checkNotNull(factory.cache)
            val key = "visible-playback-fragment-${System.nanoTime()}"
            val source = ResolvedMediaSource(uri = server.uri, actualQuality = "jymaster",
                cacheKey = key, contentLength = bytes.size.toLong())
            val playback = factory.createDataSource()
            val executor = Executors.newSingleThreadExecutor()
            try {
                playback.open(DataSpec.Builder().setUri(server.uri).setKey(key)
                    .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                    .setLength(bytes.size.toLong()).build())
                val buffer = ByteArray(8_192)
                var read = 0
                while (read < 1024 * 1024) {
                    val count = playback.read(buffer, 0, buffer.size)
                    assertTrue(count > 0)
                    read += count
                }
                assertTrue("A paused standby writer must expose its committed head",
                    cache.isCached(key, 0, 512 * 1024))
                val analysis = executor.submit<ByteArray> {
                    CacheMediaDataSource(source, cache, { CacheManager.getCacheDataSourceFactory(context, it) }) {}.use { reader ->
                        ByteArray(4_096).also { assertEquals(it.size, reader.readAt(0, it, 0, it.size)) }
                    }
                }
                assertArrayEquals(bytes.copyOfRange(0, 4_096), analysis.get(3, TimeUnit.SECONDS))
                assertEquals("Reading committed head bytes must not open another HTTP request",
                    1, server.requestCount)
            } finally {
                playback.close()
                executor.shutdownNow()
                cache.removeResource(key)
            }
        }
    }

    private suspend fun verifyNetworkAnalysisUsesCache(fullyCached: Boolean) {
        withFixture { file ->
            withNetworkSource(file.readBytes()) { server, factory, resolved ->
                if (fullyCached) {
                    seedCache(factory, resolved, 0, file.length())
                } else {
                    seedCache(factory, resolved, 0, 8_192)
                    seedCache(factory, resolved, file.length() - 8_192, 8_192)
                }
                val playbackBytes = server.bytesServed
                assertEquals(if (fullyCached) file.length() else 16_384L, playbackBytes)
                val unknownLengthSource = if (fullyCached) null else {
                    CacheMediaDataSource(resolved.copy(contentLength = null), checkNotNull(factory.cache), ::httpFactory) {}.also {
                        assertEquals(C.LENGTH_UNSET.toLong(), it.size)
                        assertEquals(C.RESULT_END_OF_INPUT, it.readAt(file.length() + 1, ByteArray(1), 0, 1))
                    }
                }

                var firstAnalysisBytes = playbackBytes
                repeat(2) { attempt ->
                    val decoder = AndroidAudioDecoder(context, checkNotNull(factory.cache), ::httpFactory)
                    for (startMs in listOf(0L, FIXTURE_DURATION_MS - WINDOW_MS)) {
                        val samples = decoder.decode(resolved, startMs, WINDOW_MS)
                        assertTrue(samples.any { abs(it) > 0.25f })
                    }
                    if (attempt == 0) firstAnalysisBytes = server.bytesServed
                }

                if (fullyCached) {
                    assertEquals("Cached analysis must not download playback bytes again", playbackBytes, firstAnalysisBytes)
                } else {
                    assertTrue("Partial analysis must fetch missing bytes", firstAnalysisBytes > playbackBytes)
                    assertTrue("Analysis must not refetch already cached ranges", firstAnalysisBytes <= file.length())
                    assertTrue("Tiny extractor reads must share bounded network requests", server.requestCount <= 5)
                }
                assertEquals("Repeated head/tail analysis must reuse playback bytes", firstAnalysisBytes, server.bytesServed)
                unknownLengthSource?.use {
                    assertEquals("Existing readers must discover newly stored content length", file.length(), it.size)
                    assertEquals(C.RESULT_END_OF_INPUT, it.readAt(file.length(), ByteArray(1), 0, 1))
                }
                android.util.Log.i("BeatNetCacheTest", "fullyCached=$fullyCached bytes=${server.bytesServed} requests=${server.requestCount}")
            }
        }
    }

    @Test
    fun cacheReadAheadPreservesRandomAccessAcrossBufferBoundaries() = runBlocking {
        val bytes = ByteArray(3 * 65_536 + 17) { (it * 31 + 7).toByte() }
        withNetworkSource(bytes) { server, factory, resolved ->
            CacheMediaDataSource(resolved, checkNotNull(factory.cache), ::httpFactory) {}.use { reader ->
                val start = 65_536 - 8
                val count = 65_536 + 16
                val output = ByteArray(count + 12) { -1 }
                assertEquals(count, reader.readAt(start.toLong(), output, 5, count))
                assertArrayEquals(bytes.copyOfRange(start, start + count), output.copyOfRange(5, 5 + count))
                assertTrue(output.take(5).all { it == (-1).toByte() })
                assertTrue(output.drop(5 + count).all { it == (-1).toByte() })
                val firstReadBytes = server.bytesServed
                assertEquals(2 * 65_536L, firstReadBytes)

                val backward = ByteArray(32)
                assertEquals(backward.size, reader.readAt(start + 4L, backward, 0, backward.size))
                assertArrayEquals(bytes.copyOfRange(start + 4, start + 4 + backward.size), backward)
                assertEquals("Backward seeks must reuse the downloaded ranges", firstReadBytes, server.bytesServed)

                val tail = ByteArray(16) { -1 }
                assertEquals(8, reader.readAt(bytes.size - 8L, tail, 3, 13))
                assertArrayEquals(bytes.takeLast(8).toByteArray(), tail.copyOfRange(3, 11))
                assertTrue(tail.take(3).all { it == (-1).toByte() })
                assertTrue(tail.drop(11).all { it == (-1).toByte() })
                assertEquals(C.RESULT_END_OF_INPUT, reader.readAt(bytes.size.toLong(), tail, 0, tail.size))
                assertEquals(0, reader.readAt(bytes.size.toLong(), tail, tail.size, 0))
            }
        }
    }

    private fun httpFactory(cache: Cache) = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())

    private suspend fun withNetworkSource(
        bytes: ByteArray,
        block: suspend (HttpFixture, CacheDataSource.Factory, ResolvedMediaSource) -> Unit,
    ) {
        val directory = File(context.cacheDir, "beatnet-http-${System.nanoTime()}").apply { mkdirs() }
        val database = StandaloneDatabaseProvider(context)
        val cache = SimpleCache(directory, NoOpCacheEvictor(), database)
        try {
            HttpFixture(bytes).use { server ->
                val factory = CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
                val resolved = ResolvedMediaSource(
                    uri = server.uri,
                    actualQuality = "lossless",
                    cacheKey = playbackCacheKey("beatnet-network", "lossless", "fixture", bytes.size.toLong()),
                    contentLength = bytes.size.toLong(),
                )
                block(server, factory, resolved)
            }
        } finally {
            cache.release()
            database.close()
            directory.deleteRecursively()
        }
    }

    private fun seedCache(factory: CacheDataSource.Factory, resolved: ResolvedMediaSource, position: Long, length: Long) {
        val source = factory.createDataSource()
        try {
            source.open(
                DataSpec.Builder().setUri(resolved.uri).setKey(resolved.cacheKey)
                    .setPosition(position).setLength(length).build(),
            )
            val bytes = ByteArray(8_192)
            while (source.read(bytes, 0, bytes.size) != C.RESULT_END_OF_INPUT) Unit
        } finally {
            source.close()
        }
    }

    private fun assertAnalysis(analysis: BeatNetTrackAnalysis, startMs: Long) {
        assertTrue("Native inference must produce a finite tempo", analysis.bpm.isFinite() && analysis.bpm in 50.0..250.0)
        assertTrue("Native inference must produce finite confidence", analysis.confidence.isFinite() && analysis.confidence in 0.0..1.0)
        assertTrue("Temporal decoding must produce beat candidates", analysis.beatsMs.isNotEmpty())
        assertTrue("Beat candidates must stay inside the requested window", analysis.beatsMs.all { it in startMs until startMs + WINDOW_MS })
        assertTrue("Beat candidates must remain ordered", (1 until analysis.beatsMs.size).all { analysis.beatsMs[it] > analysis.beatsMs[it - 1] })
        assertTrue("Downbeats must be beat candidates", analysis.downbeatsMs.all { it in analysis.beatsMs })
    }

    private suspend fun withFixture(block: suspend (File) -> Unit) {
        // The 36-second fixture contains 10 ms square pulses every 4 seconds.
        // ffmpeg -f lavfi -i 'aevalsrc=if(lt(mod(t\,4)\,0.01)\,0.5\,0):s=192000:d=36'
        //   -ac 2 -sample_fmt s16 -c:a flac -compression_level 12 -frame_size 16384 -map_metadata -1 fixture.flac
        val file = File.createTempFile("beatnet-high-rate-", ".flac", context.cacheDir)
        try {
            instrumentation.context.assets.open("beatnet-192khz-stereo.flac").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                assertEquals(1, extractor.trackCount)
                val format = extractor.getTrackFormat(0)
                // Some platform FLAC extractors decode the track and expose PCM directly.
                assertTrue(format.getString(MediaFormat.KEY_MIME) in setOf("audio/flac", "audio/raw"))
                assertEquals(192_000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                assertEquals(2, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                assertEquals(FIXTURE_DURATION_MS * 1_000, format.getLong(MediaFormat.KEY_DURATION))
            } finally {
                extractor.release()
            }
            block(file)
        } finally {
            file.delete()
        }
    }

    /** Serves real range requests and counts response bodies without a networking mock. */
    private class HttpFixture(private val bytes: ByteArray) : AutoCloseable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val handlers = Executors.newCachedThreadPool()
        private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
        private val activeHandlers = AtomicInteger()
        val uri: Uri = Uri.parse("http://127.0.0.1:${socket.localPort}/fixture.flac")
        private val trafficLock = Any()
        private var transferredBytes = 0L
        private var receivedRequests = 0
        val bytesServed: Long get() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (activeHandlers.get() != 0 && System.nanoTime() < deadline) Thread.sleep(1)
            check(activeHandlers.get() == 0) { "HTTP response bodies did not finish" }
            return synchronized(trafficLock) { transferredBytes }
        }
        val requestCount: Int get() = synchronized(trafficLock) { receivedRequests }
        private val worker = thread(name = "beatnet-http-fixture", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (error: java.io.IOException) {
                    if (socket.isClosed) break else throw error
                }
                clients += client
                activeHandlers.incrementAndGet()
                handlers.execute { serve(client) }
            }
        }

        fun awaitRequests(count: Int, timeoutMs: Long): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (System.nanoTime() < deadline) {
                if (requestCount >= count) return true
                Thread.sleep(5)
            }
            return requestCount >= count
        }

        private fun serve(client: Socket) {
            try {
                client.use {
                    val input = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                    input.readLine() ?: return@use
                    var range: String? = null
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                    }
                    val requested = range?.removePrefix("bytes=")?.split('-', limit = 2)
                    synchronized(trafficLock) { receivedRequests++ }
                    val start = requested?.getOrNull(0)?.toIntOrNull() ?: 0
                    if (start >= bytes.size) {
                        synchronized(trafficLock) {
                            client.getOutputStream().write(
                                ("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\n" +
                                    "Content-Range: bytes */${bytes.size}\r\nConnection: close\r\n\r\n")
                                    .toByteArray(Charsets.US_ASCII),
                            )
                        }
                        return@use
                    }
                    val end = (requested?.getOrNull(1)?.toIntOrNull() ?: bytes.lastIndex).coerceAtMost(bytes.lastIndex)
                    val length = (end - start + 1).coerceAtLeast(0)
                    val headers = buildString {
                        append(if (range == null) "HTTP/1.1 200 OK\r\n" else "HTTP/1.1 206 Partial Content\r\n")
                        append("Content-Type: audio/flac\r\nAccept-Ranges: bytes\r\nConnection: close\r\n")
                        append("Content-Length: $length\r\n")
                        if (range != null) append("Content-Range: bytes $start-$end/${bytes.size}\r\n")
                        append("\r\n")
                    }
                    try {
                        client.getOutputStream().apply {
                            write(headers.toByteArray(Charsets.US_ASCII))
                            var sent = 0
                            while (sent < length) {
                                val count = minOf(8_192, length - sent)
                                write(bytes, start + sent, count)
                                synchronized(trafficLock) { transferredBytes += count }
                                sent += count
                            }
                            flush()
                        }
                    } catch (_: java.io.IOException) {
                        // Platform extractors may close a range early after finding their track.
                    }
                }
            } catch (_: java.io.IOException) {
                // Cancellation closes active fixture sockets.
            } finally {
                clients -= client
                activeHandlers.decrementAndGet()
            }
        }

        override fun close() {
            socket.close()
            worker.join(5_000)
            clients.forEach { runCatching { it.close() } }
            handlers.shutdownNow()
            handlers.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    companion object {
        private const val WINDOW_MS = 32_000L
        private const val FIXTURE_DURATION_MS = 36_000L
    }
}
