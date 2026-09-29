package com.ljyh.mei.playback

import com.google.gson.Gson
import com.ljyh.mei.data.model.SongUrl
import com.ljyh.mei.data.model.api.GetSongUrlV1
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.parasite.HostSessionBridge
import com.ljyh.mei.parasite.HostSessionChangedException
import com.ljyh.mei.parasite.HostSessionIdentity
import com.ljyh.mei.parasite.HostSessionStamp
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackUrlResolverTest {
    private var identity = HostSessionIdentity(1, true, false)
    private val sessions = HostSessionBridge().apply { bind { identity } }
    private val owner = sessions.snapshot()
    private var now = 100_000L
    private val calls = mutableListOf<Pair<GetSongUrlV1, HostSessionStamp>>()
    private var response: suspend (GetSongUrlV1) -> SongUrl = { full() }
    private val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, args ->
        check(method.name == "getSongUrlV1")
        val body = args[0] as GetSongUrlV1
        calls += body to (args[1] as HostSessionStamp)
        @Suppress("UNCHECKED_CAST")
        val continuation = args.last() as Continuation<SongUrl>
        (suspend { response(body) }).startCoroutineUninterceptedOrReturn(continuation)
    } as ApiService
    private val resolver = PlaybackUrlResolver(api, sessions) { now }
    private suspend fun resolve(quality: String = "exhigh", stamp: HostSessionStamp = owner) = resolver.resolve("123", quality, stamp)

    @Test fun fallbackKeepsOneOwnerAndCachesTheActualQuality() = runTest {
        response = { if (it.level == "hires") SongUrl(200, emptyList()) else full() }
        val source = resolve("hires")
        assertEquals(listOf("hires", "lossless"), calls.map { it.first.level })
        assertTrue(calls.all { it.second == owner && it.first.ids == "[123]" && it.first.encodeType == "flac" })
        assertEquals("exhigh", source.actualQuality)
        assertTrue(source.cacheKey.startsWith(playbackCacheKeyPrefix("123", "exhigh", owner.identity)))
        assertEquals(source, resolve("hires"))
        assertEquals(source, resolve("exhigh"))
        assertEquals(2, calls.size)
    }

    @Test fun sameAccountReauthorizationCannotReuseSignedUrls() = runTest {
        resolve()
        sessions.invalidate()
        assertTrue(runCatching { resolve() }.exceptionOrNull() is HostSessionChangedException)
        resolve(stamp = sessions.snapshot())
        assertEquals(2, calls.size)
    }

    @Test fun accountIdentityAlsoGuardsCachesWithoutAnInvalidationCallback() = runTest {
        val first = resolve()
        identity = HostSessionIdentity(2, true, false)
        assertTrue(runCatching { resolve() }.exceptionOrNull() is HostSessionChangedException)
        val second = resolve(stamp = sessions.snapshot())
        assertNotEquals(first.cacheKey, second.cacheKey)
        assertEquals(2, calls.size)
    }

    @Test fun recoveryBlocksAlreadyCachedUrlsAndFurtherNetworkRequests() = runTest {
        resolve()
        sessions.setRecoveryRequired(true)
        assertTrue(runCatching { resolve() }.exceptionOrNull() is HostSessionChangedException)
        assertEquals(1, calls.size)
    }

    @Test fun expirationUsesRequestStartAndKeepsASafetyMargin() = runTest {
        response = { now += 5_000; full(expi = 60) }
        resolve()
        now = 129_999
        resolve()
        assertEquals(1, calls.size)
        now = 130_000
        resolve()
        assertEquals(2, calls.size)
    }

    @Test fun shortOrZeroTtlDoesNotExtendAnExpiredSource() = runTest {
        for (ttl in listOf(0, 1, 30)) {
            resolver.invalidate("123")
            response = { full(expi = ttl) }
            val before = calls.size
            resolve()
            resolve()
            assertEquals(before + 2, calls.size)
        }
    }

    @Test fun manualInvalidationRemovesAllQualityAliases() = runTest {
        resolve("hires")
        resolver.invalidate("123")
        resolve("exhigh")
        assertEquals(2, calls.size)
    }

    @Test fun failuresNeverTriggerQualityFallback() = runTest {
        for (failure in listOf<suspend (GetSongUrlV1) -> SongUrl>(
            { SongUrl(301, emptyList()) }, { throw IOException("Offline") },
            { Gson().fromJson("{\"code\":200}", SongUrl::class.java) },
        )) {
            response = failure
            val before = calls.size
            assertTrue(runCatching { resolve("jymaster") }.exceptionOrNull() is IOException)
            assertEquals(before + 1, calls.size)
        }
    }

    @Test fun mismatchedFailedAndTrialSourcesCannotBecomeFullPlayback() = runTest {
        for (source in listOf(full(id = 999).data.single(), full(code = 403).data.single(),
            full(trial = true).data.single())) {
            response = { SongUrl(200, listOf(source)) }
            assertTrue(runCatching { resolve("standard") }.exceptionOrNull() is SourceNotFoundException)
        }
    }

    @Test fun invalidIdsNeverReachTheHost() = runTest {
        for (id in listOf("0", "-1", "local_123", "1,2", "invalid")) {
            assertTrue(runCatching { resolver.resolve(id, "standard", owner) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(calls.isEmpty())
    }

    @Test fun lateNonCooperativeResponsesCannotPopulateANewSessionCache() = runTest {
        val pending = CompletableDeferred<SongUrl>()
        response = { withContext(NonCancellable) { pending.await() } }
        val old = async { runCatching { resolve() }.exceptionOrNull() }
        runCurrent()
        sessions.invalidate()
        pending.complete(full())
        runCurrent()
        assertTrue(old.await() is HostSessionChangedException)
        response = { full() }
        resolve(stamp = sessions.snapshot())
        assertEquals(2, calls.size)
    }

    @Test fun cancellationAfterAResponseDoesNotCacheIt() = runTest {
        val pending = CompletableDeferred<SongUrl>()
        response = { withContext(NonCancellable) { pending.await() } }
        val old = launch { resolve() }
        runCurrent()
        old.cancel()
        pending.complete(full())
        runCurrent()
        response = { full() }
        resolve()
        assertEquals(2, calls.size)
    }

    @Test fun persistentCacheNamespacesSeparateGuestsAccountsAndLegacyBytes() {
        val identities = listOf(owner.identity, HostSessionIdentity(2, true, false),
            HostSessionIdentity(0, false, true), HostSessionIdentity(1, false, true))
        val keys = identities.map { playbackCacheKey("123", "exhigh", "abc", 100, it) }
        assertEquals(4, keys.distinct().size)
        assertTrue(keys.none { it.startsWith(playbackCacheKeyPrefix("123")) })
        assertEquals(keys.first(), playbackCacheKey("123", "EXHIGH", "ABC", 100, owner.identity.copy()))
    }

    private fun full(expi: Int = 300, id: Long = 123, code: Int = 200, trial: Boolean = false): SongUrl = Gson().fromJson(
        """{"code":200,"data":[{"id":$id,"code":$code,"url":"https://example.invalid/source","level":"exhigh","md5":"abc","size":12345,"expi":$expi,"payed":0,"freeTrialInfo":${if (trial) "{\"end\":30000}" else "null"}}]}""",
        SongUrl::class.java,
    )
}
