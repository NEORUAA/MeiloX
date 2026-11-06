package com.ljyh.mei.source.script

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ljyh.mei.source.api.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Only capability adapters and JSON values cross the isolated runtime boundary. */
internal class ScriptMusicProvider(
    val manifest: PluginManifest,
    private val directory: File,
    private val runtime: ScriptRuntime,
    enabled: Boolean,
    private val configurationSnapshot: Map<String, String>,
    private val isCurrent: () -> Boolean,
    private val config: () -> Map<String, String>,
    private val storage: () -> Map<String, String>,
    private val saveStorage: (Map<String, String>) -> Unit,
    private val onFailure: (SourceErrorCode) -> Unit,
    configurationVersion: Long,
) : MusicProvider, CatalogCapability, PlaybackCapability, LyricsCapability, ResourceValidationCapability,
    FeedCapability, TypedSearchCapability, AlbumCapability, ArtistCapability, PlaylistCapability,
    AccountCapability, LibraryCapability, LikeCapability, CollectionCapability, PlaylistEditCapability {
    override val id = manifest.providerId
    override val instanceId = ProviderInstanceId("script:${manifest.id}:default")
    override val displayName = manifest.name
    override val version = "${manifest.version}+$configurationVersion"
    override val capabilities = manifest.sourceCapabilities
    override val feedKinds = FeedKind.entries.filter { it.operation in manifest.capabilities }.toSet()
    override val searchTypes = ContentType.entries.filter { it.wireName in manifest.searchTypes }.toSet()
    override val collections = LibraryCollection.entries.filter { it.operation in manifest.capabilities }.toSet()
    override val collectionTypes = ContentType.entries.filter { it.wireName in manifest.collectionTypes }.toSet()
    override val playlistEditActions = PlaylistEditAction.entries.filter { it.operation in manifest.capabilities }.toSet()
    override val canReadLikes get() = "track.liked" in manifest.capabilities
    override val canWriteLikes get() = "track.like" in manifest.capabilities
    override val canReadCollections get() = "collection.saved" in manifest.capabilities
    override val canWriteCollections get() = "collection.save" in manifest.capabilities
    @Volatile var enabled = enabled
    @Volatile var failed = false
    override val status: SourceStatus get() = when {
        !enabled || !isCurrent() -> SourceStatus.DISABLED
        !manifest.supported || !runtime.supported -> SourceStatus.INCOMPATIBLE
        failed -> SourceStatus.FAILED
        manifest.config.any { it.required && configurationSnapshot[it.key].isNullOrBlank() } -> SourceStatus.NEEDS_CONFIGURATION
        else -> SourceStatus.READY
    }
    private val network = ScriptNetworkPolicy(manifest.permissions.network)
    private val operations = Mutex()
    private val gson = Gson()
    private val parser = ScriptContentParser(id, manifest.apiVersion, playlistEditActions) { network.artwork(it, directory, ::ensureCurrent) }

    override suspend fun search(query: String, page: PageRequest): SourcePage<Track> = validated {
        if (manifest.apiVersion >= 2) {
            val result = search(query, ContentType.TRACK, page)
            return@validated SourcePage(result.items.map { (it as? SourceItem.TrackItem)?.track ?: invalid() }, result.hasMore, result.nextCursor)
        }
        val result = invoke("catalog.search", pageParams(page).apply { addProperty("query", query) }).asJsonObject
        val tracks = result.getAsJsonArray("items")?.take(page.limit)?.map { parseTrack(it.asJsonObject) }.orEmpty()
        SourcePage(tracks, result.get("hasMore")?.asBoolean ?: false)
    }

    override suspend fun search(query: String, type: ContentType, page: PageRequest): SourcePage<SourceItem> = validated {
        if (type !in searchTypes) throw SourceException(SourceErrorCode.UNSUPPORTED)
        if (manifest.apiVersion == 1) {
            val result = search(query, page)
            return@validated SourcePage<SourceItem>(result.items.map { SourceItem.TrackItem(it) }, result.hasMore, result.nextCursor)
        }
        val result = invoke("catalog.search", pageParams(page).apply {
            addProperty("query", query)
            addProperty("type", type.wireName)
        }).asJsonObject
        parsePage(result, page) { parseItem(it, type) }
    }

    override suspend fun getFeed(kind: FeedKind, categoryId: String?, page: PageRequest): SourceFeedPage = validated {
        parser.feed(invoke(kind.operation, pageParams(page).apply { categoryId?.let { addProperty("categoryId", it) } }).asJsonObject, page)
    }

    override suspend fun album(ref: AlbumRef): SourceAlbumInfo = validated {
        requireRef(ref)
        parseAlbum(invoke("album.get", refParams("albumId", ref)).asJsonObject).also { if (it.ref != ref) invalid() }
    }
    override suspend fun albumTracks(ref: AlbumRef, page: PageRequest): SourcePage<Track> = trackPage("album.tracks", "albumId", ref, page)
    override suspend fun artist(ref: ArtistRef): SourceArtistInfo = validated {
        requireRef(ref)
        parseArtist(invoke("artist.get", refParams("artistId", ref)).asJsonObject).also { if (it.ref != ref) invalid() }
    }
    override suspend fun artistTracks(ref: ArtistRef, page: PageRequest): SourcePage<Track> = trackPage("artist.tracks", "artistId", ref, page)
    override suspend fun artistAlbums(ref: ArtistRef, page: PageRequest): SourcePage<SourceAlbumInfo> = validated {
        requireRef(ref)
        parsePage(invoke("artist.albums", refParams("artistId", ref, page)).asJsonObject, page, ::parseAlbum)
    }
    override suspend fun playlist(ref: PlaylistRef): SourcePlaylist = validated {
        requireRef(ref)
        parsePlaylist(invoke("playlist.get", refParams("playlistId", ref)).asJsonObject).also { if (it.ref != ref) invalid() }
    }
    override suspend fun playlistTracks(ref: PlaylistRef, page: PageRequest): SourcePage<Track> = trackPage("playlist.tracks", "playlistId", ref, page)

    override suspend fun account(): SourceAccount? = validated {
        val result = invoke("account.get", JsonObject())
        if (result.isJsonNull) null else parser.account(result.asJsonObject, instanceId, version)
    }
    override suspend fun library(collection: LibraryCollection, page: PageRequest): SourcePage<SourceItem> = validated {
        parsePage(invoke(collection.operation, pageParams(page)).asJsonObject, page) { parseItem(it, collection.type) }
    }
    override suspend fun liked(ref: TrackRef): Boolean = validated {
        requireRef(ref)
        boolean(invoke("track.liked", refParams("trackId", ref)))
    }
    override suspend fun setLiked(ref: TrackRef, liked: Boolean) = validated {
        requireRef(ref)
        confirm(invoke("track.like", refParams("trackId", ref).apply { addProperty("liked", liked) }))
    }
    override suspend fun saved(ref: ContentRef): Boolean = validated {
        requireCollectionRef(ref)
        boolean(invoke("collection.saved", collectionParams(ref)))
    }
    override suspend fun setSaved(ref: ContentRef, saved: Boolean) = validated {
        requireCollectionRef(ref)
        confirm(invoke("collection.save", collectionParams(ref).apply { addProperty("saved", saved) }))
    }
    override suspend fun createPlaylist(title: String, description: String?): SourcePlaylist = validated {
        requireTitle(title)
        parsePlaylist(invoke("playlist.create", JsonObject().apply {
            addProperty("title", title)
            description?.let { requireDescription(it); addProperty("description", it) }
        }).asJsonObject)
    }
    override suspend fun updatePlaylist(ref: PlaylistRef, title: String?, description: String?) = validated {
        requireRef(ref)
        if (title == null && description == null) throw SourceException(SourceErrorCode.UNSUPPORTED)
        confirm(invoke("playlist.update", refParams("playlistId", ref).apply {
            title?.let { requireTitle(it); addProperty("title", it) }
            description?.let { requireDescription(it); addProperty("description", it) }
        }))
    }
    override suspend fun deletePlaylist(ref: PlaylistRef) = validated {
        requireRef(ref)
        confirm(invoke("playlist.delete", refParams("playlistId", ref)))
    }
    override suspend fun addTracks(ref: PlaylistRef, tracks: List<TrackRef>) = editTracks("playlist.addTracks", ref, tracks)
    override suspend fun removeTracks(ref: PlaylistRef, tracks: List<TrackRef>) = editTracks("playlist.removeTracks", ref, tracks)
    override suspend fun reorderTracks(ref: PlaylistRef, tracks: List<TrackRef>) = editTracks("playlist.reorderTracks", ref, tracks)

    private suspend fun trackPage(operation: String, key: String, ref: ContentRef, page: PageRequest): SourcePage<Track> = validated {
        requireRef(ref)
        parsePage(invoke(operation, refParams(key, ref, page)).asJsonObject, page, ::parseTrack)
    }
    private suspend fun editTracks(operation: String, ref: PlaylistRef, tracks: List<TrackRef>) = validated {
        requireRef(ref)
        if (tracks.size > 1000) throw SourceException(SourceErrorCode.UNSUPPORTED)
        tracks.forEach(::requireRef)
        confirm(invoke(operation, refParams("playlistId", ref).apply { add("trackIds", gson.toJsonTree(tracks.map { it.trackId })) }))
    }

    override suspend fun getTrack(ref: TrackRef): Track? = validated {
        requireRef(ref)
        val result = invoke("catalog.track", JsonObject().apply { addProperty("trackId", ref.trackId) })
        if (result.isJsonNull) return@validated null
        parseTrack(result.asJsonObject).also { if (it.ref != ref) invalid() }
    }

    override suspend fun resolve(ref: TrackRef, requestedQuality: String): AudioResource = validated {
        requireRef(ref)
        val result = invoke("playback.resolve", JsonObject().apply {
            addProperty("trackId", ref.trackId)
            addProperty("quality", requestedQuality)
        }).asJsonObject
        val quality = result.get("quality")?.asString?.takeIf { it.isNotBlank() && it.length <= 50 } ?: invalid()
        val headers = result.getAsJsonObject("headers")?.entrySet()?.associate { it.key to it.value.asString }.orEmpty()
        val resource = AudioResource(
            uri = result.get("uri")?.asString ?: invalid(),
            quality = quality,
            headers = headers,
            expiresAtMs = result.get("expiresAtMs")?.takeUnless { it.isJsonNull }?.asLong,
            contentLength = result.get("contentLength")?.takeUnless { it.isJsonNull }?.asLong,
            contentId = result.get("contentId")?.takeUnless { it.isJsonNull }?.asString?.take(256),
            mimeType = result.get("mimeType")?.takeUnless { it.isJsonNull }?.asString?.take(100),
            cachePolicy = if (result.get("cachePolicy")?.asString == "NO_STORE") CachePolicy.NO_STORE else CachePolicy.ALLOW,
        )
        if (resource.expiresAtMs?.let { it <= System.currentTimeMillis() } == true || resource.contentLength?.let { it <= 0 } == true) invalid()
        ensureCurrent()
        ScriptAudioResources.register(manifest.id, resource, network, directory)
    }

    override suspend fun getLyrics(ref: TrackRef): SourceLyrics? = validated {
        requireRef(ref)
        val result = invoke("lyrics.get", JsonObject().apply { addProperty("trackId", ref.trackId) })
        if (result.isJsonNull) return@validated null
        SourceLyrics(result.asJsonObject.get("text")?.asString ?: invalid(), result.asJsonObject.get("translation")?.asString)
    }

    override fun acceptsResource(resource: AudioResource): Boolean = ScriptAudioResources.isRegistered(resource.uri)

    private suspend fun <T> validated(block: suspend () -> T): T = try {
        ensureCurrent()
        block().also { ensureCurrent() }
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (error: SourceException) {
        ensureCurrent()
        if (error.code == SourceErrorCode.INVALID_RESPONSE && !failed) {
            failed = true
            onFailure(error.code)
        }
        throw error
    } catch (_: Exception) {
        ensureCurrent()
        failed = true
        onFailure(SourceErrorCode.INVALID_RESPONSE)
        invalid()
    }

    private suspend fun invoke(operation: String, params: JsonObject): com.google.gson.JsonElement = withContext(Dispatchers.IO) { operations.withLock {
        ensureCurrent()
        if (operation !in manifest.capabilities) throw SourceException(SourceErrorCode.UNSUPPORTED)
        if (status != SourceStatus.READY) throw SourceException(when (status) {
            SourceStatus.NEEDS_CONFIGURATION -> SourceErrorCode.AUTH_REQUIRED
            SourceStatus.INCOMPATIBLE -> SourceErrorCode.UNSUPPORTED
            else -> SourceErrorCode.MISSING_PROVIDER
        })
        try {
            val call = runtime.invoke(File(directory, manifest.entry).readText(), manifest, operation, params, config(), storage(), network,
                ensureCurrent = ::ensureCurrent)
            ensureCurrent()
            call.storage?.let(saveStorage)
            ensureCurrent()
            call.result
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: SourceException) {
            ensureCurrent()
            if (error.code == SourceErrorCode.PLUGIN || error.code == SourceErrorCode.INVALID_RESPONSE) {
                failed = true
                onFailure(error.code)
            }
            throw error
        } catch (_: Exception) {
            ensureCurrent()
            failed = true
            onFailure(SourceErrorCode.PLUGIN)
            throw SourceException(SourceErrorCode.PLUGIN, "Source execution failed")
        }
    } }

    private fun ensureCurrent() {
        if (!isCurrent()) throw CancellationException("Source revision changed")
    }

    private suspend fun parseTrack(value: JsonObject): Track = parser.track(value)
    private suspend fun parseAlbum(value: JsonObject): SourceAlbumInfo = parser.album(value)
    private suspend fun parseArtist(value: JsonObject): SourceArtistInfo = parser.artist(value)
    private suspend fun parsePlaylist(value: JsonObject): SourcePlaylist = parser.playlist(value)
    private suspend fun parseItem(value: JsonObject, expected: ContentType? = null): SourceItem = parser.item(value, expected)
    private suspend fun <T> parsePage(value: JsonObject, request: PageRequest, parse: suspend (JsonObject) -> T): SourcePage<T> =
        parser.page(value, request, parse)
    private fun boolean(value: com.google.gson.JsonElement): Boolean = parser.boolean(value)
    private fun confirm(value: com.google.gson.JsonElement) {
        if (!boolean(value)) throw SourceException(SourceErrorCode.UNAVAILABLE)
    }
    private fun pageParams(page: PageRequest) = JsonObject().apply {
        addProperty("offset", page.offset)
        addProperty("limit", page.limit)
        page.cursor?.let { addProperty("cursor", it) }
    }
    private fun refParams(key: String, ref: ContentRef, page: PageRequest? = null) = (page?.let(::pageParams) ?: JsonObject()).apply {
        addProperty(key, ref.contentId)
    }
    private fun collectionParams(ref: ContentRef) = JsonObject().apply {
        addProperty("type", ref.type.wireName)
        addProperty("id", ref.contentId)
    }
    private fun requireCollectionRef(ref: ContentRef) {
        requireRef(ref)
        if (ref.type !in collectionTypes) throw SourceException(SourceErrorCode.UNSUPPORTED)
    }
    private fun requireRef(ref: ContentRef) {
        if (ref.providerId != id || ref.contentId.isEmpty() || ref.contentId.length > 2048) throw SourceException(SourceErrorCode.NOT_FOUND)
    }
    private fun requireTitle(title: String) {
        if (title.isBlank() || title.length > 1000) throw SourceException(SourceErrorCode.UNSUPPORTED)
    }
    private fun requireDescription(description: String) {
        if (description.length > 10_000) throw SourceException(SourceErrorCode.UNSUPPORTED)
    }
    private fun invalid(): Nothing = throw SourceException(SourceErrorCode.INVALID_RESPONSE, "Source returned invalid data")
}
