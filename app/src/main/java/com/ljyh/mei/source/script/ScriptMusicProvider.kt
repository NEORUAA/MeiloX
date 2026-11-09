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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Cache refreshes can be retried while retaining the original private account owner. */
internal class SourceCatalogInvalidatedException(
    val retryContext: CoroutineContext = EmptyCoroutineContext,
) : CancellationException("Source catalog changed")

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
    private val artworkDirectory: File = directory,
    private val coreExecutor: com.ljyh.mei.source.core.SourceFeatureExecutor? = null,
    private val coreHandler: SourceHostEffectHandler = SourceHostEffectHandler.Unsupported,
    private val artworkStore: SourceArtworkStore? = null,
) : MusicProvider, CatalogCapability, PlaybackCapability, LyricsCapability, ResourceValidationCapability,
    FeedCapability, TypedSearchCapability, AlbumCapability, ArtistCapability, PlaylistCapability,
    AccountCapability, LibraryCapability, LikeCapability, CollectionCapability, PlaylistEditCapability,
    SourceFeatureCapability, FeatureContribution, QualityCapability, AccountRevisionCapability, SourceLinkCapability, SourceFeatureContentCapability,
    AccountScopedLikeCapability, AccountScopedFeatureCapability, SourceCoreEffectCapability, BatchLikeCapability, BatchCatalogCapability, CatalogIndexCapability {
    override val id = manifest.providerId
    override val instanceId = ProviderInstanceId("script:${manifest.id}:default")
    override val displayName = manifest.name
    override val version = "${manifest.version}+$configurationVersion"
    override val capabilities = manifest.sourceCapabilities
    override val supportsBatchTrackRequests: Boolean get() = "catalog.tracks" in manifest.capabilities
    override val featureRoutes = manifest.featureRoutes
    override val features = manifest.features
    override val linkHosts = manifest.linkHosts
    private val sessionRevision = MutableStateFlow(sessionFingerprint(storage()))
    private val catalog = runtime.catalogCache
    @Volatile private var catalogOwner = catalog.bind(instanceId.value, version, sessionRevision.value)
    override val accountRevision = sessionRevision.asStateFlow()
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
        set(value) { field = value; if (!value) catalog.release(catalogOwner) }
    @Volatile var failed = false
        set(value) { field = value; if (value) catalog.release(catalogOwner) }
    override val status: SourceStatus get() = when {
        !enabled || !isCurrent() -> SourceStatus.DISABLED.also { catalog.release(catalogOwner) }
        !manifest.supported || !runtime.supported -> SourceStatus.INCOMPATIBLE
        failed -> SourceStatus.FAILED
        manifest.config.any { it.required && configurationSnapshot[it.key].isNullOrBlank() } -> SourceStatus.NEEDS_CONFIGURATION
        else -> SourceStatus.READY
    }
    internal fun platformConfiguration(): Map<String, String> {
        ensureCurrent()
        val verified = catalog.account(catalogOwner, ::ensureCurrent).second?.ref?.accountId?.value
        return config() + storage().filterKeys {
            it in setOf("cookie", "sessionType", "ursAppId", "deviceId", "sDeviceId", "musicA", "csrf")
        } + ("sdkInstance" to instanceId.value) + if (verified == null) emptyMap() else mapOf("userId" to verified)
    }
    private val network = ScriptNetworkPolicy(manifest.permissions.network, manifest.permissions.nativeSdks, ::platformConfiguration)
    private val operations = Mutex()
    private val gson = Gson()
    private val parser = ScriptContentParser(id, manifest.apiVersion, playlistEditActions, features, featureRoutes) { featureArtwork(it) }

    private class CatalogRead(val provider: ScriptMusicProvider, var owner: SourceCatalogCache.Owner, var generation: Long) :
        AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<CatalogRead>
    }
    private class InvocationOwner(val provider: ScriptMusicProvider, var owner: SourceCatalogCache.Owner) :
        AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<InvocationOwner>
    }

    private fun ensureCatalog(read: CatalogRead) {
        ensureCurrent()
        catalog.ensureOwner(read.owner) {
            if (catalogOwner !== read.owner || sessionRevision.value != read.owner.account) {
                throw CancellationException("Source catalog owner changed")
            }
            if (catalog.generation(read.owner) != read.generation) {
                throw SourceCatalogInvalidatedException(InvocationOwner(this, read.owner))
            }
        }
    }

    private suspend fun <T> catalogRead(block: suspend (CatalogRead) -> T): T {
        val inherited = currentCoroutineContext()[CatalogRead]?.takeIf { it.provider === this }
        if (inherited != null) {
            ensureCatalog(inherited)
            return block(inherited).also { ensureCatalog(inherited) }
        }
        val owner = catalogOwner
        val read = CatalogRead(this, owner, catalog.generation(owner))
        return withContext(read) { ensureCatalog(read); block(read).also { ensureCatalog(read) } }
    }

    private fun invalidateCatalog(read: CatalogRead? = null) {
        read?.let(::ensureCatalog)
        val owner = read?.owner ?: catalogOwner
        val generation = catalog.invalidateCatalog(owner) { ensureCurrent(); read?.let(::ensureCatalog) }
        if (read?.owner === owner) read.generation = generation
    }

    override suspend fun resolveLink(url: String): SourceLinkTarget? = validated {
        val uri = java.net.URI(url)
        if (uri.scheme !in setOf("https", "http") || uri.host?.lowercase() !in linkHosts || uri.userInfo != null || url.length > 8192) {
            throw SourceException(SourceErrorCode.UNSUPPORTED)
        }
        val result = invoke("links.resolve", JsonObject().apply { addProperty("url", url) })
        if (result.isJsonNull) return@validated null
        val value = result.asJsonObject
        if (value.has("contentRef")) {
            if (value.has("featureRoute")) invalid()
            val content = value.getAsJsonObject("contentRef")
            if (content.get("providerId")?.asString != id) invalid()
            val identity = content.get("contentId")?.asString ?: invalid()
            val ref = when (content.get("type")?.asString) {
                "track" -> TrackRef(id, identity)
                "album" -> AlbumRef(id, identity)
                "artist" -> ArtistRef(id, identity)
                "playlist" -> PlaylistRef(id, identity)
                else -> invalid()
            }
            SourceLinkTarget.Content(ref)
        } else {
            val route = value.get("featureRoute")?.asString?.takeIf { it in featureRoutes } ?: invalid()
            SourceLinkTarget.Feature(route, value.getAsJsonObject("parameters") ?: JsonObject())
        }
    }

    override suspend fun parseFeatureTrack(value: JsonObject): Track = validated { parser.track(value) }
    override suspend fun featureArtwork(url: String): String = validated {
        val artworkIdentity = artworkAccountIdentity()
        currentCoroutineContext()[InvocationOwner]?.takeIf { it.provider === this }?.let {
            catalog.ensureOwner(it.owner)
        }
        if (url.isNotBlank() && !SourceArtworkRegistry.isLegacy(url) && artworkStore?.owns(url) != true) {
            try { network.validateUrl(url) }
            catch (error: SourceException) {
                // Optional remote artwork may reference a new CDN. Reject the image without
                // issuing a request or discarding the source's otherwise valid catalog data.
                if (error.code == SourceErrorCode.PLUGIN) return@validated ""
                throw error
            }
        }
        when {
            url.isBlank() -> ""
            SourceArtworkRegistry.isLegacy(url) -> SourceArtworkRegistry.resolve(url)
            artworkStore?.owns(url) == true -> artworkStore.acceptOwned(this, url)
            artworkStore != null -> artworkStore.register(this, url, artworkIdentity)
            else -> network.artwork(url, artworkDirectory) { ensureArtworkCurrent(artworkIdentity) }
        }
    }

    internal fun artworkAccountIdentity(): String = sessionFingerprint(storage(), includeVersion = false) ?: "anonymous"
    internal fun ensureArtworkCurrent(identity: String) {
        ensureCurrent()
        if (status != SourceStatus.READY || artworkAccountIdentity() != identity) throw CancellationException("Source artwork owner changed")
    }
    internal fun validateArtworkUrl(url: String) { network.validateUrl(url) }
    internal suspend fun downloadArtwork(url: String, identity: String): String =
        network.artwork(url, artworkDirectory) { ensureArtworkCurrent(identity) }

    override suspend fun invokeFeature(feature: String, action: String, parameters: JsonObject): com.google.gson.JsonElement =
        invokeFeature(feature, action, parameters, sessionRevision.value, null)

    override suspend fun invokeFeature(feature: String, action: String, parameters: JsonObject,
        expectedAccountRevision: String?, onSessionCommitted: ((String?) -> Unit)?): com.google.gson.JsonElement = validated {
        if (action !in features[feature].orEmpty()) throw SourceException(SourceErrorCode.UNSUPPORTED)
        val readOnlyStorage = onSessionCommitted == null && action in manifest.concurrentReadFeatures[feature].orEmpty()
        val result = serialized(readOnlyStorage) {
            if (sessionRevision.value != expectedAccountRevision) throw CancellationException("Source account changed before feature operation")
            invokeLocked("feature.invoke", JsonObject().apply {
                addProperty("feature", feature); addProperty("action", action); add("parameters", parameters.deepCopy())
            }, onSessionCommitted, readOnlyStorage)
        }
        if (result.isJsonObject && result.asJsonObject.has("hostEffect")) result else sanitizeFeatureArtwork(result)

    }

    private suspend fun sanitizeFeatureArtwork(value: com.google.gson.JsonElement): com.google.gson.JsonElement {
        val revision = sessionRevision.value
        val imageKeys = setOf("avatarUrl", "backgroundUrl", "coverUrl", "picUrl", "artworkUrl", "avatarBadgeUrl", "imageUrl")
        suspend fun copy(element: com.google.gson.JsonElement, depth: Int = 0): com.google.gson.JsonElement {
            if (depth > 64) invalid()
            if (element.isJsonArray) return com.google.gson.JsonArray().apply { element.asJsonArray.forEach { add(copy(it, depth + 1)) } }
            if (!element.isJsonObject) return element.deepCopy()
            return JsonObject().apply { element.asJsonObject.entrySet().forEach { (key, child) ->
                if (key in imageKeys && !child.isJsonNull) {
                    if (!child.isJsonPrimitive || !child.asJsonPrimitive.isString) invalid()
                    val url = child.asString
                    if (url.isBlank()) addProperty(key, "") else if (artworkStore != null) {
                        addProperty(key, featureArtwork(url))
                    } else {
                        network.validateUrl(url)
                        val handle = SourceArtworkRegistry.register("${instanceId.value}:$version:$revision", url) {
                            ensureCurrent()
                            if (sessionRevision.value != revision) throw CancellationException("Source account changed")
                            network.artwork(url, artworkDirectory) {
                                ensureCurrent()
                                if (sessionRevision.value != revision) throw CancellationException("Source account changed")
                            }
                        }
                        addProperty(key, handle)
                    }
                } else add(key, copy(child, depth + 1))
            } }
        }
        return copy(value)
    }

    override suspend fun availableQualities(ref: TrackRef): List<String> = validated {
        requireRef(ref)
        if ("playback.qualities" !in manifest.capabilities) return@validated emptyList()
        val collected = linkedSetOf<String>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        repeat(3) {
            val response = invoke("playback.qualities", refParams("trackId", ref).apply {
                cursor?.let { addProperty("cursor", it) }
            })
            val values = if (response.isJsonArray) response.asJsonArray else response.asJsonObject.getAsJsonArray("items") ?: invalid()
            if (values.size() > 32) invalid()
            values.forEach { value -> collected += value.asString.also { if (it.length !in 1..64) invalid() } }
            if (collected.size > 32) invalid()
            val next = if (response.isJsonArray) null else response.asJsonObject.get("nextCursor")?.takeUnless { it.isJsonNull }?.asString
            if (next == null) return@validated collected.toList()
            if (next.length !in 1..128 || !cursors.add(next)) invalid()
            cursor = next
        }
        invalid()
    }

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
        catalogRead { read ->
            (catalog.albumSnapshot(read.owner, ref.albumId) { ensureCatalog(read) } ?: loadAlbum(ref, read)).album
        }
    }
    override suspend fun albumTracks(ref: AlbumRef, page: PageRequest): SourcePage<Track> = validated {
        requireRef(ref)
        catalogRead { read ->
            if (page.refresh) invalidateCatalog(read)
            val snapshot = catalog.albumSnapshot(read.owner, ref.albumId) { ensureCatalog(read) }
                ?: if (page.refresh && "album.get" in manifest.capabilities) loadAlbum(ref, read, page.limit) else null
            snapshot?.ids?.let { indexedPage(it, page, read) } ?: cachedPage("album.tracks", ref.albumId, page, read) {
                parsePage(invoke("album.tracks", refParams("albumId", ref, page)).asJsonObject, page) {
                    SourceItem.TrackItem(parseCatalogTrack(it))
                }
            }.let { values -> SourcePage(values.items.map { (it as? SourceItem.TrackItem)?.track ?: invalid() }, values.hasMore, values.nextCursor) }
        }
    }
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
        catalogRead { read ->
            val cached = catalog.snapshot(read.owner, ref.playlistId) { ensureCatalog(read) }
            val snapshot = cached ?: loadPlaylist(ref, read)
            snapshot.playlist
        }
    }
    override suspend fun playlistTracks(ref: PlaylistRef, page: PageRequest): SourcePage<Track> = validated {
        requireRef(ref)
        catalogRead { read ->
            if (page.refresh) invalidateCatalog(read)
            val cached = catalog.snapshot(read.owner, ref.playlistId) { ensureCatalog(read) }
            val snapshot = cached ?: if ("playlist.get" in manifest.capabilities)
                // The complete index is enough to hydrate the requested page through catalog.tracks.
                // Never couple the first page size to playlist metadata: large lists would make
                // the initial playlist.get response unnecessarily expensive and race the header load.
                loadPlaylist(ref, read, metadataLimit = 1) else null
            snapshot?.ids?.let { indexedPage(it, page, read) } ?: cachedPage("playlist.tracks", ref.playlistId, page, read) {
                parsePage(invoke("playlist.tracks", refParams("playlistId", ref, page)).asJsonObject, page) {
                    SourceItem.TrackItem(parseCatalogTrack(it))
                }
            }.let { values -> SourcePage(values.items.map { (it as? SourceItem.TrackItem)?.track ?: invalid() }, values.hasMore, values.nextCursor) }
        }
    }

    override suspend fun completeTrackIds(playlistId: String, collection: LibraryCollection?): SourceTrackIndex? = validated {
        catalogRead { read ->
            val snapshot = when (collection) {
                null -> {
                    val ref = PlaylistRef(id, playlistId).also(::requireRef)
                    catalog.snapshot(read.owner, playlistId) { ensureCatalog(read) }
                        ?: if ("playlist.get" in manifest.capabilities) loadPlaylist(ref, read, metadataLimit = 1) else return@catalogRead null
                }
                LibraryCollection.LIKED_TRACKS -> likedSnapshot(read, metadataLimit = 1) ?: return@catalogRead null
                else -> return@catalogRead null
            }
            snapshot.ids?.let { SourceTrackIndex(snapshot.playlist.ref.playlistId, it) }
        }
    }

    override suspend fun account(): SourceAccount? = validated {
        catalogRead { read ->
            val result = invoke("account.get", JsonObject())
            val account = if (result.isJsonNull) null else parser.account(result.asJsonObject, instanceId, version)
            catalog.putAccount(read.owner, account) { ensureCatalog(read) }
            account
        }
    }

    /** Web bridge metadata must never recursively launch another interactive host effect. */
    internal suspend fun accountWithoutEffects(expectedAccountRevision: String?): SourceAccount? = validated {
        val result = invokeCoreOperation("account.get", JsonObject(), expectedAccountRevision)
        if (result.isJsonObject && result.asJsonObject.has("hostEffect")) throw SourceException(SourceErrorCode.UNSUPPORTED)
        if (sessionRevision.value != expectedAccountRevision) throw CancellationException("Source account changed")
        (if (result.isJsonNull) null else parser.account(result.asJsonObject, instanceId, version)).also {
            catalog.putAccount(catalogOwner, it, ::ensureCurrent)
        }
    }
    override suspend fun library(collection: LibraryCollection, page: PageRequest): SourcePage<SourceItem> = validated {
        catalogRead { read ->
            if (page.refresh) invalidateCatalog(read)
            ensureVerifiedAccount(read)
            if (collection == LibraryCollection.LIKED_TRACKS) {
                val snapshot = likedSnapshot(read, page, if (page.offset == 0 && page.cursor == null) page.limit.coerceIn(1, 5000) else 1)
                if (snapshot?.ids != null) {
                    val values = indexedPage(snapshot.ids, page, read)
                    if (values != null) return@catalogRead SourcePage(values.items.map { SourceItem.TrackItem(it) }, values.hasMore, values.nextCursor)
                }
            }
            cachedPage(collection.operation, "", page, read) {
                ensureVerifiedAccount(read)
                parsePage(invoke(collection.operation, pageParams(page)).asJsonObject, page) { parseItem(it, collection.type) }
            }
        }
    }
    override suspend fun liked(ref: TrackRef): Boolean = validated {
        requireRef(ref)
        boolean(invoke("track.liked", refParams("trackId", ref)))
    }
    override suspend fun liked(refs: List<TrackRef>): Map<TrackRef, Boolean> = validated {
        if (refs.isEmpty()) return@validated emptyMap()
        if (refs.size > 100) invalid()
        refs.forEach(::requireRef)
        val unique = refs.distinct()
        val value = invoke("track.liked", JsonObject().apply {
            addProperty("trackId", unique.first().trackId)
            add("trackIds", com.google.gson.JsonArray().apply { unique.forEach { add(it.trackId) } })
        })
        if (value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) {
            // API 1/2 sources that only implement a singular read retain their contract.
            return@validated unique.associateWith { ref -> if (ref == unique.first()) value.asBoolean else liked(ref) }
        }
        val rows = value.takeIf { it.isJsonObject }?.asJsonObject?.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: invalid()
        if (rows.size() != unique.size) invalid()
        val result = linkedMapOf<TrackRef, Boolean>()
        rows.forEach { element ->
            val row = element.takeIf { it.isJsonObject }?.asJsonObject ?: invalid()
            val key = row.get("trackId")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: invalid()
            val ref = TrackRef(id, key)
            if (ref !in unique || result.put(ref, boolean(row.get("liked") ?: invalid())) != null) invalid()
        }
        result
    }
    override suspend fun setLiked(ref: TrackRef, liked: Boolean) = validated {
        requireRef(ref)
        confirm(invoke("track.like", refParams("trackId", ref).apply { addProperty("liked", liked) }))
    }
    override suspend fun setLiked(ref: TrackRef, liked: Boolean, expectedAccountRevision: String?) = validated {
        requireRef(ref)
        val parameters = refParams("trackId", ref).apply { addProperty("liked", liked) }
        if (coreExecutor != null) {
            confirm(coreExecutor.executeCore(this, "track.like", parameters, coreHandler, expectedAccountRevision))
        } else serialized {
            ensureCurrent()
            if (sessionRevision.value != expectedAccountRevision) {
                throw CancellationException("Source account changed before updating a favorite")
            }
            confirm(invokeLocked("track.like", parameters))
        }
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
    override suspend fun createPlaylist(title: String, description: String?, privacy: Boolean, type: String): SourcePlaylist = validated {
        requireTitle(title)
        if (type !in setOf("NORMAL", "VIDEO", "SHARED")) throw SourceException(SourceErrorCode.UNSUPPORTED)
        parsePlaylist(invoke("playlist.create", JsonObject().apply {
            addProperty("title", title); addProperty("privacy", privacy); addProperty("type", type)
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

    private suspend fun parseCatalogTrack(value: JsonObject): Track {
        if (artworkStore != null) return parser.track(value)
        val lazyArtwork = ScriptContentParser(id, manifest.apiVersion, playlistEditActions, features, featureRoutes) { url ->
            try {
                sanitizeFeatureArtwork(JsonObject().apply { addProperty("artworkUrl", url) })
                    .asJsonObject.get("artworkUrl").asString
            } catch (error: SourceException) {
                if (error.code == SourceErrorCode.PLUGIN) "" else throw error
            }
        }
        return lazyArtwork.track(value)
    }

    private suspend fun loadAlbum(ref: AlbumRef, read: CatalogRead, metadataLimit: Int = 30): SourceCatalogCache.AlbumSnapshot {
        val params = refParams("albumId", ref)
        val value = try {
            metadataResult("album.get", params, metadataLimit.coerceIn(1, 5000)).asJsonObject
        } catch (error: SourceResponseSizeException) {
            // Optional indexes must not make an otherwise usable legacy album unavailable.
            invoke("album.get", params.deepCopy().apply { addProperty("includeTracks", false) }).asJsonObject
        }
        val album = parseAlbum(value).also { if (it.ref != ref) invalid() }
        val canHydrate = "catalog.track" in manifest.capabilities
        val ids = if (canHydrate) catalogIds(value) else null
        if (ids != null && album.trackCount > ids.size) invalid()
        if (canHydrate) storeCatalogRows(value, ids, read)
        val snapshot = SourceCatalogCache.AlbumSnapshot(album, ids)
        catalog.putAlbumSnapshot(read.owner, snapshot) { ensureCatalog(read) }
        return snapshot
    }

    private suspend fun loadPlaylist(ref: PlaylistRef, read: CatalogRead, metadataLimit: Int = 1): SourceCatalogCache.Snapshot {
        val value = metadataResult("playlist.get", refParams("playlistId", ref), metadataLimit).asJsonObject
        if (value.get("playlistId")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString != ref.playlistId) invalid()
        return storeSnapshot(value, read, metadataLimit)
    }

    private suspend fun metadataResult(operation: String, params: JsonObject, metadataLimit: Int): com.google.gson.JsonElement = try {
        invoke(operation, params.deepCopy().apply { addProperty("metadataLimit", metadataLimit) })
    } catch (error: SourceResponseSizeException) {
        if (metadataLimit == 1) throw error
        invoke(operation, params.deepCopy().apply { addProperty("metadataLimit", 1) })
    }

    private suspend fun storeSnapshot(value: JsonObject, read: CatalogRead, metadataLimit: Int): SourceCatalogCache.Snapshot {
        val playlist = parsePlaylist(value)
        val ids = catalogIds(value)
        storeCatalogRows(value, ids, read)
        val snapshot = SourceCatalogCache.Snapshot(playlist, ids, metadataLimit)
        catalog.putSnapshot(read.owner, snapshot) { ensureCatalog(read) }
        return snapshot
    }

    private fun catalogIds(value: JsonObject): List<TrackRef>? =
        value.get("trackIds")?.takeUnless { it.isJsonNull }?.let { rows ->
            if (!rows.isJsonArray || rows.asJsonArray.size() > 100_000) invalid()
            rows.asJsonArray.map { row ->
                if (!row.isJsonPrimitive || !row.asJsonPrimitive.isString) invalid()
                TrackRef(id, row.asString).also(::requireRef)
            }
        }

    private suspend fun storeCatalogRows(value: JsonObject, ids: List<TrackRef>?, read: CatalogRead) {
        val allowedIds = ids?.toHashSet()
        val rows = value.get("tracks")?.takeUnless { it.isJsonNull }
        if (rows != null && (!rows.isJsonArray || rows.asJsonArray.size() > 5000)) invalid()
        val tracks = rows?.asJsonArray?.mapNotNull { row ->
            ensureCatalog(read)
            try {
                if (!row.isJsonObject) null else parseCatalogTrack(row.asJsonObject).also { track ->
                    if (allowedIds != null && track.ref !in allowedIds) invalid()
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: SourceException) {
                if (error.code !in setOf(SourceErrorCode.INVALID_RESPONSE, SourceErrorCode.NOT_FOUND)) throw error
                null
            }
        }.orEmpty()
        catalog.putTracks(read.owner, tracks) { ensureCatalog(read) }
    }

    private suspend fun ensureVerifiedAccount(read: CatalogRead) {
        if (SourceCapability.ACCOUNT !in capabilities) return
        val (known, value) = catalog.account(read.owner) { ensureCatalog(read) }
        val account = if (known) value else account()
        if (account == null) throw SourceException(SourceErrorCode.AUTH_REQUIRED)
    }

    private suspend fun likedSnapshot(read: CatalogRead, page: PageRequest = PageRequest(), metadataLimit: Int = 30): SourceCatalogCache.Snapshot? {
        ensureVerifiedAccount(read)
        catalog.likedPlaylist(read.owner) { ensureCatalog(read) }?.let { playlistId ->
            catalog.snapshot(read.owner, playlistId) { ensureCatalog(read) }?.let { return it }
        }
        val key = SourceCatalogCache.PageKey(LibraryCollection.LIKED_TRACKS.operation, "", page.copy(refresh = false))
        if (catalog.page(read.owner, key) { ensureCatalog(read) } != null) return null
        val result = metadataResult(LibraryCollection.LIKED_TRACKS.operation, pageParams(page).apply { addProperty("metadataOnly", true) }, metadataLimit).asJsonObject
        if (result.has("items")) {
            val legacy = parsePage(result, page) { parseItem(it, ContentType.TRACK) }
            catalog.putPage(read.owner, key, legacy) { ensureCatalog(read) }
            return null
        }
        return storeSnapshot(result, read, metadataLimit).also { snapshot ->
            catalog.putLikedPlaylist(read.owner, snapshot.playlist.ref.playlistId) { ensureCatalog(read) }
        }
    }

    private suspend fun indexedPage(ids: List<TrackRef>, page: PageRequest, read: CatalogRead): SourcePage<Track>? {
        val offset = page.cursor?.toIntOrNull()?.takeIf { it >= 0 } ?: page.offset
        val end = (offset.toLong() + page.limit).coerceAtMost(ids.size.toLong()).toInt()
        if (offset > ids.size) return SourcePage(emptyList())
        val refs = ids.subList(offset, end)
        if ("catalog.track" !in manifest.capabilities && catalog.tracks(read.owner, refs) { ensureCatalog(read) }.size < refs.distinct().size) return null
        val tracks = getTracks(refs)
        ensureCatalog(read)
        return SourcePage(tracks, end < ids.size, if (end < ids.size) end.toString() else null)
    }

    private suspend fun cachedPage(operation: String, identity: String, page: PageRequest, read: CatalogRead,
        load: suspend () -> SourcePage<SourceItem>): SourcePage<SourceItem> {
        val key = SourceCatalogCache.PageKey(operation, identity, page.copy(refresh = false))
        return catalog.page(read.owner, key) { ensureCatalog(read) } ?: load().also { value ->
            catalog.putTracks(read.owner, value.items.mapNotNull { (it as? SourceItem.TrackItem)?.track }) { ensureCatalog(read) }
            catalog.putPage(read.owner, key, value) { ensureCatalog(read) }
        }
    }

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
        catalogRead { read ->
            catalog.tracks(read.owner, listOf(ref)) { ensureCatalog(read) }[ref]?.let { return@catalogRead it }
            val result = invoke("catalog.track", JsonObject().apply { addProperty("trackId", ref.trackId) })
            if (result.isJsonNull) return@catalogRead null
            parseCatalogTrack(result.asJsonObject).also {
                if (it.ref != ref) invalid()
                catalog.putTracks(read.owner, listOf(it)) { ensureCatalog(read) }
            }
        }
    }

    override suspend fun getTracks(refs: List<TrackRef>): List<Track> = validated {
        refs.forEach(::requireRef)
        catalogRead { read ->
            val tracks = catalog.tracks(read.owner, refs) { ensureCatalog(read) }.toMutableMap()
            val missing = refs.distinct().filterNot(tracks::containsKey)
            if ("catalog.tracks" !in manifest.capabilities) {
                missing.mapNotNull { getTrack(it) }.forEach { tracks[it.ref] = it }
            } else missing.chunked(manifest.catalogBatchSize).forEach { batch ->
                val result = invoke("catalog.tracks", JsonObject().apply { add("trackIds", gson.toJsonTree(batch.map { it.trackId })) })
                if (!result.isJsonArray || result.asJsonArray.size() > batch.size) invalid()
                val requested = batch.toSet()
                val values = result.asJsonArray.map { value ->
                    if (!value.isJsonObject) invalid()
                    parseCatalogTrack(value.asJsonObject).also { if (it.ref !in requested) invalid() }
                }
                if (values.map { it.ref }.distinct().size != values.size) invalid()
                catalog.putTracks(read.owner, values) { ensureCatalog(read) }
                values.forEach { tracks[it.ref] = it }
            }
            refs.mapNotNull(tracks::get)
        }
    }

    override suspend fun resolve(ref: TrackRef, requestedQuality: String): AudioResource = validated {
        requireRef(ref)
        val result = playbackResponse(ref, requestedQuality)
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
        try { ScriptAudioResources.register(manifest.id, resource, network, directory) }
        catch (error: SourceException) {
            // Rejected metadata never opens a connection. Keep other source operations usable;
            // a script that actively requests an undeclared host still fails at the network bridge.
            if (error.code == SourceErrorCode.PLUGIN) {
                throw SourceException(SourceErrorCode.INVALID_RESPONSE, "Source returned an invalid audio resource")
            }
            throw error
        }
    }

    private suspend fun playbackResponse(ref: TrackRef, quality: String): JsonObject {
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        repeat(3) {
            val result = invoke("playback.resolve", JsonObject().apply {
                addProperty("trackId", ref.trackId); addProperty("quality", quality)
                cursor?.let { addProperty("cursor", it) }
            }).asJsonObject
            if (result.has("uri")) return result
            val next = result.get("nextCursor")?.takeUnless { it.isJsonNull }?.asString ?: invalid()
            if (next.length !in 1..128 || !cursors.add(next)) invalid()
            cursor = next
        }
        invalid()
    }

    override suspend fun getLyrics(ref: TrackRef): SourceLyrics? = validated {
        requireRef(ref)
        val result = invoke("lyrics.get", JsonObject().apply { addProperty("trackId", ref.trackId) })
        if (result.isJsonNull) return@validated null
        SourceLyrics(result.asJsonObject.get("text")?.asString ?: invalid(), result.asJsonObject.get("translation")?.asString)
    }

    override fun acceptsResource(resource: AudioResource): Boolean = ScriptAudioResources.isRegistered(resource.uri)

    private suspend fun <T> validated(block: suspend () -> T): T {
        val owner = currentCoroutineContext()[InvocationOwner]?.takeIf { it.provider === this }
            ?: return withContext(InvocationOwner(this, catalogOwner)) { validated(block) }
        return try {
            ensureCurrent()
            catalog.ensureOwner(owner.owner)
            block().also { ensureCurrent(); catalog.ensureOwner(owner.owner) }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: SourceException) {
            ensureCurrent()
            if (error.code == SourceErrorCode.AUTH_REQUIRED || error.code == SourceErrorCode.AUTH_EXPIRED) {
                catalog.ensureOwner(owner.owner) {
                    ensureCurrent()
                    if (catalogOwner !== owner.owner) throw CancellationException("Source account changed")
                }
                val read = currentCoroutineContext()[CatalogRead]?.takeIf { it.provider === this }
                invalidateCatalog(read)
                catalog.putAccount(owner.owner, null, ::ensureCurrent)
            }
            throw error
        } catch (_: Exception) {
            ensureCurrent()
            // A malformed operation result does not revoke unrelated provider capabilities.
            invalid()
        }
    }

    private suspend fun invoke(operation: String, params: JsonObject): com.google.gson.JsonElement {
        val read = currentCoroutineContext()[CatalogRead]?.takeIf { it.provider === this }
        read?.let(::ensureCatalog)
        val pin = kotlinx.coroutines.currentCoroutineContext()[com.ljyh.mei.source.core.SourceMutationContext]?.owner
        if (pin != null && (pin.provider !== this || pin.version != version)) throw CancellationException("Source mutation owner changed")
        val revision = if (pin != null) pin.revision else read?.owner?.account ?: sessionRevision.value
        val readOnlyStorage = isConcurrentRead(operation)
        return coreExecutor?.executeCore(this, operation, params, coreHandler, revision) ?: serialized(readOnlyStorage) {
            read?.let(::ensureCatalog)
            if (sessionRevision.value != revision) throw CancellationException("Source account changed before core operation")
            invokeLocked(operation, params, readOnlyStorage = readOnlyStorage)
        }
    }

    override suspend fun invokeCoreOperation(operation: String, parameters: JsonObject,
        expectedAccountRevision: String?): com.google.gson.JsonElement = serialized(isConcurrentRead(operation)) {
        if (operation == "feature.invoke") throw SourceException(SourceErrorCode.UNSUPPORTED)
        if (sessionRevision.value != expectedAccountRevision) throw CancellationException("Source account changed before core operation")
        invokeLocked(operation, parameters, readOnlyStorage = isConcurrentRead(operation))
    }

    private fun isConcurrentRead(operation: String): Boolean = operation in manifest.concurrentReadOperations ||
        !manifest.permissions.storage && operation in PluginManifest.CONCURRENT_READ_OPERATIONS

    private suspend fun <T> serialized(readOnlyStorage: Boolean = false, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            if (readOnlyStorage) withTimeout(20_000) { block() }
            else operations.withScriptOperationTimeout(20_000, block)
        }
        catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            ensureCurrent()
            throw SourceException(SourceErrorCode.TIMEOUT)
        }
    }

    private suspend fun invokeLocked(operation: String, params: JsonObject,
        onSessionCommitted: ((String?) -> Unit)? = null, readOnlyStorage: Boolean = false): com.google.gson.JsonElement {
        ensureCurrent()
        val read = currentCoroutineContext()[CatalogRead]?.takeIf { it.provider === this }
        read?.let(::ensureCatalog)
        kotlinx.coroutines.currentCoroutineContext()[com.ljyh.mei.source.core.SourceMutationContext]?.owner?.let { pin ->
            if (pin.provider !== this || pin.version != version || sessionRevision.value != pin.revision) {
                throw CancellationException("Source account changed while a mutation was queued")
            }
        }
        if (operation !in manifest.capabilities) throw SourceException(SourceErrorCode.UNSUPPORTED)
        if (status != SourceStatus.READY) throw SourceException(when (status) {
            SourceStatus.NEEDS_CONFIGURATION -> SourceErrorCode.AUTH_REQUIRED
            SourceStatus.INCOMPATIBLE -> SourceErrorCode.UNSUPPORTED
            else -> SourceErrorCode.MISSING_PROVIDER
        })
        val mutationOwner = read?.owner ?: catalogOwner
        val mutation = if (operation in CATALOG_MUTATIONS) {
            catalog.beginMutation(mutationOwner) { ensureCurrent(); read?.let(::ensureCatalog) }.also {
                if (read?.owner === mutationOwner) read.generation = it.generation
            }
        } else null
        val readOwner = catalogOwner.takeIf { readOnlyStorage }
        try {
            var parameters = params
            repeat(9) { segment ->
                val callOwner = catalogOwner
                if (readOwner != null && callOwner !== readOwner) throw CancellationException("Source account changed during a read")
                val originalStorage = storage().toMap()
                val inputStorage = originalStorage.filterKeys { it !in manifest.discardStorageKeys }
                if (!readOnlyStorage && inputStorage != originalStorage) saveStorage(inputStorage)
                val verifiedUserId = catalog.account(callOwner, ::ensureCurrent).second?.ref?.accountId?.value
                val call = runtime.invoke(File(directory, manifest.entry).readText(), manifest, operation, parameters, config(), inputStorage, network,
                    verifiedUserId = verifiedUserId, readOnlyStorage = readOnlyStorage, ensureCurrent = {
                        ensureCurrent()
                        catalog.ensureOwner(callOwner)
                        read?.let(::ensureCatalog)
                    })
                ensureCurrent()
                call.storage?.let { values ->
                    if (readOnlyStorage) {
                        if (values != inputStorage) invalid()
                        return@let
                    }
                    val nextStorage = values.filterKeys { it !in manifest.discardStorageKeys }
                    saveStorage(nextStorage)
                    val next = sessionFingerprint(nextStorage)
                    try {
                        if (!(call.result.isJsonObject && call.result.asJsonObject.has("hostEffect"))) onSessionCommitted?.invoke(next)
                    } finally {
                        // Durable credentials and their revision must remain in sync even if the caller was cancelled.
                        val unknownAccountChanged = manifest.sessionKeys.isEmpty() && SourceCapability.ACCOUNT in capabilities && nextStorage != inputStorage
                        if (next != callOwner.account || unknownAccountChanged) {
                            val rebound = catalog.rebind(callOwner, next)
                            catalogOwner = rebound
                            val generation = 0L // A newly rebound cache owner starts at generation zero.
                            if (read?.owner === callOwner) {
                                read.owner = rebound
                                read.generation = generation
                            }
                            currentCoroutineContext()[InvocationOwner]?.takeIf { it.provider === this && it.owner === callOwner }?.owner = rebound
                        }
                        sessionRevision.value = next
                    }
                }
                ensureCurrent()
                readOwner?.let { catalog.ensureOwner(it) }
                val value = call.result.takeIf { it.isJsonObject }?.asJsonObject
                val control = value?.get("hostEffect")?.takeIf { it.isJsonObject }?.asJsonObject
                if (operation == "feature.invoke" || control?.get("type")?.asString != "source.continue") return call.result
                if (segment == 8 || control.getAsJsonObject("parameters")?.size()?.let { it > 0 } == true) invalid()
                val state = value.get("continuation") ?: invalid()
                if (state.toString().toByteArray(Charsets.UTF_8).size > 65_536) invalid()
                val token = ByteArray(64).also(java.security.SecureRandom()::nextBytes)
                parameters = params.deepCopy().apply { add("\$continuation", JsonObject().apply {
                    add("state", state.deepCopy())
                    add("effect", JsonObject().apply { addProperty("ok", true); add("result", com.google.gson.JsonNull.INSTANCE) })
                    addProperty("token", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(token))
                }) }
                if (parameters.toString().toByteArray(Charsets.UTF_8).size > 262_144) invalid()
            }
            invalid()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: SourceException) {
            ensureCurrent()
            if (error.code == SourceErrorCode.PLUGIN) {
                failed = true
                onFailure(error.code)
            }
            throw error
        } catch (_: Exception) {
            ensureCurrent()
            failed = true
            onFailure(SourceErrorCode.PLUGIN)
            throw SourceException(SourceErrorCode.PLUGIN, "Source execution failed")
        } finally {
            mutation?.close()
            mutation?.completedGeneration?.let { generation ->
                if (read?.owner === mutationOwner) read.generation = generation
            }
        }
    }

    private fun sessionFingerprint(values: Map<String, String>, includeVersion: Boolean = true): String? {
        if (manifest.sessionKeys.isEmpty()) return null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        if (includeVersion) digest.update(version.toByteArray(Charsets.UTF_8))
        manifest.sessionKeys.sorted().forEach { key ->
            val value = (values[key] ?: configurationSnapshot[key]).orEmpty()
            val identity = manifest.sessionCookieNames[key]?.let { names ->
                val cookies = linkedMapOf<String, String>()
                value.split(';').forEach { part ->
                    val split = part.indexOf('=')
                    if (split > 0) cookies.putIfAbsent(part.substring(0, split).trim(), part.substring(split + 1).trim())
                }
                if (names.size == 1 && ';' !in value && names.none { value.trim().startsWith("$it=") }) {
                    cookies[names.single()] = value.trim()
                }
                names.sorted().joinToString(";") { name -> "$name=${cookies[name].orEmpty()}" }
            } ?: value
            val bytes = identity.toByteArray(Charsets.UTF_8)
            digest.update(key.toByteArray(Charsets.UTF_8)); digest.update(0)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
        }
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest())
    }

    private fun ensureCurrent() {
        if (!enabled || !isCurrent()) {
            catalog.release(catalogOwner)
            throw CancellationException("Source revision changed")
        }
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
        addProperty("refresh", page.refresh)
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

    private companion object {
        val CATALOG_MUTATIONS = setOf("track.like", "collection.save", "playlist.create", "playlist.update", "playlist.delete",
            "playlist.addTracks", "playlist.removeTracks", "playlist.reorderTracks")
    }
}

/** Queue admission and execution share one bounded operation deadline. */
internal suspend fun <T> Mutex.withScriptOperationTimeout(timeoutMillis: Long, block: suspend () -> T): T =
    withTimeout(timeoutMillis) { withLock { block() } }
