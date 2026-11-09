package com.ljyh.mei.playback.queue

import androidx.media3.common.MediaItem
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.playback.SourceContentQueueSnapshot
import com.ljyh.mei.playback.QueueSourceSession
import com.ljyh.mei.source.api.AccountRevisionCapability
import com.ljyh.mei.source.api.AlbumRef
import com.ljyh.mei.source.api.ArtistRef
import com.ljyh.mei.source.api.ContentRef
import com.ljyh.mei.source.api.ContentType
import com.ljyh.mei.source.api.LibraryCollection
import com.ljyh.mei.source.api.PageRequest
import com.ljyh.mei.source.api.PlaylistRef
import com.ljyh.mei.source.api.SourceAccount
import com.ljyh.mei.source.api.SourceCapability
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.api.SourceItem
import com.ljyh.mei.source.api.SourcePage
import com.ljyh.mei.source.api.SourceStatus
import com.ljyh.mei.source.api.Track
import com.ljyh.mei.source.api.TrackRef
import com.ljyh.mei.source.api.TrackRefCodec
import com.ljyh.mei.source.api.type
import com.ljyh.mei.source.core.SourceRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** A selected content prefix starts immediately; only the next playback page is requested. */
class SourceContentQueue private constructor(
    private val router: SourceRouter,
    val providerId: String,
    val contentRef: ContentRef?,
    val libraryCollection: LibraryCollection?,
    override val title: String?,
    initialTracks: List<Track>,
    val startIndex: Int,
    hasMore: Boolean,
    nextCursor: String?,
    nextOffset: Int,
    private val pageSize: Int,
    private val knownTotalCount: Int?,
    private val account: SourceAccount?,
    initialAccountRevision: String?,
    private val mediaItemFactory: (Track) -> MediaItem?,
    private val recoveryContext: SourceContentQueueSnapshot? = null,
) : Queue {
    constructor(
        router: SourceRouter,
        contentRef: ContentRef,
        title: String?,
        initialTracks: List<Track> = emptyList(),
        startIndex: Int = 0,
        hasMore: Boolean = true,
        nextCursor: String? = null,
        nextOffset: Int = initialTracks.size,
        pageSize: Int = 30,
        knownTotalCount: Int? = null,
        account: SourceAccount? = null,
        initialAccountRevision: String? = null,
        mediaItemFactory: (Track) -> MediaItem? = { it.toMediaMetadata().toMediaItem() },
    ) : this(router, contentRef.providerId, contentRef, null, title, initialTracks, startIndex,
        hasMore, nextCursor, nextOffset, pageSize, knownTotalCount, account, initialAccountRevision, mediaItemFactory)

    override val id: String = "source-content:${providerId.length}:$providerId:" +
        (contentRef?.let { "${it.type.wireName}:${it.contentId}" } ?: "library:${libraryCollection?.name}")
    override val preloadItem: String? = null
    override val playlistSource: PlaylistQueueSource? = when (val ref = contentRef) {
        is PlaylistRef -> PlaylistQueueSource(ref.playlistId.toLongOrNull() ?: 0L,
            providerId = providerId, sourcePlaylistId = ref.playlistId)
        null -> libraryCollection?.takeIf { it == LibraryCollection.LIKED_TRACKS }?.let {
            PlaylistQueueSource(0L, providerId = providerId, libraryCollection = it)
        }
        else -> null
    }
    private val lock = Any()
    private val tracks = initialTracks.toMutableList()
    private val boundProvider = router.registry.provider(providerId)
    private val boundVersion = boundProvider?.version
    private val revisionProvider = boundProvider as? AccountRevisionCapability
    private var accountRevision: String? = initialAccountRevision ?:
        account?.sessionRevision?.takeIf { revisionProvider != null }
    private var accountBound = account != null
    private var boundAccount = account
    private var more = hasMore && (nextCursor != null || recoveryContext?.hasMore == true ||
        knownTotalCount == null || tracks.size < knownTotalCount)
    private var cursor = nextCursor
    private var offset = nextOffset
    private var generation = 0L
    private var released = false
    private var cloudOrdered = recoveryContext?.hasMore == false
    private var terminalFailure: Throwable? = null
    private var recoveryPending = recoveryContext != null
    private var canonicalShuffleRefs: List<TrackRef>? = null
    private var canonicalShuffleOwner: QueueSourceSession? = null
    private var flight: PageFlight? = null
    private val seenCursors = mutableSetOf<String>().apply { nextCursor?.let(::add) }
    private val seenPages = mutableSetOf<List<com.ljyh.mei.source.api.TrackRef>>().apply {
        if (initialTracks.isNotEmpty()) add(initialTracks.takeLast(pageSize).map { it.ref })
    }
    private val mutableState = MutableStateFlow<Queue.QueueState>(Queue.QueueState.Idle)
    override val state: StateFlow<Queue.QueueState> = mutableState
    override val totalCount: Int get() = synchronized(lock) { maxOf(knownTotalCount ?: tracks.size, tracks.size) }
    override val loadedCount: Int get() = synchronized(lock) { tracks.size }

    internal fun loadedTracks(): List<Track> = synchronized(lock) { tracks.toList() }
    internal val hasCloudOrder: Boolean get() = synchronized(lock) { cloudOrdered }
    internal val playbackPageSize: Int get() = pageSize

    /** Only an explicit complete source identity response can populate this lifecycle cache. */
    internal suspend fun canonicalShuffleInput(): Pair<List<TrackRef>, QueueSourceSession?>? {
        if (!isSourceCurrent()) staleAccount()
        val input = synchronized(lock) { canonicalShuffleRefs?.let { it to canonicalShuffleOwner } }
        try {
            input?.second?.ensureCurrent()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            synchronized(lock) { clearCanonicalShuffleInputLocked() }
            throw error
        }
        return synchronized(lock) {
            if (released || !providerMatches()) staleAccount()
            input
        }
    }

    internal suspend fun rememberCanonicalShuffleInput(refs: List<TrackRef>, owner: QueueSourceSession) {
        if (refs.isEmpty() || refs.any { it.providerId != providerId } || owner.providerId != providerId) invalidPage()
        owner.ensureCurrent()
        if (!isSourceCurrent()) staleAccount()
        synchronized(lock) {
            if (released || !providerMatches()) staleAccount()
            canonicalShuffleRefs = refs.distinct()
            canonicalShuffleOwner = owner
        }
    }

    /** Local shuffle needs the complete collection, while sequential playback stays paged. */
    internal suspend fun loadAll(): Queue.Status {
        synchronized(lock) { terminalFailure }?.let { throw it }
        while (hasNextPage()) {
            currentCoroutineContext().ensureActive()
            when (val result = nextPage(100)) {
                is Queue.Result.Error -> throw result.exception ?: SourceException(SourceErrorCode.UNAVAILABLE)
                is Queue.Result.Success -> Unit
            }
        }
        if (!isSourceCurrent()) throw SourceException(SourceErrorCode.AUTH_EXPIRED)
        synchronized(lock) {
            terminalFailure?.let { throw it }
        }
        return getInitialStatus()
    }

    init {
        require(contentRef is AlbumRef || contentRef is ArtistRef || contentRef is PlaylistRef ||
            contentRef == null && libraryCollection?.type == ContentType.TRACK)
        require(providerId.isNotBlank() && pageSize in 1..100 && nextOffset >= initialTracks.size)
        require(startIndex >= 0 && (initialTracks.isEmpty() || startIndex < initialTracks.size))
        require(knownTotalCount == null || knownTotalCount >= 0)
        require(initialTracks.all { it.ref.providerId == providerId })
        require(account == null || account.ref.instanceId == boundProvider?.instanceId)
    }

    override suspend fun getInitialStatus(): Queue.Status {
        // Bind an account before playback, without reloading the already visible tracks.
        if (hasNextPage() || playlistSource != null) {
            try {
                withContext(Dispatchers.IO) { checkAccountAndProvider() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                stopPagination()
            }
        }
        if (loadedCount == 0 && hasNextPage()) {
            when (val result = nextPage()) {
                is Queue.Result.Error -> throw result.exception ?: SourceException(SourceErrorCode.UNAVAILABLE)
                is Queue.Result.Success -> Unit
            }
        }
        return synchronized(lock) {
            Queue.Status(title, itemsLocked(), startIndex).also { publishLoadedLocked(it.ids) }
        }
    }

    override fun hasNextPage(): Boolean = synchronized(lock) {
        if (!providerMatches()) {
            more = false
            clearCanonicalShuffleInputLocked()
        }
        !released && more
    }

    /** Concurrent callers share one request and never advance two pages for one boundary. */
    override suspend fun nextPage(): Queue.Result<List<String>> = nextPage(pageSize)

    private suspend fun nextPage(limit: Int): Queue.Result<List<String>> = coroutineScope {
        val (request, owner) = synchronized(lock) {
            flight?.let { return@synchronized it to false }
            if (released || !more || !providerMatches()) {
                more = false
                return@coroutineScope Queue.Result.Success(emptyList())
            }
            PageFlight(generation).also {
                flight = it
                mutableState.value = Queue.QueueState.Loading
            } to true
        }
        if (!owner) return@coroutineScope request.result.await()
        val requestJob = currentCoroutineContext()[Job]
        synchronized(lock) {
            request.job = requestJob
            if (request.generation != generation || released) request.job?.cancel()
        }
        fun failedPage(error: Exception): Queue.Result.Error {
            val result = Queue.Result.Error("音源队列加载失败", error)
            synchronized(lock) {
                if (request.generation == generation && !released) {
                    if (error is SourceException && error.code in terminalErrors) {
                        more = false
                        terminalFailure = error
                    }
                    mutableState.value = Queue.QueueState.Error(result.message)
                }
            }
            request.result.complete(result)
            return result
        }
        try {
            val result = withContext(Dispatchers.IO) { loadPage(request, limit) }
            request.result.complete(result)
            result
        } catch (error: CancellationException) {
            // A retired source can reject a read without cancelling this queue's caller.
            // Preserve its loaded audio and stop pagination just like an explicit stale-session response.
            val retiredOwner = try {
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.IO) { checkAccountAndProvider() }
                null
            } catch (cancelled: CancellationException) {
                request.result.cancel(cancelled)
                throw cancelled
            } catch (failure: SourceException) { failure }
            if (retiredOwner == null) {
                request.result.cancel(error)
                throw error
            }
            failedPage(retiredOwner)
        } catch (error: Exception) {
            failedPage(error)
        } finally {
            synchronized(lock) { if (flight === request) flight = null }
        }
    }

    private suspend fun loadPage(request: PageFlight, limit: Int): Queue.Result<List<String>> {
        checkAccountAndProvider()
        recoverCursor(request)
        val pageRequest = synchronized(lock) {
            if (!more) return Queue.Result.Success(emptyList())
            PageRequest(offset, limit, cursor)
        }
        val page = fetchPage(pageRequest)
        currentCoroutineContext().ensureActive()
        checkAccountAndProvider()
        val items = page.items.map { track -> TrackRefCodec.mediaId(track.ref) to mediaItemFactory(track) }
        return synchronized(lock) {
            if (released || request.generation != generation || !providerMatches()) {
                more = false
                return@synchronized Queue.Result.Success(emptyList())
            }
            if (page.items.size > pageRequest.limit || page.items.any { it.ref.providerId != providerId }) invalidPage()
            val refs = page.items.map { it.ref }
            val repeatedOffsetPage = page.nextCursor == null && knownTotalCount == null && refs in seenPages
            if (page.hasMore && (refs.isEmpty() || repeatedOffsetPage ||
                    page.nextCursor != null && page.nextCursor in seenCursors)) invalidPage()
            tracks.addAll(page.items)
            offset += page.items.size
            cursor = page.nextCursor
            page.nextCursor?.let(seenCursors::add)
            if (refs.isNotEmpty()) seenPages.add(refs)
            more = page.hasMore && page.items.isNotEmpty() &&
                (page.nextCursor != null || knownTotalCount == null || tracks.size < knownTotalCount)
            publishLoadedLocked(itemsLocked())
            Queue.Result.Success(items.map { it.first })
        }
    }

    /** Rebuild opaque cursors in memory and prove that the restored prefix still owns this page. */
    private suspend fun recoverCursor(request: PageFlight) {
        val saved = synchronized(lock) { recoveryContext?.takeIf { recoveryPending } } ?: return
        val prefix = synchronized(lock) { tracks.map { it.ref } }
        var replayOffset = 0
        var replayCursor: String? = null
        var replayHasMore = true
        val replayCursors = mutableSetOf<String>()
        while (replayOffset < saved.nextOffset) {
            currentCoroutineContext().ensureActive()
            checkAccountAndProvider()
            val page = PageRequest(replayOffset, minOf(pageSize, saved.nextOffset - replayOffset), replayCursor)
            val result = fetchPage(page)
            currentCoroutineContext().ensureActive()
            checkAccountAndProvider()
            val end = replayOffset + result.items.size
            if (result.items.isEmpty() || result.items.size > page.limit || end > prefix.size ||
                result.items.map { it.ref } != prefix.subList(replayOffset, end)) invalidPage()
            if (result.hasMore && result.nextCursor != null && !replayCursors.add(result.nextCursor)) invalidPage()
            replayOffset = end
            replayCursor = result.nextCursor
            replayHasMore = result.hasMore
            if (!replayHasMore && replayOffset < saved.nextOffset) invalidPage()
        }
        synchronized(lock) {
            if (released || generation != request.generation || !providerMatches()) staleAccount()
            cursor = replayCursor
            more = more && replayHasMore
            recoveryPending = false
            seenCursors.addAll(replayCursors)
            publishLoadedLocked(itemsLocked())
        }
    }

    private suspend fun fetchPage(page: PageRequest): SourcePage<Track> = when (val ref = contentRef) {
        is AlbumRef -> router.albumTracks(ref, page)
        is ArtistRef -> router.artistTracks(ref, page)
        is PlaylistRef -> router.playlistTracks(ref, page)
        null -> router.library(providerId, requireNotNull(libraryCollection), page).let { result ->
            SourcePage(result.items.map { (it as? SourceItem.TrackItem)?.track ?: invalidPage() },
                result.hasMore, result.nextCursor)
        }
        else -> throw SourceException(SourceErrorCode.UNSUPPORTED)
    }

    private suspend fun checkAccountAndProvider() {
        if (!synchronized(lock) { !released && providerMatches() }) staleAccount()
        if (revisionProvider != null) {
            val current = revisionProvider.accountRevision.first()
            synchronized(lock) {
                if (accountRevision == null) accountRevision = current
                else if (accountRevision != current) staleAccount()
            }
        } else if (boundProvider != null && SourceCapability.ACCOUNT in boundProvider.capabilities) {
            val current = router.account(providerId)
            synchronized(lock) {
                if (!accountBound) {
                    boundAccount = current
                    accountBound = true
                } else if (current?.ref != boundAccount?.ref ||
                    current?.sessionRevision != boundAccount?.sessionRevision) staleAccount()
            }
        }
        if (!synchronized(lock) { !released && providerMatches() }) staleAccount()
        synchronized(lock) {
            if (recoveryContext != null && accountFingerprintLocked() != recoveryContext.accountFingerprint) staleAccount()
        }
    }

    private fun providerMatches(): Boolean = boundProvider != null &&
        router.registry.instance(boundProvider.instanceId) === boundProvider &&
        boundProvider.status == SourceStatus.READY && boundProvider.version == boundVersion

    internal suspend fun isSourceCurrent(): Boolean = try {
        withContext(Dispatchers.IO) { checkAccountAndProvider() }
        true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        stopPagination()
        false
    }

    /** The owner cancels in-flight pages when the bound plugin or native account changes. */
    internal suspend fun awaitInvalidation() = withContext(Dispatchers.IO) {
        try {
            checkAccountAndProvider()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return@withContext
        }
        coroutineScope {
            val providerChange = async {
                router.registry.revision.first { !synchronized(lock) { providerMatches() } }
            }
            val accountChange = revisionProvider?.let { provider -> async {
                provider.accountRevision.first { current ->
                    synchronized(lock) { accountRevision?.let { it != current } == true }
                }
            } }
            try {
                select<Unit> {
                    providerChange.onAwait { }
                    if (accountChange != null) accountChange.onAwait { }
                }
            } finally {
                providerChange.cancel()
                accountChange?.cancel()
            }
        }
    }

    /** The player may not have appended a just-fetched page yet; save its visible prefix only. */
    internal fun snapshotContext(visibleRefs: List<TrackRef>): SourceContentQueueSnapshot? = synchronized(lock) {
        if (released || !providerMatches() || visibleRefs.isEmpty() || visibleRefs.any { it.providerId != providerId }) return@synchronized null
        if (!cloudOrdered && (visibleRefs.size > tracks.size || tracks.take(visibleRefs.size).map { it.ref } != visibleRefs ||
            !more && visibleRefs.size == tracks.size && playlistSource == null)) return@synchronized null
        val ownerOnly = cloudOrdered || !more && visibleRefs.size == tracks.size
        val fingerprint = accountFingerprintLocked() ?: recoveryContext?.accountFingerprint ?: return@synchronized null
        SourceContentQueueSnapshot(
            providerId = providerId,
            instanceId = requireNotNull(boundProvider).instanceId.value,
            providerVersion = requireNotNull(boundVersion),
            contentType = contentRef?.type?.wireName,
            contentId = contentRef?.contentId,
            libraryCollection = libraryCollection?.name,
            loadedCount = visibleRefs.size,
            nextOffset = if (ownerOnly) visibleRefs.size else offset - (tracks.size - visibleRefs.size),
            pageSize = pageSize,
            hasMore = !ownerOnly,
            knownTotalCount = if (ownerOnly) null else knownTotalCount,
            accountFingerprint = fingerprint,
        )
    }

    private fun accountFingerprintLocked(): String? {
        val parts = when {
            revisionProvider != null -> listOf("revision", accountRevision ?: return null)
            boundProvider != null && SourceCapability.ACCOUNT in boundProvider.capabilities -> {
                if (!accountBound) return null
                listOf("account", boundAccount?.ref?.instanceId?.value.orEmpty(),
                    boundAccount?.ref?.accountId?.value.orEmpty(), boundAccount?.sessionRevision.orEmpty())
            }
            else -> listOf("public")
        }
        val canonical = parts.joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    override suspend fun reloadCurrentPage(): Queue.Result<List<Pair<String, MediaItem?>>> =
        synchronized(lock) { Queue.Result.Success(itemsLocked()) }

    override suspend fun getItemAt(position: Int): Queue.Result<Pair<String, MediaItem?>> = synchronized(lock) {
        tracks.getOrNull(position)?.let {
            Queue.Result.Success(TrackRefCodec.mediaId(it.ref) to mediaItemFactory(it))
        } ?: Queue.Result.Error("Position $position out of bounds")
    }

    private fun itemsLocked(): List<Pair<String, MediaItem?>> = tracks.map {
        TrackRefCodec.mediaId(it.ref) to mediaItemFactory(it)
    }

    private fun publishLoadedLocked(items: List<Pair<String, MediaItem?>>) {
        mutableState.value = if (items.isEmpty() && !more) Queue.QueueState.Completed
            else Queue.QueueState.Loaded(items, more)
    }

    private fun stopPagination() = synchronized(lock) {
        more = false
        clearCanonicalShuffleInputLocked()
    }

    private fun clearCanonicalShuffleInputLocked() {
        canonicalShuffleRefs = null
        canonicalShuffleOwner = null
    }

    /** Keep the account owner alive after the server replaces canonical paging with an order. */
    internal fun freezePagination() = synchronized(lock) {
        cloudOrdered = true
        generation++
        more = false
        flight?.job?.cancel()
        flight?.result?.cancel()
        flight = null
    }

    override fun clear() = synchronized(lock) {
        generation++
        more = false
        clearCanonicalShuffleInputLocked()
        flight?.job?.cancel()
        flight?.result?.cancel()
        flight = null
        tracks.clear()
        mutableState.value = Queue.QueueState.Completed
    }

    /** Cancelling remote work does not change the media items already held by the player. */
    override fun release() = synchronized(lock) {
        released = true
        generation++
        more = false
        clearCanonicalShuffleInputLocked()
        flight?.job?.cancel()
        flight?.result?.cancel()
        flight = null
    }

    private class PageFlight(val generation: Long) {
        val result = CompletableDeferred<Queue.Result<List<String>>>()
        var job: Job? = null
    }

    companion object {
        /** Attaches to existing media items; network validation is deferred until remote continuation. */
        fun restore(
            router: SourceRouter,
            context: SourceContentQueueSnapshot,
            title: String?,
            initialTracks: List<Track>,
            mediaItemFactory: (Track) -> MediaItem? = { it.toMediaMetadata().toMediaItem() },
        ): SourceContentQueue? = runCatching {
            val provider = router.registry.provider(context.providerId) ?: return null
            require(context.instanceId.isNotBlank() && context.instanceId == provider.instanceId.value)
            require(context.providerVersion.isNotBlank() && context.providerVersion == provider.version)
            require(context.accountFingerprint.matches(Regex("[0-9a-f]{64}")))
            require(context.loadedCount > 0 && context.loadedCount == initialTracks.size)
            require(context.nextOffset == context.loadedCount && context.pageSize in 1..100)
            require(context.knownTotalCount == null || context.knownTotalCount >= 0)
            val ref: ContentRef? = when (context.contentType) {
                ContentType.PLAYLIST.wireName -> PlaylistRef(provider.id, requireNotNull(context.contentId))
                ContentType.ALBUM.wireName -> AlbumRef(provider.id, requireNotNull(context.contentId))
                ContentType.ARTIST.wireName -> ArtistRef(provider.id, requireNotNull(context.contentId))
                null -> null
                else -> return null
            }
            val collection = context.libraryCollection?.let { LibraryCollection.valueOf(it) }
            require(ref != null && collection == null || ref == null && context.contentId == null &&
                collection?.type == ContentType.TRACK)
            require(context.hasMore || ref is PlaylistRef || collection == LibraryCollection.LIKED_TRACKS)
            SourceContentQueue(router, provider.id, ref, collection, title, initialTracks, 0, context.hasMore,
                null, context.nextOffset, context.pageSize, context.knownTotalCount, null, null,
                mediaItemFactory, context)
        }.getOrNull()

        fun forLibrary(
            router: SourceRouter,
            providerId: String,
            collection: LibraryCollection = LibraryCollection.LIKED_TRACKS,
            title: String? = null,
            initialTracks: List<Track> = emptyList(),
            startIndex: Int = 0,
            hasMore: Boolean = true,
            nextCursor: String? = null,
            nextOffset: Int = initialTracks.size,
            pageSize: Int = 30,
            knownTotalCount: Int? = null,
            account: SourceAccount? = null,
            initialAccountRevision: String? = null,
            mediaItemFactory: (Track) -> MediaItem? = { it.toMediaMetadata().toMediaItem() },
        ): SourceContentQueue = SourceContentQueue(router, providerId, null, collection, title,
            initialTracks, startIndex, hasMore, nextCursor, nextOffset, pageSize, knownTotalCount,
            account, initialAccountRevision, mediaItemFactory)

        private val terminalErrors = setOf(SourceErrorCode.AUTH_REQUIRED, SourceErrorCode.AUTH_EXPIRED,
            SourceErrorCode.MISSING_PROVIDER, SourceErrorCode.UNSUPPORTED, SourceErrorCode.INVALID_RESPONSE)
        private fun invalidPage(): Nothing = throw SourceException(SourceErrorCode.INVALID_RESPONSE)
        private fun staleAccount(): Nothing = throw SourceException(SourceErrorCode.AUTH_EXPIRED)
    }
}
