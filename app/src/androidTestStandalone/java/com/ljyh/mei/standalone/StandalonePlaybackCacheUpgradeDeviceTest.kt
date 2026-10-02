package com.ljyh.mei.standalone

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.ljyh.mei.data.model.SongUrl
import com.ljyh.mei.data.model.api.GetSongUrlV1
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.di.dao.SongDao
import com.ljyh.mei.di.repository.SongRepository
import com.ljyh.mei.playback.CacheManager
import com.ljyh.mei.playback.MediaUriProvider
import com.ljyh.mei.playback.PlaybackUrl
import com.ljyh.mei.playback.PlaybackUrlResolver
import com.ljyh.mei.playback.playbackCacheKey
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StandalonePlaybackCacheUpgradeDeviceTest {
    private val owner = SessionIdentity(10, true, false)
    private val second = SessionIdentity(20, true, false)
    private val bytes = ByteArray(256) { (it % 127).toByte() }
    private val digest = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun resolvedAuthorizedSourceReadsOriginalSpansAndPersistsAffinityAcrossReopen() = runBlocking {
        Fixture().use { fixture ->
            val legacy = fixture.seed(digest)
            assertNull(CacheManager.findFullyCachedPlaybackKey(fixture.cache, "123", "standard", owner))
            val sessions = sessions(owner)
            val stamp = sessions.snapshot()
            var calls = 0
            val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, args ->
                check(method.name == "getSongUrlV1")
                val body = args[0] as GetSongUrlV1
                assertEquals("[123]", body.ids)
                assertEquals("hires", body.level)
                assertEquals(stamp, args[1])
                calls++
                Gson().fromJson(
                    """{"code":200,"data":[{"id":123,"code":200,"url":"https://example.invalid/source","level":"standard","md5":"$digest","size":${bytes.size},"expi":300,"payed":0,"freeTrialInfo":null}]}""",
                    SongUrl::class.java,
                )
            } as ApiService
            val dao = Proxy.newProxyInstance(SongDao::class.java.classLoader, arrayOf(SongDao::class.java)) { _, method, _ ->
                check(method.name == "getSong")
                flowOf<Song?>(null)
            } as SongDao
            val provider = MediaUriProvider(PlaybackUrlResolver(api, sessions), SongRepository(dao), sessions)
            val resolved = provider.resolveMediaSource("123", "hires", stamp)
            val source = checkNotNull(resolved.onlineSource)
            assertEquals("standard", resolved.actualQuality)
            assertEquals(digest, source.sourceMd5)
            assertEquals(bytes.size.toLong(), source.sourceSize)
            val key = CacheManager.authorizedPlaybackKey(fixture.cache, "123", source, sessions, stamp)
            assertEquals(legacy, key)
            assertEquals(1, calls)
            assertEquals(setOf(legacy), fixture.cache.keys)
            assertEquals(bytes.size.toLong(), fixture.cache.cacheSpace)
            assertArrayEquals(bytes, fixture.read(key)) // Null upstream: a cache miss fails instead of making a request.
            assertNull(CacheManager.findFullyCachedPlaybackKey(fixture.cache, "123", "hires", owner))
            fixture.reopen()
            assertEquals(legacy, CacheManager.findFullyCachedPlaybackKey(fixture.cache, "123", "standard", owner.copy()))
            assertNull(CacheManager.findFullyCachedPlaybackKey(fixture.cache, "123", "standard", second))
            assertNull(CacheManager.findFullyCachedPlaybackKey(fixture.cache, "123", "standard", SessionIdentity(0, false, true)))
            assertArrayEquals(bytes, fixture.read(legacy))
            assertEquals(setOf(legacy), fixture.cache.keys)
            assertEquals(bytes.size.toLong(), fixture.cache.cacheSpace)
        }
    }

    @Test fun partialLegacySpanContinuesAtItsBoundaryWithoutMixingOrDuplicatingBytes() {
        Fixture().use { fixture ->
            val initial = 37
            val legacy = fixture.seed(digest, cachedLength = initial)
            val sessions = sessions(owner)
            val source = source(owner)
            val key = CacheManager.authorizedPlaybackKey(fixture.cache, "123", source, sessions, sessions.snapshot())
            assertEquals(legacy, key)
            assertNull(CacheManager.findFullyCachedPlaybackKey(fixture.cache, "123", "standard", owner))
            val opened = mutableListOf<DataSpec>()
            var upstreamBytes = 0
            val upstream = ByteArrayDataSource(bytes)
            val recording = object : DataSource by upstream {
                override fun open(dataSpec: DataSpec): Long { opened += dataSpec; return upstream.open(dataSpec) }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length).also {
                    if (it > 0) upstreamBytes += it
                }
            }
            assertArrayEquals(bytes, fixture.read(key, recording))
            assertEquals(initial.toLong(), opened.single().position)
            assertEquals((bytes.size - initial).toLong(), opened.single().length)
            assertEquals(bytes.size - initial, upstreamBytes)
            assertEquals(legacy, CacheManager.findFullyCachedPlaybackKey(fixture.cache, "123", "standard", owner))
            assertEquals(bytes.size.toLong(), fixture.cache.cacheSpace)
            assertEquals(setOf(legacy), fixture.cache.keys)
            fixture.reopen()
            assertArrayEquals(bytes, fixture.read(legacy))
        }
    }

    @Test fun rejectionPersistsPerAccountWhileOtherProofsAndOriginalBytesSurvive() {
        Fixture().use { fixture ->
            val legacy = fixture.seed(digest)
            val firstSession = sessions(owner)
            val secondSession = sessions(second)
            assertEquals(legacy, CacheManager.authorizedPlaybackKey(fixture.cache, "123", source(owner), firstSession, firstSession.snapshot()))
            assertEquals(legacy, CacheManager.authorizedPlaybackKey(fixture.cache, "123", source(second), secondSession, secondSession.snapshot()))
            assertEquals(1, CacheManager.removePlaybackEntries(fixture.cache, "123", owner))
            fixture.reopen()
            assertNull(CacheManager.findFullyCachedPlaybackKey(fixture.cache, "123", "standard", owner))
            assertEquals(legacy, CacheManager.findFullyCachedPlaybackKey(fixture.cache, "123", "standard", second))
            assertEquals(source(owner).cacheKey, CacheManager.authorizedPlaybackKey(fixture.cache, "123", source(owner), firstSession, firstSession.snapshot()))
            assertArrayEquals(bytes, fixture.read(legacy))
            assertEquals(bytes.size.toLong(), fixture.cache.cacheSpace)
        }
    }

    @Test fun authorizedLegacyWavDecodesToCompletionWithNoNetworkUpstream() {
        val pcm = ByteArray(48_000)
        val wav = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + pcm.size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1); putInt(48_000); putInt(96_000); putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(pcm.size); put(pcm)
        }.array()
        val md5 = MessageDigest.getInstance("MD5").digest(wav).joinToString("") { "%02x".format(it) }
        Fixture(wav).use { fixture ->
            val legacy = fixture.seed(md5)
            val sessions = sessions(owner)
            val source = PlaybackUrl("https://example.invalid/cache.wav", "standard",
                playbackCacheKey("123", "standard", md5, wav.size.toLong(), owner), md5, wav.size.toLong())
            val key = CacheManager.authorizedPlaybackKey(fixture.cache, "123", source, sessions, sessions.snapshot())
            assertEquals(legacy, key)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val ended = CountDownLatch(1)
            val error = AtomicReference<PlaybackException>()
            var player: ExoPlayer? = null
            try {
                instrumentation.runOnMainSync {
                    val factory = CacheDataSource.Factory().setCache(fixture.cache)
                    val current = ExoPlayer.Builder(instrumentation.targetContext)
                        .setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build()
                    player = current
                    current.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED) ended.countDown()
                        }
                        override fun onPlayerError(playbackError: PlaybackException) {
                            error.set(playbackError)
                            ended.countDown()
                        }
                    })
                    current.setMediaItem(MediaItem.Builder().setUri(source.url).setCustomCacheKey(key).build())
                    current.prepare()
                    current.play()
                }
                assertTrue("Cache-only WAV did not finish", ended.await(15, TimeUnit.SECONDS))
                assertNull(error.get())
                var state = Player.STATE_IDLE
                var duration = 0L
                var format: androidx.media3.common.Format? = null
                var renderedBuffers = 0
                instrumentation.runOnMainSync {
                    val current = checkNotNull(player)
                    state = current.playbackState
                    duration = current.duration
                    format = current.audioFormat
                    renderedBuffers = current.audioDecoderCounters?.renderedOutputBufferCount ?: 0
                }
                assertEquals(Player.STATE_ENDED, state)
                assertEquals(500L, duration)
                assertEquals(48_000, format?.sampleRate)
                assertEquals(1, format?.channelCount)
                assertTrue(renderedBuffers > 0)
                assertEquals(wav.size.toLong(), fixture.cache.cacheSpace)
                assertEquals(setOf(legacy), fixture.cache.keys)
            } finally {
                instrumentation.runOnMainSync { player?.release() }
            }
        }
    }

    private fun sessions(identity: SessionIdentity) = SessionStore().apply { bind { identity } }

    private fun source(identity: SessionIdentity) = PlaybackUrl(
        "https://example.invalid/source", "standard",
        playbackCacheKey("123", "standard", digest, bytes.size.toLong(), identity), digest, bytes.size.toLong(),
    )

    private inner class Fixture(private val content: ByteArray = bytes) : Closeable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val directory = File(context.cacheDir, "standalone-legacy-cache-test-${UUID.randomUUID()}")
        private val database = StandaloneDatabaseProvider(context)
        var cache = SimpleCache(directory, NoOpCacheEvictor(), database)
            private set

        fun seed(md5: String, cachedLength: Int = content.size): String {
            val key = playbackCacheKey("123", "standard", md5, content.size.toLong())
            val hole = cache.startReadWrite(key, 0, cachedLength.toLong())
            try {
                val file = cache.startFile(key, 0, cachedLength.toLong())
                file.writeBytes(content.copyOf(cachedLength))
                cache.commitFile(file, cachedLength.toLong())
                cache.applyContentMetadataMutations(key, ContentMetadataMutations().also {
                    ContentMetadataMutations.setContentLength(it, content.size.toLong())
                })
            } finally { cache.releaseHoleSpan(hole) }
            return key
        }

        fun read(key: String, upstream: DataSource? = null): ByteArray {
            val factory = CacheDataSource.Factory().setCache(cache).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            upstream?.let { factory.setUpstreamDataSourceFactory { it } }
            val source = factory.createDataSource()
            try {
                source.open(DataSpec.Builder().setUri(Uri.parse("https://example.invalid/source")).setKey(key).build())
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(29)
                while (true) {
                    val count = source.read(buffer, 0, buffer.size)
                    if (count == C.RESULT_END_OF_INPUT) break
                    output.write(buffer, 0, count)
                }
                return output.toByteArray()
            } finally { source.close() }
        }

        fun reopen() {
            cache.release()
            cache = SimpleCache(directory, NoOpCacheEvictor(), database)
        }

        override fun close() {
            cache.release()
            SimpleCache.delete(directory, database)
            database.close()
        }
    }
}
