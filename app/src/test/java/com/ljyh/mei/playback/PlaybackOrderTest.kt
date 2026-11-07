package com.ljyh.mei.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.ShuffleOrder
import com.google.gson.Gson
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.playback.queue.PlaylistQueueSource
import com.ljyh.mei.playback.queue.SourceContentQueue
import com.ljyh.mei.playback.queue.Queue
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.ProviderRegistry
import com.ljyh.mei.source.core.SourceRouter
import com.ljyh.mei.ui.screen.source.SourceContentState
import com.ljyh.mei.ui.screen.source.validateSourceContentPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.lang.reflect.Proxy
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackOrderTest {
    @Test
    fun queueArtworkRepairKeepsTheExistingTrackMetadata() {
        val existing = MediaMetadata(1, "Queue title", "", listOf(MediaMetadata.Artist(2, "Artist")),
            120_000, MediaMetadata.Album(3, "Queue album"), trackRef = TrackRef("qq", "song"), tns = "Alias")
        val refreshed = existing.copy(title = "Catalog title", coverUrl = "file:///cache/new-cover", duration = 180_000,
            album = MediaMetadata.Album(4, "Catalog album"), tns = null)
        assertEquals(existing.copy(coverUrl = refreshed.coverUrl), repairQueueArtworkMetadata(existing, refreshed))
        assertSame(existing, repairQueueArtworkMetadata(existing, refreshed.copy(coverUrl = "")))
        assertSame(existing, repairQueueArtworkMetadata(existing, refreshed.copy(trackRef = TrackRef("qq", "other"))))
        val local = existing.copy(isLocal = true)
        val podcast = existing.copy(isPodcast = true)
        assertSame(local, repairQueueArtworkMetadata(local, refreshed))
        assertSame(podcast, repairQueueArtworkMetadata(podcast, refreshed))
        assertSame(refreshed, repairQueueArtworkMetadata(refreshed, refreshed))
    }

    @Test
    fun nextAutomaticEntryFollowsShuffleAndRepeatModes() {
        val timeline = OrderedTimeline(listOf(2, 0, 3, 1))
        assertEquals(3, playerFor(timeline, shuffle = true, currentIndex = 0).nextPlaybackMediaItemIndex())
        assertEquals(1, playerFor(timeline, shuffle = false, currentIndex = 0).nextPlaybackMediaItemIndex())
        assertEquals(C.INDEX_UNSET, playerFor(timeline, true, currentIndex = 1).nextPlaybackMediaItemIndex())
        assertEquals(2, playerFor(timeline, true, 1, Player.REPEAT_MODE_ALL).nextPlaybackMediaItemIndex())
        assertEquals(1, playerFor(timeline, true, 1, Player.REPEAT_MODE_ONE).nextPlaybackMediaItemIndex())
        assertEquals(C.INDEX_UNSET, playerFor(OrderedTimeline(emptyList()), true).nextPlaybackMediaItemIndex())
    }

    @Test
    fun visibleListSelectionCorrectsAStaleIndexAndKeepsItsPlaylistSource() {
        val source = PlaylistQueueSource(42L, "visible-list")
        val queue = ListQueue(
            id = "visible", title = "Visible playlist", items = listOf("10" to null, "20" to null),
            position = 5_000, playlistSource = source,
        )
        val selected = requireNotNull(queue.startingAt("20"))
        assertEquals("visible", selected.id)
        assertEquals("Visible playlist", selected.title)
        assertEquals(1, selected.startIndex)
        assertEquals(0, selected.position)
        assertEquals(2, selected.totalCount)
        assertEquals(source, selected.playlistSource)
    }

    @Test
    fun visibleListSelectionKeepsTheClickedDuplicateAndItsRequestedPosition() {
        val queue = ListQueue(
            id = "duplicates", items = listOf("10" to null, "20" to null, "10" to null),
            startIndex = 2, position = 9_000,
        )
        assertSame(queue, queue.startingAt("10"))
        assertNull(queue.startingAt("missing"))
        assertNull(ListQueue("empty", items = emptyList()).startingAt("10"))
    }

    @Test
    fun timelineTraversalUsesShuffleOrderAndIgnoresRepeatMode() {
        val timeline = OrderedTimeline(listOf(2, 0, 3, 1))
        val player = playerFor(timeline, shuffle = true)
        assertEquals(listOf(2, 0, 3, 1), player.playbackOrderIndices())
        assertEquals(listOf(0, 1, 2, 3), player.playbackOrderIndices(false))
        assertTrue(timeline.repeatModes.all { it == Player.REPEAT_MODE_OFF })
    }

    @Test
    fun storedShuffleOrderCanBeReadWhileShuffleIsDisabled() {
        val player = playerFor(OrderedTimeline(listOf(1, 2, 0)), shuffle = false)
        assertEquals(listOf(0, 1, 2), player.playbackOrderIndices())
        assertEquals(listOf(1, 2, 0), player.playbackOrderIndices(true))
    }

    @Test
    fun emptyTimelineDoesNotReadAnyWindowOrCurrentItem() {
        assertEquals(emptyList<Int>(), playerFor(OrderedTimeline(emptyList()), true).playbackOrderIndices())
    }

    @Test
    fun emptyAndSingletonPermutationsAreValid() {
        assertTrue(emptyList<Int>().isPlaybackPermutation(0))
        assertTrue(listOf(0).isPlaybackPermutation(1))
    }

    @Test
    fun missingExtraDuplicateAndOutOfRangeIndicesAreRejected() {
        listOf(listOf(0, 1), listOf(0, 1, 2, 3), listOf(0, 0, 2),
            listOf(-1, 1, 2), listOf(0, 1, 3)).forEach {
            assertFalse("Invalid permutation: $it", it.isPlaybackPermutation(3))
        }
    }

    @Test
    fun nullIndicesFromCorruptJsonAreRejected() {
        assertFalse(listOf(0, null, 2).isPlaybackPermutation(3))
    }

    @Test
    fun arbitraryPermutationsRetainExactMedia3ForwardAndReverseOrder() {
        repeat(200) { seed ->
            val size = seed % 50
            val order = (0 until size).shuffled(Random(seed))
            assertTrue(order.isPlaybackPermutation(size))
            val shuffle = ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), seed.toLong())
            assertEquals(order, shuffle.forward())
            val reverse = mutableListOf<Int>()
            var index = shuffle.lastIndex
            while (index != C.INDEX_UNSET) {
                reverse.add(index)
                index = shuffle.getPreviousIndex(index)
            }
            assertEquals(order.reversed(), reverse)
        }
    }

    @Test
    fun duplicateSongIdsStillHaveDistinctQueuePositions() {
        val songIds = listOf("10", "20", "10", "30")
        val order = listOf(2, 1, 0, 3)
        assertTrue(order.isPlaybackPermutation(songIds.size))
        assertEquals(listOf("10", "20", "10", "30"), order.map(songIds::get))
        assertEquals(order, ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), 1L).forward())
    }

    @Test
    fun removingAnEntryPreservesSurvivingPlaybackOrder() {
        val original = listOf(3, 1, 4, 0, 2)
        val shuffle = ShuffleOrder.DefaultShuffleOrder(original.toIntArray(), 7L)
        val remaining = shuffle.cloneAndRemove(1, 2).forward()
        assertEquals(listOf(2, 3, 0, 1), remaining)
        assertTrue(remaining.isPlaybackPermutation(4))
    }

    @Test
    fun sourceQueueStartsTheVisiblePrefixAndPreservesDuplicateOrigins() = runBlocking {
        val provider = ContentProvider()
        provider.load = { _, page ->
            assertEquals(PageRequest(3, 30, "page-2"), page)
            SourcePage(listOf(track("four"), track("five")))
        }
        val queue = sourceQueue(provider, listOf(track("same"), track("other"), track("same")),
            startIndex = 2, nextCursor = "page-2")
        val initial = queue.getInitialStatus()
        assertEquals(2, initial.mediaItemIndex)
        assertEquals(listOf("same", "other", "same"), initial.ids.map { TrackRefCodec.decode(it.first)?.trackId })
        assertEquals(0, provider.calls.size)
        assertEquals(PlaylistRef(provider.id, "playlist"), queue.contentRef)
        assertNull(queue.playlistSource)

        val result = queue.nextPage() as Queue.Result.Success<List<String>>
        assertEquals(listOf("four", "five"), result.data.map { TrackRefCodec.decode(it)?.trackId })
        assertEquals(5, queue.loadedCount)
        assertFalse(queue.hasNextPage())
        assertEquals(1, provider.calls.size)
    }

    @Test
    fun sourceQueueRoutesAlbumArtistAndLikedTracksWithAnExplicitProvider() = runBlocking {
        val provider = ContentProvider()
        val router = SourceRouter(ProviderRegistry(listOf(provider)))
        val album = SourceContentQueue(router, AlbumRef(provider.id, "album"), "Album", mediaItemFactory = { null })
        val artist = SourceContentQueue(router, ArtistRef(provider.id, "artist"), "Artist", mediaItemFactory = { null })
        val library = SourceContentQueue.forLibrary(router, provider.id, title = "Liked", mediaItemFactory = { null })
        listOf(album, artist, library).forEach {
            assertEquals(1, it.getInitialStatus().ids.size)
            assertFalse(it.hasNextPage())
        }
        assertEquals(listOf("album:album", "artist:artist", "library:LIKED_TRACKS"), provider.calls.map { it.first })
        assertEquals(LibraryCollection.LIKED_TRACKS, library.libraryCollection)
        assertEquals(provider.id, library.providerId)
    }

    @Test
    fun sourceQueueKeepsRepeatedRecordingsAcrossPagesWhenTheCursorAdvances() = runBlocking {
        val provider = ContentProvider().apply { load = { _, _ ->
            SourcePage(listOf(track("same"), track("same")), true, "page-3")
        } }
        val queue = sourceQueue(provider, listOf(track("same"), track("same")), nextCursor = "page-2")
        assertTrue(queue.nextPage() is Queue.Result.Success)
        assertEquals(4, queue.loadedCount)
        assertTrue(queue.hasNextPage())
        assertEquals(listOf("same", "same", "same", "same"), queue.getInitialStatus().ids.map {
            TrackRefCodec.decode(it.first)?.trackId
        })
    }

    @Test
    fun sourceQueueSharesOneBoundaryRequestAndCommitsOnePage() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val provider = ContentProvider().apply { load = { _, _ ->
            started.complete(Unit)
            response.await()
            SourcePage(listOf(track("next")))
        } }
        val queue = sourceQueue(provider, listOf(track("first")))
        val first = async(start = CoroutineStart.UNDISPATCHED) { queue.nextPage() }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { queue.nextPage() }
        response.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, provider.calls.size)
        assertEquals(2, queue.loadedCount)
    }

    @Test
    fun sourceQueueReleaseRejectsEvenANonCancellableLatePage() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val provider = ContentProvider().apply { load = { _, _ ->
            withContext(NonCancellable) {
                started.complete(Unit)
                response.await()
            }
            SourcePage(listOf(track("late")))
        } }
        val queue = sourceQueue(provider, listOf(track("first")))
        val pending = async(start = CoroutineStart.UNDISPATCHED) { queue.nextPage() }
        started.await()
        queue.release()
        response.complete(Unit)
        try { pending.await() } catch (_: CancellationException) { }
        assertFalse(queue.hasNextPage())
        assertEquals(1, queue.loadedCount)
        assertEquals("first", TrackRefCodec.decode(queue.getInitialStatus().ids.single().first)?.trackId)
    }

    @Test
    fun sourceQueueRejectsAccountAndPluginChangesWithoutRemovingQueuedAudio() = runBlocking {
        for (replacePlugin in listOf(false, true)) {
            val started = CompletableDeferred<Unit>()
            val response = CompletableDeferred<Unit>()
            val provider = ContentProvider().apply { load = { _, _ ->
                started.complete(Unit)
                response.await()
                SourcePage(listOf(track("foreign-session")), hasMore = true)
            } }
            val registry = ProviderRegistry(listOf(provider))
            val queue = SourceContentQueue(SourceRouter(registry), PlaylistRef(provider.id, "playlist"), null,
                initialTracks = listOf(track("first")), initialAccountRevision = "account-a", mediaItemFactory = { null })
            val pending = async(start = CoroutineStart.UNDISPATCHED) { queue.nextPage() }
            started.await()
            if (replacePlugin) registry.register(ContentProvider()) else provider.accountRevision.value = "account-b"
            response.complete(Unit)
            assertTrue(pending.await() is Queue.Result.Error)
            assertFalse(queue.hasNextPage())
            assertEquals(1, queue.loadedCount)
        }
    }

    @Test
    fun sourceQueueRejectsAStaleVisibleAccountBeforeFetchingTheNextPage() = runBlocking {
        val provider = ContentProvider().apply { accountRevision.value = "account-b" }
        val queue = SourceContentQueue(SourceRouter(ProviderRegistry(listOf(provider))),
            PlaylistRef(provider.id, "playlist"), null, initialTracks = listOf(track("first")),
            initialAccountRevision = "account-a", mediaItemFactory = { null })
        assertEquals(1, queue.getInitialStatus().ids.size)
        assertFalse(queue.hasNextPage())
        assertEquals(0, provider.calls.size)
    }

    @Test
    fun sourceQueueStopsEmptyOrRepeatedPagesAndRetriesTransientErrorsAtTheSameOffset() = runBlocking {
        val invalidPages = listOf(
            SourcePage<Track>(emptyList(), hasMore = true, nextCursor = "next"),
            SourcePage(listOf(track("next")), hasMore = true, nextCursor = "current"),
            SourcePage(listOf(track("first")), hasMore = true),
        )
        invalidPages.forEach { invalid ->
            val provider = ContentProvider().apply { load = { _, _ -> invalid } }
            val queue = sourceQueue(provider, listOf(track("first")), nextCursor = "current")
            assertTrue(queue.nextPage() is Queue.Result.Error)
            assertFalse(queue.hasNextPage())
            assertEquals(1, queue.loadedCount)
        }
        var attempts = 0
        val provider = ContentProvider().apply { load = { _, _ ->
            if (attempts++ == 0) throw SourceException(SourceErrorCode.NETWORK)
            SourcePage(listOf(track("next")))
        } }
        val queue = sourceQueue(provider, listOf(track("first")), nextCursor = "retry")
        assertTrue(queue.nextPage() is Queue.Result.Error)
        assertTrue(queue.hasNextPage())
        assertTrue(queue.nextPage() is Queue.Result.Success)
        assertEquals(listOf(PageRequest(1, 30, "retry"), PageRequest(1, 30, "retry")), provider.calls.map { it.second })
        assertEquals(2, queue.loadedCount)
    }

    @Test
    fun restoredSourceAndLibraryQueuesReplayThePrefixToRecoverPrivateCursors() = runBlocking {
        for (library in listOf(false, true)) {
            val initial = (0 until 4).map { track(it.toString()) }
            val provider = ContentProvider()
            val registry = ProviderRegistry(listOf(provider))
            val router = SourceRouter(registry)
            val queue = if (library) SourceContentQueue.forLibrary(router, provider.id, initialTracks = initial,
                pageSize = 2, nextCursor = "private-session-4", mediaItemFactory = { null })
            else SourceContentQueue(router, PlaylistRef(provider.id, "playlist"), null, initial,
                pageSize = 2, nextCursor = "private-session-4", mediaItemFactory = { null })
            queue.getInitialStatus()
            val saved = requireNotNull(queue.snapshotContext(initial.map { it.ref }))
            val encoded = Gson().toJson(saved)
            assertFalse(encoded.contains("private-session"))
            assertFalse(encoded.contains("account-a"))
            assertFalse(encoded.contains("cursor", ignoreCase = true))
            assertEquals(64, saved.accountFingerprint.length)
            queue.release()

            val restoredProvider = ContentProvider().apply { load = { _, page ->
                val end = page.offset + page.limit
                SourcePage((page.offset until end).map { track(it.toString()) }, end < 6,
                    if (end < 6) "private-session-$end" else null)
            } }
            registry.register(restoredProvider)
            val restored = requireNotNull(SourceContentQueue.restore(router, saved, "Restored", initial, mediaItemFactory = { null }))
            assertEquals(4, restored.getInitialStatus().ids.size)
            assertTrue(restoredProvider.calls.isEmpty())
            assertTrue(restored.nextPage() is Queue.Result.Success)
            assertEquals(listOf(PageRequest(0, 2), PageRequest(2, 2, "private-session-2"),
                PageRequest(4, 2, "private-session-4")), restoredProvider.calls.map { it.second })
            assertEquals(6, restored.loadedCount)
            assertFalse(restored.hasNextPage())
            assertEquals(if (library) LibraryCollection.LIKED_TRACKS else null, restored.libraryCollection)
        }
    }

    @Test
    fun restoredSourceQueueRejectsChangedPrefixesAccountsAndIncompleteLocators() = runBlocking {
        val initial = listOf(track("one"), track("two"))
        val provider = ContentProvider()
        val registry = ProviderRegistry(listOf(provider))
        val router = SourceRouter(registry)
        val queue = SourceContentQueue(router, PlaylistRef(provider.id, "playlist"), null, initial,
            pageSize = 2, nextCursor = "private-session", mediaItemFactory = { null })
        queue.getInitialStatus()
        val saved = requireNotNull(queue.snapshotContext(initial.map { it.ref }))
        listOf(saved.copy(accountFingerprint = ""), saved.copy(providerVersion = "changed"),
            saved.copy(instanceId = ""), saved.copy(nextOffset = Int.MAX_VALUE)).forEach {
            assertNull(SourceContentQueue.restore(router, it, null, initial, mediaItemFactory = { null }))
        }
        for (changeAccount in listOf(false, true)) {
            val replacement = ContentProvider().apply {
                if (changeAccount) accountRevision.value = "account-b"
                load = { _, _ -> SourcePage(initial.reversed(), true, "changed-prefix") }
            }
            registry.register(replacement)
            val restored = requireNotNull(SourceContentQueue.restore(router, saved, null, initial, mediaItemFactory = { null }))
            assertTrue(restored.nextPage() is Queue.Result.Error)
            assertFalse(restored.hasNextPage())
            assertEquals(initial.map { it.ref }, restored.getInitialStatus().ids.map { TrackRefCodec.decode(it.first) })
            assertEquals(if (changeAccount) 0 else 1, replacement.calls.size)
        }
    }

    @Test
    fun sourceQueueSnapshotUsesOnlyThePrefixAlreadyAppendedToThePlayer() = runBlocking {
        val initial = listOf(track("one"), track("two"))
        val provider = ContentProvider().apply { load = { _, _ ->
            SourcePage(listOf(track("three"), track("four")), true, "private-next")
        } }
        val queue = sourceQueue(provider, initial, nextCursor = "private-current")
        queue.getInitialStatus()
        queue.nextPage()
        val snapshot = requireNotNull(queue.snapshotContext(initial.map { it.ref }))
        assertEquals(2, snapshot.loadedCount)
        assertEquals(2, snapshot.nextOffset)
        assertTrue(snapshot.hasMore)
        assertNull(queue.snapshotContext(initial.reversed().map { it.ref }))
    }

    @Test
    fun contentPagesKeepRepeatedRecordingsWhenTheCursorOrKnownTotalAdvances() {
        val track = track("repeated")
        val incoming = listOf<ContentRef>(track.ref, track.ref)
        val items = List(4) { SourceItem.TrackItem(track) }
        val page = PageRequest(2, 2, "current")
        val cursorPage = SourceContentState(items = items, hasMore = true, nextCursor = "next")
        val progressed = validateSourceContentPage(cursorPage, page, incoming, 2, true,
            setOf(incoming), setOf("current"))
        assertEquals(items, progressed.items)
        assertTrue(progressed.hasMore)

        for (album in listOf(false, true)) {
            fun detail(total: Int): SourceItem = if (album) SourceItem.AlbumItem(
                SourceAlbumInfo(AlbumRef("queue.source", "album"), "Album", trackCount = total))
            else SourceItem.PlaylistItem(SourcePlaylist(PlaylistRef("queue.source", "playlist"), "Playlist", trackCount = total))
            val offsetPage = cursorPage.copy(nextCursor = null, detail = detail(6))
            assertTrue(validateSourceContentPage(offsetPage, PageRequest(2, 2), incoming, 2, true,
                setOf(incoming), emptySet()).hasMore)
            val completed = validateSourceContentPage(offsetPage.copy(detail = detail(4)), PageRequest(2, 2),
                incoming, 2, true, setOf(incoming), emptySet())
            assertFalse(completed.hasMore)
            assertEquals(items, completed.items)
        }
    }

    @Test
    fun contentPagesStopUnknownOffsetRepeatsEmptyPagesAndCursorCycles() {
        val track = track("repeated")
        val incoming = listOf<ContentRef>(track.ref, track.ref)
        val state = SourceContentState(items = List(4) { SourceItem.TrackItem(track) }, hasMore = true)
        val page = PageRequest(2, 2, "current")
        listOf(state, state.copy(nextCursor = "current"), state.copy(nextCursor = "earlier"),
            state.copy(nextCursor = "current", detail = SourceItem.PlaylistItem(
                SourcePlaylist(PlaylistRef("queue.source", "playlist"), "Playlist", trackCount = 6)))).forEach { result ->
            try {
                validateSourceContentPage(result, page, incoming, 2, true, setOf(incoming), setOf("earlier", "current"))
                org.junit.Assert.fail("Expected non-progressing page rejection")
            } catch (error: SourceException) { assertEquals(SourceErrorCode.INVALID_RESPONSE, error.code) }
        }
        try {
            validateSourceContentPage(state.copy(nextCursor = "next"), page, emptyList(), 0, true, emptySet(), emptySet())
            org.junit.Assert.fail("Expected empty continuation rejection")
        } catch (error: SourceException) { assertEquals(SourceErrorCode.INVALID_RESPONSE, error.code) }
        val completed = state.copy(hasMore = false)
        assertSame(completed, validateSourceContentPage(completed, page, incoming, 2, true, setOf(incoming), emptySet()))
    }

    private fun sourceQueue(
        provider: ContentProvider,
        tracks: List<Track>,
        startIndex: Int = 0,
        nextCursor: String? = null,
    ) = SourceContentQueue(SourceRouter(ProviderRegistry(listOf(provider))), PlaylistRef(provider.id, "playlist"),
        "Content", tracks, startIndex = startIndex, nextCursor = nextCursor, mediaItemFactory = { null })

    private fun track(id: String) = Track(TrackRef("queue.source", id), id, listOf(SourceArtist("Artist")))

    private inner class ContentProvider : MusicProvider, PlaylistCapability, AlbumCapability,
        ArtistCapability, LibraryCapability, AccountRevisionCapability {
        override val id = "queue.source"
        override val displayName = "Queue source"
        override val capabilities = setOf(SourceCapability.PLAYLIST, SourceCapability.ALBUM,
            SourceCapability.ARTIST, SourceCapability.LIBRARY)
        override val accountRevision = MutableStateFlow("account-a")
        override val collections = setOf(LibraryCollection.LIKED_TRACKS)
        val calls = mutableListOf<Pair<String, PageRequest>>()
        var load: suspend (String, PageRequest) -> SourcePage<Track> = { _, _ -> SourcePage(listOf(track("next"))) }
        private suspend fun page(route: String, page: PageRequest): SourcePage<Track> {
            calls.add(route to page)
            return load(route, page)
        }
        override suspend fun playlist(ref: PlaylistRef) = SourcePlaylist(ref, "Playlist")
        override suspend fun playlistTracks(ref: PlaylistRef, page: PageRequest) = page("playlist:${ref.playlistId}", page)
        override suspend fun album(ref: AlbumRef) = SourceAlbumInfo(ref, "Album")
        override suspend fun albumTracks(ref: AlbumRef, page: PageRequest) = page("album:${ref.albumId}", page)
        override suspend fun artist(ref: ArtistRef) = SourceArtistInfo(ref, "Artist")
        override suspend fun artistTracks(ref: ArtistRef, page: PageRequest) = page("artist:${ref.artistId}", page)
        override suspend fun artistAlbums(ref: ArtistRef, page: PageRequest) = SourcePage<SourceAlbumInfo>(emptyList())
        override suspend fun library(collection: LibraryCollection, page: PageRequest): SourcePage<SourceItem> =
            page("library:${collection.name}", page).let { result ->
                SourcePage(result.items.map { SourceItem.TrackItem(it) }, result.hasMore, result.nextCursor)
            }
    }

    private fun ShuffleOrder.forward(): List<Int> {
        val result = mutableListOf<Int>()
        var index = firstIndex
        while (index != C.INDEX_UNSET) {
            check(result.size < length) { "Shuffle order contains a cycle" }
            result.add(index)
            index = getNextIndex(index)
        }
        return result
    }

    private fun playerFor(
        timeline: Timeline,
        shuffle: Boolean,
        currentIndex: Int = 0,
        repeatMode: Int = Player.REPEAT_MODE_OFF,
    ): Player = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getCurrentTimeline" -> timeline
            "getShuffleModeEnabled" -> shuffle
            "getCurrentMediaItemIndex" -> currentIndex
            "getRepeatMode" -> repeatMode
            else -> error("Unexpected player access: ${method.name}")
        }
    } as Player

    private class OrderedTimeline(private val order: List<Int>) : Timeline() {
        val repeatModes = mutableListOf<Int>()

        override fun getWindowCount() = order.size
        override fun getPeriodCount() = order.size
        override fun getFirstWindowIndex(shuffleModeEnabled: Boolean): Int =
            if (shuffleModeEnabled) order.firstOrNull() ?: C.INDEX_UNSET
            else if (order.isEmpty()) C.INDEX_UNSET else 0

        override fun getNextWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int {
            repeatModes.add(repeatMode)
            if (repeatMode == Player.REPEAT_MODE_ONE) return windowIndex
            val indices = if (shuffleModeEnabled) order else order.indices.toList()
            return indices.getOrNull(indices.indexOf(windowIndex) + 1)
                ?: if (repeatMode == Player.REPEAT_MODE_ALL) indices.firstOrNull() ?: C.INDEX_UNSET
                else C.INDEX_UNSET
        }

        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
            error("Traversal must not allocate or read windows")

        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
            error("Traversal must not read periods")

        override fun getIndexOfPeriod(uid: Any) = C.INDEX_UNSET
        override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex
    }
}
