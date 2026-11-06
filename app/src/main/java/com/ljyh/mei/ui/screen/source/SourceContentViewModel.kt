package com.ljyh.mei.ui.screen.source

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.playback.queue.Queue
import com.ljyh.mei.playback.queue.SourceContentQueue
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.ProviderRegistry
import com.ljyh.mei.source.core.SourcePreferences
import com.ljyh.mei.source.core.SourceRouter
import com.ljyh.mei.source.script.ScriptPluginManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed interface SourceContentRequest {
    data class Feed(val kind: FeedKind, val categoryId: String? = null) : SourceContentRequest
    data class Library(val collection: LibraryCollection, val providerId: String? = null) : SourceContentRequest
    data class Detail(val ref: ContentRef) : SourceContentRequest
    data class Account(val providerId: String) : SourceContentRequest
    data class Editor(val providerId: String) : SourceContentRequest
}

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
class SourceContentViewModel @Inject constructor(
    private val router: SourceRouter,
    private val preferences: SourcePreferences,
    private val registry: ProviderRegistry,
    private val plugins: ScriptPluginManager,
) : ViewModel() {
    val providers = registry.providers
    val contentRevision = router.contentRevision
    val installedPlugins = plugins.installed
    val selectedProviderId = preferences.settings.map { it.preferredBrowseProvider }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    private val request = MutableStateFlow<SourceContentRequest?>(null)
    private val refreshVersion = MutableStateFlow(0L)
    private val mutableState = MutableStateFlow(SourceContentState())
    val state = mutableState.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableMessage = MutableStateFlow<SourceErrorCode?>(null)
    val message = mutableMessage.asStateFlow()
    private var generation = 0L
    private var displayedRequest: SourceContentRequest? = null
    private var pagination: Job? = null
    private var albumPagination: Job? = null
    private val seenPages = mutableSetOf<List<ContentRef>>()
    private val seenCursors = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            combine(request, selectedProviderId, registry.revision, router.contentRevision, refreshVersion) {
                    requested, preferred, registryRevision, contentRevision, refresh ->
                PageSelection(requested, requested.providerId(preferred), registryRevision, contentRevision, refresh)
            }.distinctUntilChanged().collectLatest { selection ->
                invalidatePage()
                val requested = selection.request ?: return@collectLatest
                val id = selection.providerId.takeIf(String::isNotBlank) ?: return@collectLatest
                val provider = registry.provider(id)
                val revisionFlow: Flow<String?> = (provider as? AccountRevisionCapability)?.accountRevision ?: flowOf(null)
                try {
                    revisionFlow.distinctUntilChanged().collectLatest { accountRevision ->
                        invalidatePage()
                        val token = generation
                        val previous = state.value
                        val sameOwner = displayedRequest == requested && previous.providerId == id &&
                            previous.providerVersion == provider?.version.orEmpty() && previous.accountRevision == accountRevision
                        if (!sameOwner) mutableMessage.value = null
                        mutableState.value = if (sameOwner) previous.copy(loading = true, error = null,
                            albumsLoading = false, albumsError = null) else SourceContentState(providerId = id,
                            providerVersion = provider?.version.orEmpty(), accountRevision = accountRevision, loading = true)
                        displayedRequest = requested
                        load(requested, id, token, append = false)
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
        pagination?.cancel()
        albumPagination?.cancel()
        seenPages.clear()
        seenCursors.clear()
    }
    fun open(value: SourceContentRequest) { request.value = value }
    fun prepareEditor(providerId: String) { request.value = SourceContentRequest.Editor(providerId) }
    fun refresh() { refreshVersion.value++ }
    fun selectProvider(id: String) { viewModelScope.launch { preferences.update { it.copy(preferredBrowseProvider = id) } } }
    fun dismissMessage() { mutableMessage.value = null }
    fun provider(id: String = state.value.providerId): MusicProvider? = registry.provider(id)
    fun pluginId(id: String = state.value.providerId): String? = plugins.installed.value
        .firstOrNull { it.manifest.providerId == id }?.manifest?.id

    fun loadMore() {
        val current = mutableState.value
        val requested = request.value ?: return
        if (!current.hasMore || current.loading || pagination?.isActive == true) return
        val token = generation
        mutableState.value = current.copy(loading = true, error = null)
        pagination = viewModelScope.launch { load(requested, current.providerId, token, append = true) }
    }

    private suspend fun load(requested: SourceContentRequest, id: String, token: Long, append: Boolean) {
        val previous = mutableState.value
        val page = PageRequest(if (append) previous.nextOffset else 0, 30, if (append) previous.nextCursor else null)
        var received = 0
        var incomingRefs = emptyList<ContentRef>()
        try {
            val boundProvider = registry.requireProvider(id)
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
                        router.account(id) else null
                    val collection = router.library(id, requested.collection, page)
                    received = collection.items.size
                    incomingRefs = collection.items.map { it.ref }
                    previous.copy(account = account, items = mergeItems(previous.items, collection.items, append),
                        hasMore = collection.hasMore, nextCursor = collection.nextCursor)
                }
                is SourceContentRequest.Detail -> when (val ref = requested.ref) {
                    is PlaylistRef -> {
                        val info = if (append) (previous.detail as? SourceItem.PlaylistItem)?.playlist ?: router.playlist(ref) else router.playlist(ref)
                        val tracks = router.playlistTracks(ref, page)
                        received = tracks.items.size
                        incomingRefs = tracks.items.map { it.ref }
                        previous.copy(title = info.title, artworkUrl = info.artworkUrl, description = info.description,
                            detail = SourceItem.PlaylistItem(info), items = mergeTrackItems(previous.items, tracks.items, append),
                            hasMore = tracks.hasMore, nextCursor = tracks.nextCursor)
                    }
                    is AlbumRef -> {
                        val info = if (append) (previous.detail as? SourceItem.AlbumItem)?.album ?: router.album(ref) else router.album(ref)
                        val tracks = router.albumTracks(ref, page)
                        received = tracks.items.size
                        incomingRefs = tracks.items.map { it.ref }
                        previous.copy(title = info.title, artworkUrl = info.artworkUrl, description = info.description,
                            detail = SourceItem.AlbumItem(info), items = mergeTrackItems(previous.items, tracks.items, append),
                            hasMore = tracks.hasMore, nextCursor = tracks.nextCursor)
                    }
                    is ArtistRef -> {
                        val info = if (append) (previous.detail as? SourceItem.ArtistItem)?.artist ?: router.artist(ref) else router.artist(ref)
                        val tracks = router.artistTracks(ref, page)
                        received = tracks.items.size
                        incomingRefs = tracks.items.map { it.ref }
                        var albumsError: SourceErrorCode? = null
                        val albums = if (append) null else try { optionalArtistAlbums(ref) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { albumsError = error.sourceCode(); null }
                        previous.copy(title = info.name, artworkUrl = info.artworkUrl, description = info.description,
                            detail = SourceItem.ArtistItem(info), items = mergeTrackItems(previous.items, tracks.items, append),
                            sections = if (append) previous.sections else albumSections(albums?.items.orEmpty()),
                            albumsHasMore = if (append) previous.albumsHasMore else albums?.hasMore == true || albumsError != null,
                            albumsError = if (append) previous.albumsError else albumsError,
                            albumsNextCursor = if (append) previous.albumsNextCursor else albums?.nextCursor,
                            albumsNextOffset = if (append) previous.albumsNextOffset else albums?.items?.size ?: 0,
                            hasMore = tracks.hasMore, nextCursor = tracks.nextCursor)
                    }
                    is TrackRef -> {
                        val track = router.getTrack(ref) ?: throw SourceException(SourceErrorCode.NOT_FOUND)
                        received = 1
                        previous.copy(title = track.title, artworkUrl = track.artworkUrl,
                            detail = SourceItem.TrackItem(track), items = listOf(SourceItem.TrackItem(track)), hasMore = false, nextCursor = null)
                    }
                }
                is SourceContentRequest.Account -> previous.copy(account = router.account(id))
                is SourceContentRequest.Editor -> {
                    val editor = boundProvider as? PlaylistEditCapability
                    if (SourceCapability.PLAYLIST_EDIT !in boundProvider.capabilities ||
                        PlaylistEditAction.CREATE !in editor?.playlistEditActions.orEmpty()) throw SourceException(SourceErrorCode.UNSUPPORTED)
                    previous
                }
            }
            currentCoroutineContext().ensureActive()
            if (token != generation || !sameOwner(boundProvider, previous)) return
            val progressed = validateSourceContentPage(result, page, incomingRefs, received, append, seenPages, seenCursors)
            if (incomingRefs.isNotEmpty()) seenPages += incomingRefs
            result.nextCursor?.let(seenCursors::add)
            val latest = state.value
            val updated = if (append && requested is SourceContentRequest.Detail && requested.ref is ArtistRef) progressed.copy(
                sections = latest.sections, albumsHasMore = latest.albumsHasMore, albumsNextCursor = latest.albumsNextCursor,
                albumsNextOffset = latest.albumsNextOffset, albumsLoading = latest.albumsLoading, albumsError = latest.albumsError,
            ) else progressed
            mutableState.value = updated.copy(providerId = id, loading = false, error = null, nextOffset = page.offset + received)
        } catch (cancelled: CancellationException) {
            if (token == generation) mutableState.value = state.value.copy(loading = false)
            throw cancelled
        } catch (error: Exception) {
            if (token == generation) mutableState.value = state.value.copy(loading = false, error = error.sourceCode(),
                hasMore = state.value.hasMore && error.sourceCode() != SourceErrorCode.INVALID_RESPONSE)
        }
    }

    private suspend fun optionalArtistAlbums(ref: ArtistRef): SourcePage<SourceAlbumInfo>? = try {
        router.artistAlbums(ref, PageRequest(limit = 30))
    } catch (error: SourceException) {
        if (error.code == SourceErrorCode.UNSUPPORTED) null else throw error
    }

    fun loadMoreArtistAlbums() {
        val ref = ((request.value as? SourceContentRequest.Detail)?.ref as? ArtistRef) ?: return
        val current = state.value
        if (!current.albumsHasMore || current.albumsLoading || current.loading) return
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
                if (albums.hasMore && albums.nextCursor == page.cursor && merged == latest.sections) {
                    throw SourceException(SourceErrorCode.INVALID_RESPONSE)
                }
                mutableState.value = latest.copy(sections = merged, albumsLoading = false, albumsError = null,
                    albumsHasMore = albums.hasMore, albumsNextCursor = albums.nextCursor,
                    albumsNextOffset = page.offset + albums.items.size)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (token == generation) mutableState.value = state.value.copy(albumsLoading = false, albumsError = error.sourceCode())
            }
        }
    }

    fun pagedQueue(value: SourceContentRequest, initialTracks: List<Track>, startIndex: Int, title: String?): Queue? {
        val current = state.value
        val tracks = current.items.filterIsInstance<SourceItem.TrackItem>().map { it.track }
        if (tracks.isEmpty() || startIndex !in tracks.indices || initialTracks.map { it.ref } != tracks.map { it.ref } ||
            provider(current.providerId)?.version != current.providerVersion) return null
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

    fun setLiked(track: TrackRef, liked: Boolean, expectedProvider: MusicProvider? = null, expectedAccountRevision: String? = null) =
        mutate(track.providerId, expectedProvider = expectedProvider, expectedAccountRevision = expectedAccountRevision) {
            router.setLiked(track, liked)
        }
    fun setSaved(ref: ContentRef, saved: Boolean) = mutate(ref.providerId) { router.setSaved(ref, saved) }
    suspend fun liked(ref: TrackRef): Boolean = router.liked(ref)
    suspend fun saved(ref: ContentRef): Boolean = router.saved(ref)
    fun createPlaylist(title: String, description: String? = null, onCreated: (SourcePlaylist) -> Unit = {}) {
        val id = state.value.providerId
        mutate(id, onCreated) { router.createPlaylist(id, title, description) }
    }
    fun updatePlaylist(ref: PlaylistRef, title: String, description: String?, onUpdated: () -> Unit = {}) =
        mutate(ref.providerId, { _: Unit -> onUpdated() }) { router.updatePlaylist(ref, title, description) }
    fun deletePlaylist(ref: PlaylistRef, onDeleted: () -> Unit) =
        mutate(ref.providerId, { _: Unit -> onDeleted() }) { router.deletePlaylist(ref) }
    fun addTracks(ref: PlaylistRef, tracks: List<TrackRef>, onAdded: () -> Unit = {}) =
        mutate(ref.providerId, { _: Unit -> onAdded() }) { router.addTracks(ref, tracks) }
    fun removeTrack(ref: PlaylistRef, track: TrackRef) {
        val playlist = (state.value.detail as? SourceItem.PlaylistItem)?.playlist
        if (playlist == null || playlist.ref != ref || PlaylistEditAction.REMOVE_TRACKS !in playlist.editableActions) {
            mutableMessage.value = SourceErrorCode.UNSUPPORTED
            return
        }
        mutate(ref.providerId) { router.removeTracks(ref, listOf(track)) }
    }
    fun reorderTracks(ref: PlaylistRef, tracks: List<TrackRef>, onReordered: () -> Unit = {}) {
        val playlist = (state.value.detail as? SourceItem.PlaylistItem)?.playlist
        val original = state.value.items.map { it.ref }
        if (playlist == null || playlist.ref != ref || !canReorder(playlist) ||
            tracks.size != original.size || tracks.toSet().size != tracks.size || tracks.toSet() != original.toSet()) {
            mutableMessage.value = SourceErrorCode.UNSUPPORTED
            return
        }
        mutate(ref.providerId, { _: Unit -> onReordered() }) { router.reorderTracks(ref, tracks) }
    }
    fun signOut(providerId: String) = mutate(providerId) {
        val pluginId = pluginId(providerId) ?: throw SourceException(SourceErrorCode.UNSUPPORTED)
        plugins.configure(pluginId, emptyMap())
    }

    private fun <T> mutate(id: String, onSuccess: (T) -> Unit = {}, expectedProvider: MusicProvider? = null,
        expectedAccountRevision: String? = null, action: suspend () -> T) {
        if (mutableBusy.value) return
        val requested = request.value
        val provider = provider(id) ?: run { mutableMessage.value = SourceErrorCode.MISSING_PROVIDER; return }
        if (expectedProvider != null && provider !== expectedProvider) return
        val version = provider.version
        val loadedRevision = expectedAccountRevision ?: state.value.accountRevision.takeIf { state.value.providerId == id }
        val pageProvider = state.value.providerId
        val contentRevision = router.contentRevision.value
        val registryRevision = registry.revision.value
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                val revisionCapability = provider as? AccountRevisionCapability
                val revision = revisionCapability?.accountRevision?.first()
                if (loadedRevision != null && revision != loadedRevision || request.value != requested ||
                    registry.provider(id) !== provider || provider.version != version) throw CancellationException("Source changed before editing")
                val result = action()
                currentCoroutineContext().ensureActive()
                if (request.value == requested && state.value.providerId == pageProvider && registry.provider(id) === provider &&
                    provider.version == version && (revisionCapability == null || revisionCapability.accountRevision.first() == revision)) {
                    onSuccess(result)
                    if (router.contentRevision.value == contentRevision && registry.revision.value == registryRevision) refresh()
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
    is SourceContentRequest.Detail -> ref.providerId
    is SourceContentRequest.Account -> providerId
    is SourceContentRequest.Library -> providerId ?: preferred
    is SourceContentRequest.Editor -> providerId
    else -> preferred
}
private fun albumSections(albums: List<SourceAlbumInfo>): List<SourceFeedSection> =
    if (albums.isEmpty()) emptyList() else listOf(SourceFeedSection("artist-albums", "专辑", SourceFeedLayout.ALBUMS,
        albums.map { SourceItem.AlbumItem(it) }))
private fun mergeTrackItems(previous: List<SourceItem>, incoming: List<Track>, append: Boolean): List<SourceItem> =
    (if (append) previous else emptyList()) + incoming.map { SourceItem.TrackItem(it) }
private fun mergeItems(previous: List<SourceItem>, incoming: List<SourceItem>, append: Boolean): List<SourceItem> =
    ((if (append) previous else emptyList()) + incoming).distinctBy { it.ref }
private fun mergeSections(previous: List<SourceFeedSection>, incoming: List<SourceFeedSection>): List<SourceFeedSection> {
    val merged = previous.toMutableList()
    incoming.forEach { section ->
        val index = merged.indexOfFirst { it.id == section.id }
        if (index < 0) merged += section
        else merged[index] = section.copy(items = (merged[index].items + section.items).distinctBy { it.ref })
    }
    return merged
}
