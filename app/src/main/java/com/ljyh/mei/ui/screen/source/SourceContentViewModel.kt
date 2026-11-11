package com.ljyh.mei.ui.screen.source

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.playback.queue.Queue
import com.ljyh.mei.playback.queue.SourceContentQueue
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.ProviderRegistry
import com.ljyh.mei.source.core.SourcePreferences
import com.ljyh.mei.source.core.SourceRouter
import com.ljyh.mei.source.core.SourceFeatureClient
import com.ljyh.mei.source.core.SourceHomeFeedCache
import com.ljyh.mei.source.core.SourceHomeCacheOwner
import com.ljyh.mei.source.shared.whileCurrent
import com.ljyh.mei.source.script.ScriptPluginManager
import com.ljyh.mei.source.script.InstalledPlugin
import com.ljyh.mei.source.script.SourceCatalogInvalidatedException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.ljyh.mei.ui.screen.artist.ArtistCatalogSearchPageBudget
import com.ljyh.mei.ui.screen.artist.ArtistCatalogSearchDebounceMillis

sealed interface SourceContentRequest {
    data class Feed(val kind: FeedKind, val categoryId: String? = null, val providerId: String? = null) : SourceContentRequest
    data class Library(val collection: LibraryCollection, val providerId: String? = null) : SourceContentRequest
    data class Detail(val ref: ContentRef) : SourceContentRequest
    data class ArtistInformation(val ref: ArtistRef) : SourceContentRequest
    data class Account(val providerId: String) : SourceContentRequest
    data class Editor(val providerId: String) : SourceContentRequest
}

internal data class SourceDetailTracks(
    val ref: ContentRef,
    val title: String,
    val tracks: List<Track>,
    val provider: MusicProvider,
    val providerVersion: String,
    val accountRevision: String?,
    val account: SourceAccount?,
    val generation: Long,
    val contentRevision: Long,
    val editableActions: Set<PlaylistEditAction> = emptySet(),
)

data class SourceContentState(
    val providerId: String = "",
    val providerVersion: String = "",
    val accountRevision: String? = null,
    val title: String = "",
    val artworkUrl: String = "",
    val description: String? = null,
    val detail: SourceItem? = null,
    val account: SourceAccount? = null,
    val items: List<SourceItem> = emptyList(),
    val sections: List<SourceFeedSection> = emptyList(),
    val categories: List<SourceCategory> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
    val nextOffset: Int = 0,
    val error: SourceErrorCode? = null,
    val albumsHasMore: Boolean = false,
    val albumsNextCursor: String? = null,
    val albumsNextOffset: Int = 0,
    val albumsLoading: Boolean = false,
    val albumsError: SourceErrorCode? = null,
)

/** Every page is pinned to its provider and account; stale work cannot refill a replacement page. */
@HiltViewModel
class SourceContentViewModel internal constructor(
    private val router: SourceRouter,
    private val preferences: SourcePreferences,
    private val registry: ProviderRegistry,
    val installedPlugins: StateFlow<List<InstalledPlugin>> = MutableStateFlow(emptyList()),
    private val configurePlugin: suspend (String, Map<String, String>) -> Unit = { _, _ -> throw SourceException(SourceErrorCode.UNSUPPORTED) },
    private val features: SourceFeatureClient? = null,
    private val homeCache: SourceHomeFeedCache? = null,
) : ViewModel() {
    @Inject constructor(router: SourceRouter, preferences: SourcePreferences, registry: ProviderRegistry,
        plugins: ScriptPluginManager, features: SourceFeatureClient, homeCache: SourceHomeFeedCache) :
        this(router, preferences, registry, plugins.installed, { id, values -> plugins.configure(id, values); Unit }, features, homeCache)
    val providers = registry.providers
    val contentRevision = router.contentRevision
    val selectedProviderId = preferences.settings.map { it.preferredBrowseProvider }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    private val request = MutableStateFlow<SourceContentRequest?>(null)
    private val refreshVersion = MutableStateFlow(0L)
    private var consumedRefreshVersion = 0L
    private val mutableState = MutableStateFlow(SourceContentState(loading = true))
    val state = mutableState.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableMessage = MutableStateFlow<SourceErrorCode?>(null)
    val message = mutableMessage.asStateFlow()
    private var generation = 0L
    private var displayedRequest: SourceContentRequest? = null
    private var displayedProvider: MusicProvider? = null
    private var pagination: Job? = null
    private var albumPagination: Job? = null
    private var artistSearchJob: Job? = null
    private var artistSearchPage: Job? = null
    private var artistSearchSelection: Triple<ArtistRef, Boolean, String>? = null
    private val seenPages = mutableSetOf<List<ContentRef>>()
    private val seenCursors = mutableSetOf<String>()
    private val detailTracksMutex = Mutex()
    private var detailTracksCache: SourceDetailTracks? = null

    init {
        viewModelScope.launch {
            combine(request, selectedProviderId, registry.revision, router.contentRevision, refreshVersion) {
                    requested, preferred, registryRevision, contentRevision, refresh ->
                val catalog = requested is SourceContentRequest.ArtistInformation ||
                    requested is SourceContentRequest.Detail && requested.ref is ArtistRef
                PageSelection(requested, requested.providerId(preferred), registryRevision, if (catalog) 0L else contentRevision, refresh)
            }.distinctUntilChanged().collectLatest { selection ->
                val explicitRefresh = selection.refresh > consumedRefreshVersion
                consumedRefreshVersion = maxOf(consumedRefreshVersion, selection.refresh)
                invalidatePage()
                val requested = selection.request ?: return@collectLatest
                val id = selection.providerId.takeIf(String::isNotBlank) ?: return@collectLatest
                registry.awaitInitialized()
                val provider = registry.provider(id)
                val revisionFlow: Flow<String?> = (provider as? AccountRevisionCapability)?.accountRevision ?: flowOf(null)
                var refreshPending = explicitRefresh
                try {
                    revisionFlow.distinctUntilChanged().collectLatest account@{ accountRevision ->
                        invalidatePage()
                        val token = generation
                        val refreshing = refreshPending
                        refreshPending = false
                        val previous = state.value
                        val sameSource = displayedProvider === provider && previous.providerId == id && previous.providerVersion == provider?.version.orEmpty() &&
                            previous.accountRevision == accountRevision
                        val sameOwner = displayedRequest == requested && sameSource
                        val retainedCategories = previous.categories.takeIf {
                            sameSource && requested is SourceContentRequest.Feed &&
                                (displayedRequest as? SourceContentRequest.Feed)?.kind == requested.kind
                        }.orEmpty()
                        if (!sameOwner) mutableMessage.value = null
                        mutableState.value = if (sameOwner) previous.copy(loading = true, refreshing = refreshing, error = null,
                            albumsLoading = false, albumsError = null) else SourceContentState(providerId = id,
                            providerVersion = provider?.version.orEmpty(), accountRevision = accountRevision,
                            account = previous.account.takeIf { sameSource }, categories = retainedCategories, loading = true,
                            refreshing = refreshing)
                        displayedRequest = requested
                        displayedProvider = provider
                        val home = requested is SourceContentRequest.Feed && requested.kind == FeedKind.HOME
                        val owner = provider?.takeIf { home }?.let { SourceHomeCacheOwner(id, it.instanceId, it.version, accountRevision) }
                        val cacheGuard: suspend () -> Unit = {
                            currentCoroutineContext().ensureActive()
                            if (token != generation || provider == null || !sameOwner(provider, state.value)) throw CancellationException("Home cache owner changed")
                        }
                        val cached = if (home && !refreshing && owner != null && provider != null)
                            homeCache?.read(owner, provider, cacheGuard) else null
                        if (cached != null) {
                            mutableState.value = state.value.copy(sections = cached.sections, categories = cached.categories, loading = false,
                                hasMore = false, nextCursor = null, error = null)
                            return@account
                        }
                        load(requested, id, token, append = false, refresh = refreshing, forceAccountRefresh = refreshing)
                        // Home originally publishes the complete recommendation feed, including later modules.
                        if (requested is SourceContentRequest.Feed && requested.kind == FeedKind.HOME) {
                            var pages = 1
                            while (token == generation && state.value.error == null && state.value.hasMore && pages < 10) {
                                mutableState.value = state.value.copy(loading = true)
                                load(requested, id, token, append = true)
                                pages++
                            }
                            if (token == generation && state.value.error == null && state.value.hasMore) {
                                mutableState.value = state.value.copy(loading = false, hasMore = false, error = SourceErrorCode.INVALID_RESPONSE)
                            }
                            if (token == generation && state.value.error == null && !state.value.hasMore && owner != null) {
                                homeCache?.write(owner, SourceFeedPage(state.value.sections, state.value.categories), cacheGuard)
                            }
                        }
                        if (token == generation) mutableState.value = state.value.copy(refreshing = false)
                    }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) {
                    invalidatePage()
                    mutableState.value = SourceContentState(providerId = id, providerVersion = provider?.version.orEmpty(), error = error.sourceCode())
                }
            }
        }
    }

    private fun invalidatePage() {
        generation++
        detailTracksCache = null
        pagination?.cancel()
        albumPagination?.cancel()
        artistSearchJob?.cancel()
        seenPages.clear()
        seenCursors.clear()
    }
    fun open(value: SourceContentRequest) { request.value = value }
    fun prepareEditor(providerId: String) { request.value = SourceContentRequest.Editor(providerId) }
    fun refresh() { refreshVersion.value++ }
    fun selectProvider(id: String) { viewModelScope.launch { preferences.update { it.copy(preferredBrowseProvider = id) } } }
    fun dismissMessage() { mutableMessage.value = null }
    fun provider(id: String = state.value.providerId): MusicProvider? = registry.provider(id)
    fun pluginId(id: String = state.value.providerId): String? = installedPlugins.value
        .firstOrNull { it.manifest.providerId == id }?.manifest?.id

    fun loadMore() {
        val current = mutableState.value
        val requested = request.value ?: return
        if (requested != displayedRequest || !current.hasMore || current.loading || current.refreshing || pagination?.isActive == true) return
        val token = generation
        mutableState.value = current.copy(loading = true, error = null)
        pagination = viewModelScope.launch { load(requested, current.providerId, token, append = true) }
    }

    internal suspend fun indexedTrackMembership(requested: SourceContentRequest, track: TrackRef): Boolean? {
        val owner = displayedProvider ?: return false
        if (requested != displayedRequest || requested != request.value || owner.id != track.providerId) return false
        val catalog = owner as? CatalogIndexCapability ?: return null
        val playlistId = ((requested as? SourceContentRequest.Detail)?.ref as? PlaylistRef)?.playlistId
        val collection = (requested as? SourceContentRequest.Library)?.collection
        if (playlistId == null && collection != LibraryCollection.LIKED_TRACKS) return false
        val token = generation
        val revision = contentRevision.value
        val index = try {
            catalog.completeTrackIds(playlistId.orEmpty(), collection)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        if (token != generation || revision != contentRevision.value || requested != request.value ||
            registry.provider(owner.id) !== owner) return false
        return index?.let { (playlistId == null || it.playlistId == playlistId) && track in it.ids }
    }

    internal suspend fun locateTrack(requested: SourceContentRequest, track: TrackRef): Int? {
        val token = generation
        val revision = contentRevision.value
        var retried = false
        while (token == generation && revision == contentRevision.value && requested == displayedRequest &&
            requested == request.value && track.providerId == displayedProvider?.id) {
            val current = state.value
            val index = current.items.indexOfFirst { it.ref == track }
            if (index >= 0) return index
            if (current.refreshing || !current.hasMore) return null

            val pending = pagination?.takeIf { it.isActive } ?: run {
                if (current.loading || current.error != null && retried) return null
                if (current.error != null) retried = true
                loadMore()
                if (state.value == current) return null
                pagination ?: return null
            }
            if (withTimeoutOrNull(10_000L) { pending.join() } == null) return null
        }
        return null
    }

    private suspend fun load(requested: SourceContentRequest, id: String, token: Long, append: Boolean, refresh: Boolean = false,
        forceAccountRefresh: Boolean = false, retryCancellation: Boolean = true) {
        val previous = mutableState.value
        val page = PageRequest(if (append) previous.nextOffset else 0, 30, if (append) previous.nextCursor else null, refresh = refresh && !append)
        var received = 0
        var incomingRefs = emptyList<ContentRef>()
        var loadingProvider: MusicProvider? = null
        suspend fun ownsActivePage(): Boolean {
            currentCoroutineContext().ensureActive()
            val owner = loadingProvider ?: return false
            return token == generation && request.value == requested && displayedRequest == requested &&
                displayedProvider === owner && sameOwner(owner, previous)
        }
        try {
            val boundProvider = registry.requireProvider(id)
            loadingProvider = boundProvider
            if (boundProvider.version != previous.providerVersion) throw CancellationException("Source changed before loading")
            val result = when (requested) {
                is SourceContentRequest.Feed -> {
                    val feed = router.getFeed(id, requested.kind, requested.categoryId, page)
                    received = page.limit
                    incomingRefs = feed.sections.flatMap { section -> section.items.map { it.ref } }
                    previous.copy(sections = mergeSections(if (append) previous.sections else emptyList(), feed.sections),
                        categories = feed.categories.ifEmpty { previous.categories },
                        hasMore = feed.hasMore, nextCursor = feed.nextCursor)
                }
                is SourceContentRequest.Library -> {
                    val account = if (append) previous.account else if (SourceCapability.ACCOUNT in router.registry.requireProvider(id).capabilities)
                        router.account(id, refresh = forceAccountRefresh) else null
                    currentCoroutineContext().ensureActive()
                    if (token != generation || !sameOwner(boundProvider, previous)) return
                    mutableState.value = state.value.copy(account = account)
                    val collection = router.library(id, requested.collection, page)
                    received = collection.items.size
                    incomingRefs = collection.items.map { it.ref }
                    previous.copy(account = account, items = mergeItems(previous.items, collection.items, append),
                        hasMore = collection.hasMore, nextCursor = collection.nextCursor)
                }
                is SourceContentRequest.Detail -> when (val ref = requested.ref) {
                    is PlaylistRef -> {
                        val refreshedTracks = if (page.refresh && !append) router.playlistTracks(ref, page) else null
                        val info = if (append) (previous.detail as? SourceItem.PlaylistItem)?.playlist ?: router.playlist(ref) else router.playlist(ref)
                        currentCoroutineContext().ensureActive()
                        if (token != generation || !sameOwner(boundProvider, previous)) return
                        if (!append) mutableState.value = state.value.copy(title = info.title, artworkUrl = info.artworkUrl,
                            description = info.description, detail = SourceItem.PlaylistItem(info))
                        val tracks = refreshedTracks ?: router.playlistTracks(ref, page)
                        received = tracks.items.size
                        incomingRefs = tracks.items.map { it.ref }
                        previous.copy(title = info.title, artworkUrl = info.artworkUrl, description = info.description,
                            detail = SourceItem.PlaylistItem(info), items = mergeTrackItems(previous.items, tracks.items, append),
                            hasMore = tracks.hasMore, nextCursor = tracks.nextCursor)
                    }
                    is AlbumRef -> {
                        val refreshedTracks = if (page.refresh && !append) router.albumTracks(ref, page) else null
                        val info = if (append) (previous.detail as? SourceItem.AlbumItem)?.album ?: router.album(ref) else router.album(ref)
                        currentCoroutineContext().ensureActive()
                        if (token != generation || !sameOwner(boundProvider, previous)) return
                        if (!append) mutableState.value = state.value.copy(title = info.title, artworkUrl = info.artworkUrl,
                            description = info.description, detail = SourceItem.AlbumItem(info))
                        val tracks = refreshedTracks ?: router.albumTracks(ref, page)
                        received = tracks.items.size
                        incomingRefs = tracks.items.map { it.ref }
                        previous.copy(title = info.title, artworkUrl = info.artworkUrl, description = info.description,
                            detail = SourceItem.AlbumItem(info), items = mergeTrackItems(previous.items, tracks.items, append),
                            hasMore = tracks.hasMore, nextCursor = tracks.nextCursor)
                    }
                    is ArtistRef -> {
                        if (!append) loadInitialArtistAlbums(ref, boundProvider, previous, token)
                        val info = if (append) (previous.detail as? SourceItem.ArtistItem)?.artist ?: router.artist(ref) else router.artist(ref)
                        currentCoroutineContext().ensureActive()
                        if (token != generation || !sameOwner(boundProvider, previous)) return
                        if (!append) mutableState.value = state.value.copy(title = info.name, artworkUrl = info.artworkUrl,
                            description = info.description, detail = SourceItem.ArtistItem(info))
                        val tracks = router.artistTracks(ref, page)
                        received = tracks.items.size
                        incomingRefs = tracks.items.map { it.ref }
                        previous.copy(title = info.name, artworkUrl = info.artworkUrl, description = info.description,
                            detail = SourceItem.ArtistItem(info), items = mergeTrackItems(previous.items, tracks.items, append),
                            hasMore = tracks.hasMore, nextCursor = tracks.nextCursor)
                    }
                    is TrackRef -> {
                        val track = router.getTrack(ref) ?: throw SourceException(SourceErrorCode.NOT_FOUND)
                        received = 1
                        previous.copy(title = track.title, artworkUrl = track.artworkUrl,
                            detail = SourceItem.TrackItem(track), items = listOf(SourceItem.TrackItem(track)), hasMore = false, nextCursor = null)
                    }
                }
                is SourceContentRequest.ArtistInformation -> router.artist(requested.ref).let { info ->
                    previous.copy(title = info.name, artworkUrl = info.artworkUrl, description = info.description,
                        detail = SourceItem.ArtistItem(info), hasMore = false, nextCursor = null)
                }
                is SourceContentRequest.Account -> previous.copy(account = router.account(id, refresh = forceAccountRefresh))
                is SourceContentRequest.Editor -> {
                    val editor = boundProvider as? PlaylistEditCapability
                    if (SourceCapability.PLAYLIST_EDIT !in boundProvider.capabilities ||
                        PlaylistEditAction.CREATE !in editor?.playlistEditActions.orEmpty()) throw SourceException(SourceErrorCode.UNSUPPORTED)
                    previous
                }
            }
            currentCoroutineContext().ensureActive()
            if (token != generation || !sameOwner(boundProvider, previous)) return
            val progressed = if (requested is SourceContentRequest.Feed) {
                val grew = result.sections.sumOf { it.items.size + it.cards.size } >
                    previous.sections.sumOf { it.items.size + it.cards.size }
                if (result.hasMore && (result.nextCursor == null || result.nextCursor == page.cursor ||
                        result.nextCursor in seenCursors || append && !grew)) {
                    throw SourceException(SourceErrorCode.INVALID_RESPONSE)
                }
                result
            } else validateSourceContentPage(result, page, incomingRefs, received, append, seenPages, seenCursors)
            if (incomingRefs.isNotEmpty()) seenPages += incomingRefs
            result.nextCursor?.let(seenCursors::add)
            val latest = state.value
            val updated = if (requested is SourceContentRequest.Detail && requested.ref is ArtistRef) progressed.copy(
                sections = latest.sections, albumsHasMore = latest.albumsHasMore, albumsNextCursor = latest.albumsNextCursor,
                albumsNextOffset = latest.albumsNextOffset, albumsLoading = latest.albumsLoading, albumsError = latest.albumsError,
            ) else progressed
            mutableState.value = updated.copy(providerId = id, loading = false, error = null, nextOffset = page.offset + received)
        } catch (cancelled: CancellationException) {
            if (!ownsActivePage()) throw cancelled
            if (retryCancellation && cancelled is SourceCatalogInvalidatedException) {
                delay(50)
                if (!ownsActivePage()) throw cancelled
                // Catalog cancellation follows an invalidation. Reuse that invalidation on
                // retry rather than making concurrent refreshes invalidate one another again.
                withContext(cancelled.retryContext) {
                    load(requested, id, token, append, refresh = refresh && requested is SourceContentRequest.Feed,
                        forceAccountRefresh = forceAccountRefresh, retryCancellation = false)
                }
            } else {
                mutableState.value = state.value.copy(loading = false, refreshing = false, error = SourceErrorCode.UNAVAILABLE)
            }
        } catch (error: Exception) {
            timber.log.Timber.tag("SourceContent").w(error, "Source content unavailable")
            if (token == generation) mutableState.value = state.value.copy(loading = false, error = error.sourceCode(),
                account = state.value.account.takeUnless { error.sourceCode() == SourceErrorCode.AUTH_REQUIRED || error.sourceCode() == SourceErrorCode.AUTH_EXPIRED },
                hasMore = state.value.hasMore && error.sourceCode() != SourceErrorCode.INVALID_RESPONSE)
        }
    }

    private suspend fun optionalArtistAlbums(ref: ArtistRef): SourcePage<SourceAlbumInfo>? = try {
        router.artistAlbums(ref, PageRequest(limit = 30))
    } catch (error: SourceException) {
        if (error.code == SourceErrorCode.UNSUPPORTED) null else throw error
    }

    /** Albums and the artist header can publish before the song request completes. */
    private fun loadInitialArtistAlbums(ref: ArtistRef, provider: MusicProvider, snapshot: SourceContentState, token: Long) {
        albumPagination?.cancel()
        mutableState.value = state.value.copy(albumsLoading = true, albumsError = null)
        albumPagination = viewModelScope.launch {
            try {
                val albums = optionalArtistAlbums(ref)
                currentCoroutineContext().ensureActive()
                if (token != generation || !sameOwner(provider, snapshot)) return@launch
                mutableState.value = state.value.copy(sections = albumSections(albums?.items.orEmpty()),
                    albumsLoading = false, albumsHasMore = albums?.hasMore == true, albumsError = null,
                    albumsNextCursor = albums?.nextCursor, albumsNextOffset = albums?.items?.size ?: 0)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (token == generation) mutableState.value = state.value.copy(albumsError = error.sourceCode(), albumsHasMore = true)
            } finally {
                if (token == generation) mutableState.value = state.value.copy(albumsLoading = false)
            }
        }
    }

    fun loadMoreArtistAlbums() {
        val ref = ((request.value as? SourceContentRequest.Detail)?.ref as? ArtistRef) ?: return
        val current = state.value
        if (request.value != displayedRequest || !current.albumsHasMore || current.albumsLoading || current.loading || current.refreshing) return
        val token = generation
        val boundProvider = provider(ref.providerId) ?: return
        mutableState.value = current.copy(albumsLoading = true, albumsError = null)
        albumPagination = viewModelScope.launch {
            try {
                val page = PageRequest(current.albumsNextOffset, 30, current.albumsNextCursor)
                val albums = router.artistAlbums(ref, page)
                currentCoroutineContext().ensureActive()
                if (token != generation || !sameOwner(boundProvider, current)) return@launch
                val latest = state.value
                val merged = mergeSections(latest.sections, albumSections(albums.items))
                if (albums.hasMore && merged == latest.sections) {
                    throw SourceException(SourceErrorCode.INVALID_RESPONSE)
                }
                mutableState.value = latest.copy(sections = merged, albumsLoading = false, albumsError = null,
                    albumsHasMore = albums.hasMore, albumsNextCursor = albums.nextCursor,
                    albumsNextOffset = page.offset + albums.items.size)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (token == generation) mutableState.value = state.value.copy(albumsLoading = false, albumsError = error.sourceCode(),
                    albumsHasMore = state.value.albumsHasMore && error.sourceCode() != SourceErrorCode.INVALID_RESPONSE)
            } finally {
                if (token == generation) mutableState.value = state.value.copy(albumsLoading = false)
            }
        }
    }

    fun updateArtistCatalogSearch(ref: ArtistRef, albums: Boolean, query: String) {
        val selection = Triple(ref, albums, query.trim())
        if (selection == artistSearchSelection) return
        artistSearchSelection = selection
        searchArtistCatalog(selection, debounce = true)
    }

    fun continueArtistCatalogSearch() {
        artistSearchSelection?.let { searchArtistCatalog(it, debounce = false) }
    }

    private fun searchArtistCatalog(selection: Triple<ArtistRef, Boolean, String>, debounce: Boolean) {
        artistSearchJob?.cancel()
        artistSearchPage?.takeIf { it.isActive }?.let {
            generation++
            it.cancel()
            mutableState.value = state.value.copy(loading = false, albumsLoading = false)
        }
        artistSearchPage = null
        if (selection.third.isEmpty()) return
        artistSearchJob = viewModelScope.launch {
            if (debounce) delay(ArtistCatalogSearchDebounceMillis)
            state.first { !it.loading && (!selection.second || !it.albumsLoading) && it.providerId == selection.first.providerId }
            repeat(ArtistCatalogSearchPageBudget) { pageIndex ->
                if (artistSearchSelection != selection || (request.value as? SourceContentRequest.Detail)?.ref != selection.first) return@launch
                val current = state.value
                if (selection.second) {
                    if (!current.albumsHasMore || current.albumsError != null && pageIndex > 0) return@launch
                    loadMoreArtistAlbums()
                } else {
                    if (!current.hasMore || current.error != null && pageIndex > 0) return@launch
                    loadMore()
                }
                val pending = if (selection.second) albumPagination else pagination
                artistSearchPage = pending
                try { pending?.join() }
                finally { if (!currentCoroutineContext().isActive) pending?.cancel() }
            }
        }
    }

    /** A pending card remains pinned to the displayed source and account until playback commits. */
    suspend fun feedQueue(card: SourceFeedCard): Pair<List<Track>, Boolean> {
        val displayed = state.value
        val token = generation
        if (displayed.sections.none { card in it.cards }) throw SourceException(SourceErrorCode.INVALID_RESPONSE)
        val target = card.target as? SourceFeedTarget.Queue ?: throw SourceException(SourceErrorCode.UNSUPPORTED)
        val owner = (features ?: throw SourceException(SourceErrorCode.UNSUPPORTED))
            .bind(displayed.providerId, "feed-card:${displayed.providerId}")
        val expected = provider(displayed.providerId) ?: throw SourceException(SourceErrorCode.MISSING_PROVIDER)
        if (!sameOwner(expected, displayed)) throw CancellationException("Feed card owner changed")
        return owner.whileCurrent {
            val result = owner.invoke(target.feature, target.action, target.parameters)
                .takeIf { it.isJsonObject }?.asJsonObject ?: throw SourceException(SourceErrorCode.INVALID_RESPONSE)
            val rows = result.get("tracks")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: throw SourceException(SourceErrorCode.INVALID_RESPONSE)
            if (rows.isEmpty()) throw SourceException(SourceErrorCode.INVALID_RESPONSE)
            val tracks = rows.map { owner.track(it.takeIf { value -> value.isJsonObject }?.asJsonObject
                ?: throw SourceException(SourceErrorCode.INVALID_RESPONSE)) }
            val mediaType = result.get("mediaType")?.takeUnless { it.isJsonNull }?.asString ?: "music"
            if (mediaType !in setOf("music", "episode")) throw SourceException(SourceErrorCode.INVALID_RESPONSE)
            if (token != generation || !sameOwner(expected, displayed)) throw CancellationException("Feed card owner changed")
            tracks to (mediaType == "episode")
        }
    }

    fun pagedQueue(value: SourceContentRequest, initialTracks: List<Track>, startIndex: Int, title: String?): Queue? {
        val current = state.value
        val tracks = current.items.filterIsInstance<SourceItem.TrackItem>().map { it.track }
        if (tracks.isEmpty() || startIndex !in tracks.indices || initialTracks.size != tracks.size ||
            tracks.indices.any { initialTracks[it].ref != tracks[it].ref } ||
            provider(current.providerId) !== displayedProvider || provider(current.providerId)?.version != current.providerVersion) return null
        val total = when (val detail = current.detail) {
            is SourceItem.PlaylistItem -> detail.playlist.trackCount
            is SourceItem.AlbumItem -> detail.album.trackCount
            else -> null
        }?.takeIf { it >= tracks.size && (!current.hasMore || it > tracks.size) }
        return when (value) {
            is SourceContentRequest.Detail -> if (value.ref is TrackRef || value.ref != current.detail?.ref) null else SourceContentQueue(
                router, value.ref, title, tracks, startIndex, current.hasMore, current.nextCursor, current.nextOffset,
                knownTotalCount = total, account = current.account, initialAccountRevision = current.accountRevision)
            is SourceContentRequest.Library -> if (value.collection.type != ContentType.TRACK ||
                value.providerId != null && value.providerId != current.providerId ||
                (request.value as? SourceContentRequest.Library)?.collection != value.collection) null else SourceContentQueue.forLibrary(
                router, current.providerId, value.collection, title, tracks, startIndex, current.hasMore,
                current.nextCursor, current.nextOffset, account = current.account, initialAccountRevision = current.accountRevision)
            else -> null
        }
    }

    fun canReorder(playlist: SourcePlaylist): Boolean {
        val current = state.value
        val editor = provider(playlist.ref.providerId) as? PlaylistEditCapability
        val tracks = current.items.filterIsInstance<SourceItem.TrackItem>()
        return current.detail?.ref == playlist.ref && !current.loading && !current.hasMore && current.error == null &&
            tracks.size == current.items.size && tracks.size == playlist.trackCount && tracks.isNotEmpty() &&
            tracks.map { it.ref }.distinct().size == tracks.size &&
            PlaylistEditAction.REORDER_TRACKS in playlist.editableActions &&
            PlaylistEditAction.REORDER_TRACKS in editor?.playlistEditActions.orEmpty()
    }

    /** Explicit selections already have metadata in the visible page or a completed Select All snapshot. */
    internal suspend fun selectedDetailTracks(ref: ContentRef, ids: Set<String>): SourceDetailTracks = detailTracksMutex.withLock {
        val displayed = state.value
        val snapshot = detailTracksCache?.takeIf { it.ref == ref }?.also { ensureDetailCurrent(it) }
            ?: detailSnapshot(ref, displayed).copy(tracks = displayed.items.filterIsInstance<SourceItem.TrackItem>().map { it.track })
        val selected = snapshot.tracks.filter { TrackRefCodec.mediaId(it.ref) in ids }
        if (ids.isEmpty() || selected.map { TrackRefCodec.mediaId(it.ref) }.toSet() != ids) {
            throw SourceException(SourceErrorCode.NOT_FOUND)
        }
        ensureDetailCurrent(snapshot)
        snapshot.copy(tracks = selected)
    }

    private suspend fun detailSnapshot(ref: ContentRef, displayed: SourceContentState): SourceDetailTracks {
        val owner = registry.requireProvider(ref.providerId)
        val token = generation
        val revision = router.contentRevision.value
        val account = if (owner !is AccountRevisionCapability && SourceCapability.ACCOUNT in owner.capabilities)
            router.account(ref.providerId) else displayed.account
        return SourceDetailTracks(ref, displayed.title, emptyList(), owner, displayed.providerVersion,
            displayed.accountRevision, account, token, revision).also { ensureDetailCurrent(it) }
    }

    /** Bulk actions share the playback paginator while keeping the visible list and its scroll position. */
    internal suspend fun detailTracks(ref: ContentRef, reload: Boolean = false): SourceDetailTracks = detailTracksMutex.withLock {
        if (!reload) detailTracksCache?.takeIf { it.ref == ref }?.let {
            ensureDetailCurrent(it)
            return@withLock it
        }
        val displayed = state.value
        val reloadDetail = reload || displayed.error != null
        val snapshot = detailSnapshot(ref, displayed)
        val detail = if (reloadDetail) when (ref) {
            is PlaylistRef -> SourceItem.PlaylistItem(router.playlist(ref))
            is AlbumRef -> SourceItem.AlbumItem(router.album(ref))
            is TrackRef -> SourceItem.TrackItem(router.getTrack(ref) ?: throw SourceException(SourceErrorCode.NOT_FOUND))
            else -> displayed.detail
        } else displayed.detail
        ensureDetailCurrent(snapshot)
        val initialTracks = if (reloadDetail) emptyList() else displayed.items.filterIsInstance<SourceItem.TrackItem>().map { it.track }
        val total = when (detail) {
            is SourceItem.PlaylistItem -> detail.playlist.trackCount
            is SourceItem.AlbumItem -> detail.album.trackCount
            else -> null
        }?.takeIf { it > 0 }
        val tracks = if (ref is TrackRef) listOf((detail as? SourceItem.TrackItem)?.track
            ?: throw SourceException(SourceErrorCode.INVALID_RESPONSE)) else {
            val queue = SourceContentQueue(router, ref, displayed.title, initialTracks,
                hasMore = reloadDetail || displayed.hasMore,
                nextCursor = if (reloadDetail) null else displayed.nextCursor,
                nextOffset = if (reloadDetail) 0 else displayed.nextOffset,
                knownTotalCount = total, account = snapshot.account,
                initialAccountRevision = displayed.accountRevision, mediaItemFactory = { null })
            try {
                while (queue.hasNextPage()) {
                    ensureDetailCurrent(snapshot)
                    when (val result = queue.nextPage()) {
                        is Queue.Result.Error -> throw result.exception ?: SourceException(SourceErrorCode.INVALID_RESPONSE)
                        is Queue.Result.Success -> Unit
                    }
                }
                queue.loadedTracks()
            } finally { queue.release() }
        }
        ensureDetailCurrent(snapshot)
        if (total != null && tracks.size != total) throw SourceException(SourceErrorCode.INVALID_RESPONSE)
        snapshot.copy(title = detail?.title ?: displayed.title, tracks = tracks,
            editableActions = (detail as? SourceItem.PlaylistItem)?.playlist?.editableActions.orEmpty()).also { detailTracksCache = it }
    }

    internal suspend fun ensureDetailCurrent(snapshot: SourceDetailTracks, includeContent: Boolean = true) {
        currentCoroutineContext().ensureActive()
        val revision = (snapshot.provider as? AccountRevisionCapability)?.accountRevision?.first()
        val account = if (snapshot.provider !is AccountRevisionCapability && SourceCapability.ACCOUNT in snapshot.provider.capabilities)
            router.account(snapshot.ref.providerId) else snapshot.account
        if (request.value != SourceContentRequest.Detail(snapshot.ref) || state.value.detail?.ref != snapshot.ref ||
            displayedProvider !== snapshot.provider || registry.provider(snapshot.ref.providerId) !== snapshot.provider || snapshot.provider.status != SourceStatus.READY ||
            snapshot.provider.version != snapshot.providerVersion || revision != snapshot.accountRevision ||
            account?.ref != snapshot.account?.ref || account?.sessionRevision != snapshot.account?.sessionRevision ||
            includeContent && (generation != snapshot.generation || router.contentRevision.value != snapshot.contentRevision)) {
            throw CancellationException("Detail source changed")
        }
    }

    internal suspend fun saveTrackOrder(snapshot: SourceDetailTracks, orderedRefs: List<TrackRef>): Boolean {
        ensureDetailCurrent(snapshot)
        val ref = snapshot.ref as? PlaylistRef ?: throw SourceException(SourceErrorCode.UNSUPPORTED)
        val original = snapshot.tracks.map { it.ref }
        if (orderedRefs.size != original.size || orderedRefs.toSet().size != orderedRefs.size || orderedRefs.toSet() != original.toSet()) {
            throw SourceException(SourceErrorCode.INVALID_RESPONSE)
        }
        if (orderedRefs == original) return true
        val latest = detailTracks(ref, reload = true)
        if (latest.tracks.map { it.ref } != original) return false
        if (PlaylistEditAction.REORDER_TRACKS !in latest.editableActions) throw SourceException(SourceErrorCode.UNSUPPORTED)
        ensureDetailCurrent(snapshot)
        try {
            router.reorderTracks(ref, orderedRefs, snapshot.accountRevision, snapshot.provider,
                expectedContentRevision = snapshot.contentRevision)
        } catch (error: SourceException) {
            if (error.code == SourceErrorCode.CANCELLED && router.contentRevision.value != snapshot.contentRevision) return false
            throw error
        }
        ensureDetailCurrent(snapshot, includeContent = false)
        return true
    }

    fun setLiked(track: TrackRef, liked: Boolean, expectedProvider: MusicProvider? = null, expectedAccountRevision: String? = null) =
        mutate(track.providerId, expectedProvider = expectedProvider, expectedAccountRevision = expectedAccountRevision, refreshPage = false) { owner, revision ->
            router.setLiked(track, liked, revision, owner)
        }
    fun setSaved(ref: ContentRef, saved: Boolean) = mutate(ref.providerId, refreshPage = false) { owner, revision -> router.setSaved(ref, saved, revision, owner) }
    suspend fun liked(ref: TrackRef): Boolean = router.liked(ref)
    suspend fun saved(ref: ContentRef): Boolean = router.saved(ref)
    fun createPlaylist(title: String, description: String? = null, privacy: Boolean = false, type: String = "NORMAL",
        expectedProvider: MusicProvider? = null, expectedAccountRevision: String? = null,
        onCreated: (SourcePlaylist) -> Unit = {}) {
        val id = state.value.providerId
        mutate(id, onCreated, expectedProvider, expectedAccountRevision) { owner, revision ->
            router.createPlaylist(id, title, description, privacy, type, revision, owner)
        }
    }
    fun updatePlaylist(ref: PlaylistRef, title: String, description: String?, onUpdated: () -> Unit = {}) =
        mutate(ref.providerId, { _: Unit -> onUpdated() }) { owner, revision -> router.updatePlaylist(ref, title, description, revision, owner) }
    fun deletePlaylist(ref: PlaylistRef, onDeleted: () -> Unit) =
        mutate(ref.providerId, { _: Unit -> onDeleted() }) { owner, revision -> router.deletePlaylist(ref, revision, owner) }
    fun addTracks(ref: PlaylistRef, tracks: List<TrackRef>, onAdded: () -> Unit = {}) =
        mutate(ref.providerId, { _: Unit -> onAdded() }) { owner, revision -> router.addTracks(ref, tracks, revision, owner) }
    fun removeTrack(ref: PlaylistRef, track: TrackRef) {
        val playlist = (state.value.detail as? SourceItem.PlaylistItem)?.playlist
        if (playlist == null || playlist.ref != ref || PlaylistEditAction.REMOVE_TRACKS !in playlist.editableActions) {
            mutableMessage.value = SourceErrorCode.UNSUPPORTED
            return
        }
        mutate(ref.providerId) { owner, revision -> router.removeTracks(ref, listOf(track), revision, owner) }
    }
    fun reorderTracks(ref: PlaylistRef, tracks: List<TrackRef>, onReordered: () -> Unit = {}) {
        val playlist = (state.value.detail as? SourceItem.PlaylistItem)?.playlist
        val original = state.value.items.map { it.ref }
        if (playlist == null || playlist.ref != ref || !canReorder(playlist) ||
            tracks.size != original.size || tracks.toSet().size != tracks.size || tracks.toSet() != original.toSet()) {
            mutableMessage.value = SourceErrorCode.UNSUPPORTED
            return
        }
        mutate(ref.providerId, { _: Unit -> onReordered() }) { owner, revision -> router.reorderTracks(ref, tracks, revision, owner) }
    }
    fun webLogin(providerId: String) {
        if (mutableBusy.value) return
        val requested = request.value
        val owner = registry.provider(providerId) ?: run { mutableMessage.value = SourceErrorCode.MISSING_PROVIDER; return }
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                if (registry.provider(providerId) !== owner) throw CancellationException("Source changed before login")
                val bound = (features ?: throw SourceException(SourceErrorCode.UNSUPPORTED))
                    .bind(providerId, "source-web-login:${java.util.UUID.randomUUID()}")
                if (registry.provider(providerId) !== owner) throw CancellationException("Source changed while opening login")
                bound.webLogin()
                preferences.update { settings ->
                    settings.copy(fallbackOrder = (settings.fallbackOrder + registry.playable().map { it.instanceId.value }).distinct())
                }
                if (request.value == requested && registry.provider(providerId) === owner) refresh()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (request.value == requested && registry.provider(providerId) === owner) mutableMessage.value = error.sourceCode()
            } finally { mutableBusy.value = false }
        }
    }

    fun signOut(providerId: String) = mutate(providerId) { owner, revision ->
        if ("logout" in (owner as? SourceFeatureCapability)?.features?.get("login").orEmpty()) {
            val version = owner.version
            val bound = (features ?: throw SourceException(SourceErrorCode.UNSUPPORTED))
                .bind(providerId, "source-logout:${java.util.UUID.randomUUID()}")
            if (registry.provider(providerId) !== owner || owner.version != version ||
                (owner as? AccountRevisionCapability)?.accountRevision?.first() != revision) {
                throw CancellationException("Source changed while opening logout")
            }
            bound.logout()
        } else {
            val pluginId = pluginId(providerId) ?: throw SourceException(SourceErrorCode.UNSUPPORTED)
            configurePlugin(pluginId, emptyMap())
        }
    }

    private fun <T> mutate(id: String, onSuccess: (T) -> Unit = {}, expectedProvider: MusicProvider? = null,
        expectedAccountRevision: String? = null, refreshPage: Boolean = true, action: suspend (MusicProvider, String?) -> T) {
        if (mutableBusy.value) return
        val requested = request.value
        val provider = provider(id) ?: run { mutableMessage.value = SourceErrorCode.MISSING_PROVIDER; return }
        if (expectedProvider != null && provider !== expectedProvider) return
        val version = provider.version
        val displayed = state.value
        if (expectedProvider == null && displayed.providerId == id && displayed.providerVersion != version) return
        val loadedRevision = if (expectedProvider != null) expectedAccountRevision else if (displayed.providerId == id)
            displayed.accountRevision else ((provider as? AccountRevisionCapability)?.accountRevision as? kotlinx.coroutines.flow.StateFlow<String?>)?.value
        val pageProvider = state.value.providerId
        val contentRevision = router.contentRevision.value
        val registryRevision = registry.revision.value
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                val revisionCapability = provider as? AccountRevisionCapability
                val revision = revisionCapability?.accountRevision?.first()
                if (revision != loadedRevision || request.value != requested ||
                    registry.provider(id) !== provider || provider.version != version) throw CancellationException("Source changed before editing")
                val result = action(provider, loadedRevision)
                currentCoroutineContext().ensureActive()
                if (request.value == requested && state.value.providerId == pageProvider && registry.provider(id) === provider &&
                    provider.version == version && (revisionCapability == null || revisionCapability.accountRevision.first() == revision)) {
                    onSuccess(result)
                    if (refreshPage && router.contentRevision.value == contentRevision && registry.revision.value == registryRevision) refresh()
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (request.value == requested && state.value.providerId == pageProvider && registry.provider(id) === provider &&
                    provider.version == version && (loadedRevision == null || state.value.accountRevision == loadedRevision)) {
                    mutableMessage.value = error.sourceCode()
                }
            } finally { mutableBusy.value = false }
        }
    }

    private suspend fun sameOwner(provider: MusicProvider, snapshot: SourceContentState): Boolean =
        registry.provider(snapshot.providerId) === provider && provider.version == snapshot.providerVersion &&
            ((provider as? AccountRevisionCapability)?.accountRevision?.first() == snapshot.accountRevision)

    private data class PageSelection(val request: SourceContentRequest?, val providerId: String, val registryRevision: Long,
        val contentRevision: Long, val refresh: Long)
}

internal fun Throwable.sourceCode(): SourceErrorCode = (this as? SourceException)?.code ?: SourceErrorCode.NETWORK

/** Repeated recordings remain valid when a cursor or a finite declared total advances the page. */
internal fun validateSourceContentPage(
    result: SourceContentState,
    requested: PageRequest,
    incomingRefs: List<ContentRef>,
    received: Int,
    append: Boolean,
    seenPages: Set<List<ContentRef>>,
    seenCursors: Set<String>,
): SourceContentState {
    if (!result.hasMore) return result
    val total = when (val detail = result.detail) {
        is SourceItem.PlaylistItem -> detail.playlist.trackCount
        is SourceItem.AlbumItem -> detail.album.trackCount
        else -> null
    }?.takeIf { it > 0 }
    val repeatedOffsetPage = append && result.nextCursor == null && total == null && incomingRefs in seenPages
    val repeatedCursor = result.nextCursor != null &&
        (result.nextCursor == requested.cursor || result.nextCursor in seenCursors)
    if (received == 0 || incomingRefs.isEmpty() || repeatedOffsetPage || repeatedCursor) {
        throw SourceException(SourceErrorCode.INVALID_RESPONSE)
    }
    return if (total != null && result.items.size >= total) result.copy(hasMore = false) else result
}

private fun SourceContentRequest?.providerId(preferred: String): String = when (this) {
    is SourceContentRequest.Feed -> providerId ?: preferred
    is SourceContentRequest.Detail -> ref.providerId
    is SourceContentRequest.ArtistInformation -> ref.providerId
    is SourceContentRequest.Account -> providerId
    is SourceContentRequest.Library -> providerId ?: preferred
    is SourceContentRequest.Editor -> providerId
    else -> preferred
}
private fun albumSections(albums: List<SourceAlbumInfo>): List<SourceFeedSection> =
    if (albums.isEmpty()) emptyList() else listOf(SourceFeedSection("artist-albums", "专辑", SourceFeedLayout.ALBUMS,
        albums.distinctBy { it.ref }.map { SourceItem.AlbumItem(it) }))
private fun mergeTrackItems(previous: List<SourceItem>, incoming: List<Track>, append: Boolean): List<SourceItem> =
    (if (append) previous else emptyList()) + incoming.map { SourceItem.TrackItem(it) }
private fun mergeItems(previous: List<SourceItem>, incoming: List<SourceItem>, append: Boolean): List<SourceItem> =
    ((if (append) previous else emptyList()) + incoming).distinctBy { it.ref }
private fun mergeSections(previous: List<SourceFeedSection>, incoming: List<SourceFeedSection>): List<SourceFeedSection> {
    val merged = previous.toMutableList()
    incoming.forEach { section ->
        val index = merged.indexOfFirst { it.id == section.id }
        if (index < 0) merged += section
        else merged[index] = section.copy(items = (merged[index].items + section.items).distinctBy { it.ref },
            cards = (merged[index].cards + section.cards).distinctBy { it.id })
    }
    return merged
}
