package com.ljyh.mei.ui.screen.search

import androidx.lifecycle.ViewModelStore
import com.google.gson.Gson
import com.ljyh.mei.data.model.api.SearchResult
import com.ljyh.mei.data.model.api.SearchSuggest
import com.ljyh.mei.data.model.melox.SearchDiscovery
import com.ljyh.mei.data.model.melox.SearchDiscoveryPlaylist
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.SearchSource
import com.ljyh.mei.parasite.HostSessionBridge
import com.ljyh.mei.parasite.HostSessionIdentity
import com.ljyh.mei.parasite.HostSessionStamp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchSessionTest {
    private var identity = HostSessionIdentity(1, true, false)
    private val sessions = HostSessionBridge().apply { bind { identity } }
    private val source = Source()
    private var discovery: suspend (HostSessionStamp) -> SearchDiscovery = { discovery(it.identity.userId) }

    private data class Request(val session: HostSessionStamp, val query: String, val type: Int, val offset: Int)
    private class Source : SearchSource {
        val requests = mutableListOf<Request>()
        val suggestions = mutableListOf<Pair<HostSessionStamp, String>>()
        var search: suspend (Request) -> Resource<SearchResult> = { Resource.Success(page(listOf(1), it.type)) }
        var suggest: suspend (String) -> Resource<SearchSuggest> = { Resource.Success(suggestion(it)) }
        override suspend fun search(session: HostSessionStamp, keyword: String, type: Int, limit: Int, offset: Int): Resource<SearchResult> {
            assertEquals(30, limit)
            val request = Request(session, keyword, type, offset)
            requests += request
            return search(request)
        }
        override suspend fun searchSuggest(session: HostSessionStamp, keyword: String): Resource<SearchSuggest> {
            suggestions += session to keyword
            return suggest(keyword)
        }
    }

    private fun checkModels(check: suspend TestScope.(SearchViewModel, SearchDiscoveryViewModel, ViewModelStore) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val search = SearchViewModel(source, sessions)
            val landing = SearchDiscoveryViewModel(sessions) { discovery(it) }
            store.put("search", search)
            store.put("landing", landing)
            runCurrent()
            check(search, landing, store)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun guestSearchesAllFiveTypesThroughItsOfficialSession() {
        identity = HostSessionIdentity(0, false, true)
        checkModels { search, landing, _ ->
            search.onSearchInit("music", 1)
            runCurrent()
            SearchType.entries.filter { it != SearchType.History }.forEach {
                search.onTabChange(it)
                runCurrent()
                assertEquals(1, (search.state.value.result as Resource.Success).data.itemCount(it))
            }
            assertEquals(listOf(1, 100, 10, 1000, 1009), source.requests.map { it.type })
            assertTrue(source.requests.all { it.session.identity.anonymous })
            assertFalse(landing.state.value.loading)
        }
    }

    @Test fun lateQueryCannotReplaceTheNewQueryOrPopulateItsCache() {
        val old = CompletableDeferred<SearchResult>()
        source.search = { request -> Resource.Success(
            if (request.query == "old") withContext(NonCancellable) { old.await() } else page(listOf(2), request.type)
        ) }
        checkModels { search, _, _ ->
            try {
                search.onSearchInit("old", 1)
                runCurrent()
                search.onSearchInit("new", 1)
                runCurrent()
            } finally {
                old.complete(page(listOf(1)))
                runCurrent()
            }
            assertEquals("new", search.state.value.query)
            assertEquals(listOf(2L), search.songIds())
            search.onTabChange(SearchType.Artist)
            runCurrent()
            search.onTabChange(SearchType.Song)
            runCurrent()
            assertEquals(listOf(2L), search.songIds())
            assertEquals(3, source.requests.size)
        }
    }

    @Test fun oldTabResultCannotOverwriteAnUncachedReselection() {
        val old = CompletableDeferred<SearchResult>()
        var calls = 0
        source.search = { request -> Resource.Success(
            if (calls++ == 0) withContext(NonCancellable) { old.await() } else page(listOf(2), request.type)
        ) }
        checkModels { search, _, _ ->
            try {
                search.onSearchInit("music", 1)
                runCurrent()
                search.onTabChange(SearchType.Artist)
                runCurrent()
                search.onTabChange(SearchType.Song)
                runCurrent()
            } finally {
                old.complete(page(listOf(1)))
                runCurrent()
            }
            assertEquals(SearchType.Song, search.state.value.type)
            assertEquals(listOf(2L), search.songIds())
        }
    }

    @Test fun paginationUsesRawOffsetsDeduplicatesAndIgnoresConcurrentLoads() {
        source.search = { Resource.Success(when (it.offset) {
            0 -> page(listOf(1, 1, 2), total = 6)
            3 -> page(listOf(2, 3), total = 6)
            else -> page(listOf(4), total = 6)
        }) }
        checkModels { search, _, _ ->
            search.onSearchInit("music", 1)
            runCurrent()
            search.loadMore()
            search.loadMore()
            runCurrent()
            search.loadMore()
            runCurrent()
            search.loadMore()
            runCurrent()
            assertEquals(listOf(0, 3, 5), source.requests.map { it.offset })
            assertEquals(listOf(1L, 2L, 3L, 4L), search.songIds())
            assertEquals(6, search.state.value.nextOffset)
            assertFalse(search.state.value.hasMore)
        }
    }

    @Test fun appendFailureKeepsRowsAndCursorUntilExplicitRetry() {
        source.search = { if (it.offset == 0) Resource.Success(page(listOf(1), total = 2)) else Resource.Error("Offline") }
        checkModels { search, _, _ ->
            search.onSearchInit("music", 1)
            runCurrent()
            search.loadMore()
            runCurrent()
            assertEquals(listOf(1L), search.songIds())
            assertEquals("Offline", search.state.value.loadMoreError)
            assertEquals(1, search.state.value.nextOffset)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(2, source.requests.size)
            source.search = { Resource.Success(page(listOf(2), total = 2)) }
            search.loadMore()
            runCurrent()
            assertEquals(listOf(0, 1, 1), source.requests.map { it.offset })
            assertEquals(listOf(1L, 2L), search.songIds())
            assertNull(search.state.value.loadMoreError)
        }
    }

    @Test fun allResultTypesAppendWithoutLosingOrderAndRestoreTheirPagedCache() {
        source.search = { Resource.Success(page(if (it.offset == 0) listOf(1, 1) else listOf(2, 3), it.type, total = 4)) }
        checkModels { search, _, _ ->
            search.onSearchInit("music", 1)
            runCurrent()
            val types = SearchType.entries.filter { it != SearchType.History }
            types.forEach {
                search.onTabChange(it)
                runCurrent()
                search.loadMore()
                runCurrent()
                assertEquals(3, (search.state.value.result as Resource.Success).data.itemCount(it))
                assertEquals(4, search.state.value.nextOffset)
                assertFalse(search.state.value.hasMore)
            }
            val count = source.requests.size
            types.reversed().forEach {
                search.onTabChange(it)
                runCurrent()
                assertEquals(4, search.state.value.nextOffset)
            }
            assertEquals(count, source.requests.size)
            assertEquals(listOf(1L, 2L, 3L), search.songIds())
        }
    }

    @Test fun absentCountsAndOptionalTrpSupportPagingUntilAnEmptyTerminalPage() {
        source.search = {
            val response = page(if (it.offset == 0) (1L..30L).toList() else emptyList())
            Resource.Success(response.copy(result = response.result.copy(songCount = null)))
        }
        checkModels { search, _, _ ->
            search.onSearchInit("music", 1)
            runCurrent()
            assertTrue(search.state.value.hasMore)
            assertNull((search.state.value.result as Resource.Success).data.trp)
            search.loadMore()
            runCurrent()
            assertEquals((1L..30L).toList(), search.songIds())
            assertEquals(listOf(0, 30), source.requests.map { it.offset })
            assertFalse(search.state.value.hasMore)
        }
    }

    @Test fun nonAdvancingPagesAreErrorsInsteadOfInfinitePagingOrFalseCompletion() {
        source.search = { Resource.Success(page(listOf(1), total = 3)) }
        checkModels { search, _, _ ->
            search.onSearchInit("music", 1)
            runCurrent()
            search.loadMore()
            runCurrent()
            assertEquals("Search pagination did not advance", search.state.value.loadMoreError)
            assertEquals(1, search.state.value.nextOffset)
            assertTrue(search.state.value.hasMore)
        }
    }

    @Test fun sameAccountReauthorizationClearsCacheAndDiscoverySynchronously() {
        var loads = 0L
        source.search = { Resource.Success(page(listOf(++loads), it.type)) }
        checkModels { search, landing, _ ->
            search.onSearchInit("music", 1)
            runCurrent()
            val before = search.state.value.session
            sessions.invalidate()
            assertNull(search.state.value.session)
            assertEquals(Resource.Loading, search.state.value.result)
            assertNull(landing.state.value.discovery)
            runCurrent()
            assertNotEquals(before, search.state.value.session)
            assertEquals(listOf(2L), search.songIds())
            assertNotNull(landing.state.value.discovery)
        }
    }

    @Test fun accountChangeRejectsLateSearchAndPersonalizedDiscovery() {
        val old = CompletableDeferred<SearchResult>()
        val oldDiscovery = CompletableDeferred<SearchDiscovery>()
        source.search = { Resource.Success(if (it.session.identity.userId == 1L)
            withContext(NonCancellable) { old.await() } else page(listOf(2))) }
        discovery = { if (it.identity.userId == 1L) withContext(NonCancellable) { oldDiscovery.await() } else discovery(2) }
        checkModels { search, landing, _ ->
            try {
                search.onSearchInit("music", 1)
                runCurrent()
                sessions.beginTransition().use { identity = HostSessionIdentity(2, true, false) }
                runCurrent()
                assertEquals(listOf(2L), search.songIds())
                assertEquals(2L, landing.state.value.discovery!!.recommendations.single().id)
            } finally {
                old.complete(page(listOf(1)))
                oldDiscovery.complete(discovery(1))
                runCurrent()
            }
            assertEquals(listOf(2L), search.songIds())
            assertEquals(2L, landing.state.value.discovery!!.recommendations.single().id)
        }
    }

    @Test fun suggestionsDebounceAndClearImmediatelyOnBlankInput() {
        checkModels { search, _, _ ->
            search.updateInputQuery("m")
            advanceTimeBy(100)
            search.updateInputQuery("music")
            advanceTimeBy(299)
            runCurrent()
            assertTrue(source.suggestions.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf("music"), source.suggestions.map { it.second })
            assertTrue(search.searchSuggest.value is Resource.Success)
            search.updateInputQuery("")
            assertEquals(Resource.Loading, search.searchSuggest.value)
        }
    }

    @Test fun lateSuggestionCannotReturnAfterCancellationOrAccountChange() {
        val old = CompletableDeferred<SearchSuggest>()
        source.suggest = { if (it == "old") withContext(NonCancellable) { Resource.Success(old.await()) }
            else Resource.Success(suggestion(it)) }
        checkModels { search, _, _ ->
            try {
                search.updateInputQuery("old")
                advanceTimeBy(300)
                runCurrent()
                search.updateInputQuery("new")
                advanceTimeBy(300)
                runCurrent()
                sessions.invalidate()
                assertEquals(Resource.Loading, search.searchSuggest.value)
                runCurrent()
                advanceTimeBy(300)
                runCurrent()
            } finally {
                old.complete(suggestion("old"))
                runCurrent()
            }
            val result = search.searchSuggest.value as Resource.Success
            assertEquals("new", result.data.result.songs!!.single().name)
            assertEquals(sessions.snapshot(), source.suggestions.last().first)
        }
    }

    @Test fun businessFailureIsNotAnEmptySuccessfulSearch() {
        source.search = { Resource.Success(page(emptyList()).copy(code = 301)) }
        checkModels { search, _, _ ->
            search.onSearchInit("music", 1)
            runCurrent()
            assertEquals(Resource.Error("Search request failed (301)"), search.state.value.result)
            source.search = { Resource.Success(page(listOf(1))) }
            search.onSearchInit("music", 1)
            runCurrent()
            assertEquals(listOf(1L), search.songIds())
        }
    }

    @Test fun failedRecoveryStopsLoadingAndResumesOnlyAfterRecovery() {
        checkModels { search, landing, _ ->
            search.onSearchInit("music", 1)
            runCurrent()
            val transition = sessions.beginTransition()
            try {
                sessions.setRecoveryRequired(true)
                runCurrent()
                assertTrue(search.state.value.result is Resource.Error)
                assertTrue(landing.state.value.error)
                assertFalse(landing.state.value.loading)
            } finally {
                sessions.setRecoveryRequired(false)
                transition.close()
                runCurrent()
            }
            assertEquals(listOf(1L), search.songIds())
            assertFalse(landing.state.value.error)
        }
    }

    @Test fun discoveryFailurePreservesOnlyCurrentSessionContentAndCanRetry() {
        checkModels { _, landing, _ ->
            discovery = { error("Offline") }
            landing.refresh()
            runCurrent()
            assertTrue(landing.state.value.error)
            assertEquals(1L, landing.state.value.discovery!!.recommendations.single().id)
            sessions.beginTransition().use { identity = HostSessionIdentity(2, true, false) }
            runCurrent()
            assertNull(landing.state.value.discovery)
            discovery = { discovery(2) }
            landing.refresh()
            runCurrent()
            assertEquals(2L, landing.state.value.discovery!!.recommendations.single().id)
            assertFalse(landing.state.value.error)
        }
    }

    @Test fun clearingModelsPreventsLateResults() {
        val old = CompletableDeferred<SearchResult>()
        source.search = { withContext(NonCancellable) { Resource.Success(old.await()) } }
        checkModels { search, _, store ->
            search.onSearchInit("music", 1)
            runCurrent()
            store.clear()
            val before = search.state.value
            old.complete(page(listOf(1)))
            runCurrent()
            assertEquals(before, search.state.value)
        }
    }

    private companion object {
        fun SearchViewModel.songIds() = (state.value.result as Resource.Success).data.result.songs.orEmpty().map { it.id }
        fun page(ids: List<Long>, type: Int = 1, total: Int = ids.size): SearchResult {
            val (field, count) = when (type) {
                100 -> "artists" to "artistCount"
                10 -> "albums" to "albumCount"
                1000 -> "playlists" to "playlistCount"
                1009 -> "djRadios" to "djRadiosCount"
                else -> "songs" to "songCount"
            }
            val rows = ids.joinToString(",") { "{\"id\":$it}" }
            return Gson().fromJson("""{"code":200,"result":{"$field":[$rows],"$count":$total}}""", SearchResult::class.java)
        }
        fun suggestion(name: String): SearchSuggest = Gson().fromJson(
            """{"code":200,"result":{"songs":[{"id":1,"name":"$name"}]}}""", SearchSuggest::class.java,
        )
        fun discovery(id: Long) = SearchDiscovery(listOf(SearchDiscoveryPlaylist(id, "Playlist", null, null, null)))
    }
}
