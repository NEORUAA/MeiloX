package com.ljyh.mei.playback

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.SongUrl
import com.ljyh.mei.data.model.Tracks
import com.ljyh.mei.data.model.api.GetSongUrlV1
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.di.dao.SongDao
import com.ljyh.mei.di.repository.SongRepository
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaUriProviderQualityTest {
    private val gson = Gson()

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
        assertEquals("master-source", resolved.sourceIdentity)
        assertEquals(resolved, provider.resolvedMediaSource("1"))
        assertEquals(listOf(resolved), observed)
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
        assertEquals("working-source", fallback.sourceIdentity)
        assertEquals(listOf("jymaster", "jymaster", "hires", "lossless"), calls)
        assertEquals(listOf(MusicQuality.LOSSLESS), provider.getAvailableMusicQualities("1", true))

        provider.resetRejectedSources()
        assertFalse(provider.isRejectedCachedSource("1", failed.cacheKey))
        assertEquals("jymaster", provider.resolveMediaSource("1", "jymaster").actualQuality)
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
        assertEquals("new-account-source", provider.resolveMediaSource("1", "jymaster").sourceIdentity)
        assertEquals(2, calls.get())
    }

    private fun provider(
        catalog: String,
        response: suspend (GetSongUrlV1) -> SongUrl,
    ): MediaUriProvider {
        val track = gson.fromJson(catalog, JsonObject::class.java).apply { addProperty("id", 1) }
        val detail = gson.fromJson(
            """{"code":200,"songs":[$track],"privileges":[]}""",
            Tracks::class.java,
        )
        val api = Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "getSongDetail" -> detail
                "getSongUrlV1" -> {
                    val request = arguments!![0] as GetSongUrlV1
                    @Suppress("UNCHECKED_CAST")
                    val continuation = arguments.last() as Continuation<SongUrl>
                    val operation: suspend () -> SongUrl = { response(request) }
                    operation.startCoroutine(continuation)
                    COROUTINE_SUSPENDED
                }
                else -> error("Unexpected API call: ${method.name}")
            }
        } as ApiService
        val songDao = Proxy.newProxyInstance(
            SongDao::class.java.classLoader,
            arrayOf(SongDao::class.java),
        ) { _, method, _ ->
            check(method.name == "getSong") { "Unexpected database call: ${method.name}" }
            flowOf<Song?>(null)
        } as SongDao
        return MediaUriProvider(api, SongRepository(songDao))
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
