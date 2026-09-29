package com.ljyh.mei.ui.screen.podcast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.model.melox.Podcast
import com.ljyh.mei.data.model.melox.PodcastDetail
import com.ljyh.mei.data.model.melox.PodcastHome
import com.ljyh.mei.data.model.melox.PodcastProgram
import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.data.repository.PodcastSource
import com.ljyh.mei.parasite.HostAccountStore
import com.ljyh.mei.parasite.HostSessionChangedException
import com.ljyh.mei.parasite.HostSessionStamp
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class PodcastTab { Discover, Subscriptions }

data class PodcastUiState(
    val session: HostSessionStamp? = null,
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
    val subscriptionOffset: Int = 0,
    val subscriptionsError: String? = null,
    val subscriptionsLoadMoreError: String? = null,
) {
    val authenticated: Boolean get() = session?.identity?.authenticated == true
}

data class PodcastDetailUiState(
    val session: HostSessionStamp? = null,
    val isLoading: Boolean = true,
    val detail: PodcastDetail? = null,
    val error: String? = null,
    val isLoadingMore: Boolean = false,
    val loadMoreError: String? = null,
    val isUpdatingSubscription: Boolean = false,
)

class PodcastViewModel internal constructor(
    private val repository: PodcastSource,
    private val accounts: HostAccountStore,
) : ViewModel() {
    @Inject constructor(repository: MeloXRepository, accounts: HostAccountStore) : this(repository as PodcastSource, accounts)

    private val mutableState = MutableStateFlow(PodcastUiState())
    val state = mutableState.asStateFlow()
    private val stateLock = Any()
    private val discoveryVersion = AtomicLong()
    private val subscriptionVersion = AtomicLong()
    private var discoveryJob: Job? = null
    private var subscriptionJob: Job? = null
    private var subscriptionsRequested = false
    private val invalidation = accounts.sessions.onInvalidated { revision ->
        synchronized(stateLock) {
            if ((state.value.session?.generation ?: -1) < revision) {
                discoveryVersion.incrementAndGet()
                subscriptionVersion.incrementAndGet()
                mutableState.value = pendingState()
            }
        }
    }

    init {
        viewModelScope.launch {
            combine(accounts.sessions.changes, accounts.sessions.recoveryRequired) { _, _ -> Unit }.collect {
                val stamp = runCatching { accounts.sessions.snapshot() }.getOrNull()
                if (stamp == null) {
                    discoveryJob?.cancel()
                    subscriptionJob?.cancel()
                    synchronized(stateLock) {
                        discoveryVersion.incrementAndGet()
                        subscriptionVersion.incrementAndGet()
                        mutableState.value = pendingState()
                    }
                } else if (state.value.session != stamp) {
                    discoveryJob?.cancel()
                    subscriptionJob?.cancel()
                    refreshDiscover()
                    if (subscriptionsRequested || state.value.selectedTab == PodcastTab.Subscriptions) loadSubscriptions(true)
                }
            }
        }
    }

    fun selectTab(tab: PodcastTab) {
        mutableState.update { it.copy(selectedTab = tab) }
        if (tab == PodcastTab.Subscriptions) ensureSubscriptionsLoaded()
        else if (state.value.home == null) refreshDiscover()
    }

    fun refresh() {
        if (state.value.selectedTab == PodcastTab.Subscriptions) refreshSubscriptions() else refreshDiscover()
    }

    fun refreshSubscriptions() {
        subscriptionsRequested = true
        if (state.value.isSubscriptionsLoading) return
        loadSubscriptions(true)
    }

    private fun refreshDiscover() {
        val stamp = runCatching { accounts.sessions.snapshot() }.getOrNull() ?: return
        discoveryJob?.cancel()
        val version = prepare(stamp, discoveryVersion) { it.copy(isLoading = true, error = null, selectedCategoryId = null) } ?: return
        discoveryJob = viewModelScope.launch {
            perform(stamp, discoveryVersion, version, { repository.podcastHome(stamp) },
                success = { current, home -> current.copy(isLoading = false, home = home, categoryPodcasts = emptyList()) },
                failure = { current, error -> current.copy(isLoading = false, error = error) },
            )
        }
    }

    fun selectCategory(id: Long?) {
        val stamp = state.value.session ?: return
        discoveryJob?.cancel()
        val version = prepare(stamp, discoveryVersion) {
            it.copy(isLoading = id != null, selectedCategoryId = id, categoryPodcasts = emptyList(), error = null)
        } ?: return
        if (id == null) return
        discoveryJob = viewModelScope.launch {
            perform(stamp, discoveryVersion, version, { repository.podcasts(stamp, id) },
                success = { current, podcasts -> current.copy(isLoading = false, categoryPodcasts = podcasts) },
                failure = { current, error -> current.copy(isLoading = false, error = error) },
            )
        }
    }

    fun ensureSubscriptionsLoaded() {
        subscriptionsRequested = true
        if (!state.value.subscriptionsLoaded && !state.value.isSubscriptionsLoading) loadSubscriptions(true)
    }

    fun loadMoreSubscriptions() {
        val current = state.value
        if (!current.subscriptionsLoaded || !current.hasMoreSubscriptions ||
            current.isSubscriptionsLoading || current.isLoadingMoreSubscriptions) return
        loadSubscriptions(false)
    }

    private fun loadSubscriptions(reset: Boolean) {
        val stamp = runCatching { accounts.requireAuthenticated() }.getOrNull() ?: return
        subscriptionJob?.cancel()
        val version = prepare(stamp, subscriptionVersion) {
            it.copy(isSubscriptionsLoading = reset, isLoadingMoreSubscriptions = !reset,
                subscriptionsError = null, subscriptionsLoadMoreError = null)
        } ?: return
        val offset = if (reset) 0 else state.value.subscriptionOffset
        subscriptionJob = viewModelScope.launch {
            perform(stamp, subscriptionVersion, version, { repository.subscribedPodcasts(stamp, offset) },
                success = { current, page ->
                    val existing = if (reset) emptyList() else current.subscribedPodcasts
                    val merged = appendUniquePodcasts(existing, page.podcasts)
                    check(!page.hasMore || (page.fetchedCount > 0 && merged.size > existing.size)) {
                        "Podcast pagination did not advance"
                    }
                    current.copy(subscribedPodcasts = merged, subscriptionsLoaded = true,
                        isSubscriptionsLoading = false, isLoadingMoreSubscriptions = false,
                        hasMoreSubscriptions = page.hasMore, subscriptionOffset = offset + page.fetchedCount,
                        subscriptionTotalCount = maxOf(page.totalCount, merged.size))
                },
                failure = { current, error ->
                    if (reset) current.copy(isSubscriptionsLoading = false, subscriptionsError = error)
                    else current.copy(isLoadingMoreSubscriptions = false, subscriptionsLoadMoreError = error)
                },
            )
        }
    }

    private fun pendingState(): PodcastUiState {
        val error = if (accounts.sessions.recoveryRequired.value) "Official session recovery is required" else null
        return PodcastUiState(selectedTab = state.value.selectedTab, isLoading = error == null,
            error = error, subscriptionsError = error)
    }

    private fun prepare(stamp: HostSessionStamp, counter: AtomicLong, update: (PodcastUiState) -> PodcastUiState): Long? =
        runCatching {
            accounts.sessions.withCurrent(stamp) {
                synchronized(stateLock) {
                    val current = state.value.takeIf { it.session == stamp }
                        ?: PodcastUiState(session = stamp, selectedTab = state.value.selectedTab)
                    mutableState.value = update(current)
                    counter.incrementAndGet()
                }
            }
        }.getOrNull()

    private fun publish(stamp: HostSessionStamp, counter: AtomicLong, version: Long, update: (PodcastUiState) -> PodcastUiState) {
        accounts.sessions.withCurrent(stamp) {
            synchronized(stateLock) {
                if (counter.get() == version) mutableState.update(update)
            }
        }
    }

    private suspend fun <T> perform(
        stamp: HostSessionStamp, counter: AtomicLong, version: Long, load: suspend () -> T,
        success: (PodcastUiState, T) -> PodcastUiState, failure: (PodcastUiState, String) -> PodcastUiState,
    ) {
        try {
            accounts.sessions.requireCurrent(stamp)
            val value = load()
            currentCoroutineContext().ensureActive()
            publish(stamp, counter, version) { success(it, value) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: HostSessionChangedException) {
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            runCatching { publish(stamp, counter, version) { failure(it, error.message ?: "Podcast request failed") } }
        }
    }

    override fun onCleared() {
        invalidation.close()
        discoveryVersion.incrementAndGet()
        subscriptionVersion.incrementAndGet()
        super.onCleared()
    }
}

class PodcastDetailViewModel internal constructor(
    private val repository: PodcastSource,
    private val accounts: HostAccountStore,
) : ViewModel() {
    @Inject constructor(repository: MeloXRepository, accounts: HostAccountStore) : this(repository as PodcastSource, accounts)

    fun requireDetail(owner: HostSessionStamp, id: Long) {
        accounts.sessions.withCurrent(owner) {
            synchronized(stateLock) {
                if (accounts.sessions.recoveryRequired.value || state.value.session != owner || loadedId != id ||
                    state.value.detail?.podcast?.id != id) throw CancellationException("Podcast changed")
            }
        }
    }

    private val mutableState = MutableStateFlow(PodcastDetailUiState())
    val state = mutableState.asStateFlow()
    private val stateLock = Any()
    private val generation = AtomicLong()
    private var loadedId: Long? = null
    private var loadJob: Job? = null
    private var moreJob: Job? = null
    private var subscriptionJob: Job? = null
    private val allProgramsMutex = Mutex()
    private var allProgramsCache: List<PodcastProgram>? = null
    private val invalidation = accounts.sessions.onInvalidated { revision ->
        synchronized(stateLock) {
            if ((state.value.session?.generation ?: -1) < revision) {
                generation.incrementAndGet()
                allProgramsCache = null
                mutableState.value = pendingState()
            }
        }
    }

    init {
        viewModelScope.launch {
            combine(accounts.sessions.changes, accounts.sessions.recoveryRequired) { _, _ -> Unit }.collect {
                val stamp = runCatching { accounts.sessions.snapshot() }.getOrNull()
                if (stamp == null) {
                    loadJob?.cancel()
                    moreJob?.cancel()
                    subscriptionJob?.cancel()
                    synchronized(stateLock) {
                        generation.incrementAndGet()
                        allProgramsCache = null
                        mutableState.value = pendingState()
                    }
                } else if (state.value.session != stamp) loadedId?.let { load(it, true) }
            }
        }
    }

    /** Reads every page for search and bulk actions without disturbing scroll pagination. */
    suspend fun allPrograms(id: Long): List<PodcastProgram> = allProgramsMutex.withLock {
        currentCoroutineContext().ensureActive()
        val stamp = checkNotNull(state.value.session)
        val (version, detail, cached) = accounts.sessions.withCurrent(stamp) {
            synchronized(stateLock) {
                if (loadedId != id || state.value.session != stamp) throw CancellationException("Podcast changed")
                Triple(generation.get(), checkNotNull(state.value.detail), allProgramsCache)
            }
        }
        cached?.let { return@withLock it }
        var programs = detail.programs
        var hasMore = detail.hasMore
        var offset = detail.nextOffset
        while (hasMore) {
            currentCoroutineContext().ensureActive()
            checkCurrent(stamp, version, id)
            val page = repository.podcastPrograms(stamp, id, offset)
            checkCurrent(stamp, version, id)
            val merged = appendUniquePrograms(programs, page.programs)
            check(!page.hasMore || (page.fetchedCount > 0 && merged.size > programs.size)) { "Podcast pagination did not advance" }
            programs = merged
            hasMore = page.hasMore
            offset += page.fetchedCount
        }
        currentCoroutineContext().ensureActive()
        accounts.sessions.withCurrent(stamp) {
            synchronized(stateLock) {
                if (generation.get() != version || loadedId != id) throw CancellationException("Podcast changed")
                allProgramsCache = programs
            }
        }
        programs
    }

    fun load(id: Long, force: Boolean = false) {
        val stamp = runCatching { accounts.sessions.snapshot() }.getOrNull()
        loadedId = id
        if (stamp == null) return
        if (!force && state.value.session == stamp && state.value.detail?.podcast?.id == id) return
        loadJob?.cancel()
        moreJob?.cancel()
        subscriptionJob?.cancel()
        val version = runCatching {
            accounts.sessions.withCurrent(stamp) {
                synchronized(stateLock) {
                    val previous = state.value
                    allProgramsCache = null
                    mutableState.value = PodcastDetailUiState(session = stamp,
                        detail = previous.detail.takeIf { previous.session == stamp && it?.podcast?.id == id && !previous.isUpdatingSubscription })
                    generation.incrementAndGet()
                }
            }
        }.getOrNull() ?: return
        loadJob = viewModelScope.launch {
            perform(stamp, version, id, { repository.podcastDetail(stamp, id) },
                success = { _, detail ->
                    check(!detail.hasMore || detail.nextOffset > 0) { "Podcast pagination did not advance" }
                    PodcastDetailUiState(session = stamp, isLoading = false,
                        detail = detail.copy(programs = appendUniquePrograms(emptyList(), detail.programs)))
                },
                failure = { current, error -> current.copy(isLoading = false, error = error) },
            )
        }
    }

    fun loadMore() {
        val current = state.value
        val detail = current.detail ?: return
        val stamp = current.session ?: return
        if (!detail.hasMore || current.isLoading || current.isLoadingMore) return
        val version = generation.get()
        if (!runCatching { publish(stamp, version) { it.copy(isLoadingMore = true, loadMoreError = null) } }.getOrDefault(false)) return
        moreJob = viewModelScope.launch {
            perform(stamp, version, detail.podcast.id, { repository.podcastPrograms(stamp, detail.podcast.id, detail.nextOffset) },
                success = { latest, page ->
                    val before = checkNotNull(latest.detail)
                    val programs = appendUniquePrograms(before.programs, page.programs)
                    check(!page.hasMore || (page.fetchedCount > 0 && programs.size > before.programs.size)) { "Podcast pagination did not advance" }
                    latest.copy(isLoadingMore = false, detail = before.copy(programs = programs, hasMore = page.hasMore,
                        totalCount = maxOf(before.totalCount, page.totalCount, programs.size),
                        nextOffset = detail.nextOffset + page.fetchedCount))
                },
                failure = { latest, error -> latest.copy(isLoadingMore = false, loadMoreError = error) },
            )
        }
    }

    fun toggleSubscription() {
        val current = state.value
        val detail = current.detail ?: return
        val stamp = current.session ?: return
        if (current.isLoading || current.isUpdatingSubscription) return
        val version = generation.get()
        if (!stamp.identity.authenticated) {
            runCatching { publish(stamp, version) { it.copy(error = "Official sign-in required") } }
            return
        }
        val target = !detail.podcast.isSubscribed
        if (!runCatching {
            publish(stamp, version) { it.copy(detail = detail.copy(podcast = detail.podcast.copy(isSubscribed = target)),
                isUpdatingSubscription = true, error = null) }
        }.getOrDefault(false)) return
        subscriptionJob = viewModelScope.launch {
            perform(stamp, version, detail.podcast.id, { repository.setPodcastSubscribed(stamp, detail.podcast.id, target) },
                success = { latest, _ -> latest.copy(isUpdatingSubscription = false) },
                failure = { latest, error -> latest.copy(isUpdatingSubscription = false, error = error,
                    detail = latest.detail?.let { it.copy(podcast = it.podcast.copy(isSubscribed = !target)) }) },
            )
        }
    }

    private fun pendingState(): PodcastDetailUiState {
        val error = if (accounts.sessions.recoveryRequired.value) "Official session recovery is required" else null
        return PodcastDetailUiState(isLoading = error == null, error = error)
    }

    private fun checkCurrent(stamp: HostSessionStamp, version: Long, id: Long) {
        accounts.sessions.requireCurrent(stamp)
        if (generation.get() != version || loadedId != id) throw CancellationException("Podcast changed")
    }

    private fun publish(stamp: HostSessionStamp, version: Long, update: (PodcastDetailUiState) -> PodcastDetailUiState): Boolean =
        accounts.sessions.withCurrent(stamp) {
            synchronized(stateLock) {
                if (generation.get() != version) false else {
                    mutableState.update(update)
                    true
                }
            }
        }

    private suspend fun <T> perform(
        stamp: HostSessionStamp, version: Long, id: Long, load: suspend () -> T,
        success: (PodcastDetailUiState, T) -> PodcastDetailUiState,
        failure: (PodcastDetailUiState, String) -> PodcastDetailUiState,
    ) {
        try {
            checkCurrent(stamp, version, id)
            val result = load()
            currentCoroutineContext().ensureActive()
            checkCurrent(stamp, version, id)
            publish(stamp, version) { success(it, result) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: HostSessionChangedException) {
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            runCatching { publish(stamp, version) { failure(it, error.message ?: "Podcast request failed") } }
        }
    }

    override fun onCleared() {
        invalidation.close()
        generation.incrementAndGet()
        super.onCleared()
    }
}

internal fun appendUniquePodcasts(existing: List<Podcast>, incoming: List<Podcast>): List<Podcast> {
    val ids = existing.mapTo(mutableSetOf(), Podcast::id)
    return existing + incoming.filter { ids.add(it.id) }
}

internal fun appendUniquePrograms(existing: List<PodcastProgram>, incoming: List<PodcastProgram>): List<PodcastProgram> {
    val ids = existing.mapTo(mutableSetOf(), PodcastProgram::id)
    return existing + incoming.filter { ids.add(it.id) }
}
