package com.ljyh.mei.ui.screen.playlist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import com.google.gson.Gson
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.utils.playlistCoverWithoutOverlays
import com.ljyh.mei.data.model.PlaylistDetail
import com.ljyh.mei.data.model.PlaylistOrderChangedException
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.model.validatePlaylistTrackOrder
import com.ljyh.mei.data.model.api.UpdatePlaylistTrackOrder
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.ProviderRegistry
import com.ljyh.mei.source.core.SourceMutationCoordinator
import com.ljyh.mei.source.core.SourcePreferences
import com.ljyh.mei.source.core.SourceRouter
import com.ljyh.mei.source.script.SourceCatalogInvalidatedException
import com.ljyh.mei.ui.screen.source.SourceContentRequest
import com.ljyh.mei.ui.screen.source.SourceContentViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistContentTest {
    @Test
    fun memberRadarUsesOriginalBackgroundArtworkInsteadOfTheBakedLabelCover() {
        val radar = Gson().fromJson(
            """{
                "name":"会员雷达", "specialType":100,
                "coverImgUrl":"http://p1.music.126.net/q2jXNF7fIRXS72JBHb9zEw==/109951168636800773.jpg",
                "backgroundCoverUrl":"https://p1.music.126.net/4BTe96FPQ_kW1pmNnlNvHg==/2536573326765583.jpg"
            }""",
            PlaylistDetail.Playlist::class.java,
        )
        fun changedCover(field: String, value: Any): String {
            val json = Gson().toJsonTree(radar).asJsonObject
            json.add(field, Gson().toJsonTree(value))
            return Gson().fromJson(json, PlaylistDetail.Playlist::class.java).cleanDetailCover()
        }
        assertEquals(radar.backgroundCoverUrl, radar.cleanDetailCover())
        val homeArtwork = "https://p1.music.126.net/home-original.jpg?imageView=1"
        assertEquals(homeArtwork, radar.cleanDetailCover("$homeArtwork%7Cwatermark&type=1&image=dim"))
        assertEquals(radar.coverImgUrl, changedCover("backgroundCoverUrl", ""))
        assertEquals(radar.coverImgUrl, changedCover("backgroundCoverUrl", 0))
        assertEquals(radar.coverImgUrl, changedCover("backgroundCoverUrl", "not a URL"))
        assertEquals(radar.coverImgUrl, changedCover("name", "热门金曲"))
        assertEquals(radar.coverImgUrl, changedCover("specialType", 0))
    }

    @Test
    fun detailArtworkRemovesCaptionAndLogoButRetainsImageTransforms() {
        val artwork = "https://p1.music.126.net/artwork.jpg?imageView=1&thumbnail=800y800"
        val caption = "imageView=1&watermark&type=1&image=Y2FwdGlvbg=="
        val logo = "watermark&type=1&image=bG9nbw=="
        val expected = "$artwork%7CimageView=1&quality=90"
        for (separator in listOf("%7C", "%7c", "|")) {
            assertEquals(expected, playlistCoverWithoutOverlays(
                listOf(artwork, caption, logo, "imageView=1&quality=90").joinToString(separator),
            ))
        }
        assertEquals(artwork, playlistCoverWithoutOverlays("$artwork&watermark&type=1&image=bG9nbw=="))
    }

    @Test
    fun plainDetailCoversRemainUnchangedAndCleaningIsIdempotent() {
        for (cover in listOf("", "/music/artwork.jpg", "content://media/42",
            "https://p1.music.126.net/artwork.jpg?param=500y500",
            "https://p1.music.126.net/watermark.jpg?imageView=1|thumbnail=800y800")) {
            assertEquals(cover, playlistCoverWithoutOverlays(cover))
        }
        val clean = "https://p1.music.126.net/artwork.jpg?imageView=1%7Cquality=90"
        assertEquals(clean, playlistCoverWithoutOverlays(clean))
    }

    @Test
    fun removingTracksRetainsLoadedPagesAndFiltersLaterAppendsWithoutRestartingThePager() = runTest {
        var sourceCount = 0
        val requests = mutableListOf<List<String>>()
        val removedIds = MutableStateFlow(emptySet<Long>())
        val snapshots = mutableListOf<List<Long>>()
        val presenter = object : PagingDataPresenter<MediaMetadata>(StandardTestDispatcher(testScheduler)) {
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<MediaMetadata>) {
                snapshots += snapshot().items.map { it.id }
            }
        }
        val tracks = Pager(PagingConfig(pageSize = 20, initialLoadSize = 20, enablePlaceholders = false)) {
            sourceCount++
            PlaylistTrackSource(
                loadTracks = { ids -> requests += ids; ids.map { track(it.toLong()) } },
                firstData = emptyList(),
                ids = (1..120).map(Int::toString),
            )
        }.flow.cachedIn(backgroundScope).excludeRemovedPlaylistTracks(removedIds)
        backgroundScope.launch { tracks.collectLatest(presenter::collectFrom) }
        runCurrent()
        repeat(3) {
            presenter[presenter.size - 1]
            runCurrent()
        }
        val loadedIds = presenter.snapshot().items.map { it.id }
        assertEquals((1L..80L).toList(), loadedIds)
        val requestsBeforeRemoval = requests.toList()

        removedIds.value = setOf(60L, 90L)
        runCurrent()
        removedIds.value += 5L
        runCurrent()

        assertEquals(loadedIds.filterNot { it in removedIds.value }, presenter.snapshot().items.map { it.id })
        assertEquals(requestsBeforeRemoval, requests)
        assertEquals(1, sourceCount)
        assertTrue(snapshots.all { it.isNotEmpty() })

        presenter[presenter.size - 1]
        runCurrent()
        assertEquals((1L..100L).filterNot { it in removedIds.value }, presenter.snapshot().items.map { it.id })
        assertEquals(1, sourceCount)
    }

    @Test
    fun removingSearchResultsReusesTheCompletedSearchIncludingWhenTheLastResultIsDeleted() = runTest {
        var searches = 0
        val removedIds = MutableStateFlow(setOf(1L))
        val presenter = object : PagingDataPresenter<MediaMetadata>(StandardTestDispatcher(testScheduler)) {
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<MediaMetadata>) = Unit
        }
        val tracks = flow {
            searches++
            emit(PagingData.from((1L..3L).map { track(it).toMediaMetadata() }))
        }.cachedIn(backgroundScope).excludeRemovedPlaylistTracks(removedIds)
        backgroundScope.launch { tracks.collectLatest(presenter::collectFrom) }
        runCurrent()
        assertEquals(listOf(2L, 3L), presenter.snapshot().items.map { it.id })

        removedIds.value += 2L
        runCurrent()
        assertEquals(listOf(3L), presenter.snapshot().items.map { it.id })
        removedIds.value += 3L
        runCurrent()
        assertTrue(presenter.snapshot().items.isEmpty())
        assertEquals(1, searches)
    }

    @Test
    fun orderEditorPreservesUnavailableTracksAndCanonicalPositions() {
        val items = playlistOrderItems(listOf(3, 1, 2), listOf(track(2).toMediaMetadata(), track(3).toMediaMetadata()))
        assertEquals(listOf(3L, 1L, 2L), items.map { it.id })
        assertNull(items[1].track)
        val moved = items.toMutableList().apply { add(0, removeAt(1)) }
        validatePlaylistTrackOrder(items.map { it.id }, moved.map { it.id }, items.map { it.id })
        assertEquals(listOf(1L, 3L, 2L), moved.map { it.id })
        assertNull(moved.first().track)
    }

    @Test
    fun orderUpdatesRejectDroppedDuplicatedAndConcurrentlyChangedIds() {
        val original = listOf(1L, 2L, 3L)
        for (invalid in listOf(listOf(2L, 1L), listOf(2L, 1L, 1L), listOf(2L, 1L, 4L))) {
            assertTrue(runCatching { validatePlaylistTrackOrder(original, invalid, original) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(runCatching {
            validatePlaylistTrackOrder(original, original.reversed(), original + 4L)
        }.exceptionOrNull() is PlaylistOrderChangedException)
        assertTrue(runCatching {
            validatePlaylistTrackOrder(original, original.reversed(), listOf(2L, 1L, 3L))
        }.exceptionOrNull() is PlaylistOrderChangedException)
    }

    @Test
    fun orderRequestIncludesEveryOrderedIdAsAString() {
        val request = UpdatePlaylistTrackOrder("42", listOf(3, 1, 2))
        val payload = Gson().toJsonTree(request).asJsonObject
        assertEquals("42", payload["pid"].asString)
        assertEquals("update", payload["op"].asString)
        assertEquals("[\"3\",\"1\",\"2\"]", payload["trackIds"].asString)
    }

    @Test
    fun sourceCollectionHeadersAppearBeforeTracksAndRemainAfterTrackFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (album in listOf(false, true)) {
                val gate = CompletableDeferred<Unit>()
                val provider = DetailProvider().apply { beforeTracks = { gate.await() } }
                val registry = ProviderRegistry(listOf(provider))
                val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
                val model = SourceContentViewModel(router, SourcePreferences(MemoryPreferences()), registry)
                val ref = if (album) AlbumRef(provider.id, "album") else PlaylistRef(provider.id, "playlist")
                try {
                    model.open(SourceContentRequest.Detail(ref))
                    runCurrent()
                    assertEquals(ref, model.state.value.detail?.ref)
                    assertEquals(if (album) "Album" else "Playlist", model.state.value.title)
                    assertTrue(model.state.value.loading)
                    assertTrue(model.state.value.items.isEmpty())
                    assertNull(model.state.value.error)

                    gate.completeExceptionally(SourceException(SourceErrorCode.NETWORK))
                    runCurrent()
                    assertFalse(model.state.value.loading)
                    assertEquals(SourceErrorCode.NETWORK, model.state.value.error)
                    assertEquals(ref, model.state.value.detail?.ref)
                    assertEquals(if (album) "Album" else "Playlist", model.state.value.title)
                } finally { model.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun sourceCollectionRefreshKeepsRowsAndReadsFreshTracksBeforeMetadata() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (album in listOf(false, true)) {
                val provider = DetailProvider()
                val registry = ProviderRegistry(listOf(provider))
                val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
                val model = SourceContentViewModel(router, SourcePreferences(MemoryPreferences()), registry)
                val ref = if (album) AlbumRef(provider.id, "album") else PlaylistRef(provider.id, "playlist")
                val kind = if (album) "album" else "playlist"
                try {
                    model.open(SourceContentRequest.Detail(ref))
                    runCurrent()
                    val before = model.state.value
                    assertEquals(30, before.items.size)
                    val gate = CompletableDeferred<Unit>()
                    provider.calls.clear()
                    provider.beforeTracks = { if (it.refresh) gate.await() }
                    provider.titleSuffix = " refreshed"
                    provider.tracks = provider.tracks.drop(1)
                    model.refresh()
                    runCurrent()
                    assertTrue(model.state.value.loading)
                    assertTrue(model.state.value.refreshing)
                    assertEquals(before.title, model.state.value.title)
                    assertEquals(before.items, model.state.value.items)
                    assertEquals(listOf("$kind.tracks:refresh"), provider.calls)

                    gate.complete(Unit)
                    runCurrent()
                    assertEquals(listOf("$kind.tracks:refresh", "$kind.get"), provider.calls)
                    assertFalse(model.state.value.loading)
                    assertFalse(model.state.value.refreshing)
                    assertEquals(before.title + " refreshed", model.state.value.title)
                    assertEquals(provider.tracks.take(30).map { it.ref }, model.state.value.items.map { it.ref })
                } finally { model.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun sourceCatalogCancellationRetriesWithoutRepeatingRefreshInvalidation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (album in listOf(false, true)) {
                val provider = DetailProvider()
                val registry = ProviderRegistry(listOf(provider))
                val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
                val model = SourceContentViewModel(router, SourcePreferences(MemoryPreferences()), registry)
                val ref = if (album) AlbumRef(provider.id, "album") else PlaylistRef(provider.id, "playlist")
                try {
                    model.open(SourceContentRequest.Detail(ref))
                    runCurrent()
                    val original = model.state.value.items
                    provider.pageRequests.clear()
                    provider.tracks = provider.tracks.drop(1)
                    var first = true
                    provider.beforeTracks = {
                        if (first) { first = false; throw SourceCatalogInvalidatedException() }
                    }
                    model.refresh()
                    runCurrent()
                    assertTrue(model.state.value.loading)
                    assertTrue(model.state.value.refreshing)
                    assertEquals(original, model.state.value.items)
                    advanceTimeBy(50)
                    runCurrent()
                    assertEquals(listOf(true, false), provider.pageRequests.map { it.refresh })
                    assertFalse(model.state.value.loading)
                    assertFalse(model.state.value.refreshing)
                    assertNull(model.state.value.error)
                    assertEquals(provider.tracks.take(30).map { it.ref }, model.state.value.items.map { it.ref })
                } finally { model.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun repeatedCatalogCancellationSettlesRefreshAndPaginationAndAllowsRecovery() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val provider = DetailProvider()
        val registry = ProviderRegistry(listOf(provider))
        val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
        val model = SourceContentViewModel(router, SourcePreferences(MemoryPreferences()), registry)
        val ref = PlaylistRef(provider.id, "playlist")
        try {
            model.open(SourceContentRequest.Detail(ref))
            runCurrent()
            val original = model.state.value.items
            provider.pageRequests.clear()
            provider.beforeTracks = { throw SourceCatalogInvalidatedException() }
            model.refresh()
            runCurrent()
            advanceTimeBy(50)
            runCurrent()
            assertEquals(2, provider.pageRequests.size)
            assertEquals(listOf(true, false), provider.pageRequests.map { it.refresh })
            assertFalse(model.state.value.loading)
            assertFalse(model.state.value.refreshing)
            assertEquals(SourceErrorCode.UNAVAILABLE, model.state.value.error)
            assertEquals(original, model.state.value.items)

            provider.beforeTracks = {}
            model.refresh()
            runCurrent()
            assertNull(model.state.value.error)
            provider.pageRequests.clear()
            provider.beforeTracks = { throw SourceCatalogInvalidatedException() }
            model.loadMore()
            runCurrent()
            advanceTimeBy(50)
            runCurrent()
            assertEquals(listOf(30, 30), provider.pageRequests.map { it.offset })
            assertEquals(SourceErrorCode.UNAVAILABLE, model.state.value.error)
            assertFalse(model.state.value.loading)
            assertFalse(model.state.value.refreshing)
            assertEquals(original, model.state.value.items)
            assertEquals(30, model.state.value.nextOffset)
            provider.beforeTracks = {}
            model.loadMore()
            runCurrent()
            assertNull(model.state.value.error)
            assertEquals(60, model.state.value.items.size)
        } finally {
            try { model.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            finally { Dispatchers.resetMain() }
        }
    }

    @Test
    fun activeUnknownCancellationSettlesThePageWithoutRetryingAnUnknownOwner() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val provider = DetailProvider()
        val registry = ProviderRegistry(listOf(provider))
        val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
        val model = SourceContentViewModel(router, SourcePreferences(MemoryPreferences()), registry)
        try {
            model.open(SourceContentRequest.Detail(PlaylistRef(provider.id, "playlist")))
            runCurrent()
            val original = model.state.value.items
            provider.pageRequests.clear()
            provider.beforeTracks = { throw CancellationException("An untracked owner changed") }
            model.refresh()
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertEquals(1, provider.pageRequests.size)
            assertEquals(SourceErrorCode.UNAVAILABLE, model.state.value.error)
            assertEquals(original, model.state.value.items)
            assertFalse(model.state.value.loading)
            assertFalse(model.state.value.refreshing)
        } finally {
            try { model.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            finally { Dispatchers.resetMain() }
        }
    }

    @Test
    fun cancelledCatalogRetryCannotRefillANavigatedReplacedOrChangedAccountPage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (change in listOf("navigation", "provider", "account", "dispose")) {
                val provider = DetailProvider().apply {
                    beforeTracks = { throw SourceCatalogInvalidatedException() }
                }
                val registry = ProviderRegistry(listOf(provider))
                val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
                val model = SourceContentViewModel(router, SourcePreferences(MemoryPreferences()), registry)
                val ref = PlaylistRef(provider.id, "old")
                try {
                    model.open(SourceContentRequest.Detail(ref))
                    runCurrent()
                    assertEquals(1, provider.pageRequests.size)
                    val cancelledState = model.state.value
                    provider.beforeTracks = {}
                    when (change) {
                        "navigation" -> model.open(SourceContentRequest.Detail(PlaylistRef(provider.id, "new")))
                        "provider" -> registry.register(DetailProvider().apply { titleSuffix = " new" })
                        "account" -> provider.accountRevision.value = "account-B"
                        "dispose" -> model.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
                    }
                    runCurrent()
                    advanceTimeBy(50)
                    runCurrent()
                    when (change) {
                        "navigation" -> assertEquals("new", model.state.value.detail?.ref?.contentId)
                        "provider" -> assertEquals("Playlist new", model.state.value.title)
                        "account" -> assertEquals("account-B", model.state.value.accountRevision)
                        "dispose" -> assertSame(cancelledState, model.state.value)
                    }
                    assertEquals(if (change in listOf("navigation", "account")) 2 else 1, provider.pageRequests.size)
                    if (change != "dispose") {
                        assertFalse(model.state.value.loading)
                        assertFalse(model.state.value.refreshing)
                        assertNull(model.state.value.error)
                    }
                } finally { model.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun sourceCollectionHeadersRejectMetadataReturnedAfterAccountOrProviderChanges() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (album in listOf(false, true)) for (replaceProvider in listOf(false, true)) {
                val provider = DetailProvider().apply { titleSuffix = " old" }
                val registry = ProviderRegistry(listOf(provider))
                val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
                val model = SourceContentViewModel(router, SourcePreferences(MemoryPreferences()), registry)
                val ref = if (album) AlbumRef(provider.id, "album") else PlaylistRef(provider.id, "playlist")
                val titles = mutableListOf<String>()
                val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    model.state.collect { titles += it.title }
                }
                provider.beforeDetails = {
                    provider.beforeDetails = {}
                    if (replaceProvider) registry.register(DetailProvider().apply { titleSuffix = " new" })
                    else {
                        provider.titleSuffix = " new"
                        provider.accountRevision.value = "account-B"
                    }
                }
                try {
                    model.open(SourceContentRequest.Detail(ref))
                    runCurrent()
                    assertFalse(titles.any { it.endsWith(" old") })
                    assertEquals((if (album) "Album" else "Playlist") + " new", model.state.value.title)
                    assertFalse(model.state.value.loading)
                    assertEquals(30, model.state.value.items.size)
                } finally {
                    collector.cancelAndJoin()
                    model.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
                }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun sourceSelectedDownloadsUseLoadedRowsWithoutFetchingTheRestOfThePlaylist() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val provider = DetailProvider().apply { reportedTrackCount = 3000; invalidContinuation = true }
        val registry = ProviderRegistry(listOf(provider))
        val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
        val model = SourceContentViewModel(router, SourcePreferences(MemoryPreferences()), registry)
        val ref = PlaylistRef(provider.id, "playlist/opaque")
        try {
            model.open(SourceContentRequest.Detail(ref))
            runCurrent()
            val visible = model.state.value
            val track = provider.tracks[2]
            val snapshot = model.selectedDetailTracks(ref, setOf(TrackRefCodec.mediaId(track.ref)))
            assertEquals(listOf(track), snapshot.tracks)
            assertSame(visible, model.state.value)
            assertEquals(listOf(0), provider.pageRequests.map { it.offset })
            val missing = runCatching {
                model.selectedDetailTracks(ref, setOf(TrackRefCodec.mediaId(provider.tracks.last().ref)))
            }.exceptionOrNull()
            assertEquals(SourceErrorCode.NOT_FOUND, (missing as? SourceException)?.code)
            assertEquals(listOf(0), provider.pageRequests.map { it.offset })

            provider.accountRevision.value = "account-B"
            assertTrue(runCatching {
                model.selectedDetailTracks(ref, setOf(TrackRefCodec.mediaId(track.ref)))
            }.exceptionOrNull() is CancellationException)
        } finally {
            try { model.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            finally { Dispatchers.resetMain() }
        }
    }

    @Test
    fun sourceSelectedDownloadsReuseSelectAllMetadataAndPreservePlaylistPositions() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val provider = DetailProvider().apply { tracks = tracks + tracks.first() }
        val registry = ProviderRegistry(listOf(provider))
        val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
        val model = SourceContentViewModel(router, SourcePreferences(MemoryPreferences()), registry)
        val ref = PlaylistRef(provider.id, "playlist/opaque")
        try {
            model.open(SourceContentRequest.Detail(ref))
            runCurrent()
            val all = model.detailTracks(ref)
            val requests = provider.pageRequests.toList()
            val ids = setOf(TrackRefCodec.mediaId(provider.tracks[64].ref), TrackRefCodec.mediaId(provider.tracks.first().ref))
            val selected = model.selectedDetailTracks(ref, ids)
            assertEquals(listOf(provider.tracks.first(), provider.tracks[64], provider.tracks.first()), selected.tracks)
            assertEquals(all.generation, selected.generation)
            assertEquals(requests, provider.pageRequests)
            assertSame(all, model.detailTracks(ref))
        } finally {
            try { model.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            finally { Dispatchers.resetMain() }
        }
    }

    @Test
    fun sourceBulkTracksLoadEveryOpaqueIdAndReuseCacheWithoutChangingVisiblePages() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val provider = DetailProvider()
        val registry = ProviderRegistry(listOf(provider))
        val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
        val model = SourceContentViewModel(router, SourcePreferences(MemoryPreferences()), registry)
        try {
            for (ref in listOf(PlaylistRef(provider.id, "playlist/opaque"), AlbumRef(provider.id, "album/opaque"))) {
                model.open(SourceContentRequest.Detail(ref))
                runCurrent()
                val visible = model.state.value
                assertEquals(30, visible.items.size)

                val snapshot = model.detailTracks(ref)
                assertEquals(provider.tracks, snapshot.tracks)
                val match = com.ljyh.mei.ui.screen.source.sourceDetailMatches(snapshot.tracks, "Track 65").single()
                assertEquals(64, match.index)
                assertEquals(provider.tracks.last().ref, match.value.ref)
                assertEquals(listOf(0, 30, 60), provider.pageRequests.takeLast(3).map { it.offset })
                assertSame(visible, model.state.value)
                val requests = provider.pageRequests.size
                assertSame(snapshot, model.detailTracks(ref))
                assertEquals(requests, provider.pageRequests.size)
            }
        } finally {
            try { model.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            finally { Dispatchers.resetMain() }
        }
    }

    @Test
    fun sourceBulkTracksRestartFailedUnknownLengthPaginationBeforeReturningAllTracks() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val provider = DetailProvider().apply { reportedTrackCount = 0; invalidContinuation = true }
        val registry = ProviderRegistry(listOf(provider))
        val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
        val model = SourceContentViewModel(router, SourcePreferences(MemoryPreferences()), registry)
        val ref = PlaylistRef(provider.id, "playlist/opaque")
        try {
            model.open(SourceContentRequest.Detail(ref))
            runCurrent()
            model.loadMore()
            runCurrent()
            val failedPage = model.state.value
            assertEquals(SourceErrorCode.INVALID_RESPONSE, failedPage.error)
            assertFalse(failedPage.hasMore)
            assertEquals(30, failedPage.items.size)

            val failedRead = runCatching { model.detailTracks(ref) }.exceptionOrNull()
            assertEquals(SourceErrorCode.INVALID_RESPONSE, (failedRead as? SourceException)?.code)
            provider.invalidContinuation = false
            val complete = model.detailTracks(ref)
            assertEquals(provider.tracks, complete.tracks)
            assertEquals(listOf(0, 30, 60), provider.pageRequests.takeLast(3).map { it.offset })
            assertSame(failedPage, model.state.value)
        } finally {
            try { model.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            finally { Dispatchers.resetMain() }
        }
    }

    @Test
    fun sourceDetailSnapshotsRejectChangedAccountsAndReplacedProviders() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val provider = DetailProvider()
        val registry = ProviderRegistry(listOf(provider))
        val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
        val model = SourceContentViewModel(router, SourcePreferences(MemoryPreferences()), registry)
        val ref = PlaylistRef(provider.id, "playlist/opaque")
        try {
            model.open(SourceContentRequest.Detail(ref))
            runCurrent()
            val oldAccount = model.detailTracks(ref)
            provider.accountRevision.value = "account-B"
            assertTrue(runCatching { model.ensureDetailCurrent(oldAccount) }.exceptionOrNull() is CancellationException)
            assertTrue(runCatching {
                model.selectedDetailTracks(ref, setOf(TrackRefCodec.mediaId(provider.tracks.first().ref)))
            }.exceptionOrNull() is CancellationException)

            runCurrent()
            val currentAccount = model.detailTracks(ref)
            assertEquals("account-B", currentAccount.accountRevision)
            registry.register(DetailProvider())
            assertTrue(runCatching { model.ensureDetailCurrent(currentAccount) }.exceptionOrNull() is CancellationException)
            assertTrue(runCatching {
                model.selectedDetailTracks(ref, setOf(TrackRefCodec.mediaId(provider.tracks.first().ref)))
            }.exceptionOrNull() is CancellationException)
            assertTrue(provider.savedOrders.isEmpty())
        } finally {
            try { model.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            finally { Dispatchers.resetMain() }
        }
    }

    @Test
    fun sourceDetailCannotAdoptAnUncachedPrefixFromAReplacedSameVersionProvider() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val provider = DetailProvider().apply { tracks = tracks.take(3) }
        val registry = ProviderRegistry(listOf(provider))
        val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
        val model = SourceContentViewModel(router, SourcePreferences(MemoryPreferences()), registry)
        val ref = PlaylistRef(provider.id, "playlist/opaque")
        try {
            model.open(SourceContentRequest.Detail(ref))
            runCurrent()
            assertFalse(model.state.value.hasMore)
            val replacement = DetailProvider().apply { tracks = tracks.takeLast(3) }
            registry.register(replacement)

            assertTrue(runCatching { model.detailTracks(ref) }.exceptionOrNull() is CancellationException)
            assertNull(model.pagedQueue(SourceContentRequest.Detail(ref), provider.tracks, 0, "Playlist"))
            runCurrent()
            val current = model.detailTracks(ref)
            assertSame(replacement, current.provider)
            assertEquals(replacement.tracks, current.tracks)
        } finally {
            try { model.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            finally { Dispatchers.resetMain() }
        }
    }

    @Test
    fun sourceOrderSaveReloadsTheFullPlaylistAndRejectsConcurrentChanges() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val provider = DetailProvider()
        val registry = ProviderRegistry(listOf(provider))
        val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
        val model = SourceContentViewModel(router, SourcePreferences(MemoryPreferences()), registry)
        val ref = PlaylistRef(provider.id, "playlist/opaque")
        try {
            model.open(SourceContentRequest.Detail(ref))
            runCurrent()
            val snapshot = model.detailTracks(ref)
            provider.tracks = provider.tracks.drop(1) + provider.tracks.first()

            assertFalse(model.saveTrackOrder(snapshot, snapshot.tracks.map { it.ref }.reversed()))
            assertEquals(listOf(0, 30, 60), provider.pageRequests.takeLast(3).map { it.offset })
            assertTrue(provider.savedOrders.isEmpty())
        } finally {
            try { model.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            finally { Dispatchers.resetMain() }
        }
    }

    @Test
    fun sourceOrderSaveWritesAllTypedIdsAndRejectsIncompleteOrders() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val provider = DetailProvider()
        val registry = ProviderRegistry(listOf(provider))
        val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
        val model = SourceContentViewModel(router, SourcePreferences(MemoryPreferences()), registry)
        val ref = PlaylistRef(provider.id, "playlist/opaque")
        try {
            model.open(SourceContentRequest.Detail(ref))
            runCurrent()
            val snapshot = model.detailTracks(ref)
            val reversed = snapshot.tracks.map { it.ref }.reversed()
            val rejected = runCatching { model.saveTrackOrder(snapshot, reversed.dropLast(1)) }.exceptionOrNull()
            assertEquals(SourceErrorCode.INVALID_RESPONSE, (rejected as? SourceException)?.code)
            assertTrue(provider.savedOrders.isEmpty())

            assertTrue(model.saveTrackOrder(snapshot, reversed))
            assertEquals(listOf(reversed), provider.savedOrders)
        } finally {
            try { model.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            finally { Dispatchers.resetMain() }
        }
    }

    @Test
    fun cachedAndFetchedTracksFollowCanonicalIdsWithoutRefetchingCachedRows() = runBlocking {
        val requests = mutableListOf<List<String>>()
        val source = PlaylistTrackSource(
            loadTracks = { ids -> requests += ids; listOf(track(4), track(2)) },
            firstData = listOf(track(3), track(1)),
            ids = listOf("1", "2", "3", "4"),
        )
        val page = source.page(0, 3)
        assertEquals(listOf(1L, 2L, 3L), page.data.map { it.id })
        assertEquals(listOf(listOf("2")), requests)
        assertEquals(3, page.nextKey)
        assertEquals(listOf(4L), source.page(3, 3).data.map { it.id })
    }

    @Test
    fun missingTracksAndEntireMissingPagesStillAdvanceByRequestedIds() = runBlocking {
        val source = PlaylistTrackSource(
            loadTracks = { ids -> ids.filter { it == "2" || it == "5" }.map { track(it.toLong()) } },
            firstData = emptyList(), ids = (1..6).map(Int::toString),
        )
        val first = source.page(0, 2)
        assertEquals(listOf(2L), first.data.map { it.id })
        assertEquals(2, first.nextKey)
        val empty = source.page(first.nextKey!!, 2)
        assertTrue(empty.data.isEmpty())
        assertEquals(4, empty.nextKey)
        val last = source.page(empty.nextKey!!, 2)
        assertEquals(listOf(5L), last.data.map { it.id })
        assertNull(last.nextKey)
    }

    @Test
    fun cancellationPropagatesInsteadOfBecomingARetryablePage() = runBlocking {
        val source = PlaylistTrackSource(
            loadTracks = { throw CancellationException("cancelled") },
            firstData = emptyList(), ids = listOf("1"),
        )
        try {
            source.page(0, 1)
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected: leaving the screen cancels the original request.
        }
    }

    private suspend fun PlaylistTrackSource.page(offset: Int, size: Int) =
        load(PagingSource.LoadParams.Refresh(offset, size, false)) as PagingSource.LoadResult.Page<Int, MediaMetadata>

    private fun track(id: Long): PlaylistDetail.Playlist.Track = Gson().fromJson(
        """{"id":$id,"name":"Song $id","dt":180000,"ar":[],"al":{"id":1,"name":"Album","picUrl":""}}""",
        PlaylistDetail.Playlist.Track::class.java,
    )

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private class DetailProvider : MusicProvider, PlaylistCapability, AlbumCapability,
        PlaylistEditCapability, AccountRevisionCapability {
        override val id = "detail-fixture"
        override val displayName = "Detail fixture"
        override val capabilities = setOf(SourceCapability.PLAYLIST, SourceCapability.ALBUM, SourceCapability.PLAYLIST_EDIT)
        override val playlistEditActions = setOf(PlaylistEditAction.REORDER_TRACKS)
        override val accountRevision = MutableStateFlow<String?>("account-A")
        var tracks = (1..65).map { Track(TrackRef(id, "track/$it:opaque"), "Track $it", emptyList()) }
        var reportedTrackCount: Int? = null
        var invalidContinuation = false
        var titleSuffix = ""
        var beforeDetails: suspend (ContentRef) -> Unit = {}
        var beforeTracks: suspend (PageRequest) -> Unit = {}
        val calls = mutableListOf<String>()
        val pageRequests = mutableListOf<PageRequest>()
        val savedOrders = mutableListOf<List<TrackRef>>()

        override suspend fun playlist(ref: PlaylistRef): SourcePlaylist {
            calls += "playlist.get"
            val value = SourcePlaylist(ref, "Playlist$titleSuffix", trackCount = reportedTrackCount ?: tracks.size,
                editableActions = playlistEditActions)
            beforeDetails(ref)
            return value
        }
        override suspend fun album(ref: AlbumRef): SourceAlbumInfo {
            calls += "album.get"
            val value = SourceAlbumInfo(ref, "Album$titleSuffix", trackCount = tracks.size)
            beforeDetails(ref)
            return value
        }
        override suspend fun playlistTracks(ref: PlaylistRef, page: PageRequest): SourcePage<Track> {
            calls += "playlist.tracks" + if (page.refresh) ":refresh" else ""
            return trackPage(page)
        }
        override suspend fun albumTracks(ref: AlbumRef, page: PageRequest): SourcePage<Track> {
            calls += "album.tracks" + if (page.refresh) ":refresh" else ""
            return trackPage(page)
        }
        override suspend fun reorderTracks(ref: PlaylistRef, tracks: List<TrackRef>) {
            savedOrders += tracks.toList()
        }

        private suspend fun trackPage(request: PageRequest): SourcePage<Track> {
            pageRequests += request
            beforeTracks(request)
            if (invalidContinuation && request.offset > 0) return SourcePage(emptyList(), hasMore = true)
            val rows = tracks.drop(request.offset).take(request.limit)
            return SourcePage(rows, hasMore = request.offset + rows.size < tracks.size)
        }
    }
}
