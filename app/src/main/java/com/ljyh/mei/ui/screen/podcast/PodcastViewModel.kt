package com.ljyh.mei.ui.screen.podcast

import com.ljyh.mei.data.model.toSourceTrack
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.source.shared.OnlinePodcast as Podcast
import com.ljyh.mei.source.shared.OnlinePodcastDetail as PodcastDetail
import com.ljyh.mei.source.shared.OnlinePodcastHome as PodcastHome
import com.ljyh.mei.source.shared.OnlinePodcastPage as PodcastPage
import com.ljyh.mei.source.shared.OnlinePodcastProgramPage as PodcastProgramPage
import com.ljyh.mei.source.shared.SharedOnlineRepository
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
    val isSignedIn: Boolean = false,
    val isLoading: Boolean = true,
    val home: PodcastHome? = null,
    val selectedCategoryId: String? = null,
    val categoryPodcasts: List<Podcast> = emptyList(),
    val error: String? = null,
    val selectedTab: PodcastTab = PodcastTab.Discover,
    val subscribedPodcasts: List<Podcast> = emptyList(),
    val subscriptionsLoaded: Boolean = false,
    val isSubscriptionsLoading: Boolean = false,
    val isLoadingMoreSubscriptions: Boolean = false,
    val hasMoreSubscriptions: Boolean = false,
    val subscriptionTotalCount: Int = 0,
    val subscriptionOffset: Int = 0,
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
class PodcastViewModel internal constructor(
    private val repository: com.ljyh.mei.source.shared.SharedOnlineSession,
    initialTab: PodcastTab = PodcastTab.Discover,
) : ViewModel() {
    @Inject constructor(onlineRepository: SharedOnlineRepository, savedStateHandle: androidx.lifecycle.SavedStateHandle) :
        this(onlineRepository.screen("podcast-home", savedStateHandle["providerId"]))
    val providerId: String? get() = repository.providerId
    private val _state = MutableStateFlow(PodcastUiState(selectedTab = initialTab))
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch { repository.sessionChanges.collect { owner ->
            if (owner.isBlank()) _state.value = PodcastUiState(isLoading = false)
            else {
                refresh()
                val profile = runCatching { repository.accountProfileRecord() }
                repository.features().ensureCurrent()
                _state.value = _state.value.copy(isSignedIn = profile.isSuccess)
            }
        } }
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

    fun selectCategory(id: String?) {
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
            val offset = if (reset) 0 else _state.value.subscriptionOffset
            runCatching { repository.subscribedPodcasts(offset = offset) }
                .onSuccess { page ->
                    _state.value = _state.value.withSubscriptionPage(page, reset)
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
    onlineRepository: SharedOnlineRepository,
    savedStateHandle: androidx.lifecycle.SavedStateHandle,
    private val mediaUriProvider: com.ljyh.mei.playback.MediaUriProvider,
) : ViewModel() {
    private val repository = onlineRepository.screen("podcast-detail", savedStateHandle["providerId"])
    val providerId: String? get() = repository.providerId
    private val _state = MutableStateFlow(PodcastDetailUiState())
    val state = _state.asStateFlow()
    private var loadedId: String? = null
    private val allProgramsMutex = kotlinx.coroutines.sync.Mutex()
    private var allProgramsCache: List<com.ljyh.mei.source.shared.OnlinePodcastProgram>? = null
    private var generation = 0

    init { viewModelScope.launch { repository.sessionChanges.collect { owner ->
        if (owner.isBlank()) {
            generation++
            allProgramsCache = null
            _state.value = PodcastDetailUiState(isLoading = false)
        }
    } } }

    suspend fun resolveDownload(metadata: com.ljyh.mei.data.model.MediaMetadata, quality: com.ljyh.mei.constants.MusicQuality): com.ljyh.mei.source.api.PlaybackResolution {
        val owner = repository.features()
        owner.ensureCurrent()
        if (metadata.trackRef.providerId != owner.providerId) throw com.ljyh.mei.source.api.SourceException(com.ljyh.mei.source.api.SourceErrorCode.INVALID_RESPONSE)
        return mediaUriProvider.resolveDownloadSource(metadata.toSourceTrack(), quality.text).also { owner.ensureCurrent() }
    }

    /** Reads every page for search and bulk actions without disturbing scroll pagination. */
    suspend fun allPrograms(id: String): List<com.ljyh.mei.source.shared.OnlinePodcastProgram> {
        allProgramsMutex.lock()
        try {
            check(loadedId == id)
            allProgramsCache?.let { return it }
            val version = generation
            var detail = checkNotNull(_state.value.detail)
            while (detail.hasMore) {
                val page = repository.podcastPrograms(id, offset = detail.nextOffset)
                detail = detail.withProgramPage(page)
            }
            if (loadedId != id || generation != version) throw kotlinx.coroutines.CancellationException("Podcast changed")
            allProgramsCache = detail.programs
            return detail.programs
        } finally { allProgramsMutex.unlock() }
    }


    fun load(id: String, force: Boolean = false) {
        if (!force && loadedId == id && _state.value.detail != null) return
        generation++
        val requestGeneration = generation
        allProgramsCache = null
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
                    if (loadedId == id && generation == requestGeneration) {
                        _state.value = PodcastDetailUiState(isLoading = false, detail = it)
                    }
                }
                .onFailure { error ->
                    if (loadedId == id && generation == requestGeneration) {
                        _state.value = _state.value.copy(isLoading = false, error = error.message)
                    }
                }
        }
    }

    fun loadMore() {
        val current = _state.value
        val detail = current.detail ?: return
        if (!detail.hasMore || current.isLoading || current.isLoadingMore) return
        val requestGeneration = generation
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoadingMore = true, loadMoreError = null)
            runCatching {
                repository.podcastPrograms(
                    id = detail.podcast.id,
                    offset = detail.nextOffset,
                )
            }.onSuccess { page ->
                if (loadedId != detail.podcast.id || generation != requestGeneration) return@onSuccess
                val latest = _state.value.detail ?: return@onSuccess
                _state.value = _state.value.copy(
                    detail = latest.withProgramPage(page),
                    isLoadingMore = false,
                    loadMoreError = null,
                )
            }.onFailure { error ->
                if (loadedId == detail.podcast.id && generation == requestGeneration) {
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

/** Server offsets count raw rows, including entries removed by parsing or deduplication. */
internal fun PodcastUiState.withSubscriptionPage(page: PodcastPage, reset: Boolean): PodcastUiState {
    val existing = if (reset) emptyList() else subscribedPodcasts
    val merged = appendUniquePodcasts(existing, page.podcasts)
    return copy(
        subscribedPodcasts = merged,
        subscriptionsLoaded = true,
        isSubscriptionsLoading = false,
        isLoadingMoreSubscriptions = false,
        hasMoreSubscriptions = page.hasMore,
        subscriptionTotalCount = maxOf(page.totalCount, merged.size),
        subscriptionOffset = (if (reset) 0 else subscriptionOffset) + page.fetchedCount,
        subscriptionsError = null,
        subscriptionsLoadMoreError = null,
    )
}

internal fun PodcastDetail.withProgramPage(page: PodcastProgramPage): PodcastDetail {
    val merged = appendUniquePrograms(programs, page.programs)
    return copy(
        programs = merged,
        hasMore = page.hasMore,
        totalCount = maxOf(totalCount, page.totalCount, merged.size),
        nextOffset = nextOffset + page.fetchedCount,
    )
}

internal fun appendUniquePodcasts(existing: List<Podcast>, incoming: List<Podcast>): List<Podcast> {
    val ids = existing.mapTo(mutableSetOf(), Podcast::id)
    return existing + incoming.filter { ids.add(it.id) }
}

internal fun appendUniquePrograms(
    existing: List<com.ljyh.mei.source.shared.OnlinePodcastProgram>,
    incoming: List<com.ljyh.mei.source.shared.OnlinePodcastProgram>,
): List<com.ljyh.mei.source.shared.OnlinePodcastProgram> {
    val ids = existing.mapTo(mutableSetOf(), com.ljyh.mei.source.shared.OnlinePodcastProgram::id)
    return existing + incoming.filter { ids.add(it.id) }
}
