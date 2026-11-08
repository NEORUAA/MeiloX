package com.ljyh.mei.source.api

/** Content identity remains separate from an account and the plugin instance serving it. */
sealed interface ContentRef {
    val providerId: String
    val contentId: String
}

enum class ContentType(val wireName: String) { TRACK("track"), ALBUM("album"), ARTIST("artist"), PLAYLIST("playlist") }

val ContentRef.type: ContentType get() = when (this) {
    is TrackRef -> ContentType.TRACK
    is AlbumRef -> ContentType.ALBUM
    is ArtistRef -> ContentType.ARTIST
    is PlaylistRef -> ContentType.PLAYLIST
}

data class SourcePlaylist(
    val ref: PlaylistRef,
    val title: String,
    val artworkUrl: String = "",
    val creatorName: String = "",
    val trackCount: Int = 0,
    val description: String? = null,
    val editableActions: Set<PlaylistEditAction> = emptySet(),
    val playCount: Long? = null,
    val subtitle: String? = null,
)

data class SourceAlbumInfo(
    val ref: AlbumRef,
    val title: String,
    val artworkUrl: String = "",
    val artists: List<SourceArtist> = emptyList(),
    val trackCount: Int = 0,
    val description: String? = null,
    val publishedAtMs: Long? = null,
)

data class SourceArtistInfo(
    val ref: ArtistRef,
    val name: String,
    val artworkUrl: String = "",
    val description: String? = null,
    val alternateNames: List<String> = emptyList(),
    val trackCount: Int? = null,
    val albumCount: Int? = null,
    val musicVideoCount: Int? = null,
    val identities: List<String> = emptyList(),
    val expertise: List<SourceArtistExpertise> = emptyList(),
    val translatedNames: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
)

data class SourceArtistExpertise(val name: String, val count: Int? = null)

sealed interface SourceItem {
    val ref: ContentRef
    val title: String
    val artworkUrl: String

    data class TrackItem(val track: Track) : SourceItem {
        override val ref: TrackRef get() = track.ref
        override val title: String get() = track.title
        override val artworkUrl: String get() = track.artworkUrl
    }
    data class PlaylistItem(val playlist: SourcePlaylist) : SourceItem {
        override val ref: PlaylistRef get() = playlist.ref
        override val title: String get() = playlist.title
        override val artworkUrl: String get() = playlist.artworkUrl
    }
    data class AlbumItem(val album: SourceAlbumInfo) : SourceItem {
        override val ref: AlbumRef get() = album.ref
        override val title: String get() = album.title
        override val artworkUrl: String get() = album.artworkUrl
    }
    data class ArtistItem(val artist: SourceArtistInfo) : SourceItem {
        override val ref: ArtistRef get() = artist.ref
        override val title: String get() = artist.name
        override val artworkUrl: String get() = artist.artworkUrl
    }
}

enum class FeedKind(val operation: String) { HOME("feed.home"), DISCOVER("feed.discover") }
enum class SourceFeedLayout { HERO, PLAYLISTS, TRACKS, ALBUMS, ARTISTS, GRID }
data class SourceCategory(val id: String, val title: String)
/** Source-owned card actions use shared navigation and playback, never platform resource codes. */
sealed interface SourceFeedTarget {
    data class Content(val ref: ContentRef) : SourceFeedTarget
    data class Route(val route: String, val parameters: Map<String, String> = emptyMap()) : SourceFeedTarget
    data class Radio(val seedIds: List<String>, val similar: Boolean = false) : SourceFeedTarget
    data class Queue(val feature: String, val action: String, val parameters: com.google.gson.JsonObject) : SourceFeedTarget
}
data class SourceFeedCard(
    val id: String,
    val title: String,
    val artworkUrl: String,
    val subtitle: String = "",
    val iconUrl: String = "",
    val target: SourceFeedTarget,
    val coverCaptions: List<String> = emptyList(),
    val detail: String? = null,
    val titleMaxLines: Int = 2,
)
data class SourceFeedSection(
    val id: String, val title: String, val layout: SourceFeedLayout, val items: List<SourceItem>,
    val cards: List<SourceFeedCard> = emptyList(),
)
data class SourceFeedPage(
    val sections: List<SourceFeedSection>,
    val categories: List<SourceCategory> = emptyList(),
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
)

data class SourceAccountRef(val instanceId: ProviderInstanceId, val accountId: ProviderAccountId)
data class SourceAccount(
    val ref: SourceAccountRef,
    val displayName: String,
    val avatarUrl: String = "",
    val sessionRevision: String = "",
)

enum class LibraryCollection(val operation: String, val type: ContentType) {
    @com.google.gson.annotations.SerializedName("LIKED_TRACKS")
    LIKED_TRACKS("library.likedTracks", ContentType.TRACK),
    CREATED_PLAYLISTS("library.createdPlaylists", ContentType.PLAYLIST),
    SAVED_PLAYLISTS("library.savedPlaylists", ContentType.PLAYLIST),
    SAVED_ALBUMS("library.savedAlbums", ContentType.ALBUM),
    FOLLOWED_ARTISTS("library.followedArtists", ContentType.ARTIST),
}

enum class PlaylistEditAction(val wireName: String, val operation: String) {
    CREATE("create", "playlist.create"), UPDATE("update", "playlist.update"), DELETE("delete", "playlist.delete"),
    ADD_TRACKS("addTracks", "playlist.addTracks"), REMOVE_TRACKS("removeTracks", "playlist.removeTracks"),
    REORDER_TRACKS("reorderTracks", "playlist.reorderTracks"),
}

interface FeedCapability {
    val feedKinds: Set<FeedKind>
    suspend fun getFeed(kind: FeedKind, categoryId: String? = null, page: PageRequest = PageRequest()): SourceFeedPage
}

interface TypedSearchCapability {
    val searchTypes: Set<ContentType>
    suspend fun search(query: String, type: ContentType, page: PageRequest = PageRequest()): SourcePage<SourceItem>
}

interface AlbumCapability {
    suspend fun album(ref: AlbumRef): SourceAlbumInfo
    suspend fun albumTracks(ref: AlbumRef, page: PageRequest = PageRequest()): SourcePage<Track>
}

interface ArtistCapability {
    suspend fun artist(ref: ArtistRef): SourceArtistInfo
    suspend fun artistTracks(ref: ArtistRef, page: PageRequest = PageRequest()): SourcePage<Track>
    suspend fun artistAlbums(ref: ArtistRef, page: PageRequest = PageRequest()): SourcePage<SourceAlbumInfo>
}

interface PlaylistCapability {
    suspend fun playlist(ref: PlaylistRef): SourcePlaylist
    suspend fun playlistTracks(ref: PlaylistRef, page: PageRequest = PageRequest()): SourcePage<Track>
}

interface AccountCapability { suspend fun account(): SourceAccount? }
interface LibraryCapability {
    val collections: Set<LibraryCollection>
    suspend fun library(collection: LibraryCollection, page: PageRequest = PageRequest()): SourcePage<SourceItem>
}
interface LikeCapability {
    val canReadLikes: Boolean get() = true
    val canWriteLikes: Boolean get() = true
    suspend fun liked(ref: TrackRef): Boolean
    suspend fun setLiked(ref: TrackRef, liked: Boolean)
}
/** The expected account is checked under the same lock that starts a remote mutation. */
interface AccountScopedLikeCapability {
    suspend fun setLiked(ref: TrackRef, liked: Boolean, expectedAccountRevision: String?)
}
interface BatchLikeCapability {
    suspend fun liked(refs: List<TrackRef>): Map<TrackRef, Boolean>
}
interface CollectionCapability {
    val collectionTypes: Set<ContentType>
    val canReadCollections: Boolean get() = true
    val canWriteCollections: Boolean get() = true
    suspend fun saved(ref: ContentRef): Boolean
    suspend fun setSaved(ref: ContentRef, saved: Boolean)
}
interface PlaylistEditCapability {
    val playlistEditActions: Set<PlaylistEditAction>
    suspend fun createPlaylist(title: String, description: String? = null): SourcePlaylist = unsupportedSourceOperation()
    suspend fun createPlaylist(title: String, description: String?, privacy: Boolean, type: String): SourcePlaylist {
        if (privacy || type != "NORMAL") unsupportedSourceOperation()
        return createPlaylist(title, description)
    }
    suspend fun updatePlaylist(ref: PlaylistRef, title: String? = null, description: String? = null): Unit = unsupportedSourceOperation()
    suspend fun deletePlaylist(ref: PlaylistRef): Unit = unsupportedSourceOperation()
    suspend fun addTracks(ref: PlaylistRef, tracks: List<TrackRef>): Unit = unsupportedSourceOperation()
    suspend fun removeTracks(ref: PlaylistRef, tracks: List<TrackRef>): Unit = unsupportedSourceOperation()
    suspend fun reorderTracks(ref: PlaylistRef, tracks: List<TrackRef>): Unit = unsupportedSourceOperation()
}

private fun unsupportedSourceOperation(): Nothing = throw SourceException(SourceErrorCode.UNSUPPORTED)

/** Opaque revisions let host pages cancel account-owned work without receiving credentials. */
interface AccountRevisionCapability { val accountRevision: kotlinx.coroutines.flow.Flow<String?> }
