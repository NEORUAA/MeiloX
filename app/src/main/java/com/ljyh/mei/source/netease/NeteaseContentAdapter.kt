package com.ljyh.mei.source.netease

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ljyh.mei.data.model.PlaylistDetail
import com.ljyh.mei.data.model.UserPlaylist
import com.ljyh.mei.data.model.api.GetAllArtistSongs
import com.ljyh.mei.data.model.api.GetArtistAlbum
import com.ljyh.mei.data.model.api.GetArtistDetail
import com.ljyh.mei.data.model.api.GetArtistSong
import com.ljyh.mei.data.model.api.GetSearch
import com.ljyh.mei.data.model.api.GetSongDetails
import com.ljyh.mei.data.model.api.toMediaData
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.model.toSourceTrack
import com.ljyh.mei.data.model.weapi.HighQualityPlaylist
import com.ljyh.mei.data.model.weapi.Like
import com.ljyh.mei.data.network.NETEASE_EXPECTED_SESSION_HEADER
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.neteaseSessionFingerprint
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.MeloXDirectService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.netease.repository.HomeRepository
import com.ljyh.mei.source.netease.repository.LibraryAccount
import com.ljyh.mei.source.netease.repository.LibraryCollectionsRepository
import com.ljyh.mei.source.netease.repository.PlayerRepository
import com.ljyh.mei.source.netease.repository.PlaylistRepository
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

/** Native platform contracts are projected here; common screens never receive NetEase DTOs. */
@Singleton
class NeteaseContentAdapter @Inject constructor(
    private val catalog: NeteaseCatalogTransport,
    private val api: ApiService,
    private val weapi: WeApiService,
    @Named("MeloXWeapi") private val direct: MeloXDirectService,
    private val home: HomeRepository,
    private val playlists: PlaylistRepository,
    private val library: LibraryCollectionsRepository,
    private val player: PlayerRepository,
) : TypedSearchCapability, FeedCapability, AlbumCapability, ArtistCapability, PlaylistCapability,
    AccountCapability, LibraryCapability, LikeCapability, CollectionCapability, PlaylistEditCapability, AccountRevisionCapability {
    override val searchTypes = ContentType.entries.toSet()
    override val feedKinds = FeedKind.entries.toSet()
    override val collections = LibraryCollection.entries.toSet()
    override val collectionTypes = setOf(ContentType.PLAYLIST, ContentType.ALBUM, ContentType.ARTIST)
    override val playlistEditActions = PlaylistEditAction.entries.toSet()
    override val accountRevision = library.accounts.map { it?.revision().orEmpty() }.distinctUntilChanged()
    private val gson = Gson()

    override suspend fun search(query: String, type: ContentType, page: PageRequest): SourcePage<SourceItem> = read { owner ->
        val response = catalog.search(GetSearch(query, type = when (type) {
            ContentType.TRACK -> 1
            ContentType.ALBUM -> 10
            ContentType.ARTIST -> 100
            ContentType.PLAYLIST -> 1000
        }, limit = page.limit, offset = page.offset))
        requireSuccess(response.code)
        val items: List<SourceItem> = when (type) {
            ContentType.TRACK -> response.result.songs.orEmpty().map { SourceItem.TrackItem(it.toMediaData().toSourceTrack()) }
            ContentType.ALBUM -> response.result.albums.orEmpty().map { album ->
                SourceItem.AlbumItem(SourceAlbumInfo(AlbumRef(ID, album.id.toString()), album.name,
                    album.picUrl, album.artists.map { SourceArtist(it.name, ArtistRef(ID, it.id.toString())) }, album.size))
            }
            ContentType.ARTIST -> response.result.artists.orEmpty().map { artist ->
                SourceItem.ArtistItem(SourceArtistInfo(ArtistRef(ID, artist.id.toString()), artist.name,
                    artist.img1v1Url.orEmpty().ifBlank { artist.picUrl.orEmpty() }))
            }
            ContentType.PLAYLIST -> response.result.playlists.orEmpty().map { playlist ->
                SourceItem.PlaylistItem(SourcePlaylist(PlaylistRef(ID, playlist.id.toString()), playlist.name,
                    playlist.coverImgUrl, playlist.creator.nickname, playlist.trackCount, playlist.description,
                    editActions(playlist.creator.userId.toString() == owner?.userId, playlist.specialType)))
            }
        }
        SourcePage(items, items.size == page.limit)
    }

    override suspend fun getFeed(kind: FeedKind, categoryId: String?, page: PageRequest): SourceFeedPage = read { owner ->
        when (kind) {
            FeedKind.HOME -> {
                if (page.offset > 0 || page.cursor != null) return@read SourceFeedPage(emptyList())
                val blocks = home.getHomePageResourceShow().value()
                val sections = blocks.mapNotNull { block ->
                    val cards = homeCards(block.dslData, block.positionCode in PLAYLIST_BLOCKS,
                        block.positionCode in SONG_BLOCKS)
                        .distinctBy { it.ref }.take(30)
                    if (cards.isEmpty()) null else SourceFeedSection(
                        block.positionCode,
                        homeSectionTitle(block.dslData) ?: "为你推荐",
                        if (cards.all { it is SourceItem.TrackItem }) SourceFeedLayout.TRACKS else SourceFeedLayout.PLAYLISTS,
                        cards,
                    )
                }
                val songIds = sections.flatMap { it.items }.filterIsInstance<SourceItem.TrackItem>()
                    .map { it.track.ref.trackId }.distinct()
                val songDetails = songIds.chunked(500).flatMap { ids ->
                    val response = catalog.getSongDetail(GetSongDetails(ids.joinToString(",")), owner?.sessionFingerprint)
                    requireSuccess(response.code)
                    response.songs.orEmpty().map { it.toMediaMetadata().toSourceTrack() }
                }.associateBy { it.ref }
                val hydrated = sections.mapNotNull { section ->
                    val items = section.items.mapNotNull { item ->
                        if (item is SourceItem.TrackItem) songDetails[item.ref]?.let { SourceItem.TrackItem(it) } else item
                    }
                    section.copy(items = items).takeIf { items.isNotEmpty() }
                }
                if (hydrated.isNotEmpty()) SourceFeedPage(hydrated) else discovery(categoryId, page)
            }
            FeedKind.DISCOVER -> discovery(categoryId, page)
        }
    }

    private suspend fun discovery(categoryId: String?, page: PageRequest): SourceFeedPage {
        val category = categoryId?.takeIf { it in CATEGORIES } ?: "全部"
        val cursor = page.cursor?.toLongOrNull()?.takeIf { it >= 0 }
            ?: if (page.cursor == null && page.offset == 0) 0 else throw SourceException(SourceErrorCode.INVALID_RESPONSE)
        val response = weapi.getHighQualityPlaylist(HighQualityPlaylist(category, page.limit, cursor))
        requireSuccess(response.code)
        val items = response.playlists.orEmpty().map { playlist ->
            SourceItem.PlaylistItem(SourcePlaylist(PlaylistRef(ID, playlist.id.toString()), playlist.name,
                playlist.coverImgUrl, playlist.creator.nickname, playlist.trackCount, playlist.description))
        }
        if (response.more && items.isNotEmpty() && response.lasttime <= cursor) {
            throw SourceException(SourceErrorCode.INVALID_RESPONSE)
        }
        return SourceFeedPage(
            sections = listOf(SourceFeedSection("playlists-$category", if (category == "全部") "精选歌单" else category,
                SourceFeedLayout.PLAYLISTS, items)),
            categories = CATEGORIES.map { SourceCategory(it, it) },
            hasMore = response.more && items.isNotEmpty(),
            nextCursor = response.lasttime.takeIf { response.more && items.isNotEmpty() && it > cursor }?.toString(),
        )
    }

    override suspend fun album(ref: AlbumRef): SourceAlbumInfo = read {
        requireRef(ref)
        val response = api.getAlbumDetail(id = ref.albumId)
        requireSuccess(response.code)
        val album = response.album
        if (album.id.toString() != ref.albumId) throw SourceException(SourceErrorCode.NOT_FOUND)
        SourceAlbumInfo(ref, album.name, album.picUrl,
            album.artists.map { SourceArtist(it.name, ArtistRef(ID, it.id.toString())) }, album.size, album.description)
    }

    override suspend fun albumTracks(ref: AlbumRef, page: PageRequest): SourcePage<Track> = read {
        requireRef(ref)
        val response = api.getAlbumDetail(id = ref.albumId)
        requireSuccess(response.code)
        if (response.album.id.toString() != ref.albumId) throw SourceException(SourceErrorCode.NOT_FOUND)
        response.songs.orEmpty().map { it.toMediaMetadata().toSourceTrack() }.page(page)
    }

    override suspend fun artist(ref: ArtistRef): SourceArtistInfo = read {
        requireRef(ref)
        val response = api.getArtistDetail(GetArtistDetail(ref.artistId))
        requireSuccess(response.code)
        val artist = response.data?.artist ?: throw SourceException(SourceErrorCode.NOT_FOUND)
        if (artist.id.toString() != ref.artistId) throw SourceException(SourceErrorCode.NOT_FOUND)
        SourceArtistInfo(ref, artist.name, artist.avatar.ifBlank { artist.cover }, artist.briefDesc)
    }

    override suspend fun artistTracks(ref: ArtistRef, page: PageRequest): SourcePage<Track> = read {
        requireRef(ref)
        val response = api.getAllArtistSongs(GetAllArtistSongs(ref.artistId, page.offset, page.limit))
        requireSuccess(response.code)
        val songs = response.songs.orEmpty().map { it.toMediaMetadata().toSourceTrack() }
        SourcePage(songs, response.more && songs.isNotEmpty())
    }

    override suspend fun artistAlbums(ref: ArtistRef, page: PageRequest): SourcePage<SourceAlbumInfo> = read {
        requireRef(ref)
        val response = api.getArtistAlbums(GetArtistAlbum(offset = page.offset, limit = page.limit), ref.artistId)
        requireSuccess(response.code)
        val albums = response.hotAlbums.orEmpty().map { album ->
            SourceAlbumInfo(AlbumRef(ID, album.id.toString()), album.name, album.picUrl,
                album.artists.map { SourceArtist(it.name, ArtistRef(ID, it.id.toString())) }, album.size)
        }
        SourcePage(albums, response.more && albums.isNotEmpty())
    }

    override suspend fun playlist(ref: PlaylistRef): SourcePlaylist = read { owner ->
        requireRef(ref)
        val detail = playlistDetail(ref, owner)
        detail.playlist.sourcePlaylist(owner)
    }

    override suspend fun playlistTracks(ref: PlaylistRef, page: PageRequest): SourcePage<Track> = read { owner ->
        requireRef(ref)
        playlistTrackPage(ref, page, owner)
    }

    private suspend fun playlistTrackPage(ref: PlaylistRef, page: PageRequest, owner: LibraryAccount?): SourcePage<Track> {
        val playlist = playlistDetail(ref, owner).playlist
        val orderedIds = playlist.trackIds.orEmpty().map { it.id.toString() }
        if (playlist.trackCount > orderedIds.size) throw SourceException(SourceErrorCode.INVALID_RESPONSE)
        val offset = page.cursor?.toIntOrNull()?.takeIf { it >= 0 }
            ?: if (page.cursor == null) page.offset else throw SourceException(SourceErrorCode.INVALID_RESPONSE)
        val ids = orderedIds.drop(offset).take(page.limit)
        if (ids.isEmpty()) return SourcePage(emptyList())
        val response = catalog.getSongDetail(GetSongDetails(ids.joinToString(",")), owner?.sessionFingerprint)
        requireSuccess(response.code)
        val tracks = response.songs.orEmpty().map { it.toMediaMetadata().toSourceTrack() }.associateBy { it.ref.trackId }
        val nextOffset = offset + ids.size
        return SourcePage(ids.mapNotNull(tracks::get), nextOffset < orderedIds.size,
            nextOffset.takeIf { it < orderedIds.size }?.toString())
    }

    private suspend fun playlistDetail(ref: PlaylistRef, owner: LibraryAccount?): PlaylistDetail {
        val detail = playlists.getPlaylistDetail(ref.playlistId, owner?.sessionFingerprint, trackLimit = 200).value()
        requireSuccess(detail.code)
        if (detail.playlist.Id.toString() != ref.playlistId) throw SourceException(SourceErrorCode.NOT_FOUND)
        return detail
    }

    override suspend fun account(): SourceAccount? {
        library.restoreAccountIfNeeded()
        return read { owner ->
            if (owner == null) return@read null
            val response = api.getAccountDetail()
            requireSuccess(response.code)
            val profile = response.profile ?: throw SourceException(SourceErrorCode.AUTH_EXPIRED)
            if (profile.userId.toString() != owner.userId) throw SourceException(SourceErrorCode.AUTH_EXPIRED)
            SourceAccount(SourceAccountRef(ProviderInstanceId(ID), ProviderAccountId(owner.userId)),
                profile.nickname, profile.avatarUrl, owner.revision())
        }
    }

    override suspend fun library(collection: LibraryCollection, page: PageRequest): SourcePage<SourceItem> = accountCall { owner ->
        when (collection) {
            LibraryCollection.LIKED_TRACKS -> {
                val likedId = library.likedPlaylistId(owner) ?: return@accountCall SourcePage(emptyList())
                val tracks = playlistTrackPage(PlaylistRef(ID, likedId.toString()), page, owner)
                SourcePage(tracks.items.map { SourceItem.TrackItem(it) }, tracks.hasMore, tracks.nextCursor)
            }
            LibraryCollection.CREATED_PLAYLISTS, LibraryCollection.SAVED_PLAYLISTS -> {
                filteredPlaylists(owner, collection == LibraryCollection.CREATED_PLAYLISTS, page)
            }
            LibraryCollection.SAVED_ALBUMS -> {
                val items = mutableListOf<SourceItem>()
                var offset = page.offset
                var hasMore: Boolean
                do {
                    val next = library.albums(owner, offset)
                    items += next.items.map { album -> SourceItem.AlbumItem(SourceAlbumInfo(
                        AlbumRef(ID, album.id.toString()), album.title, album.cover,
                        album.artist.map { SourceArtist(it.name, ArtistRef(ID, it.id.toString())) }, album.size)) }
                    hasMore = next.hasMore
                    if (hasMore && next.nextOffset <= offset) throw SourceException(SourceErrorCode.INVALID_RESPONSE)
                    offset = next.nextOffset
                } while (items.size < page.limit && hasMore)
                SourcePage(items.take(page.limit), hasMore || items.size > page.limit)
            }
            LibraryCollection.FOLLOWED_ARTISTS -> {
                val response = directCall("/weapi/artist/sublist", mapOf("limit" to page.limit, "offset" to page.offset, "total" to true), owner)
                val artists = response.array("data").mapNotNull { element ->
                    val artist = element.objectOrNull() ?: return@mapNotNull null
                    val id = artist.id("id") ?: return@mapNotNull null
                    val name = artist.text("name") ?: return@mapNotNull null
                    SourceItem.ArtistItem(SourceArtistInfo(ArtistRef(ID, id), name,
                        artist.text("img1v1Url") ?: artist.text("picUrl").orEmpty()))
                }
                SourcePage(artists, response.flag("hasMore") ?: (artists.size == page.limit))
            }
        }
    }

    private suspend fun filteredPlaylists(owner: LibraryAccount, created: Boolean, page: PageRequest): SourcePage<SourceItem> {
        val found = linkedMapOf<String, SourceItem>()
        var offset = 0
        while (true) {
            val next = library.playlists(owner, offset)
            next.items.filter { (it.creator.userId.toString() == owner.userId) == created }.forEach { playlist ->
                found.putIfAbsent(playlist.id.toString(), SourceItem.PlaylistItem(playlist.sourcePlaylist(owner)))
            }
            if (!next.hasMore || found.size > page.offset + page.limit) {
                val items = found.values.toList()
                return SourcePage(items.drop(page.offset).take(page.limit), items.size > page.offset + page.limit || next.hasMore)
            }
            if (next.nextOffset <= offset) throw SourceException(SourceErrorCode.INVALID_RESPONSE)
            offset = next.nextOffset
        }
    }

    override suspend fun liked(ref: TrackRef): Boolean = accountCall { owner ->
        requireRef(ref)
        player.checkSongLike(ref.trackId.toLong(), owner.sessionFingerprint).value()
    }

    override suspend fun setLiked(ref: TrackRef, liked: Boolean): Unit = accountCall { owner ->
        requireRef(ref)
        requireSuccess(api.like(Like(trackId = ref.trackId, like = liked), owner.sessionFingerprint).code)
        player.refreshSongLikes(setOf(ref.trackId.toLong()), owner.sessionFingerprint).value()
    }

    override suspend fun saved(ref: ContentRef): Boolean = accountCall { owner ->
        requireRef(ref)
        when (ref) {
            is AlbumRef -> library.albumSubscription(owner, ref.albumId.toLong())
            is ArtistRef -> api.getArtistSongs(GetArtistSong(), ref.artistId).let { response ->
                requireSuccess(response.code)
                response.artist.followed ?: throw SourceException(SourceErrorCode.INVALID_RESPONSE)
            }
            is PlaylistRef -> playlistDetail(ref, owner).playlist.subscribed
            is TrackRef -> throw SourceException(SourceErrorCode.UNSUPPORTED)
        }
    }

    override suspend fun setSaved(ref: ContentRef, saved: Boolean): Unit = accountCall { owner ->
        requireRef(ref)
        when (ref) {
            is AlbumRef -> library.setAlbumSubscription(owner, ref.albumId.toLong(), saved)
            is ArtistRef -> {
                directCall(if (saved) "/weapi/artist/sub" else "/weapi/artist/unsub",
                    mapOf("artistId" to ref.artistId.toLong(), "artistIds" to "[${ref.artistId}]"), owner)
            }
            is PlaylistRef -> {
                val playlist = playlistDetail(ref, owner).playlist
                if (playlist.creator.userId.toString() == owner.userId) throw SourceException(SourceErrorCode.UNSUPPORTED)
                val response = if (saved) playlists.subscribePlaylist(ref.playlistId, owner.sessionFingerprint) else playlists.unSubscribePlaylist(ref.playlistId, owner.sessionFingerprint)
                requireSuccess(response.value().code)
            }
            is TrackRef -> throw SourceException(SourceErrorCode.UNSUPPORTED)
        }
    }

    override suspend fun createPlaylist(title: String, description: String?): SourcePlaylist = accountCall { owner ->
        validateTitle(title)
        if (description != null && description.length > 1000) throw SourceException(SourceErrorCode.INVALID_RESPONSE)
        val response = playlists.createPlaylist(title, privacy = false, expectedSession = owner.sessionFingerprint).value()
        requireSuccess(response.code)
        val ref = PlaylistRef(ID, response.playlist.id.toString())
        if (description != null) updateMetadata(ref, null, description, owner)
        SourcePlaylist(ref, response.playlist.name, response.playlist.coverImgUrl,
            accountName(response.playlist.creator), response.playlist.trackCount, description, OWNED_ACTIONS)
    }

    override suspend fun updatePlaylist(ref: PlaylistRef, title: String?, description: String?): Unit = accountCall { owner ->
        requireOwned(ref, owner, PlaylistEditAction.UPDATE)
        title?.let(::validateTitle)
        updateMetadata(ref, title, description, owner)
    }

    private suspend fun updateMetadata(ref: PlaylistRef, title: String?, description: String?, owner: LibraryAccount) {
        if (description != null && description.length > 1000) throw SourceException(SourceErrorCode.INVALID_RESPONSE)
        if (title != null) directCall("/weapi/playlist/update/name", mapOf("id" to ref.playlistId.toLong(), "name" to title), owner)
        if (description != null) directCall("/weapi/playlist/desc/update", mapOf("id" to ref.playlistId.toLong(), "desc" to description), owner)
        playlists.notifyCollectionChanged()
    }

    override suspend fun deletePlaylist(ref: PlaylistRef): Unit = accountCall { owner ->
        requireOwned(ref, owner, PlaylistEditAction.DELETE)
        requireSuccess(playlists.deletePlaylist(ref.playlistId, owner.sessionFingerprint).value().code)
    }

    override suspend fun addTracks(ref: PlaylistRef, tracks: List<TrackRef>): Unit = editTracks(ref, tracks, "add", PlaylistEditAction.ADD_TRACKS)
    override suspend fun removeTracks(ref: PlaylistRef, tracks: List<TrackRef>): Unit = editTracks(ref, tracks, "del", PlaylistEditAction.REMOVE_TRACKS)
    private suspend fun editTracks(ref: PlaylistRef, tracks: List<TrackRef>, operation: String, action: PlaylistEditAction): Unit = accountCall { owner ->
        requireOwned(ref, owner, action)
        tracks.forEach(::requireRef)
        if (tracks.isEmpty()) return@accountCall
        val response = playlists.manipulateTrack(operation, ref.playlistId, tracks.distinct().joinToString(",") { it.trackId },
            expectedSession = owner.sessionFingerprint).value()
        if (response.code != 200 && !(operation == "add" && response.code == 502)) requireSuccess(response.code)
    }

    override suspend fun reorderTracks(ref: PlaylistRef, tracks: List<TrackRef>): Unit = accountCall { owner ->
        val detail = requireOwned(ref, owner, PlaylistEditAction.REORDER_TRACKS)
        tracks.forEach(::requireRef)
        val ordered = tracks.map { it.trackId.toLong() }
        val original = detail.playlist.trackIds.map { it.id }
        requireSuccess(playlists.updatePlaylistTrackOrder(ref.playlistId, original, ordered,
            owner.userId.toLong(), owner.sessionFingerprint, isCurrent = { true }).code)
    }

    private suspend fun requireOwned(ref: PlaylistRef, owner: LibraryAccount, action: PlaylistEditAction): PlaylistDetail {
        requireRef(ref)
        val detail = playlistDetail(ref, owner)
        if (action !in detail.playlist.sourcePlaylist(owner).editableActions) throw SourceException(SourceErrorCode.UNAVAILABLE)
        return detail
    }

    private suspend fun directCall(path: String, body: Map<String, Any>, owner: LibraryAccount): JsonObject {
        ensureOwner(owner)
        val response = direct.post(path, body, headers = mapOf(NETEASE_EXPECTED_SESSION_HEADER to owner.sessionFingerprint))
        ensureOwner(owner)
        requireSuccess(response.get("code")?.takeUnless(JsonElement::isJsonNull)?.asInt ?: 0)
        return response
    }

    private suspend fun <T> read(block: suspend (LibraryAccount?) -> T): T = nativeCall {
        val owner = library.accounts.first()
        try { block(owner) } finally { ensureOwner(owner) }
    }
    private suspend fun <T> accountCall(block: suspend (LibraryAccount) -> T): T = read { owner ->
        block(owner ?: throw SourceException(SourceErrorCode.AUTH_REQUIRED))
    }
    private suspend fun ensureOwner(expected: LibraryAccount?) {
        if (library.accounts.first() != expected) throw CancellationException("NetEase account changed")
    }
    private fun LibraryAccount.revision() = neteaseSessionFingerprint("$userId:$sessionFingerprint")

    private fun PlaylistDetail.Playlist.sourcePlaylist(owner: LibraryAccount?) = SourcePlaylist(
        PlaylistRef(ID, Id.toString()), name, coverImgUrl, creator.nickname, trackCount, description,
        editActions(creator.userId.toString() == owner?.userId, specialType),
    )
    private fun UserPlaylist.Playlist.sourcePlaylist(owner: LibraryAccount?) = SourcePlaylist(
        PlaylistRef(ID, id.toString()), name, coverImgUrl, creator.nickname, trackCount, description,
        editActions(creator.userId.toString() == owner?.userId, specialType),
    )
    private fun accountName(value: Any?): String = gson.toJsonTree(value).objectOrNull()?.text("nickname").orEmpty()

    private fun homeCards(element: JsonElement?, assumePlaylist: Boolean, assumeTrack: Boolean): List<SourceItem> {
        if (element == null || element.isJsonNull) return emptyList()
        if (element.isJsonArray) return element.asJsonArray.flatMap { homeCards(it, assumePlaylist, assumeTrack) }
        val node = element.objectOrNull() ?: return emptyList()
        val id = node.id("resourceId")
        val title = node.text("title")
        val type = node.text("resourceType")
        if (id != null && !title.isNullOrBlank() && (assumeTrack || type == "song")) {
            return listOf(SourceItem.TrackItem(Track(TrackRef(ID, id), title,
                node.text("artistName")?.let { listOf(SourceArtist(it)) }.orEmpty(),
                artworkUrl = node.text("coverUrl").orEmpty())))
        }
        if (id != null && !title.isNullOrBlank() && (assumePlaylist || type in setOf("playList", "playlist"))) {
            return listOf(SourceItem.PlaylistItem(SourcePlaylist(PlaylistRef(ID, id), title,
                node.text("coverImg") ?: node.text("picUrl").orEmpty())))
        }
        return node.entrySet().flatMap { homeCards(it.value, assumePlaylist, assumeTrack) }
    }
    private fun homeSectionTitle(element: JsonElement?): String? {
        val node = element.objectOrNull() ?: return null
        node.get("header").objectOrNull()?.text("title")?.let { return it }
        if (node.get("resources")?.isJsonArray == true || node.get("blockResource")?.isJsonArray == true) return node.text("title")
        return node.entrySet().firstNotNullOfOrNull { homeSectionTitle(it.value) }
    }

    private fun requireRef(ref: ContentRef) {
        if (ref.providerId != ID || ref.contentId.toLongOrNull()?.let { it > 0 } != true) throw SourceException(SourceErrorCode.NOT_FOUND)
    }
    private fun validateTitle(title: String) {
        if (title.isBlank() || title.length > 100) throw SourceException(SourceErrorCode.INVALID_RESPONSE)
    }
    private fun <T> List<T>.page(page: PageRequest) = SourcePage(drop(page.offset).take(page.limit), page.offset + page.limit < size)
    private fun <T> Resource<T>.value(): T = when (this) {
        is Resource.Success -> data
        is Resource.Error -> throw SourceException(SourceErrorCode.NETWORK)
        Resource.Loading -> throw SourceException(SourceErrorCode.UNAVAILABLE)
    }
    private fun JsonElement?.objectOrNull(): JsonObject? = this?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        ?.takeIf { it.isString || it.isNumber }?.asString?.takeIf { it.isNotBlank() && it != "null" }
    private fun JsonObject.id(key: String) = text(key)?.takeIf { it.toLongOrNull()?.let { id -> id > 0 } == true }
    private fun JsonObject.flag(key: String): Boolean? = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        ?.takeIf { it.isBoolean }?.asBoolean
    private fun JsonObject.array(key: String) = get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()

    private companion object {
        const val ID = ProviderIds.NETEASE
        val OWNED_ACTIONS = PlaylistEditAction.entries.toSet() - PlaylistEditAction.CREATE
        val CATEGORIES = listOf("全部", "华语", "欧美", "日语", "韩语", "ACG", "流行", "摇滚", "电子", "古典")
        val PLAYLIST_BLOCKS = setOf("PAGE_RECOMMEND_RADAR", "PAGE_RECOMMEND_SPECIAL_CLOUD_VILLAGE_PLAYLIST",
            "PAGE_RECOMMEND_MIXED_ARTIST_PLAYLIST", "PAGE_RECOMMEND_RANK", "PAGE_RECOMMEND_MY_SHEET",
            "PAGE_RECOMMEND_COMBINATION", "PAGE_RECOMMEND_FEELING_PLAYLIST_LOCATION", "PAGE_RECOMMEND_SCENE_PLAYLIST_LOCATION",
            "PAGE_RECOMMEND_MONTH_YEAR_PLAYLIST")
        val SONG_BLOCKS = setOf("PAGE_RECOMMEND_PRIVATE_RCMD_SONG", "PAGE_RECOMMEND_RED_SIMILAR_SONG")
        fun editActions(owned: Boolean, specialType: Int): Set<PlaylistEditAction> = when {
            !owned -> emptySet()
            specialType == 5 -> OWNED_ACTIONS - setOf(PlaylistEditAction.UPDATE, PlaylistEditAction.DELETE)
            else -> OWNED_ACTIONS
        }
    }
}
