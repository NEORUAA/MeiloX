package com.ljyh.mei.ui.screen.artist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.ProviderRegistry
import com.ljyh.mei.source.core.SourcePreferences
import com.ljyh.mei.source.core.SourceRouter
import com.ljyh.mei.ui.screen.source.SourceContentRequest
import com.ljyh.mei.ui.screen.source.SourceContentViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** The original artist behaviors now exercise the shared provider page, without native HTTP mocks. */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtistCatalogTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<SourceContentViewModel>()
    private val ref = ArtistRef("qq", "artist/A-007")

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() = runTest(dispatcher) {
        val jobs = models.mapNotNull { it.viewModelScope.coroutineContext[Job] }
        jobs.forEach { it.cancel() }
        jobs.forEach { it.join() }
        Dispatchers.resetMain()
    }

    @Test fun returningToArtistRetainsCompletedAndPendingReads() = runTest(dispatcher) {
        val pending = CompletableDeferred<SourcePage<Track>>()
        val provider = ArtistProvider().apply { songs = { pending.await() } }
        val vm = model(provider)
        vm.open(SourceContentRequest.Detail(ref)); runCurrent()
        vm.open(SourceContentRequest.Detail(ref)); runCurrent()
        assertEquals(1, provider.songRequests.size)
        pending.complete(SourcePage(emptyList())); advanceUntilIdle()
        val loaded = vm.state.value
        vm.open(SourceContentRequest.Detail(ref)); advanceUntilIdle()
        assertSame(loaded, vm.state.value)
        assertEquals(1, provider.infoRequests)
    }

    @Test fun artistInformationReadsOnlyTheOriginalRichMetadata() = runTest(dispatcher) {
        val provider = ArtistProvider()
        val vm = model(provider)
        vm.open(SourceContentRequest.ArtistInformation(ref)); advanceUntilIdle()
        assertEquals(ref, vm.state.value.detail?.ref)
        assertEquals(1, provider.infoRequests)
        assertTrue(provider.songRequests.isEmpty())
        assertTrue(provider.albumRequests.isEmpty())
    }

    @Test fun headerAndAlbumsRemainVisibleWhenTheSongRequestFails() = runTest(dispatcher) {
        val pending = CompletableDeferred<SourcePage<Track>>()
        val provider = ArtistProvider().apply {
            songs = { pending.await() }
            albums = { albumPage(false, "release") }
        }
        val vm = model(provider)
        vm.open(SourceContentRequest.Detail(ref)); runCurrent()
        assertEquals(ref, vm.state.value.detail?.ref)
        assertEquals(listOf("release"), albumIds(vm))
        assertTrue(vm.state.value.loading)
        pending.completeExceptionally(SourceException(SourceErrorCode.NETWORK)); advanceUntilIdle()
        assertEquals(ref, vm.state.value.detail?.ref)
        assertEquals(listOf("release"), albumIds(vm))
        assertEquals(SourceErrorCode.NETWORK, vm.state.value.error)
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.albumsError)
    }

    @Test fun overlappingAlbumsUseRawOffsetsAndIgnoreConcurrentOrFinishedLoads() = runTest(dispatcher) {
        val provider = ArtistProvider().apply { albums = { page -> when (page.offset) {
            0 -> albumPage(true, "1", "2")
            2 -> albumPage(false, "2", "3")
            else -> error("Unexpected offset")
        } } }
        val vm = model(provider)
        vm.open(SourceContentRequest.Detail(ref)); advanceUntilIdle()
        vm.loadMoreArtistAlbums(); vm.loadMoreArtistAlbums(); advanceUntilIdle()
        vm.loadMoreArtistAlbums(); advanceUntilIdle()
        assertEquals(listOf(0, 2), provider.albumRequests.map { it.offset })
        assertEquals(listOf("1", "2", "3"), albumIds(vm))
        assertEquals(4, vm.state.value.albumsNextOffset)
        assertFalse(vm.state.value.albumsHasMore)
    }

    @Test fun failedAppendRetainsAlbumsAndRetriesTheSameRawOffset() = runTest(dispatcher) {
        var failure = true
        val provider = ArtistProvider().apply { albums = { page -> when {
            page.offset == 0 -> albumPage(true, "1")
            failure -> { failure = false; throw SourceException(SourceErrorCode.NETWORK) }
            else -> albumPage(false, "2")
        } } }
        val vm = model(provider)
        vm.open(SourceContentRequest.Detail(ref)); advanceUntilIdle()
        vm.loadMoreArtistAlbums(); advanceUntilIdle()
        assertEquals(listOf("1"), albumIds(vm))
        assertEquals(1, vm.state.value.albumsNextOffset)
        assertEquals(SourceErrorCode.NETWORK, vm.state.value.albumsError)
        vm.loadMoreArtistAlbums(); advanceUntilIdle()
        assertEquals(listOf(0, 1, 1), provider.albumRequests.map { it.offset })
        assertEquals(listOf("1", "2"), albumIds(vm))
        assertNull(vm.state.value.albumsError)
    }

    @Test fun malformedEmptyAlbumPageStopsInsteadOfLooping() = runTest(dispatcher) {
        val provider = ArtistProvider().apply { albums = { if (it.offset == 0) albumPage(true, "1") else albumPage(true) } }
        val vm = model(provider)
        vm.open(SourceContentRequest.Detail(ref)); advanceUntilIdle()
        vm.loadMoreArtistAlbums(); advanceUntilIdle()
        vm.loadMoreArtistAlbums(); advanceUntilIdle()
        assertEquals(listOf(0, 1), provider.albumRequests.map { it.offset })
        assertFalse(vm.state.value.albumsHasMore)
        assertEquals(SourceErrorCode.INVALID_RESPONSE, vm.state.value.albumsError)
        assertEquals(listOf("1"), albumIds(vm))
    }

    @Test fun repeatedAlbumsStopEvenWhenTheCursorChanges() = runTest(dispatcher) {
        val provider = ArtistProvider().apply { albums = { page ->
            albumPage(true, "1").copy(nextCursor = "cursor-${page.offset}")
        } }
        val vm = model(provider)
        vm.open(SourceContentRequest.Detail(ref)); advanceUntilIdle()
        vm.loadMoreArtistAlbums(); advanceUntilIdle()
        vm.loadMoreArtistAlbums(); advanceUntilIdle()
        assertEquals(listOf(0, 1), provider.albumRequests.map { it.offset })
        assertFalse(vm.state.value.albumsHasMore)
        assertEquals(SourceErrorCode.INVALID_RESPONSE, vm.state.value.albumsError)
        assertEquals(listOf("1"), albumIds(vm))
    }

    @Test fun pendingArtistSwitchCannotAppendThePreviousPage() = runTest(dispatcher) {
        val provider = ArtistProvider().apply {
            albums = { albumPage(true, "1") }
            songs = { SourcePage(listOf(Track(TrackRef("qq", "song"), "Song", emptyList())), hasMore = true) }
        }
        val vm = model(provider)
        vm.open(SourceContentRequest.Detail(ref)); advanceUntilIdle()
        vm.open(SourceContentRequest.Detail(ref.copy(artistId = "other")))
        vm.loadMore(); vm.loadMoreArtistAlbums(); advanceUntilIdle()
        assertEquals(listOf(0, 0), provider.songRequests.map { it.offset })
        assertEquals(listOf(0, 0), provider.albumRequests.map { it.offset })
    }

    @Test fun albumSearchDebouncesAndContinuesInThreePageBudgets() = runTest(dispatcher) {
        val provider = ArtistProvider().apply { albums = { page -> albumPage(page.offset < 4, page.offset.toString()) } }
        val vm = model(provider)
        vm.open(SourceContentRequest.Detail(ref)); advanceUntilIdle()
        vm.updateArtistCatalogSearch(ref, true, "album 4")
        advanceTimeBy(249); runCurrent()
        assertEquals(listOf(0), provider.albumRequests.map { it.offset })
        advanceUntilIdle()
        assertEquals(listOf(0, 1, 2, 3), provider.albumRequests.map { it.offset })
        assertTrue(vm.state.value.albumsHasMore)
        vm.continueArtistCatalogSearch(); advanceUntilIdle()
        assertEquals(listOf(0, 1, 2, 3, 4), provider.albumRequests.map { it.offset })
        assertEquals(listOf("0", "1", "2", "3", "4"), albumIds(vm))
        assertFalse(vm.state.value.albumsHasMore)
    }

    @Test fun songSearchSharesLoadedPagesAndClearingReturnsToNormalPaging() = runTest(dispatcher) {
        val provider = ArtistProvider().apply { songs = { page -> SourcePage(listOf(track(page.offset)), page.offset < 4) } }
        val vm = model(provider)
        vm.open(SourceContentRequest.Detail(ref)); advanceUntilIdle()
        vm.updateArtistCatalogSearch(ref, false, "match")
        advanceUntilIdle()
        assertEquals(listOf(0, 1, 2, 3), provider.songRequests.map { it.offset })
        vm.updateArtistCatalogSearch(ref, false, "")
        vm.loadMore(); advanceUntilIdle()
        assertEquals(listOf(0, 1, 2, 3, 4), provider.songRequests.map { it.offset })
        assertFalse(vm.state.value.hasMore)
        assertEquals(5, vm.state.value.items.size)
    }

    @Test fun cancelledOldQueryAndOldArtistCannotReplaceTheActiveCatalog() = runTest(dispatcher) {
        val stale = CompletableDeferred<SourcePage<SourceAlbumInfo>>()
        var suspended = false
        val provider = ArtistProvider().apply { albums = { page ->
            if (page.offset == 0) albumPage(true, "0") else if (!suspended) {
                suspended = true; withContext(NonCancellable) { stale.await() }
            } else albumPage(false, "fresh")
        } }
        val vm = model(provider)
        vm.open(SourceContentRequest.Detail(ref)); advanceUntilIdle()
        vm.updateArtistCatalogSearch(ref, true, "old"); advanceTimeBy(250); runCurrent()
        vm.updateArtistCatalogSearch(ref, true, "new"); advanceUntilIdle()
        assertEquals(listOf("0", "fresh"), albumIds(vm))
        stale.complete(albumPage(true, "stale")); advanceUntilIdle()
        assertEquals(listOf("0", "fresh"), albumIds(vm))
        vm.open(SourceContentRequest.ArtistInformation(ArtistRef("qq", "artist/B"))); advanceUntilIdle()
        assertEquals("artist/B", vm.state.value.detail?.ref?.contentId)
        assertEquals("artist/B", vm.state.value.title)
    }

    @Test fun collectionChangesDoNotReloadOrOverwriteTheArtistCatalog() = runTest(dispatcher) {
        val provider = ArtistProvider().apply { albums = { albumPage(false, "1", "2") } }
        val vm = model(provider)
        vm.open(SourceContentRequest.Detail(ref)); advanceUntilIdle()
        val catalog = vm.state.value
        vm.setSaved(AlbumRef("qq", "1"), true); advanceUntilIdle()
        assertTrue(vm.saved(AlbumRef("qq", "1")))
        assertSame(catalog, vm.state.value)
        vm.setSaved(ref, true); advanceUntilIdle()
        assertTrue(vm.saved(ref))
        assertEquals(1, provider.albumRequests.size)
        assertEquals(1, provider.infoRequests)
    }

    @Test fun failedUnfollowAndDoubleTapKeepTheConfirmedCollectionState() = runTest(dispatcher) {
        val mutation = CompletableDeferred<Unit>()
        val provider = ArtistProvider().apply { saved += ref; save = { _, _ -> mutation.await() } }
        val vm = model(provider)
        vm.open(SourceContentRequest.Detail(ref)); advanceUntilIdle()
        vm.setSaved(ref, false); vm.setSaved(ref, false); runCurrent()
        assertEquals(1, provider.saveRequests)
        val pendingRead = async { vm.saved(ref) }
        runCurrent()
        assertFalse(pendingRead.isCompleted)
        mutation.completeExceptionally(SourceException(SourceErrorCode.NETWORK)); advanceUntilIdle()
        assertTrue(pendingRead.await())
        assertTrue(vm.saved(ref))
        assertEquals(SourceErrorCode.NETWORK, vm.message.value)
        assertFalse(vm.busy.value)
    }

    @Test fun retiredAccountsCannotRepublishPrivateArtistReads() = runTest(dispatcher) {
        val stale = CompletableDeferred<SourcePage<Track>>()
        var first = true
        val provider = ArtistProvider().apply { songs = {
            if (first) { first = false; withContext(NonCancellable) { stale.await() } } else SourcePage(listOf(track(2)))
        } }
        val vm = model(provider)
        vm.open(SourceContentRequest.Detail(ref)); runCurrent()
        provider.accountRevision.value = "next-account"; runCurrent()
        stale.complete(SourcePage(listOf(track(1)))); advanceUntilIdle()
        assertEquals("next-account", vm.state.value.accountRevision)
        assertEquals(listOf("track-2"), vm.state.value.items.map { it.ref.contentId })
    }

    private fun model(provider: ArtistProvider): SourceContentViewModel {
        val registry = ProviderRegistry(listOf(provider))
        return SourceContentViewModel(SourceRouter(registry), SourcePreferences(MemoryPreferences()), registry).also(models::add)
    }
    private fun albumIds(vm: SourceContentViewModel) = vm.state.value.sections.flatMap { it.items }.map { it.ref.contentId }
    private fun albumPage(more: Boolean, vararg ids: String) = SourcePage(ids.map {
        SourceAlbumInfo(AlbumRef("qq", it), "Album $it", trackCount = 10)
    }, more)
    private fun track(index: Int) = Track(TrackRef("qq", "track-$index"), "Track $index", listOf(SourceArtist("Artist")), SourceAlbum("Album $index"))

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(data.value).also { data.value = it }
    }
    private class ArtistProvider : MusicProvider, ArtistCapability, CollectionCapability, AccountRevisionCapability {
        override val id = "qq"
        override val displayName = "Fixture"
        override val capabilities = setOf(SourceCapability.ARTIST, SourceCapability.COLLECTIONS)
        override val collectionTypes = setOf(ContentType.ARTIST, ContentType.ALBUM)
        override val accountRevision = MutableStateFlow<String?>("account")
        var infoRequests = 0
        val songRequests = mutableListOf<PageRequest>()
        val albumRequests = mutableListOf<PageRequest>()
        var songs: suspend (PageRequest) -> SourcePage<Track> = { SourcePage(emptyList()) }
        var albums: suspend (PageRequest) -> SourcePage<SourceAlbumInfo> = { SourcePage(emptyList()) }
        val saved = mutableSetOf<ContentRef>()
        var saveRequests = 0
        var save: suspend (ContentRef, Boolean) -> Unit = { ref, value -> if (value) saved += ref else saved -= ref }
        override suspend fun artist(ref: ArtistRef): SourceArtistInfo { infoRequests++; return SourceArtistInfo(ref, ref.artistId) }
        override suspend fun artistTracks(ref: ArtistRef, page: PageRequest): SourcePage<Track> { songRequests += page; return songs(page) }
        override suspend fun artistAlbums(ref: ArtistRef, page: PageRequest): SourcePage<SourceAlbumInfo> { albumRequests += page; return albums(page) }
        override suspend fun saved(ref: ContentRef) = ref in saved
        override suspend fun setSaved(ref: ContentRef, saved: Boolean) { saveRequests++; save(ref, saved) }
    }
}
