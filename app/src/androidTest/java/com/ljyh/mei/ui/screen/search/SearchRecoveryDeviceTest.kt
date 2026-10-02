package com.ljyh.mei.ui.screen.search

import androidx.lifecycle.ViewModelStore
import com.google.gson.Gson
import com.ljyh.mei.data.model.api.SearchResult
import com.ljyh.mei.data.model.api.SearchSuggest
import com.ljyh.mei.data.model.melox.SearchDiscovery
import com.ljyh.mei.data.model.melox.SearchDiscoveryPlaylist
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.SearchSource
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
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

/** Real Android ViewModels with synthetic sessions; no credentials, sockets or accounts. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchRecoveryDeviceTest {
    private val sessions = SessionStore().apply { bind { SessionIdentity(0, false, false) } }
    private var resultRequests = 0
    private var suggestionRequests = 0
    private var discoveryRequests = 0
    private var result: suspend () -> SearchResult = { page() }
    private var suggestions: suspend () -> SearchSuggest = { suggestion() }
    private var discovery: suspend () -> SearchDiscovery = { discoveryPage() }
    private val source = object : SearchSource {
        override suspend fun search(session: SessionStamp, keyword: String, type: Int, limit: Int, offset: Int): Resource<SearchResult> {
            sessions.requireCurrent(session)
            resultRequests++
            return Resource.Success(result())
        }

        override suspend fun searchSuggest(session: SessionStamp, keyword: String): Resource<SearchSuggest> {
            sessions.requireCurrent(session)
            suggestionRequests++
            return Resource.Success(suggestions())
        }
    }

    private fun withModels(check: suspend TestScope.(SearchViewModel, SearchDiscoveryViewModel) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val search = SearchViewModel(source, sessions)
            val landing = SearchDiscoveryViewModel(sessions) { discoveryRequests++; discovery() }
            store.put("search", search)
            store.put("landing", landing)
            runCurrent()
            check(search, landing)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun pendingCookieRecoveryBlocksRequestsAndResumesWithoutAnAccountGenerationChange() {
        sessions.setRecoveryRequired(true)
        withModels { search, landing ->
            val owner = sessions.snapshot()
            search.onSearchInit("fixture", 1)
            search.updateInputQuery("fixture")
            landing.refresh()
            advanceTimeBy(301)
            runCurrent()
            assertEquals(listOf(0, 0, 0), listOf(resultRequests, suggestionRequests, discoveryRequests))
            assertNull(search.state.value.session)
            assertTrue(search.state.value.result is Resource.Error)
            assertTrue(search.searchSuggest.value is Resource.Error)
            assertTrue(landing.state.value.error)
            assertFalse(landing.state.value.loading)

            sessions.setRecoveryRequired(false)
            runCurrent()
            advanceTimeBy(301)
            runCurrent()
            assertEquals(owner, sessions.snapshot())
            assertEquals(listOf(1, 1, 1), listOf(resultRequests, suggestionRequests, discoveryRequests))
            assertTrue(search.state.value.result is Resource.Success)
            assertTrue(search.searchSuggest.value is Resource.Success)
            assertFalse(landing.state.value.error)
        }
    }

    @Test fun recoveryDiscardsNonCooperativeResponsesOnTheActualAndroidViewModelLifecycle() {
        val pendingResult = CompletableDeferred<SearchResult>()
        val pendingSuggestion = CompletableDeferred<SearchSuggest>()
        val pendingDiscovery = CompletableDeferred<SearchDiscovery>()
        withModels { search, landing ->
            result = { withContext(NonCancellable) { pendingResult.await() } }
            suggestions = { withContext(NonCancellable) { pendingSuggestion.await() } }
            discovery = { withContext(NonCancellable) { pendingDiscovery.await() } }
            try {
                search.onSearchInit("fixture", 1)
                search.updateInputQuery("fixture")
                landing.refresh()
                advanceTimeBy(301)
                runCurrent()
                sessions.setRecoveryRequired(true)
                runCurrent()
            } finally {
                pendingResult.complete(page())
                pendingSuggestion.complete(suggestion())
                pendingDiscovery.complete(discoveryPage())
                runCurrent()
            }
            assertTrue(search.state.value.result is Resource.Error)
            assertTrue(search.searchSuggest.value is Resource.Error)
            assertNull(landing.state.value.discovery)
            assertTrue(landing.state.value.error)
            assertFalse(landing.state.value.loading)
        }
    }

    private fun page(): SearchResult = Gson().fromJson(
        """{"code":200,"result":{"songs":[{"id":1}],"songCount":1}}""", SearchResult::class.java,
    )

    private fun suggestion(): SearchSuggest = Gson().fromJson(
        """{"code":200,"result":{"songs":[]}}""", SearchSuggest::class.java,
    )

    private fun discoveryPage() = SearchDiscovery(listOf(SearchDiscoveryPlaylist(1, "Fixture", null, null, null)))
}
