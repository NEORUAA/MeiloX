package com.ljyh.mei.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.google.gson.JsonElement
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.*
import com.ljyh.mei.ui.screen.source.SourceContentRequest
import com.ljyh.mei.ui.screen.source.SourceContentViewModel
import java.io.File
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The previous Home cache policies now operate on source-neutral normalized content. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<SourceContentViewModel>()
    private val fetchedAt = Instant.parse("2026-10-15T04:00:00Z").toEpochMilli()
    private var now = fetchedAt
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() = runTest(dispatcher) {
        try {
            val jobs = models.mapNotNull { it.viewModelScope.coroutineContext[Job] }
            jobs.forEach { it.cancel() }
            jobs.joinAll()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun normalizedCacheRoundTripsAllContentAndSourceOwnedTargets() = runBlocking {
        val fixture = Fixture()
        val page = fixture.richFeed()
        assertTrue(fixture.cache.write(fixture.owner, page))
        assertEquals(page, fixture.cache.read(fixture.owner, fixture.provider))
        val bytes = fixture.cache.file(fixture.owner).readText()
        assertFalse(bytes.contains("private-account-revision"))
        assertFalse(bytes.contains("dslData"))
        assertEquals(1, fixture.directory.listFiles()!!.size)
        val corrupted = fixture.cache.file(fixture.owner).readBytes()
        val title = bytes.indexOf("Home")
        corrupted[title] = 0xc3.toByte()
        fixture.cache.file(fixture.owner).writeBytes(corrupted)
        assertNull(fixture.cache.read(fixture.owner, fixture.provider))
        assertArrayEquals(corrupted, fixture.cache.file(fixture.owner).readBytes())
    }

    @Test fun sourceVersionInstanceAndAccountEachHaveTheirOwnCache() = runBlocking {
        val fixture = Fixture()
        fixture.cache.write(fixture.owner, fixture.richFeed())
        for (other in listOf(fixture.owner.copy(providerId = "another"), fixture.owner.copy(instanceId = ProviderInstanceId("another:instance")),
            fixture.owner.copy(version = "next"), fixture.owner.copy(accountRevision = "other"), fixture.owner.copy(accountRevision = null))) {
            assertNull(fixture.cache.read(other, fixture.provider))
        }
        assertTrue(fixture.cache.file(fixture.owner).name.matches(Regex("[a-f0-9]{64}\\.json")))
        assertFalse(fixture.owner.toString().contains("private-account-revision"))
    }

    @Test fun cacheSurvivesMidnightButExpiresAtSixAmShanghaiAndRejectsTheFuture() = runBlocking {
        val fixture = Fixture()
        fixture.cache.write(fixture.owner, fixture.richFeed())
        now = Instant.parse("2026-10-15T21:59:59Z").toEpochMilli()
        assertNotNull(fixture.cache.read(fixture.owner, fixture.provider))
        now = Instant.parse("2026-10-15T22:00:00Z").toEpochMilli()
        assertNull(fixture.cache.read(fixture.owner, fixture.provider))
        now = fetchedAt - 1
        assertNull(fixture.cache.read(fixture.owner, fixture.provider))
    }

    @Test fun corruptLegacyAndIncompatibleRecordsAreMissesWithoutChangingTheirBytes() = runBlocking {
        val fixture = Fixture()
        for (invalid in listOf("{broken", "null", "{}", "[]", """{"version":2,"feed":{}}""", "[".repeat(65) + "]".repeat(65))) {
            fixture.directory.mkdirs()
            val file = fixture.cache.file(fixture.owner)
            file.writeText(invalid)
            val before = file.readBytes()
            assertNull(fixture.cache.read(fixture.owner, fixture.provider))
            assertArrayEquals(before, file.readBytes())
            assertFalse(fixture.cache.write(fixture.owner, fixture.richFeed().copy(hasMore = true)))
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test fun failedCancelledAndStaleWritesPreserveTheCompletedCache() = runBlocking {
        val fixture = Fixture()
        fixture.cache.write(fixture.owner, fixture.richFeed())
        val before = fixture.cache.file(fixture.owner).readBytes()
        for (failure in listOf(CancellationException("owner changed"), java.io.IOException("offline"))) {
            var checks = 0
            try {
                val written = fixture.cache.write(fixture.owner, fixture.richFeed("replacement")) {
                    if (++checks == 2) throw failure
                }
                assertFalse(written)
                assertFalse(failure is CancellationException)
            } catch (cancelled: CancellationException) { assertTrue(failure is CancellationException) }
            assertArrayEquals(before, fixture.cache.file(fixture.owner).readBytes())
            assertEquals(1, fixture.directory.listFiles()!!.size)
        }
    }

    @Test fun oversizedPayloadCannotReplaceACompletedCache() = runBlocking {
        val fixture = Fixture()
        fixture.cache.write(fixture.owner, fixture.richFeed())
        val before = fixture.cache.file(fixture.owner).readBytes()
        val tooLarge = SourceFeedPage(listOf(SourceFeedSection("oversized", "", SourceFeedLayout.ARTISTS,
            listOf(SourceItem.ArtistItem(SourceArtistInfo(ArtistRef("qq", "artist"), "Artist", description = "x".repeat(8 * 1024 * 1024)))))))
        assertFalse(fixture.cache.write(fixture.owner, tooLarge))
        assertArrayEquals(before, fixture.cache.file(fixture.owner).readBytes())
    }

    @Test fun cachedArtworkUsesOnlyTheFreshOwnerAndNeverFetches() = runBlocking {
        val fixture = Fixture()
        val playlist = PlaylistRef("qq", "playlist-007")
        assertNull(fixture.cache.cachedArtwork(fixture.owner, fixture.provider, playlist))
        fixture.cache.write(fixture.owner, fixture.richFeed())
        assertEquals(fixture.artwork, fixture.cache.cachedArtwork(fixture.owner, fixture.provider, playlist))
        assertNull(fixture.cache.cachedArtwork(fixture.owner.copy(accountRevision = "another"), fixture.provider, playlist))
        assertNull(fixture.cache.cachedArtwork(fixture.owner, fixture.provider, PlaylistRef("other", playlist.playlistId)))
        assertEquals(0, fixture.provider.requests.size)
    }

    @Test fun completePagedHomeIsReusedAndExplicitRefreshBypassesIt() = runTest(dispatcher) {
        val fixture = Fixture()
        val first = fixture.model()
        val refreshingStates = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            first.state.collect { refreshingStates += it.refreshing }
        }
        first.open(SourceContentRequest.Feed(FeedKind.HOME, providerId = "qq"))
        first.state.first { !it.loading && it.sections.size == 2 }
        awaitCache(fixture)
        assertEquals(listOf(0, 30), fixture.provider.requests.map { it.offset })
        first.viewModelScope.cancel()
        val reopened = fixture.model()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            reopened.state.collect { refreshingStates += it.refreshing }
        }
        reopened.open(SourceContentRequest.Feed(FeedKind.HOME, providerId = "qq"))
        reopened.state.first { !it.loading && it.sections.size == 2 }
        assertEquals(2, fixture.provider.requests.size)
        assertTrue(refreshingStates.none { it })
        refreshingStates.clear()
        reopened.refresh()
        reopened.state.first { !it.loading && !it.refreshing && fixture.provider.requests.size == 4 }
        assertEquals(listOf(false, false, true, false), fixture.provider.requests.map { it.refresh })
        assertTrue(refreshingStates.dropLast(1).isNotEmpty())
        assertTrue(refreshingStates.dropLast(1).all { it })
        assertFalse(refreshingStates.last())
    }

    @Test fun failedLaterHomePageAndCorruptRefetchKeepSavedBytesUntilSuccess() = runTest(dispatcher) {
        val fixture = Fixture()
        fixture.cache.write(fixture.owner, fixture.richFeed())
        val before = fixture.cache.file(fixture.owner).readBytes()
        val vm = fixture.model()
        vm.open(SourceContentRequest.Feed(FeedKind.HOME, providerId = "qq"))
        vm.state.first { !it.loading && it.sections.isNotEmpty() }
        fixture.provider.failAppend = true
        vm.refresh()
        vm.state.first { !it.loading && !it.refreshing && it.error != null }
        assertArrayEquals(before, fixture.cache.file(fixture.owner).readBytes())
        assertEquals(SourceErrorCode.NETWORK, vm.state.value.error)
        vm.viewModelScope.cancel()
        fixture.cache.file(fixture.owner).writeText("{broken")
        fixture.provider.failAppend = false
        val reopened = fixture.model()
        reopened.open(SourceContentRequest.Feed(FeedKind.HOME, providerId = "qq"))
        reopened.state.first { !it.loading && it.sections.size == 2 }
        awaitCache(fixture, rejects = "{broken")
        assertNotNull(fixture.cache.read(fixture.owner, fixture.provider))
    }

    @Test fun repeatedFeedContentStopsEvenWhenTheCursorChanges() = runTest(dispatcher) {
        val fixture = Fixture()
        fixture.provider.repeatAppend = true
        val vm = fixture.model()
        vm.open(SourceContentRequest.Feed(FeedKind.HOME, providerId = "qq"))
        vm.state.first { !it.loading && it.error != null }
        assertEquals(SourceErrorCode.INVALID_RESPONSE, vm.state.value.error)
        assertFalse(vm.state.value.hasMore)
        assertEquals(listOf(0, 30), fixture.provider.requests.map { it.offset })
        assertEquals(1, vm.state.value.sections.size)
    }

    @Test fun categorySwitchKeepsItsControlsWhileAnAccountSwitchClearsThem() = runTest(dispatcher) {
        val fixture = Fixture()
        val categories = listOf(SourceCategory("all", "All"), SourceCategory("next", "Next"))
        fixture.provider.categories = categories
        val vm = fixture.model()
        vm.open(SourceContentRequest.Feed(FeedKind.DISCOVER, providerId = "qq"))
        vm.state.first { !it.loading && it.sections.isNotEmpty() }
        val pending = CompletableDeferred<Unit>()
        fixture.provider.pending = pending
        vm.open(SourceContentRequest.Feed(FeedKind.DISCOVER, categoryId = "next", providerId = "qq"))
        runCurrent()
        assertTrue(vm.state.value.loading)
        assertEquals(categories, vm.state.value.categories)
        assertTrue(vm.state.value.sections.isEmpty())
        fixture.provider.accountRevision.value = "another-account"
        runCurrent()
        assertTrue(vm.state.value.categories.isEmpty())
        pending.complete(Unit)
        advanceUntilIdle()
    }

    private suspend fun awaitCache(fixture: Fixture, rejects: String? = null) = withContext(Dispatchers.IO) {
        withTimeout(3_000) {
            while (!fixture.cache.file(fixture.owner).isFile || rejects != null && fixture.cache.file(fixture.owner).readText() == rejects) delay(10)
        }
    }

    private inner class Fixture {
        val directory = temporary.newFolder()
        val artwork = File(temporary.root, "cover.jpg").toURI().toString()
        val provider = FeedProvider()
        val owner = SourceHomeCacheOwner(provider.id, provider.instanceId, provider.version, provider.accountRevision.value)
        val cache = SourceHomeFeedCache(directory, listOf(temporary.root)) { now }
        fun model(): SourceContentViewModel {
            val registry = ProviderRegistry(listOf(provider))
            return SourceContentViewModel(SourceRouter(registry), SourcePreferences(MemoryPreferences()), registry, homeCache = cache).also(models::add)
        }
        fun richFeed(title: String = "Home"): SourceFeedPage {
            val artist = SourceArtistInfo(ArtistRef("qq", "artist/A"), "Artist", artwork, "Biography", listOf("Alias"), 12, 4, 0,
                listOf("Composer"), listOf(SourceArtistExpertise("Piano", 9)), listOf("Translated"), listOf("Alias"))
            val playlist = SourcePlaylist(PlaylistRef("qq", "playlist-007"), "Playlist", artwork, "Creator", 120,
                playCount = 12_345L, subtitle = "Recommended for you")
            val album = SourceAlbumInfo(AlbumRef("qq", "album-2"), "Album", artwork, listOf(SourceArtist("Artist", artist.ref)), 120, publishedAtMs = 1234)
            val items = listOf(SourceItem.PlaylistItem(playlist), SourceItem.AlbumItem(album), SourceItem.ArtistItem(artist))
            val targets = listOf(SourceFeedTarget.Content(playlist.ref), SourceFeedTarget.Route("podcasts"),
                SourceFeedTarget.Radio(listOf("track-1")), SourceFeedTarget.Queue("discovery", "dailySongs", JsonObject().apply { addProperty("id", "public-id") }))
            return SourceFeedPage(listOf(SourceFeedSection("hero", title, SourceFeedLayout.HERO, items,
                targets.mapIndexed { index, target -> SourceFeedCard("card-$index", "Card", artwork, "Subtitle", artwork, target) }),
                SourceFeedSection("tracks", "Tracks", SourceFeedLayout.TRACKS, (1..120).map {
                    SourceItem.TrackItem(Track(TrackRef("qq", "track-$it"), "Track $it", listOf(SourceArtist("Artist", artist.ref)), SourceAlbum("Album", album.ref), 1000, artwork))
                }), SourceFeedSection("radar", "Radar", SourceFeedLayout.PLAYLISTS, listOf(SourceItem.PlaylistItem(playlist)),
                    listOf(SourceFeedCard("radar-card", "", artwork, "Creator", "", SourceFeedTarget.Content(playlist.ref),
                        coverCaptions = listOf("First caption", "Second caption"), detail = "120 plays", titleMaxLines = 1)))),
                listOf(SourceCategory("category", "Category")))
        }
    }
    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(data.value).also { data.value = it }
    }
    private class FeedProvider : MusicProvider, FeedCapability, AccountRevisionCapability, SourceFeatureCapability, FeatureContribution {
        override val id = "qq"
        override val displayName = "Fixture"
        override val version = "1"
        override val instanceId = ProviderInstanceId("fixture:qq")
        override val capabilities = setOf(SourceCapability.FEED, SourceCapability.FEATURES)
        override val accountRevision = MutableStateFlow<String?>("private-account-revision")
        override val feedKinds = setOf(FeedKind.HOME, FeedKind.DISCOVER)
        override val features = mapOf("discovery" to setOf("dailySongs", "personalRadio"))
        override val featureRoutes = setOf("podcasts")
        val requests = mutableListOf<PageRequest>()
        var failAppend = false
        var repeatAppend = false
        var categories = emptyList<SourceCategory>()
        var pending: CompletableDeferred<Unit>? = null
        override suspend fun getFeed(kind: FeedKind, categoryId: String?, page: PageRequest): SourceFeedPage {
            requests += page
            pending?.await()
            if (page.offset > 0 && failAppend) throw SourceException(SourceErrorCode.NETWORK)
            val contentOffset = if (repeatAppend) 0 else page.offset
            return SourceFeedPage(listOf(SourceFeedSection("page-$contentOffset", "Page", SourceFeedLayout.TRACKS,
                listOf(SourceItem.TrackItem(Track(TrackRef("qq", "track-$contentOffset"), "Track", listOf(SourceArtist("Artist"))))))),
                categories = categories, hasMore = page.offset == 0 || repeatAppend,
                nextCursor = if (page.offset == 0) "next" else if (repeatAppend) "next-${page.offset}" else null)
        }
        override suspend fun invokeFeature(feature: String, action: String, parameters: JsonObject): JsonElement = error("Cache lookup must not invoke a feature")
    }
}
