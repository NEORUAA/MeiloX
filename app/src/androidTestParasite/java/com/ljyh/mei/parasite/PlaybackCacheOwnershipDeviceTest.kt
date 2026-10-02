package com.ljyh.mei.parasite

import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.di.dao.SongDao
import com.ljyh.mei.di.repository.SongRepository
import com.ljyh.mei.playback.CacheManager
import com.ljyh.mei.playback.MediaUriProvider
import com.ljyh.mei.playback.PlaybackUrlResolver
import com.ljyh.mei.playback.PlaybackUrl
import com.ljyh.mei.playback.playbackCacheKey
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackCacheOwnershipDeviceTest {
    @Test fun localFilesRemainPlayableDuringLoginRecoveryWithoutNetworkRequests() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("playback-local-test-", ".mp3", context.cacheDir)
        val sessions = HostSessionBridge().apply {
            bind { SessionIdentity(10, true, false) }
            setRecoveryRequired(true)
        }
        val song = Song("local_123", "Local", emptyList(), "", "", 1, path = file.path)
        val dao = Proxy.newProxyInstance(SongDao::class.java.classLoader, arrayOf(SongDao::class.java)) { _, method, args ->
            check(method.name == "getSong")
            flowOf(song.takeIf { args[0] == it.id })
        } as SongDao
        val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, _, _ ->
            error("Local playback must not request an online URL")
        } as ApiService
        val provider = MediaUriProvider(PlaybackUrlResolver(api, sessions), SongRepository(dao), sessions)
        try {
            val owner = sessions.snapshot()
            val source = provider.resolveMediaSource("123", "exhigh", owner)
            assertEquals(file.path, source.uri.path)
            assertNull(source.cacheKey)
            assertNull(source.onlineSource)
            assertTrue(runCatching { provider.resolveMediaSource("456", "exhigh", owner) }
                .exceptionOrNull() is SessionChangedException)
            sessions.invalidate()
            assertTrue(runCatching { provider.resolveMediaSource("123", "exhigh", owner) }
                .exceptionOrNull() is SessionChangedException)
            assertEquals(file.path, provider.resolveMediaSource("123", "exhigh", sessions.snapshot()).uri.path)
        } finally { file.delete() }
    }

    @Test fun fullyCachedLookupAndRemovalCannotCrossAccountBoundaries() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "playback-owner-test-${UUID.randomUUID()}")
        val database = StandaloneDatabaseProvider(context)
        val cache = SimpleCache(directory, NoOpCacheEvictor(), database)
        val first = SessionIdentity(10, true, false)
        val second = SessionIdentity(20, true, false)
        val digest = "a".repeat(32)
        val firstKey = playbackCacheKey("123", "exhigh", digest, 4, first)
        val secondKey = playbackCacheKey("123", "exhigh", digest, 4, second)
        val legacyKey = playbackCacheKey("123", "exhigh", digest, 4)
        fun seed(key: String) {
            val hole = cache.startReadWrite(key, 0, 4)
            try {
                val file = cache.startFile(key, 0, 4)
                file.writeBytes(byteArrayOf(1, 2, 3, 4))
                cache.commitFile(file, 4)
                cache.applyContentMetadataMutations(key, ContentMetadataMutations().also {
                    ContentMetadataMutations.setContentLength(it, 4)
                })
            } finally { cache.releaseHoleSpan(hole) }
        }
        try {
            seed(legacyKey)
            assertNull(CacheManager.findFullyCachedPlaybackKey(cache, "123", "exhigh", first))
            val sessions = HostSessionBridge().apply { bind { first } }
            val source = PlaybackUrl("https://example.invalid/source", "exhigh", firstKey, digest, 4)
            assertEquals(firstKey, CacheManager.authorizedPlaybackKey(cache, "123", source, sessions, sessions.snapshot()))
            assertNull(CacheManager.findFullyCachedPlaybackKey(cache, "123", "exhigh", first))
            seed(firstKey)
            assertEquals(firstKey, CacheManager.findFullyCachedPlaybackKey(cache, "123", "exhigh", first))
            assertNull(CacheManager.findFullyCachedPlaybackKey(cache, "123", "exhigh", second))
            assertNull(CacheManager.findFullyCachedPlaybackKey(cache, "123", "lossless", first))
            seed(secondKey)
            assertEquals(1, CacheManager.removePlaybackEntries(cache, "123", first))
            assertNull(CacheManager.findFullyCachedPlaybackKey(cache, "123", "exhigh", first))
            assertEquals(secondKey, CacheManager.findFullyCachedPlaybackKey(cache, "123", "exhigh", second))
            assertTrue(cache.keys.contains(legacyKey))
        } finally {
            cache.release()
            SimpleCache.delete(directory, database)
            database.close()
        }
    }
}
