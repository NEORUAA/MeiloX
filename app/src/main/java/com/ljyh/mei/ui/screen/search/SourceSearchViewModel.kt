package com.ljyh.mei.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.source.api.TypedSearchCapability
import com.ljyh.mei.source.api.LibraryCapability
import com.ljyh.mei.source.api.FeedCapability
import com.ljyh.mei.source.api.ContentType
import com.ljyh.mei.source.api.SourceItem
import com.ljyh.mei.source.api.CatalogCapability
import com.ljyh.mei.source.api.MusicProvider
import com.ljyh.mei.source.api.PageRequest
import com.ljyh.mei.source.api.ProviderIds
import com.ljyh.mei.source.api.SourceCapability
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.api.SourceStatus
import com.ljyh.mei.source.api.Track
import com.ljyh.mei.source.core.ProviderRegistry
import com.ljyh.mei.source.core.SourcePreferences
import com.ljyh.mei.source.core.SourceRouter
import com.ljyh.mei.source.core.SourceSettings
import com.ljyh.mei.ui.screen.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class SourceSearchState(
    val query: String = "",
    val tracks: List<Track> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val error: SourceErrorCode? = null,
    val items: List<SourceItem> = emptyList(),
    val nextCursor: String? = null,
)

internal fun searchableProviders(providers: List<MusicProvider>): List<MusicProvider> = providers.filter {
    it.status == SourceStatus.READY && SourceCapability.SEARCH in it.capabilities &&
        (it is CatalogCapability || it is TypedSearchCapability && it.searchTypes.isNotEmpty())
}

internal fun browsableProviders(providers: List<MusicProvider>): List<MusicProvider> = providers.filter {
    it.status == SourceStatus.READY && (
        SourceCapability.FEED in it.capabilities && it is FeedCapability && it.feedKinds.isNotEmpty() ||
        SourceCapability.LIBRARY in it.capabilities && it is LibraryCapability && it.collections.isNotEmpty() ||
        it in searchableProviders(listOf(it)))
}

internal fun libraryProviders(providers: List<MusicProvider>): List<MusicProvider> = providers.filter {
    it.status == SourceStatus.READY && SourceCapability.LIBRARY in it.capabilities &&
        it is LibraryCapability && it.collections.isNotEmpty()
}

internal fun supportedSourceSearchType(requested: Int, supported: Set<SearchType>): SearchType? =
    SearchType.entries.firstOrNull { it.type == requested && it in supported }
        ?: SearchType.entries.firstOrNull { it in supported }


@HiltViewModel
class SourceSearchViewModel @Inject constructor(
    private val router: SourceRouter,
    private val preferences: SourcePreferences,
    registry: ProviderRegistry,
) : ViewModel() {
    val providers = registry.providers
    val sourceChoices = combine(providers, registry.revision) { providers, _ -> searchableProviders(providers) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val browseChoices = combine(providers, registry.revision) { providers, _ -> browsableProviders(providers) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val libraryChoices = combine(providers, registry.revision) { providers, _ -> libraryProviders(providers) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings = preferences.settings.map<SourceSettings, SourceSettings?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val browseMode = MutableStateFlow(false)
    val selectedProviderId = combine(settings, browseMode) { settings, browsing ->
        if (browsing) settings?.preferredBrowseProvider.orEmpty() else settings?.preferredSearchProvider.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    private val mutableState = MutableStateFlow(SourceSearchState())
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    private var generation = 0L
    private var offset = 0
    private var contentType = ContentType.TRACK

    init {
        viewModelScope.launch {
            combine(selectedProviderId, providers, registry.revision) { id, providers, revision -> Triple(id, providers, revision) }
                .distinctUntilChanged().collect { search(mutableState.value.query, force = true, type = contentType) }
        }
    }

    fun useBrowsePreference() { browseMode.value = true }

    fun supportsFeatureRoute(providerId: String, route: String): Boolean =
        router.registry.supportsFeatureRoute(providerId, route)

    fun supportedSearchTypes(providerId: String): Set<SearchType> {
        val provider = router.registry.provider(providerId) ?: return emptySet()
        val types = router.searchTypes(providerId).mapNotNull {
            when (it) {
                ContentType.TRACK -> SearchType.Song
                ContentType.ALBUM -> SearchType.Album
                ContentType.ARTIST -> SearchType.Artist
                ContentType.PLAYLIST -> SearchType.Playlist
            }
        }.toSet()
        return types + if (SourceCapability.PODCAST in provider.capabilities && supportsFeatureRoute(providerId, Screen.PodcastDetail.route))
            setOf(SearchType.Podcast) else emptySet()
    }

    fun selectProvider(providerId: String) {
        request?.cancel()
        generation++
        viewModelScope.launch {
            preferences.update {
                if (browseMode.value) it.copy(preferredBrowseProvider = providerId)
                else it.copy(preferredSearchProvider = providerId)
            }
        }
    }

    fun search(query: String, force: Boolean = false, type: ContentType = ContentType.TRACK) {
        val query = query.trim()
        if (!force && contentType == type && mutableState.value.query == query && (mutableState.value.loading || mutableState.value.tracks.isNotEmpty())) return
        request?.cancel()
        generation++
        offset = 0
        contentType = type
        mutableState.value = SourceSearchState(query = query)
        if (query.isNotEmpty() && currentProviderId().isNotEmpty()) loadPage(append = false)
    }

    fun loadMore() {
        if (mutableState.value.hasMore && !mutableState.value.loading) loadPage(append = true)
    }

    private fun loadPage(append: Boolean) {
        val query = mutableState.value.query
        val providerId = currentProviderId()
        val requestGeneration = generation
        val oldItems = if (append) mutableState.value.items else emptyList()
        val oldTracks = if (append) mutableState.value.tracks else emptyList()
        val nextCursor = if (append) mutableState.value.nextCursor else null
        val requestedType = contentType
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        request = viewModelScope.launch {
            try {
                val page = router.search(providerId, query, requestedType, PageRequest(offset, 30, nextCursor))
                if (requestGeneration != generation || providerId != currentProviderId()) return@launch
                offset += page.items.size
                val items = (oldItems + page.items).distinctBy { it.ref }
                mutableState.value = SourceSearchState(query,
                    tracks = items.filterIsInstance<SourceItem.TrackItem>().map { it.track }, items = items,
                    nextCursor = page.nextCursor, hasMore = page.hasMore && page.items.isNotEmpty() &&
                        (!append || items.size > oldItems.size || page.nextCursor != null && page.nextCursor != nextCursor))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (requestGeneration != generation || providerId != currentProviderId()) return@launch
                mutableState.value = SourceSearchState(query, tracks = oldTracks, items = oldItems, nextCursor = nextCursor,
                    error = (error as? SourceException)?.code ?: SourceErrorCode.NETWORK)
            }
        }
    }

    private fun currentProviderId(): String = settings.value?.let {
        if (browseMode.value) it.preferredBrowseProvider else it.preferredSearchProvider
    }.orEmpty()
}
