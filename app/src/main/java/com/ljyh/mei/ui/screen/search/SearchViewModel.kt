package com.ljyh.mei.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.model.api.SearchResult
import com.ljyh.mei.data.model.api.SearchSuggest
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.SearchRepository
import com.ljyh.mei.data.repository.SearchSource
import com.ljyh.mei.parasite.HostSessionBridge
import com.ljyh.mei.parasite.HostSessionChangedException
import com.ljyh.mei.parasite.HostSessionStamp
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class SearchResultsState(
    val session: HostSessionStamp? = null,
    val query: String = "",
    val type: SearchType = SearchType.Song,
    val result: Resource<SearchResult> = Resource.Loading,
    val nextOffset: Int = 0,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreError: String? = null,
)

class SearchViewModel internal constructor(
    private val repository: SearchSource,
    private val sessions: HostSessionBridge,
) : ViewModel() {
    @Inject constructor(repository: SearchRepository, sessions: HostSessionBridge) : this(repository as SearchSource, sessions)

    private val mutableState = MutableStateFlow(SearchResultsState())
    val state = mutableState.asStateFlow()
    private val mutableSuggestions = MutableStateFlow<Resource<SearchSuggest>>(Resource.Loading)
    val searchSuggest = mutableSuggestions.asStateFlow()
    private val stateLock = Any()
    private val resultCache = mutableMapOf<SearchType, SearchResultsState>()
    private var inputQuery = ""
    private var resultVersion = 0L
    private var suggestionVersion = 0L
    private var resultJob: Job? = null
    private var suggestionJob: Job? = null
    private val invalidation = sessions.onInvalidated { revision ->
        synchronized(stateLock) {
            if ((state.value.session?.generation ?: -1) < revision) resetSession(null)
        }
    }

    init {
        viewModelScope.launch {
            combine(sessions.changes, sessions.recoveryRequired) { _, _ -> Unit }.collect {
                val stamp = runCatching { sessions.snapshot() }.getOrNull()
                if (stamp == null) {
                    synchronized(stateLock) { resetSession(null) }
                    resultJob?.cancel()
                    suggestionJob?.cancel()
                } else if (state.value.session != stamp) {
                    val ready = runCatching {
                        sessions.withCurrent(stamp) { synchronized(stateLock) { resetSession(stamp) } }
                    }.isSuccess
                    if (ready) {
                        fetchResults()
                        fetchSuggestions()
                    }
                }
            }
        }
    }

    private fun resetSession(stamp: HostSessionStamp?) {
        resultVersion++
        suggestionVersion++
        resultCache.clear()
        val pending = if (sessions.recoveryRequired.value) Resource.Error("Official session recovery is required") else Resource.Loading
        mutableState.value = SearchResultsState(session = stamp, query = state.value.query, type = state.value.type, result = pending)
        mutableSuggestions.value = pending
    }

    fun updateInputQuery(query: String) {
        synchronized(stateLock) {
            if (inputQuery == query) return
            inputQuery = query
        }
        fetchSuggestions()
    }

    fun onSearchInit(query: String, type: Int) {
        val keyword = query.trim()
        synchronized(stateLock) {
            if (state.value.query == keyword && state.value.result !is Resource.Error) return
            resultCache.clear()
            mutableState.value = SearchResultsState(
                session = state.value.session, query = keyword,
                type = SearchType.entries.firstOrNull { it.type == type && it != SearchType.History } ?: SearchType.Song,
            )
        }
        fetchResults()
    }

    fun onTabChange(type: SearchType) {
        if (type == SearchType.History) return
        synchronized(stateLock) {
            if (state.value.type == type) return
            mutableState.value = SearchResultsState(session = state.value.session, query = state.value.query, type = type)
        }
        fetchResults()
    }

    fun loadMore() {
        val current = state.value
        if (!current.hasMore || current.loadingMore || current.result !is Resource.Success) return
        fetchResults(append = true)
    }

    private fun fetchResults(append: Boolean = false) {
        resultJob?.cancel()
        val current = state.value
        val stamp = current.session ?: return
        val version = runCatching {
            sessions.withCurrent(stamp) {
                synchronized(stateLock) {
                    resultVersion++
                    if (!append) {
                        mutableState.value = resultCache[current.type] ?: current.copy(result = Resource.Loading)
                    } else {
                        mutableState.value = current.copy(loadingMore = true, loadMoreError = null)
                    }
                    resultVersion
                }
            }
        }.getOrNull() ?: return
        if (current.query.isBlank() || (!append && state.value.result is Resource.Success)) return
        val offset = if (append) current.nextOffset else 0
        resultJob = viewModelScope.launch {
            try {
                sessions.requireCurrent(stamp)
                val response = repository.search(stamp, current.query, current.type.type, PAGE_SIZE, offset)
                currentCoroutineContext().ensureActive()
                when (response) {
                    is Resource.Success -> {
                        check(response.data.code == 200) { "Search request failed (${response.data.code})" }
                        val previous = if (append) (current.result as Resource.Success).data else null
                        val result = mergeSearchPage(previous, response.data, current.type)
                        val rawCount = response.data.itemCount(current.type)
                        val hasMore = response.data.hasMore(current.type, offset, PAGE_SIZE)
                        check(!hasMore || (rawCount > 0 && result.itemCount(current.type) > (previous?.itemCount(current.type) ?: 0))) {
                            "Search pagination did not advance"
                        }
                        publish(stamp, version) {
                            val loaded = current.copy(result = Resource.Success(result), nextOffset = offset + rawCount,
                                hasMore = hasMore, loadingMore = false, loadMoreError = null)
                            resultCache[current.type] = loaded
                            loaded
                        }
                    }
                    is Resource.Error -> error(response.message)
                    Resource.Loading -> error("Search request did not complete")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: HostSessionChangedException) {
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                runCatching {
                    publish(stamp, version) {
                        val message = error.message ?: "Search request failed"
                        if (append) it.copy(loadingMore = false, loadMoreError = message)
                        else it.copy(result = Resource.Error(message), loadingMore = false)
                    }
                }
            }
        }
    }

    private fun publish(stamp: HostSessionStamp, version: Long, update: (SearchResultsState) -> SearchResultsState) {
        sessions.withCurrent(stamp) {
            synchronized(stateLock) {
                if (resultVersion == version) mutableState.value = update(state.value)
            }
        }
    }

    private fun fetchSuggestions() {
        suggestionJob?.cancel()
        val (query, version) = synchronized(stateLock) {
            mutableSuggestions.value = Resource.Loading
            inputQuery to ++suggestionVersion
        }
        val stamp = state.value.session ?: return
        if (query.isBlank()) return
        suggestionJob = viewModelScope.launch {
            try {
                delay(300)
                sessions.requireCurrent(stamp)
                val response = repository.searchSuggest(stamp, query)
                currentCoroutineContext().ensureActive()
                val checked = if (response is Resource.Success && response.data.code != 200) {
                    Resource.Error("Search suggestions failed (${response.data.code})")
                } else response
                publishSuggestions(stamp, version, checked)
            } catch (error: CancellationException) {
                throw error
            } catch (_: HostSessionChangedException) {
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                runCatching { publishSuggestions(stamp, version, Resource.Error(error.message ?: "Search suggestions failed")) }
            }
        }
    }

    private fun publishSuggestions(stamp: HostSessionStamp, version: Long, result: Resource<SearchSuggest>) {
        sessions.withCurrent(stamp) {
            synchronized(stateLock) {
                if (suggestionVersion == version) mutableSuggestions.value = result
            }
        }
    }

    override fun onCleared() {
        invalidation.close()
        super.onCleared()
    }

    private companion object { const val PAGE_SIZE = 30 }
}

internal fun SearchResult.itemCount(type: SearchType): Int = when (type) {
    SearchType.Song -> result.songs.orEmpty().size
    SearchType.Artist -> result.artists.orEmpty().size
    SearchType.Album -> result.albums.orEmpty().size
    SearchType.Playlist -> result.playlists.orEmpty().size
    SearchType.Podcast -> result.podcasts.orEmpty().size
    SearchType.History -> 0
}

private fun SearchResult.hasMore(type: SearchType, offset: Int, limit: Int): Boolean {
    val count = when (type) {
        SearchType.Song -> result.songCount
        SearchType.Artist -> result.artistCount
        SearchType.Album -> result.albumCount
        SearchType.Playlist -> result.playlistCount
        SearchType.Podcast -> result.podcastCount
        SearchType.History -> 0
    }
    return result.hasMore ?: count?.let { offset + itemCount(type) < it } ?: (itemCount(type) >= limit)
}

internal fun mergeSearchPage(previous: SearchResult?, page: SearchResult, type: SearchType): SearchResult = page.copy(
    result = when (type) {
        SearchType.Song -> page.result.copy(songs = (previous?.result?.songs.orEmpty() + page.result.songs.orEmpty()).distinctBy { it.id })
        SearchType.Artist -> page.result.copy(artists = (previous?.result?.artists.orEmpty() + page.result.artists.orEmpty()).distinctBy { it.id })
        SearchType.Album -> page.result.copy(albums = (previous?.result?.albums.orEmpty() + page.result.albums.orEmpty()).distinctBy { it.id })
        SearchType.Playlist -> page.result.copy(playlists = (previous?.result?.playlists.orEmpty() + page.result.playlists.orEmpty()).distinctBy { it.id })
        SearchType.Podcast -> page.result.copy(podcasts = (previous?.result?.podcasts.orEmpty() + page.result.podcasts.orEmpty()).distinctBy { it.id })
        SearchType.History -> page.result
    },
)
