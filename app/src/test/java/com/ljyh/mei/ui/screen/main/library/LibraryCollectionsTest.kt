package com.ljyh.mei.ui.screen.main.library

import com.ljyh.mei.constants.LibraryStyle
import com.ljyh.mei.data.repository.LibraryAccount
import com.ljyh.mei.data.repository.LibraryCollectionPage
import com.ljyh.mei.data.repository.collectLibraryPages
import com.ljyh.mei.data.repository.collectPlaylistTracksIncrementally
import com.ljyh.mei.data.model.room.Playlist
import com.ljyh.mei.di.repository.observeOwnedPlaylists
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.IOException
import com.ljyh.mei.ui.navigation.ContentFeature
import com.ljyh.mei.ui.navigation.LibraryPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCollectionsTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun likedSongsPublishTheFirstPageBeforeWaitingForOrderedContinuation() = runTest {
        val ordered = (1L..405L).toList()
        val first = ordered.take(5)
        val continuation = CompletableDeferred<Unit>()
        val requested = mutableListOf<List<Long>>()
        val snapshots = mutableListOf<List<Long>>()
        val result = async {
            collectPlaylistTracksIncrementally(
                orderedIds = ordered + listOf(1L, 201L), initialTracks = first, key = { it },
                loadBatch = { ids ->
                    requested += ids
                    continuation.await()
                    ids.reversed() + ids.first()
                },
                onSnapshot = { snapshots += it },
            )
        }
        runCurrent()
        assertEquals(listOf(first), snapshots)
        assertFalse(result.isCompleted)
        assertEquals((6L..205L).toList(), requested.single())

        continuation.complete(Unit)
        runCurrent()
        assertEquals(ordered, result.await())
        assertEquals(listOf(5, 205, 405), snapshots.map { it.size })
        assertEquals(ordered.take(205), snapshots[1])
        assertEquals(first, snapshots.first())
        assertEquals(listOf(200, 200), requested.map { it.size })
    }

    @Test
    fun failedLikedSongContinuationRetainsEveryPublishedPage() = runTest {
        val snapshots = mutableListOf<List<Long>>()
        var requests = 0
        val result = runCatching {
            collectPlaylistTracksIncrementally(
                orderedIds = (1L..450L).toList(), initialTracks = (1L..20L).toList(), key = { it },
                loadBatch = { ids ->
                    if (++requests == 2) throw IOException("Second continuation unavailable")
                    ids
                },
                onSnapshot = { snapshots += it },
            )
        }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(listOf(20, 220), snapshots.map { it.size })
        assertEquals((1L..220L).toList(), snapshots.last())

        val empty = mutableListOf<List<Long>>()
        collectPlaylistTracksIncrementally<Long>(
            orderedIds = emptyList(), initialTracks = emptyList(), key = { it },
            loadBatch = { error("An empty playlist has no continuation") },
            onSnapshot = { empty += it },
        )
        assertEquals(listOf(emptyList<Long>()), empty)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun retryAndRefreshKeepLoadedLikedSongsWithinTheAuthoritativeOrder() = runTest {
        val loaded = (1L..220L).map { it to "retained" }
        val retryContinuation = CompletableDeferred<Unit>()
        val retrySnapshots = mutableListOf<List<Pair<Long, String>>>()
        val requested = mutableListOf<List<Long>>()
        val retry = async {
            collectPlaylistTracksIncrementally(
                orderedIds = (1L..450L).toList(),
                initialTracks = (1L..20L).map { it to "fresh" },
                retainedTracks = loaded,
                key = { it.first },
                loadBatch = { ids ->
                    requested += ids
                    retryContinuation.await()
                    ids.map { it to "fetched" }
                },
                onSnapshot = { retrySnapshots += it },
            )
        }
        runCurrent()
        assertEquals((1L..220L).toList(), retrySnapshots.single().map { it.first })
        assertEquals("fresh", retrySnapshots.single().first().second)
        assertEquals("retained", retrySnapshots.single().last().second)
        assertEquals((221L..420L).toList(), requested.single())
        retryContinuation.complete(Unit)
        runCurrent()
        val completed = retry.await()

        val refreshedIds = listOf(450L, 1L, 451L, 220L)
        val refreshSnapshots = mutableListOf<List<Pair<Long, String>>>()
        val refreshed = collectPlaylistTracksIncrementally(
            orderedIds = refreshedIds,
            initialTracks = listOf(1L to "refreshed"),
            retainedTracks = completed,
            key = { it.first },
            loadBatch = { ids ->
                assertEquals(listOf(451L), ids)
                ids.map { it to "new" }
            },
            onSnapshot = { refreshSnapshots += it },
        )
        assertEquals(listOf(450L, 1L, 220L), refreshSnapshots.first().map { it.first })
        assertEquals("refreshed", refreshSnapshots.first()[1].second)
        assertEquals(refreshedIds, refreshed.map { it.first })
        assertEquals(4, refreshed.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancelledLikedSongLoadCannotPublishAnOldContinuation() = runTest {
        val response = CompletableDeferred<Unit>()
        val snapshots = mutableListOf<List<Long>>()
        val initial = (1L..20L).toList()
        val request = launch {
            collectPlaylistTracksIncrementally(
                orderedIds = (1L..250L).toList(), initialTracks = initial, key = { it },
                loadBatch = { ids ->
                    withContext(NonCancellable) { response.await() }
                    ids
                },
                onSnapshot = { snapshots += it },
            )
        }
        runCurrent()
        assertEquals(listOf(initial), snapshots)
        request.cancel()
        response.complete(Unit)
        runCurrent()
        assertTrue(request.isCancelled)
        assertEquals(listOf(initial), snapshots)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun playerPlaylistChoicesFollowLoginSwitchAndLogoutWithoutDatabaseChanges() = runTest {
        fun playlist(id: String, owner: String) = Playlist(id, id, "", owner, "", "", 1)
        val rows = MutableStateFlow(listOf(playlist("a", "1"), playlist("b", "2")))
        val accounts = MutableStateFlow<LibraryAccount?>(null)
        var visible = emptyList<Playlist>()
        backgroundScope.launch { observeOwnedPlaylists(rows, accounts).collect { visible = it } }
        runCurrent()
        assertTrue(visible.isEmpty())
        accounts.value = LibraryAccount("1", "session-1")
        runCurrent()
        assertEquals(listOf("a"), visible.map { it.id })
        accounts.value = LibraryAccount("2", "session-2")
        runCurrent()
        assertEquals(listOf("b"), visible.map { it.id })
        rows.value += playlist("c", "2")
        runCurrent()
        assertEquals(listOf("b", "c"), visible.map { it.id })
        accounts.value = null
        runCurrent()
        assertTrue(visible.isEmpty())
    }

    @Test
    fun completePlaylistChoicesContinuePastOneHundredAndDeduplicateOverlap() = runBlocking {
        val offsets = mutableListOf<Int>()
        val choices = collectLibraryPages(key = { it }) { offset ->
            offsets += offset
            when (offset) {
                0 -> LibraryCollectionPage((1..100).map(Int::toString), 100, true)
                100 -> LibraryCollectionPage((100..140).map(Int::toString), 141, false)
                else -> error("Unexpected offset")
            }
        }
        assertEquals(listOf(0, 100), offsets)
        assertEquals((1..140).map(Int::toString), choices)
    }

    @Test
    fun incompletePlaylistChoicesFailOnEmptyRepeatedOrNonAdvancingContinuation() = runBlocking {
        listOf(
            LibraryCollectionPage(emptyList<String>(), 100, true),
            LibraryCollectionPage(listOf("one"), 200, true),
            LibraryCollectionPage(listOf("two"), 100, true),
        ).forEach { continuation ->
            val result = runCatching {
                collectLibraryPages(key = { it }) { offset ->
                    if (offset == 0) LibraryCollectionPage(listOf("one"), 100, true)
                    else continuation
                }
            }
            assertTrue(result.exceptionOrNull() is IllegalStateException)
        }
        val empty = collectLibraryPages<String>(key = { it }) {
            LibraryCollectionPage(emptyList(), 0, false)
        }
        assertTrue(empty.isEmpty())
    }

    @Test
    fun defaultPageUsesTheMergedAlbumPageOnlyForTheDefaultStyle() {
        val enabledFeatures = ContentFeature.entries.toSet()
        LibraryPage.entries.forEach { page ->
            assertEquals(
                page,
                resolveLibraryDefaultPage(page.name, LibraryStyle.AppleMusic, enabledFeatures),
            )
            assertEquals(
                if (page == LibraryPage.Podcasts) LibraryPage.Albums else page,
                resolveLibraryDefaultPage(page.name, LibraryStyle.Default, enabledFeatures),
            )
        }
    }

    @Test
    fun emptyUnknownAndDisabledDefaultPagesKeepTheStandardEntry() {
        val enabledFeatures = ContentFeature.entries.toSet()
        LibraryStyle.entries.forEach { style ->
            listOf("", "removed-page").forEach { value ->
                assertNull(resolveLibraryDefaultPage(value, style, enabledFeatures))
            }
            LibraryPage.entries.forEach { page ->
                page.requiredFeature?.let { feature ->
                    assertNull(
                        resolveLibraryDefaultPage(page.name, style, enabledFeatures - feature),
                    )
                }
            }
        }
    }

    @Test
    fun overlappingPagesKeepServerOrderAndAdvanceByRawOffset() {
        val first = LibraryCollectionState<String>().append(
            LibraryCollectionPage(listOf("album-1", "album-2"), nextOffset = 2, hasMore = true),
            key = { it },
        )
        val second = first.append(
            LibraryCollectionPage(listOf("album-2", "album-3"), nextOffset = 4, hasMore = true),
            key = { it },
        )

        assertEquals(listOf("album-1", "album-2", "album-3"), second.items)
        assertEquals(4, second.nextOffset)
        assertTrue(second.hasMore)
        assertFalse(second.isLoading)
    }

    @Test
    fun failedNextPageRetainsRowsAndCursorForRetry() {
        val first = LibraryCollectionState<String>().append(
            LibraryCollectionPage(listOf("playlist-1"), nextOffset = 100, hasMore = true),
            key = { it },
        )
        val failed = first.copy(isLoadingMore = true).failed("Temporarily unavailable")
        assertEquals(first.items, failed.items)
        assertEquals(100, failed.nextOffset)
        assertTrue(failed.hasMore)
        assertFalse(failed.isLoadingMore)
        assertFalse(failed.retryFromStart)

        val retried = failed.append(
            LibraryCollectionPage(listOf("playlist-2"), nextOffset = 101, hasMore = false),
            key = { it },
        )
        assertEquals(listOf("playlist-1", "playlist-2"), retried.items)
        assertNull(retried.error)
        assertFalse(retried.hasMore)
    }

    @Test
    fun failedRefreshRetriesFromStartEvenWhenTheLastPageWasAlreadyLoaded() {
        val existing = LibraryCollectionState(
            items = listOf("album-1", "album-2"), nextOffset = 2, hasMore = false,
        )
        val failed = existing.failed("Temporarily unavailable", retryFromStart = true)
        assertEquals(existing.items, failed.items)
        assertTrue(failed.retryFromStart)
        assertFalse(
            failed.append(LibraryCollectionPage(emptyList(), 2, false), key = { it }).retryFromStart,
        )

        val refreshed = LibraryCollectionState<String>().append(
            LibraryCollectionPage(listOf("album-2"), nextOffset = 1, hasMore = false),
            key = { it },
        )
        assertEquals(listOf("album-2"), refreshed.items)
        assertFalse(refreshed.retryFromStart)
        assertNull(refreshed.error)
    }

    @Test
    fun nonAdvancingPageStopsPaginationEvenWhenServerClaimsMore() {
        val state = LibraryCollectionState(items = listOf("album-1"), nextOffset = 50, hasMore = true)
        val result = state.append(
            LibraryCollectionPage(emptyList(), nextOffset = 50, hasMore = true),
            key = { it },
        )
        assertEquals(state.items, result.items)
        assertFalse(result.hasMore)
    }

    @Test
    fun accountIdentityIncludesSessionWithoutExposingCredential() {
        val account = LibraryAccount("123", "private-session-a")
        assertEquals(account, LibraryAccount("123", "private-session-a"))
        assertNotEquals(account, LibraryAccount("123", "private-session-b"))
        assertNotEquals(account, LibraryAccount("456", "private-session-a"))
        assertFalse(account.toString().contains("private-session"))
    }
}
