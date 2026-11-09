package com.ljyh.mei.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
import com.google.gson.JsonObject
import com.google.gson.JsonElement
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.*
import com.ljyh.mei.ui.screen.source.SourceContentRequest
import com.ljyh.mei.ui.screen.source.SourceContentViewModel
import com.ljyh.mei.ui.screen.source.SourceProfileState
import com.ljyh.mei.ui.screen.source.SourceProfileViewModel
import com.ljyh.mei.ui.component.sourceProfileFeatureScreen
import com.ljyh.mei.ui.screen.Screen
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
    private val models = mutableListOf<ViewModel>()
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

    @Test fun profileEntryKeepsLoadingAndTemporaryFailuresOutOfTheLoginFlow() {
        val profile = SourceProfileState(providerId = "netease", featureRoutes = setOf("account/home", "account/login"))
        val account = SourceAccount(SourceAccountRef(ProviderInstanceId("fixture:netease"), ProviderAccountId("account")), "Account")

        assertEquals(Screen.NeteaseLogin, sourceProfileFeatureScreen(profile))
        assertEquals(Screen.NeteaseLogin, sourceProfileFeatureScreen(profile.copy(error = SourceErrorCode.AUTH_EXPIRED)))
        assertEquals(Screen.NeteaseLogin, sourceProfileFeatureScreen(profile.copy(error = SourceErrorCode.AUTH_REQUIRED)))
        assertNull(sourceProfileFeatureScreen(profile.copy(loading = true)))
        assertNull(sourceProfileFeatureScreen(profile.copy(error = SourceErrorCode.NETWORK)))
        assertNull(sourceProfileFeatureScreen(profile.copy(error = SourceErrorCode.TIMEOUT)))
        assertEquals(Screen.AccountHome, sourceProfileFeatureScreen(profile.copy(account = account, loading = true)))
        assertEquals(Screen.AccountHome, sourceProfileFeatureScreen(profile.copy(account = account, error = SourceErrorCode.NETWORK)))
    }

    @Test fun sharedProfileRetriesInitialFailuresAndKeepsOnlyTheCurrentAccount() = runTest(dispatcher) {
        val provider = ProfileProvider()
        val registry = ProviderRegistry(listOf(provider))
        val preferences = SourcePreferences(MemoryPreferences())
        preferences.update { it.copy(preferredBrowseProvider = provider.id) }
        val model = SourceProfileViewModel(preferences, registry,
            readAccount = { id, _ -> (registry.requireProvider(id) as AccountCapability).account() },
            exportAccount = { error("Session export is outside this account-state regression") }).also(models::add)
        provider.failure = SourceErrorCode.NETWORK
        advanceUntilIdle()
        assertEquals(SourceErrorCode.NETWORK, model.state.value.error)
        assertNull(sourceProfileFeatureScreen(model.state.value))

        provider.failure = null
        val pending = CompletableDeferred<Unit>()
        provider.pending = pending
        model.refresh()
        runCurrent()
        assertTrue(model.state.value.loading)
        assertNull(sourceProfileFeatureScreen(model.state.value))
        pending.complete(Unit)
        advanceUntilIdle()
        assertEquals(provider.value, model.state.value.account)
        assertEquals(Screen.AccountHome, sourceProfileFeatureScreen(model.state.value))

        provider.failure = SourceErrorCode.TIMEOUT
        registry.refresh()
        advanceUntilIdle()
        assertEquals(provider.value, model.state.value.account)
        assertEquals(SourceErrorCode.TIMEOUT, model.state.value.error)
        provider.failure = null
        model.refresh()
        advanceUntilIdle()
        assertNull(model.state.value.error)

        val next = CompletableDeferred<Unit>()
        provider.pending = next
        provider.accountRevision.value = "next-account"
        provider.value = provider.value?.copy(displayName = "Next account")
        runCurrent()
        assertTrue(model.state.value.loading)
        assertNull(model.state.value.account)
        next.complete(Unit)
        advanceUntilIdle()
        assertEquals(provider.value, model.state.value.account)

        provider.failure = SourceErrorCode.AUTH_EXPIRED
        registry.refresh()
        advanceUntilIdle()
        assertNull(model.state.value.account)
        assertEquals(Screen.NeteaseLogin, sourceProfileFeatureScreen(model.state.value))
        assertEquals(6, provider.reads)
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

    @Test fun libraryAccountsPublishBeforeRowsAndSurviveTabChangesAndTemporaryFailures() = runTest(dispatcher) {
        val provider = ProfileProvider()
        val registry = ProviderRegistry(listOf(provider))
        val model = SourceContentViewModel(SourceRouter(registry), SourcePreferences(MemoryPreferences()), registry).also(models::add)
        val firstRows = CompletableDeferred<Unit>()
        provider.libraryPending = firstRows
        try {
            model.open(SourceContentRequest.Library(LibraryCollection.LIKED_TRACKS, provider.id))
            runCurrent()
            assertEquals(provider.value, model.state.value.account)
            assertTrue(model.state.value.loading)
            assertTrue(model.state.value.items.isEmpty())
            firstRows.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, provider.reads)

            val createdRows = CompletableDeferred<Unit>()
            provider.libraryPending = createdRows
            model.open(SourceContentRequest.Library(LibraryCollection.CREATED_PLAYLISTS, provider.id))
            runCurrent()
            assertEquals(provider.value, model.state.value.account)
            assertTrue(model.state.value.loading)
            assertTrue(model.state.value.items.isEmpty())
            assertEquals(1, provider.reads)
            createdRows.complete(Unit)
            advanceUntilIdle()

            val account = CompletableDeferred<Unit>()
            val freshRows = CompletableDeferred<Unit>()
            provider.pending = account
            provider.libraryPending = freshRows
            val previous = provider.value
            provider.value = previous?.copy(displayName = "Refreshed account")
            model.refresh()
            runCurrent()
            assertEquals(2, provider.reads)
            assertEquals(previous, model.state.value.account)
            account.complete(Unit)
            runCurrent()
            assertEquals(provider.value, model.state.value.account)
            assertTrue(model.state.value.loading)
            freshRows.complete(Unit)
            advanceUntilIdle()
            provider.pending = null
            provider.libraryPending = null

            for (code in listOf(SourceErrorCode.NETWORK, SourceErrorCode.TIMEOUT, SourceErrorCode.AUTH_REQUIRED, SourceErrorCode.AUTH_EXPIRED)) {
                val authentication = code == SourceErrorCode.AUTH_REQUIRED || code == SourceErrorCode.AUTH_EXPIRED
                provider.failure = code.takeIf { authentication }
                provider.libraryFailure = code.takeUnless { authentication }
                model.refresh()
                advanceUntilIdle()
                assertEquals(code, model.state.value.error)
                assertEquals(if (authentication) null else provider.value, model.state.value.account)
                provider.failure = null
                provider.libraryFailure = null
                model.refresh()
                advanceUntilIdle()
                assertEquals(provider.value, model.state.value.account)
            }
            assertTrue(provider.libraryRequests.take(2).all { !it.refresh })
            assertTrue(provider.libraryRequests.drop(2).all { it.refresh })

            model.open(SourceContentRequest.Detail(PlaylistRef(provider.id, "playlist")))
            advanceUntilIdle()
            assertEquals("Playlist 1", model.state.value.title)
            provider.remotePlaylistVersion = 2
            model.refresh()
            advanceUntilIdle()
            assertTrue(provider.playlistRequests.last().refresh)
            assertEquals("Playlist 2", model.state.value.title)
            assertEquals(TrackRef(provider.id, "track-2"), model.state.value.items.single().ref)
        } finally {
            firstRows.complete(Unit)
            provider.pending?.complete(Unit)
            provider.libraryPending?.complete(Unit)
        }
    }

    @Test fun retainedLibraryAccountsNeverCrossAnAccountVersionOrProviderInstance() = runTest(dispatcher) {
        for (change in listOf("account", "version", "instance")) {
            val original = ProfileProvider()
            val registry = ProviderRegistry(listOf(original))
            val model = SourceContentViewModel(SourceRouter(registry), SourcePreferences(MemoryPreferences()), registry).also(models::add)
            model.open(SourceContentRequest.Library(LibraryCollection.LIKED_TRACKS, original.id))
            advanceUntilIdle()
            assertEquals(original.value, model.state.value.account)
            val next = if (change == "instance") ProfileProvider(ProviderInstanceId("fixture:replacement")) else original
            val response = CompletableDeferred<Unit>()
            next.pending = response
            next.value = SourceAccount(SourceAccountRef(next.instanceId, ProviderAccountId("next")), "Next")
            try {
                when (change) {
                    "account" -> next.accountRevision.value = "next-account"
                    "version" -> { next.version = "2"; registry.refresh() }
                    else -> { registry.unregister(original.instanceId); registry.register(next) }
                }
                runCurrent()
                assertNull(model.state.value.account)
                assertTrue(model.state.value.loading)
                response.complete(Unit)
                advanceUntilIdle()
                assertEquals(next.value, model.state.value.account)
            } finally { response.complete(Unit) }
        }
    }

    @Test fun explicitProfileRefreshBypassesAccountCacheWhileAutomaticSyncUsesTheFreshResult() = runTest(dispatcher) {
        val provider = ProfileProvider()
        val registry = ProviderRegistry(listOf(provider))
        val router = SourceRouter(registry)
        val preferences = SourcePreferences(MemoryPreferences())
        preferences.update { it.copy(preferredBrowseProvider = provider.id) }
        val refreshes = mutableListOf<Boolean>()
        var nextReadGate: CompletableDeferred<Unit>? = null
        val model = SourceProfileViewModel(preferences, registry,
            readAccount = { id, refresh ->
                refreshes += refresh
                val gate = nextReadGate
                nextReadGate = null
                gate?.await()
                router.account(id, refresh = refresh)
            },
            exportAccount = { error("Unexpected export") }).also(models::add)
        advanceUntilIdle()
        registry.refresh()
        model.refresh(force = false)
        advanceUntilIdle()
        assertEquals(1, provider.reads)
        assertTrue(refreshes.none { it })
        val pending = CompletableDeferred<Unit>()
        provider.pending = pending
        val previous = provider.value
        provider.value = previous?.copy(displayName = "Manual refresh")
        try {
            model.refresh()
            model.refresh(force = false)
            runCurrent()
            assertTrue(refreshes.last())
            assertEquals(2, provider.reads)
            assertEquals(previous, model.state.value.account)
            pending.complete(Unit)
            advanceUntilIdle()
            assertEquals(provider.value, model.state.value.account)
            provider.pending = null

            provider.value = provider.value?.copy(displayName = "Account page refresh")
            router.account(provider.id, refresh = true)
            assertEquals(3, provider.reads)
            model.refresh(force = false)
            advanceUntilIdle()
            assertFalse(refreshes.last())
            assertEquals(3, provider.reads)
            assertEquals(provider.value, model.state.value.account)

            val next = CompletableDeferred<Unit>()
            provider.pending = next
            provider.accountRevision.value = "next-account"
            runCurrent()
            assertFalse(refreshes.last())
            assertNull(model.state.value.account)
            assertEquals(4, provider.reads)
            next.complete(Unit)
            advanceUntilIdle()

            val completingAutomaticRead = CompletableDeferred<Unit>()
            nextReadGate = completingAutomaticRead
            model.refresh(force = false)
            runCurrent()
            assertFalse(refreshes.last())
            val readsBefore = provider.reads
            // Complete the older automatic read before the new refresh emission is collected.
            completingAutomaticRead.complete(Unit)
            model.refresh()
            advanceUntilIdle()
            assertTrue(refreshes.last())
            assertEquals(readsBefore + 1, provider.reads)

            val cancelledManualRead = CompletableDeferred<Unit>()
            nextReadGate = cancelledManualRead
            model.refresh()
            runCurrent()
            assertTrue(refreshes.last())
            provider.accountRevision.value = "revision-during-manual-refresh"
            advanceUntilIdle()
            assertTrue(refreshes.last())
            val readsAfterRevision = provider.reads
            model.refresh(force = false)
            advanceUntilIdle()
            assertFalse(refreshes.last())
            assertEquals(readsAfterRevision, provider.reads)
            cancelledManualRead.complete(Unit)
        } finally { pending.complete(Unit); provider.pending?.complete(Unit); nextReadGate?.complete(Unit) }
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
    private class ProfileProvider(override val instanceId: ProviderInstanceId = ProviderInstanceId("fixture:profile")) : MusicProvider,
        AccountCapability, AccountRevisionCapability, FeatureContribution, LibraryCapability, PlaylistCapability {
        override val id = "profile"
        override val displayName = "Profile"
        override var version = "1"
        override val capabilities = setOf(SourceCapability.ACCOUNT, SourceCapability.LIBRARY, SourceCapability.PLAYLIST)
        override val collections = setOf(LibraryCollection.LIKED_TRACKS, LibraryCollection.CREATED_PLAYLISTS)
        override val accountRevision = MutableStateFlow<String?>("first-account")
        override val featureRoutes = setOf("account/home", "account/login")
        var value: SourceAccount? = SourceAccount(SourceAccountRef(instanceId, ProviderAccountId("account")), "Account")
        var failure: SourceErrorCode? = null
        var pending: CompletableDeferred<Unit>? = null
        var libraryPending: CompletableDeferred<Unit>? = null
        var libraryFailure: SourceErrorCode? = null
        val libraryRequests = mutableListOf<PageRequest>()
        val playlistRequests = mutableListOf<PageRequest>()
        var remotePlaylistVersion = 1
        private var displayedPlaylistVersion = 1
        var reads = 0
        override suspend fun account(): SourceAccount? {
            reads++
            pending?.await()
            failure?.let { throw SourceException(it) }
            return value
        }
        override suspend fun library(collection: LibraryCollection, page: PageRequest): SourcePage<SourceItem> {
            libraryRequests += page
            libraryPending?.await()
            libraryFailure?.let { throw SourceException(it) }
            val item = if (collection == LibraryCollection.LIKED_TRACKS)
                SourceItem.TrackItem(Track(TrackRef(id, "track"), "Track", emptyList()))
            else SourceItem.PlaylistItem(SourcePlaylist(PlaylistRef(id, "playlist"), "Playlist"))
            return SourcePage(listOf(item))
        }
        override suspend fun playlist(ref: PlaylistRef) = SourcePlaylist(ref, "Playlist $displayedPlaylistVersion")
        override suspend fun playlistTracks(ref: PlaylistRef, page: PageRequest): SourcePage<Track> {
            playlistRequests += page
            if (page.refresh) displayedPlaylistVersion = remotePlaylistVersion
            return SourcePage(listOf(Track(TrackRef(id, "track-$displayedPlaylistVersion"), "Track", emptyList())))
        }
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
