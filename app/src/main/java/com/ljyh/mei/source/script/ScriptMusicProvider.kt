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
    AccountScopedLikeCapability, AccountScopedFeatureCapability, SourceCoreEffectCapability, BatchLikeCapability {
    override val id = manifest.providerId
    override val instanceId = ProviderInstanceId("script:${manifest.id}:default")
    override val displayName = manifest.name
    override val version = "${manifest.version}+$configurationVersion"
    override val capabilities = manifest.sourceCapabilities
    override val featureRoutes = manifest.featureRoutes
    override val features = manifest.features
    override val linkHosts = manifest.linkHosts
    private val sessionRevision = MutableStateFlow(sessionFingerprint(storage()))
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
    @Volatile var failed = false
    override val status: SourceStatus get() = when {
        !enabled || !isCurrent() -> SourceStatus.DISABLED
        !manifest.supported || !runtime.supported -> SourceStatus.INCOMPATIBLE
        failed -> SourceStatus.FAILED
        manifest.config.any { it.required && configurationSnapshot[it.key].isNullOrBlank() } -> SourceStatus.NEEDS_CONFIGURATION
        else -> SourceStatus.READY
    }
    internal fun platformConfiguration(): Map<String, String> {
        ensureCurrent()
        return config() + storage().filterKeys {
            it in setOf("cookie", "sessionType", "ursAppId", "deviceId", "sDeviceId", "musicA", "csrf", "userId")
        } + ("sdkInstance" to instanceId.value)
    }
    private val network = ScriptNetworkPolicy(manifest.permissions.network, manifest.permissions.nativeSdks, ::platformConfiguration)
    private val operations = Mutex()
    private val gson = Gson()
    private val parser = ScriptContentParser(id, manifest.apiVersion, playlistEditActions, features, featureRoutes) { featureArtwork(it) }

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
            artworkStore != null -> artworkStore.register(this, url)
            else -> network.artwork(url, artworkDirectory, ::ensureCurrent)
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
        val result = serialized {
            if (sessionRevision.value != expectedAccountRevision) throw CancellationException("Source account changed before feature operation")
            invokeLocked("feature.invoke", JsonObject().apply {
                addProperty("feature", feature); addProperty("action", action); add("parameters", parameters.deepCopy())
            }, onSessionCommitted)
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

    /** Web bridge metadata must never recursively launch another interactive host effect. */
    internal suspend fun accountWithoutEffects(expectedAccountRevision: String?): SourceAccount? = validated {
        val result = invokeCoreOperation("account.get", JsonObject(), expectedAccountRevision)
        if (result.isJsonObject && result.asJsonObject.has("hostEffect")) throw SourceException(SourceErrorCode.UNSUPPORTED)
        if (sessionRevision.value != expectedAccountRevision) throw CancellationException("Source account changed")
        if (result.isJsonNull) null else parser.account(result.asJsonObject, instanceId, version)
    }
    override suspend fun library(collection: LibraryCollection, page: PageRequest): SourcePage<SourceItem> = validated {
        parsePage(invoke(collection.operation, pageParams(page)).asJsonObject, page) { parseItem(it, collection.type) }
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

    private suspend fun <T> validated(block: suspend () -> T): T = try {
        ensureCurrent()
        block().also { ensureCurrent() }
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (error: SourceException) {
        ensureCurrent()
        throw error
    } catch (_: Exception) {
        ensureCurrent()
        // A malformed operation result does not revoke unrelated provider capabilities.
        invalid()
    }

    private suspend fun invoke(operation: String, params: JsonObject): com.google.gson.JsonElement {
        val pin = kotlinx.coroutines.currentCoroutineContext()[com.ljyh.mei.source.core.SourceMutationContext]?.owner
        if (pin != null && (pin.provider !== this || pin.version != version)) throw CancellationException("Source mutation owner changed")
        val revision = if (pin != null) pin.revision else sessionRevision.value
        return coreExecutor?.executeCore(this, operation, params, coreHandler, revision) ?: serialized {
            if (sessionRevision.value != revision) throw CancellationException("Source account changed before core operation")
            invokeLocked(operation, params)
        }
    }

    override suspend fun invokeCoreOperation(operation: String, parameters: JsonObject,
        expectedAccountRevision: String?): com.google.gson.JsonElement = serialized {
        if (operation == "feature.invoke") throw SourceException(SourceErrorCode.UNSUPPORTED)
        if (sessionRevision.value != expectedAccountRevision) throw CancellationException("Source account changed before core operation")
        invokeLocked(operation, parameters)
    }

    private suspend fun <T> serialized(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { operations.withScriptOperationTimeout(20_000, block) }
        catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            ensureCurrent()
            throw SourceException(SourceErrorCode.TIMEOUT)
        }
    }

    private suspend fun invokeLocked(operation: String, params: JsonObject,
        onSessionCommitted: ((String?) -> Unit)? = null): com.google.gson.JsonElement {
        ensureCurrent()
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
        try {
            var parameters = params
            repeat(9) { segment ->
                val call = runtime.invoke(File(directory, manifest.entry).readText(), manifest, operation, parameters, config(), storage(), network,
                    ensureCurrent = ::ensureCurrent)
                ensureCurrent()
                call.storage?.let { values ->
                    saveStorage(values)
                    val next = sessionFingerprint(values)
                    try {
                        if (!(call.result.isJsonObject && call.result.asJsonObject.has("hostEffect"))) onSessionCommitted?.invoke(next)
                    } finally {
                        // Durable credentials and their revision must remain in sync even if the caller was cancelled.
                        sessionRevision.value = next
                    }
                }
                ensureCurrent()
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
}

/** Waiting for a preceding source request does not consume this operation's execution time. */
internal suspend fun <T> Mutex.withScriptOperationTimeout(timeoutMillis: Long, block: suspend () -> T): T =
    withLock { withTimeout(timeoutMillis) { block() } }
