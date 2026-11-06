package com.ljyh.mei.source.core

import com.ljyh.mei.source.api.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Singleton
class SourceRouter @Inject constructor(val registry: ProviderRegistry) {
    private val mutableContentRevision = MutableStateFlow(0L)
    val contentRevision = mutableContentRevision.asStateFlow()
    fun searchTypes(providerId: String): Set<ContentType> {
        val provider = registry.provider(providerId) ?: return emptySet()
        if (SourceCapability.SEARCH !in provider.capabilities) return emptySet()
        return (provider as? TypedSearchCapability)?.searchTypes
            ?: if (provider is CatalogCapability) setOf(ContentType.TRACK) else emptySet()
    }

    suspend fun search(providerId: String, query: String, type: ContentType, page: PageRequest = PageRequest()): SourcePage<SourceItem> {
        val provider = registry.requireProvider(providerId)
        if (type !in searchTypes(providerId)) unsupported()
        val result: SourcePage<SourceItem> = if (provider is TypedSearchCapability) provider.search(query, type, page)
            else if (type == ContentType.TRACK) search(providerId, query, page).let {
                SourcePage<SourceItem>(it.items.map { track -> SourceItem.TrackItem(track) }, it.hasMore, it.nextCursor)
            } else unsupported()
        return result.also { validateItems(providerId, it.items, type) }
    }

    suspend fun getFeed(providerId: String, kind: FeedKind, categoryId: String? = null, page: PageRequest = PageRequest()): SourceFeedPage {
        val provider = capability<FeedCapability>(providerId, SourceCapability.FEED)
        if (kind !in provider.feedKinds) unsupported()
        return provider.getFeed(kind, categoryId, page).also { feed ->
            if (feed.sections.map { it.id }.distinct().size != feed.sections.size) invalid()
            feed.sections.forEach { validateItems(providerId, it.items) }
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
    suspend fun playlist(ref: PlaylistRef): SourcePlaylist =
        capability<PlaylistCapability>(ref.providerId, SourceCapability.PLAYLIST).playlist(ref).also { if (it.ref != ref) invalid() }
    suspend fun playlistTracks(ref: PlaylistRef, page: PageRequest = PageRequest()): SourcePage<Track> =
        capability<PlaylistCapability>(ref.providerId, SourceCapability.PLAYLIST).playlistTracks(ref, page).also { validateTracks(ref.providerId, it.items) }

    suspend fun account(providerId: String): SourceAccount? {
        val provider = registry.requireProvider(providerId)
        if (SourceCapability.ACCOUNT !in provider.capabilities) unsupported()
        return (provider as? AccountCapability ?: unsupported()).account().also {
            if (it != null && it.ref.instanceId != provider.instanceId) invalid()
        }
    }

    suspend fun library(providerId: String, collection: LibraryCollection, page: PageRequest = PageRequest()): SourcePage<SourceItem> {
        val provider = capability<LibraryCapability>(providerId, SourceCapability.LIBRARY)
        if (collection !in provider.collections) unsupported()
        return provider.library(collection, page).also { validateItems(providerId, it.items, collection.type) }
    }

    suspend fun liked(ref: TrackRef): Boolean {
        val provider = capability<LikeCapability>(ref.providerId, SourceCapability.LIKE)
        if (!provider.canReadLikes) unsupported()
        return provider.liked(ref)
    }
    suspend fun setLiked(ref: TrackRef, liked: Boolean) {
        val provider = capability<LikeCapability>(ref.providerId, SourceCapability.LIKE)
        if (!provider.canWriteLikes) unsupported()
        provider.setLiked(ref, liked)
        contentChanged()
    }
    suspend fun saved(ref: ContentRef): Boolean {
        val provider = capability<CollectionCapability>(ref.providerId, SourceCapability.COLLECTIONS)
        if (!provider.canReadCollections || ref.type !in provider.collectionTypes) unsupported()
        return provider.saved(ref)
    }
    suspend fun setSaved(ref: ContentRef, saved: Boolean) {
        val provider = capability<CollectionCapability>(ref.providerId, SourceCapability.COLLECTIONS)
        if (!provider.canWriteCollections || ref.type !in provider.collectionTypes) unsupported()
        provider.setSaved(ref, saved)
        contentChanged()
    }
    suspend fun createPlaylist(providerId: String, title: String, description: String? = null): SourcePlaylist =
        playlistEditor(providerId, PlaylistEditAction.CREATE).createPlaylist(title, description).also { if (it.ref.providerId != providerId) invalid(); contentChanged() }
    suspend fun updatePlaylist(ref: PlaylistRef, title: String? = null, description: String? = null) {
        playlistEditor(ref.providerId, PlaylistEditAction.UPDATE).updatePlaylist(ref, title, description)
        contentChanged()
    }
    suspend fun deletePlaylist(ref: PlaylistRef) {
        playlistEditor(ref.providerId, PlaylistEditAction.DELETE).deletePlaylist(ref)
        contentChanged()
    }
    suspend fun addTracks(ref: PlaylistRef, tracks: List<TrackRef>) {
        validateTracksForPlaylist(ref, tracks)
        playlistEditor(ref.providerId, PlaylistEditAction.ADD_TRACKS).addTracks(ref, tracks)
        contentChanged()
    }
    suspend fun removeTracks(ref: PlaylistRef, tracks: List<TrackRef>) {
        validateTracksForPlaylist(ref, tracks)
        playlistEditor(ref.providerId, PlaylistEditAction.REMOVE_TRACKS).removeTracks(ref, tracks)
        contentChanged()
    }
    suspend fun reorderTracks(ref: PlaylistRef, tracks: List<TrackRef>) {
        validateTracksForPlaylist(ref, tracks)
        playlistEditor(ref.providerId, PlaylistEditAction.REORDER_TRACKS).reorderTracks(ref, tracks)
        contentChanged()
    }

    suspend fun search(providerId: String, query: String, page: PageRequest = PageRequest()): SourcePage<Track> {
        val provider = registry.requireProvider(providerId)
        if (SourceCapability.SEARCH !in provider.capabilities) throw SourceException(SourceErrorCode.UNSUPPORTED)
        return (provider as? CatalogCapability ?: throw SourceException(SourceErrorCode.UNSUPPORTED)).search(query, page)
    }
    suspend fun getTrack(ref: TrackRef): Track? {
        val provider = registry.requireProvider(ref.providerId)
        if (SourceCapability.TRACK !in provider.capabilities) throw SourceException(SourceErrorCode.UNSUPPORTED)
        return (provider as? CatalogCapability ?: throw SourceException(SourceErrorCode.UNSUPPORTED)).getTrack(ref)
            ?.also { if (it.ref != ref) throw SourceException(SourceErrorCode.INVALID_RESPONSE) }
    }
    suspend fun getTracks(refs: List<TrackRef>): List<Track> {
        val requested = refs.toSet()
        val tracks = refs.groupBy { it.providerId }.flatMap { (id, group) ->
            val provider = registry.requireProvider(id)
            if (SourceCapability.TRACK !in provider.capabilities) throw SourceException(SourceErrorCode.UNSUPPORTED)
            if (provider is BatchCatalogCapability) provider.getTracks(group)
            else group.distinct().mapNotNull { getTrack(it) }
        }
        if (tracks.any { it.ref !in requested }) throw SourceException(SourceErrorCode.INVALID_RESPONSE)
        val indexed = tracks.associateBy { it.ref }
        return refs.mapNotNull(indexed::get)
    }
    suspend fun availableQualities(ref: TrackRef): List<String> =
        (registry.requireProvider(ref.providerId) as? QualityCapability)?.availableQualities(ref).orEmpty()
    suspend fun lyrics(ref: TrackRef): SourceLyrics? {
        val provider = registry.requireProvider(ref.providerId)
        if (SourceCapability.LYRICS !in provider.capabilities) throw SourceException(SourceErrorCode.UNSUPPORTED)
        return (provider as? LyricsCapability ?: throw SourceException(SourceErrorCode.UNSUPPORTED)).getLyrics(ref)
    }

    private inline fun <reified T> capability(providerId: String, capability: SourceCapability): T {
        val provider = registry.requireProvider(providerId)
        if (capability !in provider.capabilities) unsupported()
        return provider as? T ?: unsupported()
    }
    private fun playlistEditor(providerId: String, action: PlaylistEditAction): PlaylistEditCapability =
        capability<PlaylistEditCapability>(providerId, SourceCapability.PLAYLIST_EDIT).also {
            if (action !in it.playlistEditActions) unsupported()
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
