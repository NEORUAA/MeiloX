package com.ljyh.mei.ui.screen.cloud

import com.google.gson.JsonParser
import com.ljyh.mei.source.shared.OnlineCloudPage as CloudMusicPage
import com.ljyh.mei.source.shared.OnlineCloudSong as CloudSong
import com.ljyh.mei.source.shared.SourceOnlineParser
import com.ljyh.mei.source.api.ProviderInstanceId
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.BoundSourceFeatures
import com.ljyh.mei.source.core.ProviderRegistry
import com.ljyh.mei.source.core.SourceRouter
import com.ljyh.mei.source.core.SourceFeatureExecutor
import com.ljyh.mei.source.shared.SourceCloudPageCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import com.ljyh.mei.source.api.SourceException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudMusicTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun cloudCursorCountsRawRowsBeforeFilteringAndDeduplication() {
        val parser = SourceOnlineParser("qq", ProviderInstanceId("fixture:qq"))
        val online = parser.cloudPage(JsonParser.parseString(
            """{"songs":[{"id":"1","name":"Song"},{"id":"1","name":"Song"}],
                "count":205,"usedSize":42,"maxSize":99,"hasMore":true,"nextOffset":204}""",
        ), offset = 200)
        assertEquals(204, online.nextOffset)
        assertTrue(online.hasMore)
        assertEquals(42L, online.usedSize)
        assertEquals(99L, online.maxSize)
        val merged = CloudMusicUiState(page = page(listOf(song(1)), true, 200)).withCloudPage(online)
        assertEquals(listOf("1"), merged.page!!.songs.map(CloudSong::id))
        assertEquals(204, merged.page.nextOffset)
        assertTrue(merged.page.hasMore)
    }

    @Test
    fun invalidContinuationIsRejectedAndEmptyFinalPageIsAccepted() {
        val parser = SourceOnlineParser("qq", ProviderInstanceId("fixture:qq"))
        assertThrows(SourceException::class.java) {
            parser.cloudPage(JsonParser.parseString("""{"songs":[],"hasMore":true,"nextOffset":200}"""), 200)
        }
        assertThrows(SourceException::class.java) {
            parser.cloudPage(JsonParser.parseString("""{"count":201,"nextOffset":201}"""), 200)
        }
        val finalPage = parser.cloudPage(
            JsonParser.parseString("""{"songs":[],"hasMore":false,"count":200,"nextOffset":200}"""), 200,
        )
        assertFalse(finalPage.hasMore)
        assertEquals(200, finalPage.nextOffset)
    }

    @Test
    fun cloudLoadsOneBoundedPagePerRequestAndContinuesAfterTwoHundredSongs() = runTest(dispatcher) {
        val requests = mutableListOf<Int>()
        lateinit var append: Continuation<CloudMusicPage>
        val viewModel = viewModel { offset ->
            requests += offset
            if (offset == 0) page((1L..200L).map(::song), true, 200)
            else suspendCoroutine { append = it }
        }
        runCurrent()
        assertEquals(listOf(0), requests)
        assertEquals(200, viewModel.state.value.page!!.songs.size)

        viewModel.loadMore()
        viewModel.loadMore()
        runCurrent()
        assertEquals(listOf(0, 200), requests)
        assertTrue(viewModel.state.value.isLoadingMore)
        append.resume(page(listOf(song(200), song(201)), false, 202))
        runCurrent()
        viewModel.loadMore()
        runCurrent()

        assertEquals(listOf(0, 200), requests)
        assertEquals((1L..201L).map(Long::toString), viewModel.state.value.page!!.songs.map(CloudSong::id))
        assertEquals(202, viewModel.state.value.page!!.nextOffset)
        assertFalse(viewModel.state.value.isLoadingMore)
    }

    @Test
    fun failedAppendPreservesRowsAndRetriesTheSameRawOffset() = runTest(dispatcher) {
        val requests = mutableListOf<Int>()
        var fail = true
        val viewModel = viewModel { offset ->
            requests += offset
            if (offset == 0) page(listOf(song(1)), true, 4)
            else if (fail) error("Page unavailable")
            else page(listOf(song(2)), false, 6)
        }
        runCurrent()
        viewModel.loadMore()
        runCurrent()
        assertEquals(listOf("1"), viewModel.state.value.page!!.songs.map(CloudSong::id))
        assertEquals(4, viewModel.state.value.page!!.nextOffset)
        assertNotNull(viewModel.state.value.loadMoreError)
        fail = false
        viewModel.loadMore()
        runCurrent()
        assertEquals(listOf(0, 4, 4), requests)
        assertEquals(listOf("1", "2"), viewModel.state.value.page!!.songs.map(CloudSong::id))
        assertNull(viewModel.state.value.loadMoreError)
    }

    @Test
    fun refreshingKeepsActiveUploadProgressAndDeletionAndRejectsDuplicateActions() = runTest(dispatcher) {
        lateinit var deletion: Continuation<Unit>
        lateinit var upload: Continuation<Unit>
        lateinit var progress: (Long, Long) -> Unit
        var deleteCalls = 0
        var uploadCalls = 0
        val viewModel = CloudMusicViewModel(
            loadPage = { page(listOf(song(1), song(2)), false, 2) },
            deleteSong = { deleteCalls++; suspendCoroutine { deletion = it } },
            uploadSong = { _, _ -> error("URI transport is not used by this state test") },
        )
        runCurrent()
        viewModel.delete(song(1))
        viewModel.delete(song(1))
        viewModel.upload { callback ->
            uploadCalls++
            progress = callback
            callback(40, 100)
            suspendCoroutine<Unit> { upload = it }
        }
        viewModel.upload { error("A second upload must not start") }
        assertEquals(setOf("1"), viewModel.state.value.deletingIds)
        assertTrue(viewModel.state.value.isUploading)
        runCurrent()
        assertEquals(1, deleteCalls)
        assertEquals(1, uploadCalls)

        viewModel.refresh()
        runCurrent()
        assertFalse(viewModel.state.value.isLoading)
        assertTrue(viewModel.state.value.isUploading)
        assertEquals(0.4f, viewModel.state.value.uploadProgress)
        assertEquals(setOf("1"), viewModel.state.value.deletingIds)
        progress(70, 100)
        assertEquals(0.7f, viewModel.state.value.uploadProgress)

        deletion.resume(Unit)
        upload.resume(Unit)
        runCurrent()
        assertTrue(viewModel.state.value.deletingIds.isEmpty())
        assertFalse(viewModel.state.value.isUploading)
        assertEquals(1f, viewModel.state.value.uploadProgress)
        progress(20, 100)
        assertEquals(1f, viewModel.state.value.uploadProgress)
    }

    @Test
    fun olderRefreshSuccessAndFailureCannotReplaceNewerRows() = runTest(dispatcher) {
        val reads = mutableListOf<Continuation<CloudMusicPage>>()
        val viewModel = viewModel { suspendCoroutine { reads += it } }
        runCurrent()
        viewModel.refresh()
        runCurrent()
        reads[1].resume(page(listOf(song(2)), false, 1))
        runCurrent()
        reads[0].resume(page(listOf(song(1)), false, 1))
        runCurrent()
        assertEquals(listOf("2"), viewModel.state.value.page!!.songs.map(CloudSong::id))

        viewModel.refresh()
        runCurrent()
        viewModel.refresh()
        runCurrent()
        reads[3].resume(page(listOf(song(3)), false, 1))
        runCurrent()
        reads[2].resumeWithException(IllegalStateException("Retired refresh"))
        runCurrent()
        assertEquals(listOf("3"), viewModel.state.value.page!!.songs.map(CloudSong::id))
        assertFalse(viewModel.state.value.isLoading)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun refreshedFirstPageRejectsRetiredPaginationResults() = runTest(dispatcher) {
        lateinit var append: Continuation<CloudMusicPage>
        var refreshed = false
        val viewModel = viewModel { offset ->
            if (offset > 0) suspendCoroutine { append = it }
            else page(listOf(song(if (refreshed) 3 else 1)), true, 4)
        }
        runCurrent()
        viewModel.loadMore()
        runCurrent()
        refreshed = true
        viewModel.refresh()
        runCurrent()
        append.resume(page(listOf(song(2)), false, 8))
        runCurrent()
        assertEquals(listOf("3"), viewModel.state.value.page!!.songs.map(CloudSong::id))
        assertEquals(4, viewModel.state.value.page!!.nextOffset)
        assertFalse(viewModel.state.value.isLoadingMore)
        assertNull(viewModel.state.value.loadMoreError)
    }

    @Test
    fun initialReadCanReuseTheFirstPageWhileExplicitRefreshBypassesItAndRetainsRows() = runTest(dispatcher) {
        var cachedReads = 0
        var refreshReads = 0
        lateinit var fresh: Continuation<CloudMusicPage>
        val model = CloudMusicViewModel(
            loadPage = { cachedReads++; page((1L..30L).map(::song), true, 30) },
            deleteSong = {}, uploadSong = { _, _ -> },
            loadFreshPage = { refreshReads++; suspendCoroutine { fresh = it } },
        )
        runCurrent()
        assertEquals(1, cachedReads)
        assertEquals(0, refreshReads)
        assertEquals(30, model.state.value.page!!.songs.size)
        model.refresh()
        runCurrent()
        assertEquals(1, refreshReads)
        assertEquals(30, model.state.value.page!!.songs.size)
        assertTrue(model.state.value.isLoading)
        fresh.resume(page(listOf(song(31)), false, 1))
        runCurrent()
        assertEquals(listOf("31"), model.state.value.page!!.songs.map(CloudSong::id))
        assertFalse(model.state.value.isLoading)
    }

    @Test
    fun cloudCacheReusesOnlyTheSameImmutableSessionAndRejectsLateRefreshWrites() = runTest(dispatcher) {
        var now = 0L
        val cache = SourceCloudPageCache { now }
        val provider = CloudFeatureProvider()
        val registry = ProviderRegistry(listOf(provider))
        val router = SourceRouter(registry)
        fun bind(owner: CloudFeatureProvider = provider) = BoundSourceFeatures(router, SourceFeatureExecutor(router), owner,
            owner.accountRevision.value, "cloud-cache-test", SourceHostEffectHandler.Unsupported)
        val first = bind()
        val ticket = cache.ticket(first)
        val data = page((1L..30L).map(::song), true, 34)
        cache.put(first, ticket, 30, data)
        assertEquals(data, cache.get(bind(), 30))
        assertNull(cache.get(bind(), 200))
        @Suppress("UNCHECKED_CAST")
        (cache.get(first, 30)!!.songs as MutableList<CloudSong>).clear()
        assertEquals(30, cache.get(bind(), 30)!!.songs.size)
        cache.invalidate(first)
        cache.put(first, ticket, 30, data)
        assertNull(cache.get(bind(), 30))
        cache.put(first, cache.ticket(first), 30, data)
        now = SourceCloudPageCache.TTL_MS
        assertNull(cache.get(bind(), 30))
        cache.put(first, cache.ticket(first), 30, data)
        provider.accountRevision.value = "account-B"
        assertNull(cache.get(bind(), 30))
        assertTrue(runCatching { cache.get(first, 30) }.exceptionOrNull() is CancellationException)
        val versionOne = bind()
        cache.put(versionOne, cache.ticket(versionOne), 30, data)
        provider.version = "2"
        assertNull(cache.get(bind(), 30))
        val replacement = CloudFeatureProvider().apply { version = "2"; accountRevision.value = "account-B" }
        registry.register(replacement)
        assertNull(cache.get(bind(replacement), 30))
    }

    @Test
    fun ownLoginTransitionCannotRelabelCachedCloudDataOrCommitAnOldTicket() = runTest(dispatcher) {
        val cache = SourceCloudPageCache()
        val provider = CloudFeatureProvider()
        val router = SourceRouter(ProviderRegistry(listOf(provider)))
        val bound = BoundSourceFeatures(router, SourceFeatureExecutor(router), provider, provider.accountRevision.value,
            "cloud-login-test", SourceHostEffectHandler.Unsupported)
        val ticket = cache.ticket(bound)
        cache.put(bound, ticket, 30, page(listOf(song(1)), false, 1))
        provider.loginAccount = "account-B"
        bound.invoke("account", "login", allowSessionChange = true)
        bound.ensureCurrent()
        assertNull(cache.get(bound, 30))
        val retired = runCatching { cache.put(bound, ticket, 30, page(listOf(song(2)), false, 1)) }.exceptionOrNull()
        assertTrue(retired is CancellationException)
        assertNull(cache.get(bound, 30))
    }

    @Test
    fun cloudCacheBoundsOwnersAndOversizedMetadataWithoutCachingAppendPages() = runTest(dispatcher) {
        val cache = SourceCloudPageCache()
        val providers = (1..5).map { CloudFeatureProvider("cloud-$it") }
        val router = SourceRouter(ProviderRegistry(providers))
        fun bind(provider: CloudFeatureProvider) = BoundSourceFeatures(router, SourceFeatureExecutor(router), provider,
            provider.accountRevision.value, "cloud-bound-test", SourceHostEffectHandler.Unsupported)
        for (provider in providers) {
            val bound = bind(provider)
            cache.put(bound, cache.ticket(bound), 30, page(listOf(song(1)), false, 1))
        }
        assertNull(cache.get(bind(providers.first()), 30))
        val bound = bind(providers.last())
        assertNotNull(cache.get(bound, 30))
        cache.invalidate(bound)
        val huge = song(1).copy(name = "x".repeat(SourceCloudPageCache.MAX_BYTES))
        cache.put(bound, cache.ticket(bound), 30, page(listOf(huge), false, 1))
        assertNull(cache.get(bound, 30))
        cache.put(bound, cache.ticket(bound), 200, page((1L..200L).map(::song), false, 200))
        assertNull(cache.get(bound, 200))
    }

    private class CloudFeatureProvider(override val id: String = "cloud-fixture") :
        MusicProvider, AccountScopedFeatureCapability, AccountRevisionCapability {
        override val displayName = "Cloud fixture"
        override var version = "1"
        override val capabilities = setOf(SourceCapability.FEATURES)
        override val features = mapOf("cloud" to setOf("cloudSongs"), "account" to setOf("login"))
        override val accountRevision = MutableStateFlow("account-A")
        var loginAccount: String? = null
        override suspend fun invokeFeature(feature: String, action: String, parameters: com.google.gson.JsonObject): com.google.gson.JsonElement =
            com.google.gson.JsonPrimitive(true)
        override suspend fun invokeFeature(feature: String, action: String, parameters: com.google.gson.JsonObject,
            expectedAccountRevision: String?, onSessionCommitted: ((String?) -> Unit)?): com.google.gson.JsonElement {
            if (accountRevision.value != expectedAccountRevision) throw CancellationException("Stale cloud account")
            loginAccount?.let { next ->
                onSessionCommitted?.invoke(next)
                accountRevision.value = next
                loginAccount = null
            }
            return com.google.gson.JsonPrimitive(true)
        }
    }

    private fun viewModel(load: suspend (Int) -> CloudMusicPage) = CloudMusicViewModel(
        loadPage = load,
        deleteSong = {},
        uploadSong = { _, _ -> },
    )

    private fun page(songs: List<CloudSong>, more: Boolean, nextOffset: Int) = CloudMusicPage(
        songs, count = 205, usedSize = 42, maxSize = 99, hasMore = more, nextOffset = nextOffset,
    )

    private fun song(id: Long) = CloudSong(id.toString(), "Song $id", "Artist", "Album", null, 1000, 42, 320, 0)
}
