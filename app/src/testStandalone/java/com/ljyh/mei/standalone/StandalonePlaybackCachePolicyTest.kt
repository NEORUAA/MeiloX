package com.ljyh.mei.standalone

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.DefaultContentMetadata
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.playback.CacheManager
import com.ljyh.mei.playback.PlaybackUrl
import com.ljyh.mei.playback.playbackCacheKey
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class StandalonePlaybackCachePolicyTest {
    private val first = SessionIdentity(10, true, false)
    private val second = SessionIdentity(20, true, false)
    private val digest = "0123456789abcdef0123456789abcdef"
    private val fixture = CacheFixture()
    private val cache get() = fixture.cache

    @Test fun unverifiedLegacyBytesAreNotAnAccountCache() {
        seed()
        assertNull(find())
        assertEquals(0, CacheManager.removePlaybackEntries(cache, "123", first))
        assertEquals(4L, fixture.bytes.values.single())
    }

    @Test fun authorizedIdentityAndEffectiveQualityReuseOnlyTheExactLegacyFile() {
        val legacy = seed()
        val source = source(quality = " EXHIGH ", md5 = digest.uppercase())
        assertEquals(legacy, authorize(source, mediaId = " 00123 "))
        assertEquals(legacy, find(quality = "EXHIGH"))
        assertNull(find(quality = "lossless"))
        assertEquals(setOf(legacy), cache.keys)
        assertEquals(1, fixture.mutations)
    }

    @Test fun anotherAccountAndGuestCannotInheritAReceipt() {
        seed()
        authorize()
        listOf(second, SessionIdentity(0, false, true), first.copy(authenticated = false), first.copy(anonymous = true))
            .forEach { assertNull(find(it)) }
        assertNotNull(find())
    }

    @Test fun anotherAccountMustIndependentlyAuthorizeTheSameFile() {
        val legacy = seed()
        authorize()
        assertNull(find(second))
        assertEquals(legacy, authorize(source(second), second))
        assertEquals(legacy, find(second))
        assertEquals(legacy, find())
    }

    @Test fun missingOrMalformedDigestsAndSizeOnlyKeysStayUnowned() {
        for (md5 in listOf(null, "", "abc", "g".repeat(32), "0".repeat(31))) {
            val source = source(md5 = md5)
            fixture.seed(playbackCacheKey("123", "exhigh", md5, 4), 4, 4)
            assertEquals(source.cacheKey, authorize(source))
        }
        assertNull(find())
        assertEquals(0, fixture.mutations)
    }

    @Test fun unknownOrMismatchedLengthsAndEmptySpansCannotBeAdopted() {
        for ((length, bytes) in listOf(-1L to 4L, 5L to 4L, 4L to 0L)) {
            fixture.seed(playbackCacheKey("123", "exhigh", digest, 4), length, bytes)
            val source = source()
            assertEquals(source.cacheKey, authorize(source))
            assertNull(find())
        }
        val zero = source(size = 0)
        assertEquals(zero.cacheKey, authorize(zero))
        assertEquals(0, fixture.mutations)
    }

    @Test fun changedSourceSongAndQualityNeverMixOldSpans() {
        seed()
        val changed = source(md5 = "f".repeat(32))
        assertEquals(changed.cacheKey, authorize(changed))
        val differentQuality = source(quality = "lossless")
        assertEquals(differentQuality.cacheKey, authorize(differentQuality))
        val differentSong = source(mediaId = "456")
        assertEquals(differentSong.cacheKey, authorize(differentSong, mediaId = "456"))
        assertNull(find())
        assertEquals(0, fixture.mutations)
    }

    @Test fun partialLegacySpansCanBeContinuedButAreNotReportedComplete() {
        val legacy = seed(bytes = 2)
        assertEquals(legacy, authorize())
        assertNull(find())
        fixture.bytes[legacy] = 4
        assertEquals(legacy, find())
    }

    @Test fun recoveryRetiresOnlyThisOwnersReceiptsAcrossQualities() {
        val legacy = seed()
        val lossless = seed(quality = "lossless")
        authorize()
        authorize(source(second), second)
        authorize(source(quality = "lossless"))
        val scoped = source().cacheKey
        fixture.seed(scoped, 4, 4)
        assertEquals(3, CacheManager.removePlaybackEntries(cache, "123", first))
        assertNull(find())
        assertNull(find(quality = "lossless"))
        assertEquals(legacy, find(second))
        assertEquals(setOf(legacy, lossless), cache.keys)
        assertEquals(source().cacheKey, authorize())
        assertEquals(source(quality = "lossless").cacheKey, authorize(source(quality = "lossless")))
        assertEquals(0, CacheManager.removePlaybackEntries(cache, "123", first))
    }

    @Test fun staleReauthorizedTransitioningAndRecoverySessionsCannotPublishReceipts() {
        seed()
        val sessions = SessionStore().apply { bind { first } }
        val stale = sessions.snapshot()
        sessions.invalidate()
        assertThrows(SessionChangedException::class.java) {
            CacheManager.authorizedPlaybackKey(cache, "123", source(), sessions, stale)
        }
        val owner = sessions.snapshot()
        sessions.beginTransition().use {
            assertThrows(SessionChangedException::class.java) {
                CacheManager.authorizedPlaybackKey(cache, "123", source(), sessions, owner)
            }
        }
        val current = sessions.snapshot()
        sessions.setRecoveryRequired(true)
        assertThrows(SessionChangedException::class.java) {
            CacheManager.authorizedPlaybackKey(cache, "123", source(), sessions, current)
        }
        assertNull(find())
        assertEquals(0, fixture.mutations)
    }

    @Test fun mismatchedOwnedKeysCannotPublishReceipts() {
        seed()
        listOf(source(second), source(mediaId = "456"), source(md5 = null).copy(sourceSize = 5), source().copy(sourceMd5 = "f".repeat(32)))
            .forEach { source -> assertThrows(IllegalArgumentException::class.java) { authorize(source) } }
        assertNull(find())
        assertEquals(0, fixture.mutations)
    }

    @Test fun oldRecoveryCannotRetireAReauthorizedSessionsCache() {
        val legacy = seed()
        val sessions = SessionStore().apply { bind { first } }
        val stale = sessions.snapshot()
        sessions.invalidate()
        val current = sessions.snapshot()
        CacheManager.authorizedPlaybackKey(cache, "123", source(), sessions, current)
        val publications = fixture.mutations
        assertThrows(SessionChangedException::class.java) {
            CacheManager.removePlaybackEntries(cache, "123", sessions, stale)
        }
        sessions.setRecoveryRequired(true)
        assertThrows(SessionChangedException::class.java) {
            CacheManager.removePlaybackEntries(cache, "123", sessions, current)
        }
        assertEquals(publications, fixture.mutations)
        assertEquals(legacy, find())
        sessions.setRecoveryRequired(false)
        assertEquals(1, CacheManager.removePlaybackEntries(cache, "123", sessions, current))
        assertNull(find())
        assertEquals(4L, fixture.bytes[legacy])
    }

    @Test fun receiptWriteFailureKeepsTheAuthorizedOnlineSourceWithoutAdoptingLegacyBytes() {
        val legacy = seed()
        fixture.mutationFailure = Cache.CacheException("Synthetic metadata write failure")
        assertEquals(source().cacheKey, authorize())
        assertNull(find())
        assertEquals(4L, fixture.bytes[legacy])
        assertEquals(0, fixture.mutations)
    }

    @Test fun privateCloudSourcesNeverAdoptNumericLegacyBytes() {
        seed()
        val cloud = SongSourceIdentity(123, 88, first.userId, 999).key
        val source = source(mediaId = cloud)
        assertEquals(source.cacheKey, authorize(source, mediaId = cloud))
        assertNull(CacheManager.findFullyCachedPlaybackKey(cache, cloud, "exhigh", first))
        assertNull(find())
        assertEquals(0, CacheManager.removePlaybackEntries(cache, cloud, first))
        assertEquals(0, fixture.mutations)
    }

    private fun source(
        owner: SessionIdentity = first, mediaId: String = "123", quality: String = "exhigh",
        md5: String? = digest, size: Long = 4,
    ) = PlaybackUrl("https://example.invalid/source", quality, playbackCacheKey(mediaId, quality, md5, size, owner), md5, size)

    private fun seed(quality: String = "exhigh", bytes: Long = 4): String =
        playbackCacheKey("123", quality, digest, 4).also { fixture.seed(it, 4, bytes) }

    private fun find(owner: SessionIdentity = first, quality: String = "exhigh") =
        CacheManager.findFullyCachedPlaybackKey(cache, "123", quality, owner)

    private fun authorize(source: PlaybackUrl = source(), owner: SessionIdentity = first, mediaId: String = "123"): String {
        val sessions = SessionStore().apply { bind { owner } }
        return CacheManager.authorizedPlaybackKey(cache, mediaId, source, sessions, sessions.snapshot())
    }

    private class CacheFixture {
        val bytes = linkedMapOf<String, Long>()
        private val metadata = linkedMapOf<String, DefaultContentMetadata>()
        var mutations = 0
        var mutationFailure: Cache.CacheException? = null
        val cache = Proxy.newProxyInstance(Cache::class.java.classLoader, arrayOf(Cache::class.java)) { _, method, args ->
            when (method.name) {
                "getKeys" -> (bytes.keys + metadata.keys).toSet()
                "getContentMetadata" -> metadata[args[0]] ?: DefaultContentMetadata.EMPTY
                "getCachedBytes" -> minOf(bytes[args[0]] ?: 0, args[2] as Long)
                "applyContentMetadataMutations" -> {
                    mutationFailure?.let { throw it }
                    val key = args[0] as String
                    metadata[key] = (metadata[key] ?: DefaultContentMetadata.EMPTY)
                        .copyWithMutationsApplied(args[1] as ContentMetadataMutations)
                    mutations++
                    null
                }
                "removeResource" -> { bytes.remove(args[0]); metadata.remove(args[0]); null }
                else -> error("Unexpected Cache method: ${method.name}")
            }
        } as Cache

        fun seed(key: String, length: Long, cachedBytes: Long) {
            bytes[key] = cachedBytes
            metadata[key] = DefaultContentMetadata.EMPTY.copyWithMutationsApplied(
                ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, length) },
            )
        }
    }
}
