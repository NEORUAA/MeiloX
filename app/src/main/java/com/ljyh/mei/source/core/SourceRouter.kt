package com.ljyh.mei.source.core

import com.ljyh.mei.source.api.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class SourceRouter @Inject constructor(
    val registry: ProviderRegistry,
    private val mutations: SourceMutationCoordinator = SourceMutationCoordinator(registry),
) {
    suspend fun feature(providerId: String, feature: String, action: String,
        parameters: com.google.gson.JsonObject = com.google.gson.JsonObject(),
        allowSessionChange: Boolean = false,
        expectedProvider: MusicProvider? = null,
        expectedAccountRevision: String? = null,
        onSessionCommitted: ((String?) -> Unit)? = null): com.google.gson.JsonElement {
        val owner = readyProvider(providerId)
        if (expectedProvider != null && owner !== expectedProvider) {
            throw kotlinx.coroutines.CancellationException("Source changed before feature operation")
        }
        val capability = owner as? SourceFeatureCapability ?: unsupported()
        if (SourceCapability.FEATURES !in owner.capabilities || action !in capability.features[feature].orEmpty()) unsupported()
        val revision = (owner as? AccountRevisionCapability)?.accountRevision?.first()
        if (expectedProvider != null && revision != expectedAccountRevision) {
            throw kotlinx.coroutines.CancellationException("Source account changed before feature operation")
        }
        val commit = onSessionCommitted.takeIf { allowSessionChange }
        val result = if (capability is AccountScopedFeatureCapability) {
            capability.invokeFeature(feature, action, parameters.deepCopy(), revision, commit)
        } else capability.invokeFeature(feature, action, parameters.deepCopy())
        if (registry.provider(providerId) !== owner || !allowSessionChange &&
            (owner as? AccountRevisionCapability)?.accountRevision?.first() != revision) {
            throw kotlinx.coroutines.CancellationException("Source changed during feature operation")
        }
        if (allowSessionChange && capability !is AccountScopedFeatureCapability &&
            !(result.isJsonObject && result.asJsonObject.has("hostEffect"))) {
            commit?.invoke((owner as? AccountRevisionCapability)?.accountRevision?.first())
        }
        return result
    }
    private val mutableContentRevision = MutableStateFlow(0L)
    val contentRevision = mutableContentRevision.asStateFlow()
    private val accountReads = mutableMapOf<String, AccountRead>()
    fun searchTypes(providerId: String): Set<ContentType> {
        val provider = registry.provider(providerId) ?: return emptySet()
        if (SourceCapability.SEARCH !in provider.capabilities) return emptySet()
        return (provider as? TypedSearchCapability)?.searchTypes
            ?: if (provider is CatalogCapability) setOf(ContentType.TRACK) else emptySet()
    }

    suspend fun search(providerId: String, query: String, type: ContentType, page: PageRequest = PageRequest()): SourcePage<SourceItem> {
        val provider = readyProvider(providerId)
        if (type !in searchTypes(providerId)) unsupported()
        val result: SourcePage<SourceItem> = if (provider is TypedSearchCapability) provider.search(query, type, page)
            else if (type == ContentType.TRACK) search(providerId, query, page).let {
                SourcePage<SourceItem>(it.items.map { track -> SourceItem.TrackItem(track) }, it.hasMore, it.nextCursor)
            } else unsupported()
        return result.also { validateItems(providerId, it.items, type) }
    }

    suspend fun getFeed(providerId: String, kind: FeedKind, categoryId: String? = null, page: PageRequest = PageRequest()): SourceFeedPage {
        val owner = readyProvider(providerId)
        if (SourceCapability.FEED !in owner.capabilities) unsupported()
        val provider = owner as? FeedCapability ?: unsupported()
        if (kind !in provider.feedKinds) unsupported()
        return provider.getFeed(kind, categoryId, page).also { feed ->
            if (feed.sections.map { it.id }.distinct().size != feed.sections.size) invalid()
            feed.sections.forEach { section ->
                validateItems(providerId, section.items)
                if (section.cards.map { it.id }.distinct().size != section.cards.size) invalid()
                section.cards.forEach { card ->
                    when (val target = card.target) {
                        is SourceFeedTarget.Content -> if (target.ref.providerId != providerId) invalid()
                        is SourceFeedTarget.Route -> if (target.route !in (owner as? FeatureContribution)?.featureRoutes.orEmpty() ||
                            "providerId" in target.parameters) invalid()
                        is SourceFeedTarget.Radio -> if ("personalRadio" !in (owner as? SourceFeatureCapability)?.features?.get("discovery").orEmpty() ||
                            target.similar && target.seedIds.isEmpty()) invalid()
                        is SourceFeedTarget.Queue -> if (target.action !in (owner as? SourceFeatureCapability)?.features?.get(target.feature).orEmpty()) invalid()
                    }
                }
            }
        }
    }

    suspend fun album(ref: AlbumRef): SourceAlbumInfo =
        capability<AlbumCapability>(ref.providerId, SourceCapability.ALBUM).album(ref).also { if (it.ref != ref) invalid() }
    suspend fun albumTracks(ref: AlbumRef, page: PageRequest = PageRequest()): SourcePage<Track> =
        capability<AlbumCapability>(ref.providerId, SourceCapability.ALBUM).albumTracks(ref, page).also { validateTracks(ref.providerId, it.items) }
    suspend fun artist(ref: ArtistRef): SourceArtistInfo =
        capability<ArtistCapability>(ref.providerId, SourceCapability.ARTIST).artist(ref).also { if (it.ref != ref) invalid() }
    suspend fun artistTracks(ref: ArtistRef, page: PageRequest = PageRequest()): SourcePage<Track> =
        capability<ArtistCapability>(ref.providerId, SourceCapability.ARTIST).artistTracks(ref, page).also { validateTracks(ref.providerId, it.items) }
    suspend fun artistAlbums(ref: ArtistRef, page: PageRequest = PageRequest()): SourcePage<SourceAlbumInfo> =
        capability<ArtistCapability>(ref.providerId, SourceCapability.ARTIST).artistAlbums(ref, page).also { albums ->
            if (albums.items.any { it.ref.providerId != ref.providerId }) invalid()
        }
    suspend fun playlist(ref: PlaylistRef): SourcePlaylist {
        val owner = mutations.capture(ref.providerId)
        val provider = ownedCapability<PlaylistCapability>(owner, SourceCapability.PLAYLIST)
        return mutations.read(owner, listOf(mutationKey(ref))) {
            provider.playlist(ref).also { if (it.ref != ref) invalid() }
        }
    }
    suspend fun playlistTracks(ref: PlaylistRef, page: PageRequest = PageRequest()): SourcePage<Track> {
        val owner = mutations.capture(ref.providerId)
        val provider = ownedCapability<PlaylistCapability>(owner, SourceCapability.PLAYLIST)
        return mutations.read(owner, listOf(mutationKey(ref))) {
            provider.playlistTracks(ref, page).also { validateTracks(ref.providerId, it.items) }
        }
    }

    suspend fun account(providerId: String, refresh: Boolean = false): SourceAccount? {
        val requestedAt = System.nanoTime()
        val owner = mutations.capture(providerId)
        val provider = ownedCapability<AccountCapability>(owner, SourceCapability.ACCOUNT)
        val read = synchronized(accountReads) {
            accountReads[providerId]?.takeIf {
                it.owner.provider === owner.provider && it.owner.version == owner.version && it.owner.revision == owner.revision
            } ?: AccountRead(owner).also { accountReads[providerId] = it }
        }
        return read.mutex.withLock {
            mutations.ensureCurrent(owner)
            // Concurrent callers share the completed result, including an explicit refresh.
            read.result?.takeIf {
                read.completedAt >= requestedAt || !refresh && it.isSuccess && owner.provider is AccountRevisionCapability &&
                    System.nanoTime() - read.completedAt < 60_000_000_000L
            }?.let { return@withLock it.getOrThrow() }
            try {
                val account = provider.account()
                mutations.ensureCurrent(owner)
                if (account != null && account.ref.instanceId != owner.provider.instanceId) invalid()
                read.result = Result.success(account)
                read.completedAt = System.nanoTime()
                account
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutations.ensureCurrent(owner)
                read.result = Result.failure(error)
                read.completedAt = System.nanoTime()
                throw error
            }
        }
    }

    private class AccountRead(val owner: SourceMutationOwner) {
        val mutex = Mutex()
        var result: Result<SourceAccount?>? = null
        var completedAt = 0L
    }

    suspend fun library(providerId: String, collection: LibraryCollection, page: PageRequest = PageRequest()): SourcePage<SourceItem> {
        val provider = capability<LibraryCapability>(providerId, SourceCapability.LIBRARY)
        if (collection !in provider.collections) unsupported()
        return provider.library(collection, page).also { validateItems(providerId, it.items, collection.type) }
    }

    suspend fun liked(ref: TrackRef): Boolean {
        val owner = mutations.capture(ref.providerId)
        val provider = ownedCapability<LikeCapability>(owner, SourceCapability.LIKE)
        if (!provider.canReadLikes) unsupported()
        return mutations.read(owner, listOf(likeKey(ref))) { mutations.confirmed(owner, likeKey(ref)) ?: provider.liked(ref) }
    }
    suspend fun liked(refs: List<TrackRef>): Map<TrackRef, Boolean> {
        if (refs.size > 1000) unsupported()
        val result = linkedMapOf<TrackRef, Boolean>()
        refs.distinct().groupBy { it.providerId }.forEach { (id, tracks) ->
            val owner = mutations.capture(id)
            val likes = ownedCapability<LikeCapability>(owner, SourceCapability.LIKE)
            if (!likes.canReadLikes) unsupported()
            result.putAll(mutations.read(owner, tracks.map(::likeKey)) {
                val states = linkedMapOf<TrackRef, Boolean>()
                tracks.forEach { ref -> mutations.confirmed(owner, likeKey(ref))?.let { states[ref] = it } }
                tracks.filterNot { it in states }.chunked(100).forEach { batch ->
                    val values = (likes as? BatchLikeCapability)?.liked(batch) ?: batch.associateWith { likes.liked(it) }
                    if (values.keys != batch.toSet()) invalid()
                    states.putAll(values)
                }
                states
            })
        }
        return result
    }
    suspend fun setLiked(ref: TrackRef, liked: Boolean, expectedAccountRevision: String? = null,
        expectedProvider: MusicProvider? = null) {
        val owner = mutations.capture(ref.providerId, expectedProvider, expectedAccountRevision)
        val provider = ownedCapability<LikeCapability>(owner, SourceCapability.LIKE)
        if (!provider.canWriteLikes) unsupported()
        write(owner, likeKey(ref), "liked:$liked") {
            applyLiked(owner, provider, ref, liked)
            mutations.ensureCurrent(owner)
            mutations.confirm(owner, likeKey(ref), liked)
        }
    }
    suspend fun toggleLike(ref: TrackRef, expectedAccountRevision: String? = null,
        expectedProvider: MusicProvider? = null): Boolean {
        val owner = mutations.capture(ref.providerId, expectedProvider, expectedAccountRevision)
        val provider = ownedCapability<LikeCapability>(owner, SourceCapability.LIKE)
        if (!provider.canReadLikes || !provider.canWriteLikes) unsupported()
        var writeStarted = false
        return write(owner, likeKey(ref), canReconcile = { writeStarted }) {
            val liked = mutations.confirmed(owner, likeKey(ref)) ?: provider.liked(ref)
            mutations.ensureCurrent(owner)
            writeStarted = true
            applyLiked(owner, provider, ref, !liked)
            mutations.ensureCurrent(owner)
            mutations.confirm(owner, likeKey(ref), !liked)
            !liked
        }
    }
    private suspend fun applyLiked(owner: SourceMutationOwner, provider: LikeCapability, ref: TrackRef, liked: Boolean) {
        if (provider is AccountScopedLikeCapability) provider.setLiked(ref, liked, owner.revision)
        else provider.setLiked(ref, liked)
    }
    suspend fun saved(ref: ContentRef): Boolean {
        val owner = mutations.capture(ref.providerId)
        val provider = ownedCapability<CollectionCapability>(owner, SourceCapability.COLLECTIONS)
        if (!provider.canReadCollections || ref.type !in provider.collectionTypes) unsupported()
        return mutations.read(owner, listOf(mutationKey(ref))) { mutations.confirmed(owner, mutationKey(ref)) ?: provider.saved(ref) }
    }
    suspend fun setSaved(ref: ContentRef, saved: Boolean, expectedAccountRevision: String? = null,
        expectedProvider: MusicProvider? = null) {
        val owner = mutations.capture(ref.providerId, expectedProvider, expectedAccountRevision)
        val provider = ownedCapability<CollectionCapability>(owner, SourceCapability.COLLECTIONS)
        if (!provider.canWriteCollections || ref.type !in provider.collectionTypes) unsupported()
        write(owner, mutationKey(ref), "saved:$saved") {
            provider.setSaved(ref, saved)
            mutations.ensureCurrent(owner)
            mutations.confirm(owner, mutationKey(ref), saved)
        }
    }
    suspend fun createPlaylist(providerId: String, title: String, description: String? = null, privacy: Boolean = false,
        type: String = "NORMAL", expectedAccountRevision: String? = null, expectedProvider: MusicProvider? = null): SourcePlaylist {
        val owner = mutations.capture(providerId, expectedProvider, expectedAccountRevision)
        val editor = ownedEditor(owner, PlaylistEditAction.CREATE)
        return write(owner, "playlist:create") {
            editor.createPlaylist(title, description, privacy, type).also { if (it.ref.providerId != providerId) invalid() }
        }
    }
    suspend fun updatePlaylist(ref: PlaylistRef, title: String? = null, description: String? = null,
        expectedAccountRevision: String? = null, expectedProvider: MusicProvider? = null) {
        val owner = mutations.capture(ref.providerId, expectedProvider, expectedAccountRevision)
        val editor = ownedEditor(owner, PlaylistEditAction.UPDATE)
        write(owner, mutationKey(ref)) { editor.updatePlaylist(ref, title, description) }
    }
    suspend fun deletePlaylist(ref: PlaylistRef, expectedAccountRevision: String? = null, expectedProvider: MusicProvider? = null) {
        val owner = mutations.capture(ref.providerId, expectedProvider, expectedAccountRevision)
        val editor = ownedEditor(owner, PlaylistEditAction.DELETE)
        write(owner, mutationKey(ref), uncertain = { it == mutationKey(ref) || it.startsWith("like:") }) {
            editor.deletePlaylist(ref)
            mutations.ensureCurrent(owner)
            mutations.invalidate(owner) { it.startsWith("like:") }
        }
    }
    suspend fun addTracks(ref: PlaylistRef, tracks: List<TrackRef>, expectedAccountRevision: String? = null,
        expectedProvider: MusicProvider? = null) = editTracks(ref, tracks, PlaylistEditAction.ADD_TRACKS, expectedAccountRevision, expectedProvider)
    suspend fun removeTracks(ref: PlaylistRef, tracks: List<TrackRef>, expectedAccountRevision: String? = null,
        expectedProvider: MusicProvider? = null) = editTracks(ref, tracks, PlaylistEditAction.REMOVE_TRACKS, expectedAccountRevision, expectedProvider)
    suspend fun reorderTracks(ref: PlaylistRef, tracks: List<TrackRef>, expectedAccountRevision: String? = null,
        expectedProvider: MusicProvider? = null, expectedContentRevision: Long? = null) =
        editTracks(ref, tracks, PlaylistEditAction.REORDER_TRACKS, expectedAccountRevision, expectedProvider, expectedContentRevision)

    private suspend fun editTracks(ref: PlaylistRef, tracks: List<TrackRef>, action: PlaylistEditAction,
        expectedAccountRevision: String?, expectedProvider: MusicProvider?, expectedContentRevision: Long? = null) {
        val selected = tracks.toList()
        validateTracksForPlaylist(ref, selected)
        val owner = mutations.capture(ref.providerId, expectedProvider, expectedAccountRevision)
        val editor = ownedEditor(owner, action)
        val affected = selected.map(::likeKey).toSet()
        write(owner, mutationKey(ref), uncertain = { it == mutationKey(ref) || it in affected }) {
            if (expectedContentRevision != null && mutableContentRevision.value != expectedContentRevision) {
                throw SourceException(SourceErrorCode.CANCELLED, "Playlist content changed before reorder")
            }
            when (action) {
                PlaylistEditAction.ADD_TRACKS -> editor.addTracks(ref, selected)
                PlaylistEditAction.REMOVE_TRACKS -> editor.removeTracks(ref, selected)
                PlaylistEditAction.REORDER_TRACKS -> editor.reorderTracks(ref, selected)
                else -> unsupported()
            }
            if (action != PlaylistEditAction.REORDER_TRACKS) {
                mutations.ensureCurrent(owner)
                mutations.invalidate(owner) { it in affected }
            }
        }
    }
    private suspend fun <T> write(owner: SourceMutationOwner, key: String, signature: String? = null,
        uncertain: (String) -> Boolean = { it == key }, canReconcile: () -> Boolean = { true }, action: suspend () -> T): T =
        mutations.mutate(owner, key, signature) {
            try { action().also { mutations.ensureCurrent(owner); contentChanged() } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                val ambiguous = if (error is SourceException) error.outcomeUnknown || error.code in setOf(
                    SourceErrorCode.NETWORK, SourceErrorCode.TIMEOUT, SourceErrorCode.INVALID_RESPONSE,
                ) else error is java.io.IOException
                if (ambiguous && canReconcile()) {
                    mutations.ensureCurrent(owner)
                    mutations.invalidate(owner, uncertain)
                    contentChanged()
                }
                throw error
            }
        }
    private inline fun <reified T> ownedCapability(owner: SourceMutationOwner, capability: SourceCapability): T {
        if (capability !in owner.provider.capabilities) unsupported()
        return owner.provider as? T ?: unsupported()
    }
    private fun ownedEditor(owner: SourceMutationOwner, action: PlaylistEditAction): PlaylistEditCapability =
        ownedCapability<PlaylistEditCapability>(owner, SourceCapability.PLAYLIST_EDIT).also {
            if (action !in it.playlistEditActions) unsupported()
        }
    private fun likeKey(ref: TrackRef): String = "like:${ref.trackId}"
    private fun mutationKey(ref: ContentRef): String = "${ref.type.wireName}:${ref.contentId}"

    suspend fun search(providerId: String, query: String, page: PageRequest = PageRequest()): SourcePage<Track> {
        val provider = readyProvider(providerId)
        if (SourceCapability.SEARCH !in provider.capabilities) throw SourceException(SourceErrorCode.UNSUPPORTED)
        return (provider as? CatalogCapability ?: throw SourceException(SourceErrorCode.UNSUPPORTED)).search(query, page)
    }
    suspend fun getTrack(ref: TrackRef): Track? {
        val provider = readyProvider(ref.providerId)
        if (SourceCapability.TRACK !in provider.capabilities) throw SourceException(SourceErrorCode.UNSUPPORTED)
        return (provider as? CatalogCapability ?: throw SourceException(SourceErrorCode.UNSUPPORTED)).getTrack(ref)
            ?.also { if (it.ref != ref) throw SourceException(SourceErrorCode.INVALID_RESPONSE) }
    }
    suspend fun getTracks(refs: List<TrackRef>): List<Track> {
        val requested = refs.toSet()
        val tracks = refs.groupBy { it.providerId }.flatMap { (id, group) ->
            val provider = readyProvider(id)
            if (SourceCapability.TRACK !in provider.capabilities) throw SourceException(SourceErrorCode.UNSUPPORTED)
            if (provider is BatchCatalogCapability) provider.getTracks(group)
            else group.distinct().mapNotNull { getTrack(it) }
        }
        if (tracks.any { it.ref !in requested }) throw SourceException(SourceErrorCode.INVALID_RESPONSE)
        val indexed = tracks.associateBy { it.ref }
        return refs.mapNotNull(indexed::get)
    }
    suspend fun availableQualities(ref: TrackRef): List<String> =
        (readyProvider(ref.providerId) as? QualityCapability)?.availableQualities(ref).orEmpty()
    suspend fun lyrics(ref: TrackRef): SourceLyrics? {
        val provider = readyProvider(ref.providerId)
        if (SourceCapability.LYRICS !in provider.capabilities) throw SourceException(SourceErrorCode.UNSUPPORTED)
        return (provider as? LyricsCapability ?: throw SourceException(SourceErrorCode.UNSUPPORTED)).getLyrics(ref)
    }

    private suspend fun readyProvider(providerId: String): MusicProvider {
        registry.awaitInitialized()
        return registry.requireProvider(providerId)
    }
    private suspend inline fun <reified T> capability(providerId: String, capability: SourceCapability): T {
        val provider = readyProvider(providerId)
        if (capability !in provider.capabilities) unsupported()
        return provider as? T ?: unsupported()
    }
    private fun validateTracksForPlaylist(ref: PlaylistRef, tracks: List<TrackRef>) {
        if (tracks.size > 1000 || tracks.any { it.providerId != ref.providerId }) unsupported()
    }
    private fun validateTracks(providerId: String, tracks: List<Track>) {
        if (tracks.any { it.ref.providerId != providerId }) invalid()
    }
    private fun validateItems(providerId: String, items: List<SourceItem>, type: ContentType? = null) {
        if (items.any { it.ref.providerId != providerId || type != null && it.ref.type != type }) invalid()
    }
    private fun unsupported(): Nothing = throw SourceException(SourceErrorCode.UNSUPPORTED)
    private fun invalid(): Nothing = throw SourceException(SourceErrorCode.INVALID_RESPONSE)
    private fun contentChanged() { mutableContentRevision.update { it + 1 } }
}
