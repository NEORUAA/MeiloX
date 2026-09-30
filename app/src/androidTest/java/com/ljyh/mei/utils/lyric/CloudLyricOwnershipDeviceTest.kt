package com.ljyh.mei.utils.lyric

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ljyh.mei.data.model.Lyric
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.model.api.GetCloudLyric
import com.ljyh.mei.data.model.api.GetLyricV1
import com.ljyh.mei.data.model.qq.u.GetLyricData
import com.ljyh.mei.data.model.qq.u.LyricResult
import com.ljyh.mei.data.model.qq.u.SearchResult
import com.ljyh.mei.data.model.room.CachedLyric
import com.ljyh.mei.data.model.room.QQSong
import com.ljyh.mei.data.network.QQMusicUApiService
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.repository.PlayerRepository
import com.ljyh.mei.data.repository.SongFavoritesBackend
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.di.repository.CachedLyricRepository
import com.ljyh.mei.di.repository.QQSongRepository
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSource
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Closed transport and private in-memory Room fixtures; no account or media mutations. */
@RunWith(AndroidJUnit4::class)
class CloudLyricOwnershipDeviceTest {
    private val source = SongSourceIdentity(999, 88, 7, 17)
    private fun metadata(source: SongSourceIdentity? = this.source) = MediaMetadata(
        source?.entryId ?: 17, "Synthetic", "", listOf(MediaMetadata.Artist(1, "Test")),
        1000, MediaMetadata.Album(1, "Test"), source = source,
    )
    private fun cloud(text: String) = JsonObject().apply {
        addProperty("code", 200)
        addProperty("lrc", "[00:01.00]$text")
    }
    private fun cached(key: String, text: String) = CachedLyric(
        songId = key, content = "[00:01.00]$text", translation = null, isVerbatim = false,
        isPureMusic = false, sourceName = LyricSource.NetEaseCloudMusic.name, parserType = "LRC", updatedAt = 1,
    )
    private fun selection(id: Long = 123) = Gson().fromJson(
        """{"id":$id,"title":"Synthetic QQ","album":{"title":"Test"},"singer":[{"name":"Test"}],"interval":1}""",
        SearchResult.Request.Data.Body.ItemSong::class.java,
    )
    private fun emptyQQ() = Gson().fromJson(
        """{"music.musichallSong.PlayLyricInfo.GetPlayLyricInfo":{"data":{"lyric":"","trans":"","roma":"","qrc_t":0}}}""",
        LyricResult::class.java,
    )
    private fun emptySearch() = Gson().fromJson(
        """{"request":{"data":{"body":{"item_song":[]}}}}""", SearchResult::class.java,
    )
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun awaitLyrics(f: Fixture, text: String): LyricData = withTimeout(5_000) {
        f.manager.lyricData.first { it.source == LyricSource.NetEaseCloudMusic && it.lyricLine.toString().contains(text) }
    }
    private suspend fun awaitCondition(block: suspend () -> Boolean) = withTimeout(5_000) {
        while (!withContext(Dispatchers.Main) { block() }) delay(10)
    }

    @Test fun currentCloudUsesAudioFileOwnerAndSourceSpecificRoomAndQQKeys() = runBlocking {
        Fixture().use { f ->
            f.cache.insert(cached("17", "catalog collision"))
            f.qq.insertSong(QQSong("17", "321", "Wrong mapping", "Test", "Test", 1))
            f.net = { name, args ->
                assertEquals("getCloudLyric", name)
                assertEquals(GetCloudLyric(999, 88), args[0])
                assertEquals(f.sessions.snapshot(), args[1])
                cloud("cloud alpha")
            }
            main { f.manager.loadLyrics(metadata()) }
            awaitLyrics(f, "cloud alpha")
            awaitCondition { f.cache.get(source.key).first()?.content?.contains("cloud alpha") == true }
            assertTrue(f.amPaths.contains("/ncm-lyrics/999.ttml"))
            assertFalse(f.amPaths.any { it.contains("17.ttml") || it.contains("meilox-cloud") })
            assertTrue(f.cache.get("17").first()!!.content.contains("catalog collision"))
            assertTrue(f.qqCalls.isEmpty())
        }
    }

    @Test fun roomCloudCacheNeverFallsBackToEntryCatalogOrAnotherAccount() = runBlocking {
        Fixture().use { f ->
            val gate = f.gate()
            f.cache.insert(cached("17", "catalog collision"))
            f.cache.insert(cached(source.copy(accountId = 8).key, "other account"))
            f.cache.insert(cached(source.key, "owned cache"))
            f.net = { _, _ -> withContext(NonCancellable) { gate.await() }; cloud("new owned") }
            main { f.manager.loadLyrics(metadata()) }
            awaitLyrics(f, "owned cache")
            assertEquals(source.key, f.manager.sourceKey)
            gate.complete(Unit)
            delay(200)
            assertTrue(f.manager.lyricData.value.lyricLine.toString().contains("owned cache"))
        }
    }

    @Test fun sameEntryDifferentCloudFileRejectsLateNetworkAndSampledResults() = runBlocking {
        Fixture().use { f ->
            val gate = f.gate()
            val entered = CompletableDeferred<Unit>()
            val second = source.copy(songId = 1000, cloudOwnerId = 89)
            f.net = { _, args ->
                if ((args[0] as GetCloudLyric).songId == 999L) {
                    entered.complete(Unit)
                    withContext(NonCancellable) { gate.await() }
                    cloud("old file")
                } else cloud("new file")
            }
            main { f.manager.loadLyrics(metadata()) }
            withTimeout(5_000) { entered.await() }
            main { f.manager.loadLyrics(metadata(second)) }
            awaitLyrics(f, "new file")
            gate.complete(Unit)
            delay(200)
            assertEquals(second.key, f.manager.sourceKey)
            assertTrue(f.manager.lyricData.value.lyricLine.toString().contains("new file"))
            assertNull(f.cache.get(source.key).first())
        }
    }

    @Test fun forceReloadSameKeyRejectsThePreviousBatch() = runBlocking {
        Fixture().use { f ->
            val gate = f.gate()
            val entered = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            f.net = { _, _ ->
                if (calls.incrementAndGet() == 1) {
                    entered.complete(Unit)
                    withContext(NonCancellable) { gate.await() }
                    cloud("old batch")
                } else cloud("new batch")
            }
            main { f.manager.loadLyrics(metadata()) }
            withTimeout(5_000) { entered.await() }
            main { f.manager.loadLyrics(metadata(), forceReload = true) }
            awaitLyrics(f, "new batch")
            gate.complete(Unit)
            delay(200)
            assertTrue(f.manager.lyricData.value.lyricLine.toString().contains("new batch"))
            awaitCondition { f.cache.get(source.key).first()?.content?.contains("new batch") == true }
        }
    }

    @Test fun sessionInvalidationClearsCurrentLyricsAndRejectsOldAccountMetadata() = runBlocking {
        Fixture().use { f ->
            main { f.manager.loadLyrics(metadata()) }
            awaitLyrics(f, "owned")
            f.sessions.setRecoveryRequired(true)
            f.sessions.invalidate()
            awaitCondition { f.manager.sourceKey == null }
            assertEquals(LyricSource.Loading, f.manager.lyricData.value.source)
            f.sessions.setRecoveryRequired(false)
            awaitLyrics(f, "owned")
            f.identity = SessionIdentity(8, true, false)
            f.sessions.invalidate()
            awaitCondition { f.manager.sourceKey == null }
            val before = f.netCalls.get()
            main { f.manager.loadLyrics(metadata()) }
            delay(150)
            assertNull(f.manager.sourceKey)
            assertEquals(before, f.netCalls.get())
            assertEquals(LyricSource.Loading, f.manager.lyricData.value.source)
        }
    }

    @Test fun preloadUsesTheCapturedCloudSourceAndRejectsLateReauthorization() = runBlocking {
        Fixture().use { f ->
            f.net = { name, args ->
                assertEquals("getCloudLyric", name)
                assertEquals(GetCloudLyric(999, 88), args[0])
                cloud("preloaded")
            }
            val result = f.preloader.preload(metadata())!!
            assertTrue(result.lyricLine.toString().contains("preloaded"))
            assertEquals(listOf("/ncm-lyrics/999.ttml"), f.amPaths.toList())
            val gate = f.gate()
            val entered = CompletableDeferred<Unit>()
            f.net = { _, _ ->
                entered.complete(Unit)
                withContext(NonCancellable) { gate.await() }
                cloud("stale preload")
            }
            val job = async { runCatching { f.preloader.preload(metadata()) } }
            withTimeout(5_000) { entered.await() }
            f.sessions.invalidate()
            gate.complete(Unit)
            assertTrue(withTimeout(5_000) { job.await() }.exceptionOrNull() is SessionChangedException)
        }
    }

    @Test fun stalePreloadSearchCannotPublishQQMapping() = runBlocking {
        Fixture().use { f ->
            val gate = f.gate()
            val entered = CompletableDeferred<Unit>()
            f.search = {
                entered.complete(Unit)
                withContext(NonCancellable) { gate.await() }
                Gson().fromJson("""{"request":{"data":{"body":{"item_song":[{"id":123,"interval":1}]}}}}""", SearchResult::class.java)
            }
            val job = async { runCatching { f.preloader.preload(metadata()) } }
            withTimeout(5_000) { entered.await() }
            f.sessions.invalidate()
            gate.complete(Unit)
            assertTrue(withTimeout(5_000) { job.await() }.exceptionOrNull() is SessionChangedException)
            assertNull(f.qq.getQQSong(source.key).first())
        }
    }

    @Test fun invalidSourceAccountAndRecoveryDoNotDispatchAnyLyricSource() = runBlocking {
        Fixture().use { f ->
            for (item in listOf(metadata(source.copy(accountId = 8)), metadata().copy(id = 18))) {
                assertTrue(runCatching { f.preloader.preload(item) }.isFailure)
                main { f.manager.loadLyrics(item) }
            }
            f.sessions.setRecoveryRequired(true)
            assertTrue(runCatching { f.preloader.preload(metadata()) }.exceptionOrNull() is SessionChangedException)
            main { f.manager.loadLyrics(metadata()) }
            delay(200)
            assertEquals(0, f.netCalls.get())
            assertTrue(f.amPaths.isEmpty())
            assertTrue(f.qqCalls.isEmpty())
            assertEquals(0, f.searchCalls.get())
        }
    }

    @Test fun manualQQStartsANewBatchAndPersistsOnlyTheCloudMappingKey() = runBlocking {
        Fixture().use { f ->
            val gate = f.gate()
            val entered = CompletableDeferred<Unit>()
            f.net = { _, _ ->
                entered.complete(Unit)
                withContext(NonCancellable) { gate.await() }
                cloud("automatic old")
            }
            main { f.manager.loadLyrics(metadata()) }
            withTimeout(5_000) { entered.await() }
            main { f.manager.selectQQSongForLyric(metadata(), selection()) }
            awaitCondition { f.qqCalls.isNotEmpty() }
            assertEquals(123L, f.qqCalls.single().getPlayLyricInfo.param.songID)
            assertEquals("123", f.qq.getQQSong(source.key).first()?.qid)
            assertNull(f.qq.getQQSong("17").first())
            gate.complete(Unit)
            delay(200)
            assertFalse(f.manager.lyricData.value.lyricLine.toString().contains("automatic old"))
            assertNull(f.cache.get(source.key).first())
        }
    }

    @Test fun manualSearchLateResultCannotReplaceTheNextSourcesSearchState() = runBlocking {
        Fixture().use { f ->
            main { f.manager.loadLyrics(metadata()) }
            awaitLyrics(f, "owned")
            val gate = f.gate()
            val entered = CompletableDeferred<Unit>()
            f.search = { args ->
                if (args[0].toString().contains("manual-keyword")) {
                    entered.complete(Unit)
                    withContext(NonCancellable) { gate.await() }
                    emptySearch()
                } else throw IOException("Synthetic no match")
            }
            main { f.manager.searchQQSong("manual-keyword") }
            withTimeout(5_000) { entered.await() }
            main { f.manager.loadLyrics(metadata(source.copy(songId = 1000))) }
            awaitLyrics(f, "owned")
            gate.complete(Unit)
            delay(200)
            assertFalse(f.manager.qqSearchResult.value is Resource.Success)
        }
    }

    @Test fun amllCancellationCancelsTheActualCallWithoutWaitingForTheBody() = runBlocking {
        Fixture().use { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            var call: Call? = null
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                call = chain.call()
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(404).message("Synthetic").body("".toResponseBody()).build()
            }.build()
            try {
                val repository = f.repository(client)
                val job = async { repository.getAMLLyric("999") }
                withTimeout(5_000) { entered.await() }
                job.cancelAndJoin()
                assertTrue(call!!.isCanceled())
            } finally {
                release.countDown()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test fun qqMappingReadAndResetKeepAllExistingPlayerControlsSourceOwned() = runBlocking {
        Fixture().use { f ->
            f.qq.insertSong(QQSong("17", "321", "Catalog", "Test", "Test", 1))
            f.qq.insertSong(QQSong(source.key, "456", "Cloud", "Test", "Test", 1))
            assertEquals("456", f.manager.getQQSongId(metadata()))
            assertEquals("321", f.manager.getQQSongId(metadata(null)))
            main { f.manager.loadLyrics(metadata()) }
            awaitLyrics(f, "owned")
            main { f.manager.resetQQSongForLyric(metadata(source.copy(cloudOwnerId = 89))) }
            assertEquals("456", f.qq.getQQSong(source.key).first()?.qid)
            main { f.manager.resetQQSongForLyric(metadata()) }
            awaitCondition { f.qq.getQQSong(source.key).first() == null }
            awaitLyrics(f, "owned")
            assertEquals("321", f.qq.getQQSong("17").first()?.qid)
            assertNull(f.manager.getQQSongId(metadata(source.copy(accountId = 8))))
            f.sessions.setRecoveryRequired(true)
            assertNull(f.manager.getQQSongId(metadata(null)))
        }
    }

    @Test fun canceledPreloadNeverReturnsASuccessfulEmptyLyric() = runBlocking {
        Fixture().use { f ->
            val gate = f.gate()
            val entered = CompletableDeferred<Unit>()
            f.net = { _, _ ->
                entered.complete(Unit)
                withContext(NonCancellable) { gate.await() }
                cloud("late canceled")
            }
            val job = async { f.preloader.preload(metadata()) }
            withTimeout(5_000) { entered.await() }
            job.cancel()
            gate.complete(Unit)
            job.join()
            assertTrue(job.isCancelled)
            assertTrue(runCatching { job.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        }
    }

    @Test fun startupRecoveryRetriesTheSameMetadataWithoutAFrontendRemount() = runBlocking {
        Fixture().use { f ->
            f.sessions.setRecoveryRequired(true)
            main { f.manager.loadLyrics(metadata()) }
            assertNull(f.manager.sourceKey)
            assertEquals(0, f.netCalls.get())
            f.sessions.setRecoveryRequired(false)
            awaitLyrics(f, "owned")
            assertEquals(source.key, f.manager.sourceKey)
            assertEquals(1, f.netCalls.get())
        }
    }

    @Test fun sameAccountTransitionReloadsAndRejectsItsPreviousResponse() = runBlocking {
        Fixture().use { f ->
            val gate = f.gate()
            val entered = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            f.net = { _, _ ->
                if (calls.incrementAndGet() == 1) {
                    entered.complete(Unit)
                    withContext(NonCancellable) { gate.await() }
                    cloud("old authorization")
                } else cloud("new authorization")
            }
            main { f.manager.loadLyrics(metadata()) }
            withTimeout(5_000) { entered.await() }
            val transition = f.sessions.beginTransition()
            awaitCondition { f.manager.sourceKey == null }
            transition.close()
            awaitLyrics(f, "new authorization")
            gate.complete(Unit)
            delay(200)
            assertTrue(f.manager.lyricData.value.lyricLine.toString().contains("new authorization"))
            awaitCondition { f.cache.get(source.key).first()?.content?.contains("new authorization") == true }
        }
    }

    @Test fun ordinaryCatalogStillUsesV1AndTheNumericCacheNamespace() = runBlocking {
        Fixture().use { f ->
            f.net = { name, args ->
                assertEquals("getLyricV1", name)
                assertEquals(GetLyricV1("17"), args[0])
                Gson().fromJson("""{"code":200,"lrc":{"lyric":"[00:01.00]ordinary"}}""", Lyric::class.java)
            }
            main { f.manager.loadLyrics(metadata(null)) }
            awaitLyrics(f, "ordinary")
            awaitCondition { f.cache.get("17").first() != null }
            assertEquals("17", f.manager.sourceKey)
            assertEquals(listOf("/ncm-lyrics/17.ttml"), f.amPaths.toList())
        }
    }

    private inner class Fixture : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val cache = CachedLyricRepository(db.cachedLyricDao())
        val qq = QQSongRepository(db.qqSongDao())
        @Volatile var identity = SessionIdentity(7, true, false)
        val sessions = SessionStore().apply { bind { identity } }
        val netCalls = AtomicInteger()
        val searchCalls = AtomicInteger()
        val qqCalls = CopyOnWriteArrayList<GetLyricData>()
        val amPaths = CopyOnWriteArrayList<String>()
        private val gates = mutableListOf<CompletableDeferred<Unit>>()
        @Volatile var net: suspend (String, List<Any?>) -> Any = { _, _ -> cloud("owned") }
        @Volatile var search: suspend (List<Any?>) -> Any = { throw IOException("Synthetic no match") }
        val api = proxy(ApiService::class.java) { name, args -> netCalls.incrementAndGet(); net(name, args) }
        private val qqApi = proxy(QQMusicUApiService::class.java) { name, args ->
            when (name) {
                "search" -> { searchCalls.incrementAndGet(); search(args) }
                "getLyric" -> { qqCalls += args[0] as GetLyricData; emptyQQ() }
                else -> error("Unexpected QQ operation")
            }
        }
        private val weApi = proxy(WeApiService::class.java) { _, _ -> error("Unexpected legacy request") }
        private val favorites = object : SongFavoritesBackend {
            override suspend fun isLiked(id: Long, owner: SessionStamp) = error("Unexpected favorite read")
            override suspend fun setLiked(id: Long, liked: Boolean, owner: SessionStamp) = error("Unexpected favorite write")
        }
        private val amClient = OkHttpClient.Builder().addInterceptor { chain ->
            amPaths += chain.request().url.encodedPath
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(404).message("Synthetic").body("".toResponseBody()).build()
        }.build()
        fun repository(client: OkHttpClient = amClient) = PlayerRepository(
            qqApi, api, weApi, sessions, favorites, amllClient = client,
        )
        val repository = repository()
        val preloader = LyricPreloader(repository, qq, sessions)
        val manager = LyricManager(repository, qq, cache, DuetDetector(), preloader, context, sessions)
        fun gate() = CompletableDeferred<Unit>().also { gates += it }
        override fun close() {
            gates.forEach { it.complete(Unit) }
            runBlocking { main { manager.release() }; delay(100) }
            amClient.dispatcher.executorService.shutdown()
            db.close()
        }
    }

    private fun <T : Any> proxy(type: Class<T>, block: suspend (String, List<Any?>) -> Any): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            @Suppress("UNCHECKED_CAST")
            val continuation = args!!.last() as Continuation<Any?>
            val work: suspend () -> Any = { block(method.name, args.dropLast(1)) }
            work.startCoroutine(continuation)
            COROUTINE_SUSPENDED
        },
    )
}
