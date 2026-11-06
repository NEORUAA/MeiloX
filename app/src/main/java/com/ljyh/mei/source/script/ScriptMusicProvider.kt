package com.ljyh.mei.source.script

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ljyh.mei.source.api.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only capability adapters and JSON values cross the isolated runtime boundary. */
internal class ScriptMusicProvider(
    val manifest: PluginManifest,
    private val directory: File,
    private val runtime: ScriptRuntime,
    enabled: Boolean,
    private val config: () -> Map<String, String>,
    private val storage: () -> Map<String, String>,
    private val saveStorage: (Map<String, String>) -> Unit,
    private val onFailure: (SourceErrorCode) -> Unit,
    configurationVersion: Long,
) : MusicProvider, CatalogCapability, PlaybackCapability, LyricsCapability, ResourceValidationCapability {
    override val id = manifest.providerId
    override val instanceId = ProviderInstanceId("script:${manifest.id}:default")
    override val displayName = manifest.name
    override val version = "${manifest.version}+$configurationVersion"
    override val capabilities = manifest.sourceCapabilities
    @Volatile var enabled = enabled
    @Volatile var failed = false
    override val status: SourceStatus get() = when {
        !enabled -> SourceStatus.DISABLED
        !manifest.supported || !runtime.supported -> SourceStatus.INCOMPATIBLE
        failed -> SourceStatus.FAILED
        manifest.config.any { it.required && config()[it.key].isNullOrBlank() } -> SourceStatus.NEEDS_CONFIGURATION
        else -> SourceStatus.READY
    }
    private val network = ScriptNetworkPolicy(manifest.permissions.network)
    private val gson = Gson()

    override suspend fun search(query: String, page: PageRequest): SourcePage<Track> = validated {
        val result = invoke("catalog.search", JsonObject().apply {
            addProperty("query", query)
            addProperty("offset", page.offset)
            addProperty("limit", page.limit)
        }).asJsonObject
        val tracks = result.getAsJsonArray("items")?.take(page.limit)?.map { parseTrack(it.asJsonObject) }.orEmpty()
        SourcePage(tracks, result.get("hasMore")?.asBoolean ?: false)
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
        ScriptAudioResources.register(manifest.id, resource, network, directory)
    }

    override suspend fun getLyrics(ref: TrackRef): SourceLyrics? = validated {
        requireRef(ref)
        val result = invoke("lyrics.get", JsonObject().apply { addProperty("trackId", ref.trackId) })
        if (result.isJsonNull) return@validated null
        SourceLyrics(result.asJsonObject.get("text")?.asString ?: invalid(), result.asJsonObject.get("translation")?.asString)
    }

    override fun acceptsResource(resource: AudioResource): Boolean = ScriptAudioResources.isRegistered(resource.uri)

    private suspend fun <T> validated(block: suspend () -> T): T = try { block()
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (error: SourceException) { throw error
    } catch (_: Exception) {
        failed = true
        onFailure(SourceErrorCode.INVALID_RESPONSE)
        invalid()
    }

    private suspend fun invoke(operation: String, params: JsonObject): com.google.gson.JsonElement = withContext(Dispatchers.IO) {
        if (operation !in manifest.capabilities) throw SourceException(SourceErrorCode.UNSUPPORTED)
        if (status != SourceStatus.READY) throw SourceException(when (status) {
            SourceStatus.NEEDS_CONFIGURATION -> SourceErrorCode.AUTH_REQUIRED
            SourceStatus.INCOMPATIBLE -> SourceErrorCode.UNSUPPORTED
            else -> SourceErrorCode.MISSING_PROVIDER
        })
        try {
            val call = runtime.invoke(File(directory, manifest.entry).readText(), manifest, operation, params, config(), storage(), network)
            call.storage?.let(saveStorage)
            call.result
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: SourceException) {
            if (error.code == SourceErrorCode.PLUGIN || error.code == SourceErrorCode.INVALID_RESPONSE) {
                failed = true
                onFailure(error.code)
            }
            throw error
        } catch (_: Exception) {
            failed = true
            onFailure(SourceErrorCode.PLUGIN)
            throw SourceException(SourceErrorCode.PLUGIN, "Source execution failed")
        }
    }

    private suspend fun parseTrack(value: JsonObject): Track {
        val trackId = value.get("trackId")?.asString?.takeIf { it.isNotEmpty() && it.length <= 2048 } ?: invalid()
        val title = value.get("title")?.asString?.takeIf { it.isNotBlank() && it.length <= 1000 } ?: invalid()
        val artistNames = value.getAsJsonArray("artists")?.take(50)?.map { element ->
            if (element.isJsonPrimitive) element.asString else element.asJsonObject.get("name")?.asString ?: invalid()
        }.orEmpty()
        val album = value.get("album")?.let { if (it.isJsonPrimitive) it.asString else it.asJsonObject.get("title")?.asString }.orEmpty()
        val duration = value.get("durationMs")?.asLong ?: 0L
        if (duration < 0 || artistNames.any { it.length > 1000 }) invalid()
        val artwork = value.get("artworkUrl")?.asString.orEmpty()
        val localArtwork = if (artwork.isNotEmpty()) network.artwork(artwork, directory) else ""
        return Track(TrackRef(id, trackId), title, artistNames.map { SourceArtist(it) }, SourceAlbum(album), duration, localArtwork,
            value.get("isrc")?.asString, value.get("version")?.asString, value.get("explicit")?.asBoolean ?: false)
    }

    private fun requireRef(ref: TrackRef) { if (ref.providerId != id) throw SourceException(SourceErrorCode.NOT_FOUND) }
    private fun invalid(): Nothing = throw SourceException(SourceErrorCode.INVALID_RESPONSE, "Source returned invalid data")
}
