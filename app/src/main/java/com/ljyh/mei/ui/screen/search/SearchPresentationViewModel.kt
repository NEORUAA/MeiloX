package com.ljyh.mei.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.ProviderRegistry
import com.ljyh.mei.source.core.SourceFeatureClient
import com.ljyh.mei.source.core.SourceRouter
import com.ljyh.mei.source.shared.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class SearchRecommendation(val ref: PlaylistRef, val name: String, val artworkUrl: String?, val copywriter: String?, val creatorNickname: String?) {
    val id get() = ref.playlistId
}
data class SearchBrowseCategory(val id: String, val title: String, val kind: String = "playlist")
data class SourceSearchDiscovery(val recommendations: List<SearchRecommendation>, val categories: List<SearchBrowseCategory>)
data class SourceSearchSuggestion(val id: String, val name: String, val type: SearchType)
data class SearchDiscoveryState(val loading: Boolean = true, val discovery: SourceSearchDiscovery? = null, val error: Boolean = false)

internal fun parseSearchCategories(value: com.google.gson.JsonElement): List<SearchBrowseCategory> = value.featureList(100).map { entry -> entry.featureObject().let { row ->
        val kind = row.featureText("kind", true, 30) ?: "playlist"
        if (kind !in setOf("playlist", "podcasts")) invalidFeature()
        SearchBrowseCategory(row.featureText("id")!!, row.featureText("title", maximum = 1000)!!, kind)
    } }
internal fun parseSearchDiscovery(providerId: String, body: com.google.gson.JsonObject): SourceSearchDiscovery {
            val recommendations = body.featureList("recommendations", 100).map { entry -> entry.featureObject().let { value ->
                SearchRecommendation(PlaylistRef(providerId, value.featureText("id")!!), value.featureText("name", maximum = 1000)!!,
                    value.featureText("artworkUrl", true, 8192), value.featureText("copywriter", true, 1000), value.featureText("creatorNickname", true, 1000))
            } }
            val categories = body.get("categories")?.let(::parseSearchCategories).orEmpty()
            return SourceSearchDiscovery(recommendations, categories)
}

@Singleton
class SearchPresentationRepository @Inject constructor(private val router: SourceRouter, private val client: SourceFeatureClient) {
    private suspend fun features(providerId: String, action: String) = router.registry.requireProvider(providerId).let { provider ->
        if (action in (provider as? SourceFeatureCapability)?.features?.get("discovery").orEmpty()) client.bind(providerId, "search:$action:${UUID.randomUUID()}") else null
    }
    suspend fun categories(providerId: String): List<SearchBrowseCategory> {
        val owner = features(providerId, "searchCategories") ?: return emptyList()
        return parseSearchCategories(owner.invoke("discovery", "searchCategories"))
    }
    suspend fun discovery(providerId: String): SourceSearchDiscovery {
        val owner = features(providerId, "searchDiscovery")
        if (owner != null) {
            return parseSearchDiscovery(providerId, owner.invoke("discovery", "searchDiscovery").featureObject())
        }
        val provider = router.registry.requireProvider(providerId)
        val feeds = (provider as? FeedCapability)?.feedKinds.orEmpty()
        val kind = if (FeedKind.DISCOVER in feeds) FeedKind.DISCOVER else FeedKind.HOME.takeIf { it in feeds }
        if (kind == null) return SourceSearchDiscovery(emptyList(), emptyList())
        val page = router.getFeed(providerId, kind, page = PageRequest(limit = 12))
        return SourceSearchDiscovery(page.sections.flatMap { it.items }.filterIsInstance<SourceItem.PlaylistItem>().distinctBy { it.ref }.take(12).map {
            SearchRecommendation(it.playlist.ref, it.title, it.artworkUrl, null, it.playlist.creatorName.takeIf(String::isNotBlank))
        }, page.categories.map { SearchBrowseCategory(it.id, it.title) })
    }
    suspend fun suggestions(providerId: String, query: String): List<SourceSearchSuggestion> {
        val owner = features(providerId, "searchSuggestions")
        if (owner == null) {
            val type = router.searchTypes(providerId).firstOrNull() ?: return emptyList()
            return router.search(providerId, query, type, PageRequest(limit = 5)).items.map { item ->
                SourceSearchSuggestion(item.ref.contentId, item.title, when (item) {
                    is SourceItem.TrackItem -> SearchType.Song; is SourceItem.ArtistItem -> SearchType.Artist
                    is SourceItem.AlbumItem -> SearchType.Album; is SourceItem.PlaylistItem -> SearchType.Playlist
                })
            }
        }
        return owner.invoke("discovery", "searchSuggestions", featureParameters { addProperty("query", query) }).featureObject()
            .featureList("items", 100).mapNotNull { entry -> entry.featureObject().let { value ->
                val type = when (value.featureText("type", maximum = 30)) {
                    "track" -> SearchType.Song; "artist" -> SearchType.Artist; "album" -> SearchType.Album; "playlist" -> SearchType.Playlist; else -> null
                }
                type?.let { SourceSearchSuggestion(value.featureText("id")!!, value.featureText("name", maximum = 1000)!!, it) }
            } }
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchDiscoveryViewModel @Inject constructor(private val repository: SearchPresentationRepository, private val registry: ProviderRegistry) : ViewModel() {
    private val mutableState = MutableStateFlow(SearchDiscoveryState()); val state = mutableState.asStateFlow()
    private val mutableSuggestions = MutableStateFlow<List<SourceSearchSuggestion>>(emptyList()); val suggestions = mutableSuggestions.asStateFlow()
    private var providerId: String? = null
    private var ownerJob: Job? = null
    private var suggestionJob: Job? = null
    private var query = ""
    private var generation = 0L
    private var needsDiscovery = false
    fun open(id: String, force: Boolean = false, loadDiscovery: Boolean = true) {
        if (id.isBlank() || id == providerId && !force && (!loadDiscovery || needsDiscovery)) return
        needsDiscovery = needsDiscovery || loadDiscovery
        providerId = id; ownerJob?.cancel(); suggestionJob?.cancel(); generation++
        mutableSuggestions.value = emptyList(); mutableState.value = SearchDiscoveryState()
        ownerJob = viewModelScope.launch {
            registry.revision.flatMapLatest {
                val owner = registry.provider(id)
                ((owner as? AccountRevisionCapability)?.accountRevision ?: flowOf(null)).map { owner to it }
            }.distinctUntilChanged().collectLatest {
                generation++; suggestionJob?.cancel(); mutableSuggestions.value = emptyList()
                mutableState.value = SearchDiscoveryState()
                if (query.isNotBlank()) updateInputQuery(query, id)
                try {
                    if (needsDiscovery) {
                        val categories = repository.categories(id)
                        if (categories.isNotEmpty()) mutableState.value = SearchDiscoveryState(discovery = SourceSearchDiscovery(emptyList(), categories))
                        mutableState.value = SearchDiscoveryState(loading = false, discovery = repository.discovery(id))
                    } else mutableState.value = SearchDiscoveryState(loading = false)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { mutableState.value = mutableState.value.copy(loading = false, error = true) }
            }
        }
    }
    fun refresh() { providerId?.let { open(it, true) } }
    fun updateInputQuery(input: String, id: String) {
        if (id.isBlank()) return
        if (providerId != id) open(id, loadDiscovery = false)
        if (query == input && suggestionJob?.isActive == true) return
        query = input; suggestionJob?.cancel(); val token = ++generation; mutableSuggestions.value = emptyList()
        if (input.isBlank()) return
        suggestionJob = viewModelScope.launch {
            delay(300)
            try {
                val values = repository.suggestions(id, input)
                if (token == generation && providerId == id && query == input) mutableSuggestions.value = values
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { if (token == generation) mutableSuggestions.value = emptyList() }
        }
    }
    fun cancelSuggestions() { suggestionJob?.cancel(); suggestionJob = null; query = ""; generation++; mutableSuggestions.value = emptyList() }
}
