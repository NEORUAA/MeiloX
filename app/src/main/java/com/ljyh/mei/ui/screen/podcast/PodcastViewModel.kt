package com.ljyh.mei.ui.screen.podcast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.model.melox.Podcast
import com.ljyh.mei.data.model.melox.PodcastDetail
import com.ljyh.mei.data.model.melox.PodcastHome
import com.ljyh.mei.data.repository.MeloXRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class PodcastTab {
    Discover,
    Subscriptions,
}

data class PodcastUiState(
    val isLoading: Boolean = true,
    val home: PodcastHome? = null,
    val selectedCategoryId: Long? = null,
    val categoryPodcasts: List<Podcast> = emptyList(),
    val error: String? = null,
    val selectedTab: PodcastTab = PodcastTab.Discover,
    val subscribedPodcasts: List<Podcast> = emptyList(),
    val subscriptionsLoaded: Boolean = false,
    val isSubscriptionsLoading: Boolean = false,
    val isLoadingMoreSubscriptions: Boolean = false,
    val hasMoreSubscriptions: Boolean = false,
    val subscriptionTotalCount: Int = 0,
    val subscriptionsError: String? = null,
    val subscriptionsLoadMoreError: String? = null,
)

data class PodcastDetailUiState(
    val isLoading: Boolean = true,
    val detail: PodcastDetail? = null,
    val error: String? = null,
    val isLoadingMore: Boolean = false,
    val loadMoreError: String? = null,
    val isUpdatingSubscription: Boolean = false,
)

@HiltViewModel
class PodcastViewModel @Inject constructor(
    private val repository: MeloXRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PodcastUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    fun selectTab(tab: PodcastTab) {
        if (_state.value.selectedTab == tab) return
        _state.value = _state.value.copy(selectedTab = tab)
        if (tab == PodcastTab.Discover && _state.value.home == null) refreshDiscover()
    }

    fun refresh() {
        if (_state.value.selectedTab == PodcastTab.Subscriptions) {
            loadSubscriptions(reset = true)
        } else {
            refreshDiscover()
        }
    }

    private fun refreshDiscover() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            runCatching { repository.podcastHome() }
                .onSuccess {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        home = it,
                        selectedCategoryId = null,
                        categoryPodcasts = emptyList(),
                        error = null,
                    )
                }
                .onFailure { _state.value = _state.value.copy(isLoading = false, error = it.message) }
        }
    }

    fun selectCategory(id: Long?) {
        if (id == null) {
            _state.value = _state.value.copy(
                isLoading = false,
                selectedCategoryId = null,
                categoryPodcasts = emptyList(),
                error = null,
            )
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, selectedCategoryId = id, error = null)
            runCatching { repository.podcasts(id) }
                .onSuccess { podcasts ->
                    if (_state.value.selectedCategoryId == id) {
                        _state.value = _state.value.copy(isLoading = false, categoryPodcasts = podcasts)
                    }
                }
                .onFailure { error ->
                    if (_state.value.selectedCategoryId == id) {
                        _state.value = _state.value.copy(isLoading = false, error = error.message)
                    }
                }
        }
    }

    fun ensureSubscriptionsLoaded() {
        val state = _state.value
        if (!state.subscriptionsLoaded && !state.isSubscriptionsLoading) {
            loadSubscriptions(reset = true)
        }
    }

    fun loadMoreSubscriptions() {
        val state = _state.value
        if (!state.subscriptionsLoaded || !state.hasMoreSubscriptions ||
            state.isSubscriptionsLoading || state.isLoadingMoreSubscriptions
        ) return
        loadSubscriptions(reset = false)
    }

    private fun loadSubscriptions(reset: Boolean) {
        val current = _state.value
        if (current.isSubscriptionsLoading || current.isLoadingMoreSubscriptions) return
        viewModelScope.launch {
            _state.value = if (reset) {
                _state.value.copy(
                    isSubscriptionsLoading = true,
                    subscriptionsError = null,
                    subscriptionsLoadMoreError = null,
                )
            } else {
                _state.value.copy(
                    isLoadingMoreSubscriptions = true,
                    subscriptionsLoadMoreError = null,
                )
            }
            val offset = if (reset) 0 else _state.value.subscribedPodcasts.size
            runCatching { repository.subscribedPodcasts(offset = offset) }
                .onSuccess { page ->
                    val existing = if (reset) emptyList() else _state.value.subscribedPodcasts
                    val merged = appendUniquePodcasts(existing, page.podcasts)
                    _state.value = _state.value.copy(
                        subscribedPodcasts = merged,
                        subscriptionsLoaded = true,
                        isSubscriptionsLoading = false,
                        isLoadingMoreSubscriptions = false,
                        hasMoreSubscriptions = page.hasMore && merged.size > existing.size,
                        subscriptionTotalCount = maxOf(page.totalCount, merged.size),
                        subscriptionsError = null,
                        subscriptionsLoadMoreError = null,
                    )
                }
                .onFailure { error ->
                    _state.value = if (reset) {
                        _state.value.copy(
                            subscriptionsLoaded = _state.value.subscribedPodcasts.isNotEmpty(),
                            isSubscriptionsLoading = false,
                            subscriptionsError = error.message,
                        )
                    } else {
                        _state.value.copy(
                            isLoadingMoreSubscriptions = false,
                            subscriptionsLoadMoreError = error.message,
                        )
                    }
                }
        }
    }
}

@HiltViewModel
class PodcastDetailViewModel @Inject constructor(
    private val repository: MeloXRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PodcastDetailUiState())
    val state = _state.asStateFlow()
    private var loadedId: Long? = null

    fun load(id: Long, force: Boolean = false) {
        if (!force && loadedId == id && _state.value.detail != null) return
        val changesPodcast = loadedId != id
        loadedId = id
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isLoading = true,
                detail = if (changesPodcast) null else _state.value.detail,
                error = null,
                isLoadingMore = false,
                loadMoreError = null,
            )
            runCatching { repository.podcastDetail(id) }
                .onSuccess {
                    if (loadedId == id) {
                        _state.value = PodcastDetailUiState(isLoading = false, detail = it)
                    }
                }
                .onFailure { error ->
                    if (loadedId == id) {
                        _state.value = _state.value.copy(isLoading = false, error = error.message)
                    }
                }
        }
    }

    fun loadMore() {
        val current = _state.value
        val detail = current.detail ?: return
        if (!detail.hasMore || current.isLoading || current.isLoadingMore) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoadingMore = true, loadMoreError = null)
            runCatching {
                repository.podcastPrograms(
                    id = detail.podcast.id,
                    offset = detail.programs.size,
                )
            }.onSuccess { page ->
                if (loadedId != detail.podcast.id) return@onSuccess
                val latest = _state.value.detail ?: return@onSuccess
                val programs = appendUniquePrograms(latest.programs, page.programs)
                _state.value = _state.value.copy(
                    detail = latest.copy(
                        programs = programs,
                        hasMore = page.hasMore && programs.size > latest.programs.size,
                        totalCount = maxOf(latest.totalCount, page.totalCount, programs.size),
                    ),
                    isLoadingMore = false,
                    loadMoreError = null,
                )
            }.onFailure { error ->
                if (loadedId == detail.podcast.id) {
                    _state.value = _state.value.copy(
                        isLoadingMore = false,
                        loadMoreError = error.message,
                    )
                }
            }
        }
    }

    fun toggleSubscription() {
        val detail = _state.value.detail ?: return
        if (_state.value.isUpdatingSubscription) return
        viewModelScope.launch {
            val target = !detail.podcast.isSubscribed
            _state.value = _state.value.copy(
                detail = detail.copy(podcast = detail.podcast.copy(isSubscribed = target)),
                isUpdatingSubscription = true,
                error = null,
            )
            runCatching { repository.setPodcastSubscribed(detail.podcast.id, target) }
                .onSuccess {
                    if (loadedId == detail.podcast.id) {
                        _state.value = _state.value.copy(isUpdatingSubscription = false)
                    }
                }
                .onFailure { error ->
                    if (loadedId != detail.podcast.id) return@onFailure
                    val latest = _state.value.detail
                    _state.value = _state.value.copy(
                        detail = latest?.copy(podcast = latest.podcast.copy(isSubscribed = !target)),
                        isUpdatingSubscription = false,
                        error = error.message,
                    )
                }
        }
    }
}

internal fun appendUniquePodcasts(existing: List<Podcast>, incoming: List<Podcast>): List<Podcast> {
    val ids = existing.mapTo(mutableSetOf(), Podcast::id)
    return existing + incoming.filter { ids.add(it.id) }
}

internal fun appendUniquePrograms(
    existing: List<com.ljyh.mei.data.model.melox.PodcastProgram>,
    incoming: List<com.ljyh.mei.data.model.melox.PodcastProgram>,
): List<com.ljyh.mei.data.model.melox.PodcastProgram> {
    val ids = existing.mapTo(mutableSetOf(), com.ljyh.mei.data.model.melox.PodcastProgram::id)
    return existing + incoming.filter { ids.add(it.id) }
}
