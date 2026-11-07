package com.ljyh.mei.playback

import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.database.StandaloneDatabaseProvider
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.metadata
import java.net.ServerSocket
import java.net.InetAddress
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.SongUrl
import com.ljyh.mei.data.model.Tracks
import com.ljyh.mei.data.model.availableMusicQualities
import com.ljyh.mei.data.model.api.GetSongUrlV1
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.model.room.SourceType
import com.ljyh.mei.di.dao.SongDao
import com.ljyh.mei.di.dao.DownloadDao
import com.ljyh.mei.di.repository.DownloadRepository
import com.ljyh.mei.data.model.room.DownloadTask
import com.ljyh.mei.di.repository.SongRepository
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.*
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaUriProviderQualityTest {
    private val gson = Gson()

    @Test
    fun realMedia3FallbackKeepsOriginUsesResourceHeadersAndHonorsNoStore() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = Track(TrackRef(ProviderIds.NETEASE, "1"), "Fixture recording",
            listOf(SourceArtist("Fixture artist")), SourceAlbum("Fixture album"), durationMs = 1_000)
        val target = original.copy(ref = TrackRef("fixture", "1/: 雪"))
        val bytes = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(16_036).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(16_000).array() + ByteArray(16_000)
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val receivedHeaders = AtomicReference<List<String>>(emptyList())
        val serving = thread(name = "source-playback-fixture", isDaemon = true) {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: IOException) { break }
                client.use {
                    val reader = it.getInputStream().bufferedReader(Charsets.US_ASCII)
                    reader.readLine() ?: return@use
                    val headers = buildList {
                        while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; add(line) }
                    }
                    receivedHeaders.set(headers)
                    val authorized = headers.any { line -> line.equals("X-Fixture-Key: resource-only", true) }
                    val range = headers.firstOrNull { line -> line.startsWith("Range:", true) }
                        ?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                    val body = if (authorized) bytes.copyOfRange(range.coerceAtMost(bytes.size), bytes.size) else byteArrayOf()
                    val status = if (!authorized) "403 Forbidden" else if (range > 0) "206 Partial Content" else "200 OK"
                    val response = "HTTP/1.1 $status\r\nContent-Type: audio/wav\r\nContent-Length: ${body.size}\r\n" +
                        (if (range > 0) "Content-Range: bytes $range-${bytes.lastIndex}/${bytes.size}\r\n" else "") +
                        "Connection: close\r\n\r\n"
                    it.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                    it.getOutputStream().write(body)
                }
            }
        }
        val plugin = object : MusicProvider, CatalogCapability, PlaybackCapability {
            override val id = "fixture"
            override val displayName = "Offline fixture"
            override val isBuiltIn = true // Instrumentation owns the localhost endpoint.
            override val capabilities = setOf(SourceCapability.SEARCH, SourceCapability.TRACK, SourceCapability.PLAYBACK)
            override suspend fun search(query: String, page: PageRequest) = SourcePage(listOf(target))
            override suspend fun getTrack(ref: TrackRef) = target.takeIf { it.ref == ref }
            override suspend fun resolve(ref: TrackRef, requestedQuality: String) = AudioResource(
                "http://127.0.0.1:${server.localPort}/recording.wav", "standard",
                headers = mapOf("X-Fixture-Key" to "resource-only", "User-Agent" to "SourceFixture/1"),
                contentLength = bytes.size.toLong(), contentId = "fixture-wav", cachePolicy = CachePolicy.NO_STORE)
        }
        val resolver = provider("{}", additionalProviders = listOf(plugin), sourceSettings = SourceSettings(
            autoFallbackEnabled = true, fallbackOrder = listOf(plugin.instanceId.value))) {
            source(it.level, "unavailable", code = 404)
        }
        resolver.rememberTrack(original.toMediaMetadata())
        val cacheDir = File(context.cacheDir, "source-playback-${UUID.randomUUID()}")
        val cache = SimpleCache(cacheDir, NoOpCacheEvictor(), StandaloneDatabaseProvider(context))
        val ended = CountDownLatch(1)
        val failure = AtomicReference<PlaybackException?>()
        var player: ExoPlayer? = null
        try {
            val dataSource = ResolvingDataSource.Factory(CacheManager.getPlaybackDataSourceFactory(context, cache)) { spec ->
                resolvedMediaDataSpec(spec, runBlocking { resolver.resolveMediaSource(checkNotNull(spec.key), "lossless") })
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                player = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(dataSource)).build().apply {
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) ended.countDown() }
                        override fun onPlayerError(error: PlaybackException) { failure.set(error); ended.countDown() }
                    })
                    setMediaItem(original.toMediaMetadata().toMediaItem())
                    prepare()
                    play()
                }
            }
            assertTrue("Playback did not complete", ended.await(10, TimeUnit.SECONDS))
            assertEquals(null, failure.get())
            val resolution = checkNotNull(resolver.resolvedMediaSource("1")?.resolution)
            assertEquals(original.ref, resolution.originTrackRef)
            assertEquals(target.ref, resolution.resolvedTrackRef)
            assertEquals("fixture", resolution.actualAudioProviderId)
            assertFalse(canReportProviderPlayback(original.ref, resolution))
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertEquals("1", player?.currentMediaItem?.mediaId)
                assertEquals(original.ref, player?.currentMediaItem?.metadata?.trackRef)
            }
            assertTrue(receivedHeaders.get().any { it.equals("X-Fixture-Key: resource-only", true) })
            assertEquals(1, receivedHeaders.get().count { it.equals("User-Agent: SourceFixture/1", true) })
            assertTrue("NO_STORE resources must not publish cached bytes", cache.keys.isEmpty())
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { player?.release() }
            server.close()
            serving.join(1_000)
            cache.release()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun menuIncludesOnlyActualFullSourcesAndUsesShortLivedResults() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val provider = provider(
            catalog = """{"l":{"br":128000},"h":{"br":320000},"sq":{"size":12345},"je":{"size":23456},"jm":{"size":34567},"dl":{"size":45678}}""",
        ) { request ->
            calls += request.level
            when (request.level) {
                "standard" -> source("standard", "standard-source")
                "exhigh" -> source("exhigh", "unavailable", code = 404)
                "lossless" -> source("lossless", "trial", trial = true)
                "jyeffect", "jymaster" -> source("jyeffect", "surround-source")
                else -> error("Unsupported Dolby must not be queried")
            }
        }

        assertEquals(
            listOf(MusicQuality.STANDARD, MusicQuality.JYEFFECT),
            provider.getAvailableMusicQualities("1", supportDolby = false),
        )
        val firstCalls = calls.size
        assertEquals(
            listOf(MusicQuality.STANDARD, MusicQuality.JYEFFECT),
            provider.getAvailableMusicQualities("1", supportDolby = false),
        )
        assertEquals(firstCalls, calls.size)
        assertFalse(calls.contains("dolby"))
    }

    @Test
    fun qualityProbeDoesNotReplaceCurrentSourceOrCacheHigherQualityAlias() = runBlocking {
        val masterCalls = AtomicInteger()
        val provider = provider(catalog = """{"jm":{"size":34567}}""") { request ->
            when (request.level) {
                "jymaster" -> if (masterCalls.incrementAndGet() == 1) {
                    source("jyeffect", "surround-source")
                } else {
                    source("jymaster", "master-source")
                }
                else -> source("standard", "standard-source")
            }
        }
        val observed = mutableListOf<ResolvedMediaSource>()
        provider.onSourceResolved = { _, source -> observed += source }

        assertEquals(
            listOf(MusicQuality.JYEFFECT),
            provider.getAvailableMusicQualities("1", supportDolby = true),
        )
        assertTrue(observed.isEmpty())
        val resolved = provider.resolveMediaSource("1", "jymaster")

        assertEquals(2, masterCalls.get())
        assertEquals("jymaster", resolved.actualQuality)
        assertEquals("master-source", resolved.resolution?.resource?.contentId)
        assertEquals(resolved, provider.resolvedMediaSource("1"))
        assertEquals(listOf(resolved), observed)
    }

    @Test
    fun unknownReportedLevelsFallBackWithoutPublishingOrCachingThem() = runBlocking {
        for ((unsupportedLevel, actualFallback) in listOf("vivid" to "jyeffect", "future-surround" to "lossless")) {
            val calls = mutableListOf<String>()
            val provider = provider(catalog = "{}") { request ->
                calls += request.level
                when (request.level) {
                    "dolby", "sky" -> source(unsupportedLevel, "unsupported-source")
                    "jyeffect" -> source(actualFallback, "supported-source")
                    else -> error("The supported fallback must retain the song")
                }
            }
            val observed = mutableListOf<ResolvedMediaSource>()
            provider.onSourceResolved = { _, source -> observed += source }

            val resolved = provider.resolveMediaSource("1", "dolby")

            assertEquals(listOf("dolby", "sky", "jyeffect"), calls)
            assertEquals(actualFallback, resolved.actualQuality)
            assertEquals("supported-source", resolved.resolution?.resource?.contentId)
            assertEquals(resolvedPlaybackCacheKey(resolved.resolution!!), resolved.cacheKey)
            assertEquals(listOf(resolved), observed)
            assertEquals(resolved, provider.resolveMediaSource("1", "dolby"))
            assertEquals(listOf("dolby", "sky", "jyeffect"), calls)

            assertEquals(resolved, provider.resolveMediaSource("1", "sky"))
            assertEquals(listOf("dolby", "sky", "jyeffect", "sky"), calls)
            assertTrue(observed.all { it.actualQuality == actualFallback })
        }
    }

    @Test
    fun unsupportedFullSourcesCannotBecomeAliasesOrAvailableQualities() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        var supported = false
        val provider = provider(catalog = """{"sk":{"size":12345},"dl":{"size":23456}}""") { request ->
            calls += request.level
            if (supported) source(request.level, "${request.level}-source")
            else source("vivid", "unsupported-source")
        }
        val observed = mutableListOf<ResolvedMediaSource>()
        provider.onSourceResolved = { _, source -> observed += source }

        try {
            provider.resolveMediaSource("1", "dolby")
            fail("An unsupported full source must not end quality fallback")
        } catch (_: SourceNotFoundException) {
            // Every attempted quality returned the same unsupported format.
        }
        assertEquals(playbackQualityFallbacks("dolby"), calls)
        assertEquals(null, provider.resolvedMediaSource("1"))
        assertTrue(observed.isEmpty())
        assertTrue(provider.getAvailableMusicQualities("1", supportDolby = true).isEmpty())
        assertTrue(provider.getAvailableDownloadQualities("1").isEmpty())

        supported = true
        calls.clear()
        val resolved = provider.resolveMediaSource("1", "dolby")

        assertEquals(listOf("dolby"), calls)
        assertEquals("dolby", resolved.actualQuality)
        assertEquals("dolby-source", resolved.resolution?.resource?.contentId)
        assertEquals(listOf(resolved), observed)
    }

    @Test
    fun knownAndMissingReportedLevelsPreserveRequestedPlayback() = runBlocking {
        for (requestedQuality in listOf("dolby", "jymaster")) {
            for (reportedQuality in listOf(requestedQuality.uppercase(), " ")) {
                val calls = mutableListOf<String>()
                val provider = provider(catalog = "{}") { request ->
                    calls += request.level
                    source(reportedQuality, "supported-source")
                }

                val resolved = provider.resolveMediaSource("1", requestedQuality)

                assertEquals(listOf(requestedQuality), calls)
                assertEquals(requestedQuality, resolved.actualQuality)
                assertEquals("supported-source", resolved.resolution?.resource?.contentId)
            }
        }
    }

    @Test
    fun unknownOrMislabeledDiskSourcesAreNotRememberedAsPlayable() = runBlocking {
        val provider = provider(catalog = "{}") { error("No URL request is expected") }
        val observed = mutableListOf<ResolvedMediaSource>()
        provider.onSourceResolved = { _, source -> observed += source }
        val unsupportedKey = playbackCacheKey("1", "vivid", "unsupported-source", 12345)
        val mismatchedKey = playbackCacheKey("1", "lossless", "mislabeled-source", 12345)

        provider.rememberCachedSource("1", "vivid", unsupportedKey, Uri.parse("https://example.test/vivid"))
        provider.rememberCachedSource("1", "dolby", mismatchedKey, Uri.parse("https://example.test/mislabeled"))

        assertTrue(provider.isRejectedCachedSource("1", unsupportedKey))
        assertFalse(provider.isRejectedCachedSource("1", mismatchedKey))
        assertEquals(null, provider.resolvedMediaSource("1"))
        assertTrue(observed.isEmpty())
        assertTrue(provider.getAvailableMusicQualities("1", supportDolby = true).isEmpty())
    }

    @Test
    fun failedBytesAreRejectedAcrossFreshQualityAliasesUntilExplicitRetry() = runBlocking {
        val calls = mutableListOf<String>()
        val provider = provider(catalog = "{}") { request ->
            calls += request.level
            when (request.level) {
                "jymaster", "hires" -> source(request.level, "failed-source")
                "lossless" -> source("lossless", "working-source")
                else -> error("A working fallback must retain the song")
            }
        }

        val failed = provider.resolveMediaSource("1", "jymaster")
        assertTrue(provider.rejectCurrentSource("1"))
        assertTrue(provider.isRejectedCachedSource("1", failed.cacheKey!!))
        assertTrue(
            provider.isRejectedCachedSource(
                "1", playbackCacheKey("1", "hires", "failed-source", 12345),
            ),
        )
        val fallback = provider.resolveMediaSource("1", "jymaster")

        assertEquals("lossless", fallback.actualQuality)
        assertEquals("working-source", fallback.resolution?.resource?.contentId)
        assertEquals(listOf("jymaster", "jymaster", "hires", "lossless"), calls)
        assertEquals(listOf(MusicQuality.LOSSLESS), provider.getAvailableMusicQualities("1", true))

        provider.resetRejectedSources()
        assertFalse(provider.isRejectedCachedSource("1", failed.cacheKey))
        assertEquals("jymaster", provider.resolveMediaSource("1", "jymaster").actualQuality)
    }

    @Test
    fun nativeQualityFallbackPrecedesExternalRequestsAfterLiveOrDiskSourceRejection() = runBlocking {
        for (restoredFromDisk in listOf(false, true)) {
            val externalRequests = AtomicInteger()
            val original = Track(TrackRef(ProviderIds.NETEASE, "1"), "Priority recording",
                listOf(SourceArtist("Fixture artist")), SourceAlbum("Album"), durationMs = 180_000)
            val target = original.copy(ref = TrackRef("fixture", "1"))
            val external = object : MusicProvider, CatalogCapability, PlaybackCapability {
                override val id = "fixture"
                override val displayName = "Fixture"
                override val capabilities = setOf(SourceCapability.SEARCH, SourceCapability.TRACK, SourceCapability.PLAYBACK)
                override suspend fun search(query: String, page: PageRequest): SourcePage<Track> {
                    externalRequests.incrementAndGet()
                    return SourcePage(listOf(target))
                }
                override suspend fun getTrack(ref: TrackRef) = target
                override suspend fun resolve(ref: TrackRef, requestedQuality: String): AudioResource {
                    externalRequests.incrementAndGet()
                    return AudioResource("https://example.test/external", requestedQuality, contentId = "external")
                }
            }
            val calls = mutableListOf<String>()
            val resolver = provider("{}", additionalProviders = listOf(external), sourceSettings = SourceSettings(
                autoFallbackEnabled = true, fallbackOrder = listOf(external.instanceId.value))) { request ->
                calls += request.level
                when (request.level) {
                    "jymaster", "hires" -> source(request.level, "rejected-shared-bytes")
                    "lossless" -> source("lossless", "native-working-bytes")
                    else -> error("Native lossless must resolve before other levels or providers")
                }
            }
            resolver.rememberTrack(original.toMediaMetadata())
            val failed = resolver.resolveMediaSource("1", "jymaster")
            if (restoredFromDisk) resolver.rememberCachedSource("1", "jymaster", failed.cacheKey!!, failed.uri)
            assertTrue(resolver.rejectCurrentSource("1"))
            val recovered = resolver.resolveMediaSource("1", "jymaster")
            assertEquals("lossless", recovered.actualQuality)
            assertEquals(ProviderIds.NETEASE, recovered.resolution?.actualAudioProviderId)
            assertEquals("native-working-bytes", recovered.resolution?.resource?.contentId)
            assertEquals(listOf("jymaster", "jymaster", "hires", "lossless"), calls)
            assertEquals("External sources must wait for the native quality ladder", 0, externalRequests.get())
        }
    }

    @Test
    fun fullyCachedSourceCanBeRejectedAndAccountChangesClearAllQualityState() = runBlocking {
        val calls = AtomicInteger()
        val provider = provider(catalog = "{}") { request ->
            calls.incrementAndGet()
            source(request.level, "new-source")
        }
        val cachedKey = playbackCacheKey("1", "jymaster", "cached-source", 12345)
        provider.rememberCachedSource("1", "jymaster", cachedKey, Uri.parse("https://example.test/cached"))

        assertEquals(listOf(MusicQuality.JYMASTER), provider.getAvailableMusicQualities("1", true))
        assertTrue(provider.rejectCurrentSource("1"))
        assertTrue(provider.getAvailableMusicQualities("1", true).isEmpty())
        provider.clearQualityState()
        assertEquals(null, provider.resolvedMediaSource("1"))
        assertFalse(provider.isRejectedCachedSource("1", cachedKey))
        provider.resolveMediaSource("1", "jymaster")
        assertEquals(1, calls.get())
    }

    @Test
    fun probingIsBoundedAndOneCandidateFailureDoesNotHideOtherQualities() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val provider = provider(
            catalog = """{"l":{"br":128000},"h":{"br":320000},"sq":{"size":12345},"hr":{"size":23456},"je":{"size":34567},"sk":{"size":45678},"jm":{"size":56789},"dl":{"size":67890}}""",
        ) { request ->
            val running = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, running) }
            try {
                delay(20)
                if (request.level == "jymaster") throw IOException("Master unavailable")
                source(request.level, "${request.level}-source")
            } finally {
                active.decrementAndGet()
            }
        }

        val qualities = provider.getAvailableMusicQualities("1", supportDolby = true)

        assertEquals(MusicQuality.entries.filter { it != MusicQuality.JYMASTER }, qualities)
        assertEquals(3, maximum.get())
        assertEquals(0, active.get())
    }

    @Test
    fun candidateCancellationIsPropagated() = runBlocking {
        val provider = provider(catalog = """{"jm":{"size":12345}}""") {
            throw CancellationException("Probe cancelled")
        }
        supervisorScope {
            val probe = async { provider.getAvailableMusicQualities("1", supportDolby = true) }
            try {
                probe.await()
                fail("Cancellation must propagate")
            } catch (actual: CancellationException) {
                assertEquals("Probe cancelled", actual.message)
            }
            assertTrue(probe.isCancelled)
        }
    }

    @Test
    fun accountChangesDiscardInFlightAvailabilityAndUrlResponses() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val complete = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val provider = provider(catalog = """{"jm":{"size":12345}}""") { request ->
            if (calls.incrementAndGet() == 1) {
                started.complete(Unit)
                complete.await()
                source(request.level, "old-account-source")
            } else {
                source(request.level, "new-account-source")
            }
        }
        val probe = async { provider.getAvailableMusicQualities("1", supportDolby = true) }
        started.await()
        provider.clearQualityState()
        complete.complete(Unit)

        assertTrue(probe.await().isEmpty())
        assertEquals("new-account-source", provider.resolveMediaSource("1", "jymaster").resolution?.resource?.contentId)
        assertEquals(2, calls.get())
    }

    @Test
    fun localCloudCopyStillOffersDownloadQualitiesWithoutChangingAnotherPlayingSong() = runBlocking {
        val localSong = Song(
            id = "1", title = "Downloaded song", artist = emptyList(), album = "", cover = "", duration = 1,
            path = "content://example.test/local-copy",
        )
        val provider = provider(
            catalog = """{"l":{"br":128000},"sq":{"size":12345},"jm":{"size":23456},"dl":{"size":34567}}""",
            localSong = localSong,
        ) { request -> source(request.level, "${request.level}-download") }
        val observed = mutableListOf<Pair<String, ResolvedMediaSource>>()
        provider.onSourceResolved = { id, source -> observed += id to source }
        provider.rememberCachedSource(
            "2", "exhigh", playbackCacheKey("2", "exhigh", "playing-source", 12345),
            Uri.parse("https://example.test/playing-source"),
        )
        val playing = provider.resolvedMediaSource("2")

        assertEquals(
            listOf(MusicQuality.STANDARD, MusicQuality.LOSSLESS, MusicQuality.JYMASTER),
            provider.getAvailableMusicQualities("1", supportDolby = false),
        )
        assertEquals(
            listOf(MusicQuality.STANDARD, MusicQuality.LOSSLESS, MusicQuality.JYMASTER, MusicQuality.DOLBY),
            provider.getAvailableDownloadQualities("1"),
        )
        assertEquals(playing, provider.resolvedMediaSource("2"))
        assertEquals(null, provider.resolvedMediaSource("1"))
        assertEquals(listOf("2" to playing), observed)
    }

    @Test
    fun decoderRejectedSourceRemainsDownloadableWithIndependentAvailabilityCache() = runBlocking {
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val complete = CompletableDeferred<Unit>()
        val provider = provider(catalog = """{"jm":{"size":12345},"dl":{"size":23456}}""") { request ->
            calls.incrementAndGet()
            if (request.level == "dolby" && !complete.isCompleted) {
                started.complete(Unit)
                complete.await()
            }
            source(request.level, "${request.level}-source")
        }
        provider.resolveMediaSource("1", "jymaster")
        val downloadQuery = async { provider.getAvailableDownloadQualities("1") }
        started.await()
        assertTrue(provider.rejectCurrentSource("1"))
        complete.complete(Unit)
        assertEquals(
            listOf(MusicQuality.JYMASTER, MusicQuality.DOLBY),
            downloadQuery.await(),
        )
        val callsBeforeRejection = calls.get()

        assertEquals(
            listOf(MusicQuality.JYMASTER, MusicQuality.DOLBY),
            provider.getAvailableDownloadQualities("1"),
        )
        assertEquals(callsBeforeRejection, calls.get())
        assertEquals(listOf(MusicQuality.DOLBY), provider.getAvailableMusicQualities("1", true))
        assertEquals("jymaster", provider.resolvedMediaSource("1")?.actualQuality)
    }

    @Test
    fun playbackQualityChangesDoNotCancelAnInFlightDownloadResolution() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val resolver = provider("{}") { request ->
            started.complete(Unit)
            release.await()
            source(request.level, "download-source")
        }
        val download = async { resolver.resolveDownloadSource(
            Track(TrackRef(ProviderIds.NETEASE, "1"), "Track", emptyList()), "lossless") }
        started.await()
        resolver.resetRejectedSources()
        resolver.preferOnlineSource("1")
        release.complete(Unit)
        assertEquals("lossless", download.await().resource.quality)
    }

    @Test
    fun downloadMenuUsesReportedFullLevelsAndExcludesTrialsAndUnavailableSources() = runBlocking {
        val provider = provider(
            catalog = """{"l":{"br":128000},"h":{"br":320000},"sq":{"size":12345},"jm":{"size":23456},"dl":{"size":34567}}""",
        ) { request ->
            when (request.level) {
                "standard" -> source("standard", "standard-source")
                "exhigh" -> source("exhigh", "unavailable", code = 404)
                "lossless" -> source("lossless", "trial", trial = true)
                "jymaster" -> source("jyeffect", "actual-surround-source")
                "dolby" -> source("dolby", "dolby-source")
                else -> error("Unexpected candidate")
            }
        }

        assertEquals(
            listOf(MusicQuality.STANDARD, MusicQuality.JYEFFECT, MusicQuality.DOLBY),
            provider.getAvailableDownloadQualities("1"),
        )
        assertEquals(null, provider.resolvedMediaSource("1"))
    }

    @Test
    fun downloadMenuDoesNotSeedUnverifiedPlayingQuality() = runBlocking {
        val provider = provider(catalog = "{}") { error("No catalog candidate should be queried") }
        provider.rememberCachedSource(
            "1", "jymaster", playbackCacheKey("1", "jymaster", "saved-source", 12345),
            Uri.parse("https://example.test/saved-source"),
        )

        assertTrue(provider.getAvailableDownloadQualities("1").isEmpty())
        assertEquals(listOf(MusicQuality.JYMASTER), provider.getAvailableMusicQualities("1", true))
    }

    @Test
    fun accountChangesDiscardInFlightDownloadAvailabilityAndUrls() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val complete = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val provider = provider(catalog = """{"jm":{"size":12345}}""") { request ->
            if (calls.incrementAndGet() == 1) {
                started.complete(Unit)
                complete.await()
                source(request.level, "old-account-source")
            } else {
                source(request.level, "new-account-source")
            }
        }
        val query = async { provider.getAvailableDownloadQualities("1") }
        started.await()
        provider.clearQualityState()
        complete.complete(Unit)

        assertTrue(query.await().isEmpty())
        assertEquals(listOf(MusicQuality.JYMASTER), provider.getAvailableDownloadQualities("1"))
        assertEquals("new-account-source", provider.resolveMediaSource("1", "jymaster").resolution?.resource?.contentId)
        assertEquals(2, calls.get())
    }

    @Test
    fun downloadedFileAndContentSourcesPublishRealFormatAndCanSwitchOnlineAndBack() = runBlocking {
        for (useContentUri in listOf(false, true)) withLocalWave(useContentUri) { uri ->
            val song = Song(
                id = "1", title = "Downloaded audio", artist = emptyList(), album = "", cover = "", duration = 100,
                path = uri.toString(), sourceType = SourceType.DOWNLOAD,
            )
            val provider = provider(
                catalog = """{"l":{"br":128000},"jm":{"size":12345}}""", localSong = song,
            ) { request -> source(request.level, "${request.level}-online") }
            val observed = mutableListOf<ResolvedMediaSource>()
            provider.onSourceResolved = { _, resolved -> observed += resolved }

            val local = provider.resolveMediaSource("1", "jymaster")
            assertEquals(uri, local.uri)
            assertEquals(null, local.cacheKey)
            assertTrue("A local file must not inherit the requested online level", local.actualQuality.isBlank())
            assertNotNull(local.localPlaybackInfo)
            assertEquals("audio/raw", local.localPlaybackInfo?.mimeType)
            assertEquals(44_100, local.localPlaybackInfo?.sampleRate)
            assertEquals(local, provider.resolvedMediaSource("1"))
            assertEquals(listOf(local), observed)

            assertEquals(listOf(MusicQuality.STANDARD, MusicQuality.JYMASTER), provider.getAvailableMusicQualities("1", true))
            assertEquals("Probing online choices must retain the local playing source", listOf(local), observed)
            provider.preferOnlineSource("1")
            val online = provider.resolveMediaSource("1", "jymaster")
            assertEquals("https", online.uri.scheme)
            assertEquals("jymaster", online.actualQuality)
            assertNotNull(online.cacheKey)
            assertEquals(null, online.localPlaybackInfo)
            assertEquals(local.localPlaybackInfo, provider.getLocalPlaybackInfo("1"))

            provider.preferLocalSource("1")
            assertEquals(local, provider.resolveMediaSource("1", "jymaster"))
            provider.preferOnlineSource("1")
            provider.clearQualityState()
            assertEquals("Account reset must return to the intact local copy", local, provider.resolveMediaSource("1", "jymaster"))
        }
    }

    @Test
    fun negativePodcastIdentityNeverOpensTheUnrelatedLocalFile() = runBlocking {
        withLocalWave(false) { uri ->
            val local = Song("local_99", "Local recording", emptyList(), "", "", 100,
                sourceType = SourceType.LOCAL, path = uri.toString())
            val calls = AtomicInteger()
            val resolver = provider("{}", localSong = local) {
                calls.incrementAndGet()
                source(it.level, "unavailable", code = 404)
            }
            val podcast = Track(TrackRef(ProviderIds.NETEASE, "-99"), "Podcast", emptyList()).toMediaMetadata()
                .copy(isPodcast = true)
            resolver.rememberTrack(podcast, "-99")
            assertEquals(null, resolver.resolveLocalSource("-99"))
            try {
                resolver.resolveMediaSource("-99", "exhigh")
                fail("A missing podcast source must not become local_99")
            } catch (_: SourceNotFoundException) { }
            assertTrue(calls.get() > 0)
            resolver.rememberTrack(local.toMediaMetadata(), "-99")
            assertEquals(uri, resolver.resolveMediaSource("-99", "exhigh").uri)
        }
    }

    @Test
    fun migratedNegativeLocalRowRetainsItsOriginalFileAndCannotBecomeAPodcastCopy() = runBlocking {
        withLocalWave(false) { uri ->
            val legacy = Song("-77", "Legacy local recording", emptyList(), "", "", 100,
                sourceType = SourceType.LOCAL, path = uri.toString())
            val alias = legacy.copy(id = "local_77", path = "content://example.test/different-copy")
            val resolver = provider("{}", localSong = legacy, extraSongs = listOf(alias)) {
                error("A migrated local file must not resolve online")
            }
            resolver.rememberTrack(legacy.toMediaMetadata(), "-77")
            assertEquals(uri, resolver.resolveMediaSource("-77", "exhigh").uri)
            assertEquals(legacy.path, resolver.resolveLocalSource("-77")?.uri.toString())
            val podcast = Track(TrackRef(ProviderIds.NETEASE, "-77"), "Podcast", emptyList())
                .toMediaMetadata().copy(isPodcast = true)
            resolver.rememberTrack(podcast, "-77")
            assertEquals(null, resolver.resolveLocalSource("-77"))
            assertEquals(null, resolver.resolveLocalSource("77"))
        }
    }

    @Test
    fun downloadedFallbackRetainsItsActualSourceAndCannotEmitOfficialHistory() = runBlocking {
        withLocalWave(false) { uri ->
            val origin = TrackRef(ProviderIds.NETEASE, "1")
            val actual = TrackRef("fixture", "1")
            val song = Song("1", "Downloaded recording", emptyList(), "", "", 100,
                sourceType = SourceType.DOWNLOAD, path = uri.toString())
            val task = DownloadTask("1", originTrackRef = TrackRefCodec.encode(origin),
                resolvedTrackRef = TrackRefCodec.encode(actual), actualAudioProviderId = actual.providerId,
                actualProviderInstanceId = "fixture:account", actualQuality = "standard")
            val resolver = provider("{}", localSong = song, downloadTasks = listOf(task)) {
                error("A downloaded file must not resolve online")
            }
            val source = resolver.resolveMediaSource("1", "lossless")
            assertEquals(uri, source.uri)
            assertNotNull(source.localPlaybackInfo)
            assertEquals(actual, source.resolution?.resolvedTrackRef)
            assertEquals("standard", source.resolution?.resource?.quality)
            assertFalse(canReportProviderPlayback(origin, source.resolution))
        }
    }

    @Test
    fun importedLocalSourceNeverQueriesAnUnrelatedOnlineSongWithTheSameNumericId() = runBlocking {
        for (hasOnlineRecord in listOf(false, true)) withLocalWave(useContentUri = true) { uri ->
            val onlineCalls = AtomicInteger()
            val song = Song(
                id = "local_1", title = "Imported audio", artist = emptyList(), album = "", cover = "", duration = 100,
                path = uri.toString(), sourceType = SourceType.LOCAL,
            )
            val provider = provider(
                catalog = """{"jm":{"size":12345}}""", localSong = song,
                extraSongs = if (hasOnlineRecord) listOf(song.copy(id = "1", path = null, sourceType = SourceType.STREAM)) else emptyList(),
                onDetailRequested = { onlineCalls.incrementAndGet() },
            ) {
                onlineCalls.incrementAndGet()
                error("Imported audio must never request an online source")
            }
            provider.preferOnlineSource("-1")
            val local = provider.resolveMediaSource("-1", "jymaster")
            assertEquals(uri, local.uri)
            assertNotNull(local.localPlaybackInfo)
            assertTrue(provider.getAvailableMusicQualities("-1", true).isEmpty())
            assertEquals(null, local.cacheKey)
            assertEquals(0, onlineCalls.get())
            assertEquals("A catalog ID must never resolve its unrelated device-library row", null, provider.resolveLocalSource("1"))
        }
    }

    private suspend fun withLocalWave(useContentUri: Boolean, block: suspend (Uri) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = context.filesDir.resolve("quality-regression-${UUID.randomUUID()}.wav")
        val pcmBytes = 4_410 * 4
        val wave = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + pcmBytes).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1.toShort()).putShort(2.toShort()).putInt(44_100)
            .putInt(44_100 * 4).putShort(4.toShort()).putShort(16.toShort())
            .put("data".toByteArray()).putInt(pcmBytes).array()
        file.writeBytes(wave)
        try {
            val uri = if (useContentUri) FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            else Uri.fromFile(file)
            block(uri)
            assertTrue("Selecting an online source must retain the original local bytes", wave.contentEquals(file.readBytes()))
        } finally {
            file.delete()
        }
    }

    private fun provider(
        catalog: String,
        localSong: Song? = null,
        extraSongs: List<Song> = emptyList(),
        additionalProviders: List<MusicProvider> = emptyList(),
        downloadTasks: List<DownloadTask> = emptyList(),
        sourceSettings: SourceSettings = SourceSettings(),
        onDetailRequested: () -> Unit = {},
        response: suspend (GetSongUrlV1) -> SongUrl,
    ): MediaUriProvider {
        val track = gson.fromJson(catalog, JsonObject::class.java).apply { addProperty("id", 1) }
        val detail = gson.fromJson(
            """{"code":200,"songs":[$track],"privileges":[]}""",
            Tracks::class.java,
        )
        val songDao = Proxy.newProxyInstance(
            SongDao::class.java.classLoader,
            arrayOf(SongDao::class.java),
        ) { _, method, arguments ->
            check(method.name == "getSong") { "Unexpected database call: ${method.name}" }
            flowOf((listOfNotNull(localSong) + extraSongs).firstOrNull { it.id == arguments!![0] })
        } as SongDao
        val native = object : MusicProvider, PlaybackCapability, QualityCapability {
            override val id = ProviderIds.NETEASE
            override val displayName = "Playback fixture"
            override val capabilities = setOf(SourceCapability.PLAYBACK)
            override val isBuiltIn = true
            override suspend fun resolve(ref: TrackRef, requestedQuality: String): AudioResource {
                val result = response(GetSongUrlV1("[${ref.trackId}]", requestedQuality))
                if (result.code in setOf(301, 401, 403)) throw SourceException(SourceErrorCode.AUTH_REQUIRED)
                if (result.code != 200) throw SourceException(SourceErrorCode.NETWORK)
                val resource = result.fullSourceFor(ref.trackId) ?: throw SourceException(SourceErrorCode.UNAVAILABLE)
                return AudioResource(resource.url!!, resource.level.orEmpty(),
                    expiresAtMs = System.currentTimeMillis() + ((resource.expi?.toLong() ?: 300) * 1000 - 30_000).coerceAtLeast(1000),
                    contentLength = resource.size.toLong().takeIf { it > 0 },
                    contentId = playbackSourceIdentity(resource.md5, resource.size.toLong()))
            }
            override suspend fun availableQualities(ref: TrackRef): List<String> {
                onDetailRequested()
                return detail.songs.first().availableMusicQualities(detail.privileges.firstOrNull()).map { it.text }
            }
        }
        val registry = ProviderRegistry(listOf(native) + additionalProviders)
        val downloads = if (downloadTasks.isEmpty()) null else DownloadRepository(
            Proxy.newProxyInstance(DownloadDao::class.java.classLoader, arrayOf(DownloadDao::class.java)) { _, method, args ->
                check(method.name == "getBySongId")
                downloadTasks.firstOrNull { it.songId == args!![0] }
            } as DownloadDao, songDao)
        return MediaUriProvider(PlaybackResolver(registry, { sourceSettings }), SourceRouter(registry),
            SongRepository(songDao), InstrumentationRegistry.getInstrumentation().targetContext, downloads)
    }

    private fun source(
        level: String,
        md5: String,
        code: Int = 200,
        trial: Boolean = false,
    ): SongUrl = gson.fromJson(
        """{"code":200,"data":[{"id":1,"code":$code,"url":"https://example.test/$md5","level":"$level","md5":"$md5","size":12345,"expi":300,"freeTrialInfo":${if (trial) "{\"start\":0,\"end\":30}" else "null"}}]}""",
        SongUrl::class.java,
    )
}
