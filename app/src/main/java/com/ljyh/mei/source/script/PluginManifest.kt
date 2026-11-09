package com.ljyh.mei.source.script

import androidx.annotation.Keep
import com.google.gson.Gson
import com.ljyh.mei.source.api.ProviderIds
import com.ljyh.mei.source.api.SourceCapability
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.api.ContentType

@Keep
data class PluginPermissions(
    val network: List<String> = emptyList(),
    val storage: Boolean = false,
    val nativeSdks: Set<String> = emptySet(),
    val effects: Set<String> = emptySet(),
)

@Keep
data class PluginConfigField(
    val key: String,
    val label: String,
    val secret: Boolean = false,
    val required: Boolean = false,
)

@Keep
data class PluginManifest(
    val id: String,
    val providerId: String = id,
    val name: String,
    val author: String,
    val version: String,
    val apiVersion: Int,
    val entry: String = "index.js",
    val capabilities: Set<String>,
    val permissions: PluginPermissions = PluginPermissions(),
    val config: List<PluginConfigField> = emptyList(),
    val description: String? = null,
    val searchTypes: Set<String> = setOf("track"),
    val collectionTypes: Set<String> = emptySet(),
    val features: Map<String, Set<String>> = emptyMap(),
    val featureRoutes: Set<String> = emptySet(),
    val sessionKeys: Set<String> = emptySet(),
    val sessionCookieNames: Map<String, Set<String>> = emptyMap(),
    val linkHosts: Set<String> = emptySet(),
    val catalogBatchSize: Int = 30,
    val discardStorageKeys: Set<String> = emptySet(),
    val concurrentReadOperations: Set<String> = emptySet(),
    val concurrentReadFeatures: Map<String, Set<String>> = emptyMap(),
) {
    val supported: Boolean get() = apiVersion in 1..2
    val sourceCapabilities: Set<SourceCapability> get() = capabilities.mapNotNull(CAPABILITIES::get).toSet()

    companion object {
        private val ID = Regex("[a-z][a-z0-9._-]{2,79}")
        private val KEY = Regex("[A-Za-z][A-Za-z0-9._-]{0,63}")
        internal val CONCURRENT_READ_OPERATIONS = setOf(
            "links.resolve", "catalog.search", "catalog.track", "catalog.tracks", "album.get", "album.tracks",
            "artist.get", "artist.tracks", "artist.albums", "playlist.get", "playlist.tracks", "feed.home", "feed.discover",
            "library.likedTracks", "library.createdPlaylists", "library.savedPlaylists", "library.savedAlbums", "library.followedArtists",
            "track.liked", "collection.saved", "playback.resolve", "playback.qualities", "lyrics.get",
        )
        val CAPABILITIES = mapOf(
            "catalog.search" to SourceCapability.SEARCH,
            "catalog.track" to SourceCapability.TRACK,
            "catalog.tracks" to SourceCapability.TRACK,
            "playback.resolve" to SourceCapability.PLAYBACK,
            "lyrics.get" to SourceCapability.LYRICS,
            "feed.home" to SourceCapability.FEED,
            "feed.discover" to SourceCapability.FEED,
            "album.get" to SourceCapability.ALBUM,
            "album.tracks" to SourceCapability.ALBUM,
            "artist.get" to SourceCapability.ARTIST,
            "artist.tracks" to SourceCapability.ARTIST,
            "artist.albums" to SourceCapability.ARTIST,
            "playlist.get" to SourceCapability.PLAYLIST,
            "playlist.tracks" to SourceCapability.PLAYLIST,
            "account.get" to SourceCapability.ACCOUNT,
            "library.likedTracks" to SourceCapability.LIBRARY,
            "library.createdPlaylists" to SourceCapability.LIBRARY,
            "library.savedPlaylists" to SourceCapability.LIBRARY,
            "library.savedAlbums" to SourceCapability.LIBRARY,
            "library.followedArtists" to SourceCapability.LIBRARY,
            "track.liked" to SourceCapability.LIKE,
            "track.like" to SourceCapability.LIKE,
            "collection.saved" to SourceCapability.COLLECTIONS,
            "collection.save" to SourceCapability.COLLECTIONS,
            "playlist.create" to SourceCapability.PLAYLIST_EDIT,
            "playlist.update" to SourceCapability.PLAYLIST_EDIT,
            "playlist.delete" to SourceCapability.PLAYLIST_EDIT,
            "playlist.addTracks" to SourceCapability.PLAYLIST_EDIT,
            "playlist.removeTracks" to SourceCapability.PLAYLIST_EDIT,
            "playlist.reorderTracks" to SourceCapability.PLAYLIST_EDIT,
            "feature.invoke" to SourceCapability.FEATURES,
            "playback.qualities" to SourceCapability.PLAYBACK,
            "links.resolve" to SourceCapability.LINKS,
        )

        fun parse(json: String): PluginManifest {
            try {
                val raw = com.google.gson.JsonParser.parseString(json).asJsonObject
                val id = raw.get("id")?.asString ?: invalid()
                if (!raw.has("providerId")) raw.addProperty("providerId", id)
                if (!raw.has("entry")) raw.addProperty("entry", "index.js")
                if (!raw.has("permissions")) raw.add("permissions", com.google.gson.JsonObject())
                val permissions = raw.getAsJsonObject("permissions")
                require(permissions.keySet().all { it in setOf("network", "storage", "nativeSdks", "effects") })
                if (!permissions.has("network")) permissions.add("network", com.google.gson.JsonArray())
                if (!permissions.has("nativeSdks")) permissions.add("nativeSdks", com.google.gson.JsonArray())
                if (!permissions.has("effects")) permissions.add("effects", com.google.gson.JsonArray())
                if (!raw.has("config")) raw.add("config", com.google.gson.JsonArray())
                if (!raw.has("searchTypes")) raw.add("searchTypes", com.google.gson.JsonArray().apply { add("track") })
                if (!raw.has("collectionTypes")) raw.add("collectionTypes", com.google.gson.JsonArray())
                if (!raw.has("features")) raw.add("features", com.google.gson.JsonObject())
                if (!raw.has("featureRoutes")) raw.add("featureRoutes", com.google.gson.JsonArray())
                if (!raw.has("sessionKeys")) raw.add("sessionKeys", com.google.gson.JsonArray())
                if (!raw.has("catalogBatchSize")) raw.addProperty("catalogBatchSize", 30)
                if (!raw.has("discardStorageKeys")) raw.add("discardStorageKeys", com.google.gson.JsonArray())
                if (!raw.has("concurrentReadOperations")) raw.add("concurrentReadOperations", com.google.gson.JsonArray())
                if (!raw.has("concurrentReadFeatures")) raw.add("concurrentReadFeatures", com.google.gson.JsonObject())
                if (!raw.has("sessionCookieNames")) raw.add("sessionCookieNames", com.google.gson.JsonObject())
                if (!raw.has("linkHosts")) raw.add("linkHosts", com.google.gson.JsonArray())
                val manifest = Gson().fromJson(raw, PluginManifest::class.java)
                require(ID.matches(manifest.id) && ID.matches(manifest.providerId))
                require(manifest.providerId != ProviderIds.LOCAL)
                require(manifest.providerId != ProviderIds.NETEASE || manifest.id == "org.meilox.source.netease")
                require(manifest.name.isNotBlank() && manifest.name.length <= 100)
                require(manifest.author.isNotBlank() && manifest.author.length <= 100)
                require(manifest.version.isNotBlank() && manifest.version.length <= 50)
                require(manifest.description == null || manifest.description.length <= 2000)
                require(manifest.apiVersion > 0 && manifest.entry == "index.js")
                require(manifest.capabilities.isNotEmpty() && manifest.capabilities.all(CAPABILITIES::containsKey))
                require("catalog.tracks" !in manifest.capabilities || "catalog.track" in manifest.capabilities)
                if (manifest.apiVersion == 1) {
                    require(manifest.capabilities.all { it in setOf("catalog.search", "catalog.track", "playback.resolve", "lyrics.get") })
                    require(manifest.searchTypes == setOf("track") && manifest.collectionTypes.isEmpty())
                    require(manifest.features.isEmpty() && manifest.featureRoutes.isEmpty() && manifest.permissions.nativeSdks.isEmpty() &&
                        manifest.permissions.effects.isEmpty() && manifest.sessionKeys.isEmpty() && manifest.sessionCookieNames.isEmpty() && manifest.linkHosts.isEmpty())
                    require(manifest.concurrentReadOperations.isEmpty() && manifest.concurrentReadFeatures.isEmpty())
                }
                require(manifest.features.size <= 32 && manifest.features.all { (feature, actions) ->
                    KEY.matches(feature) && actions.isNotEmpty() && actions.size <= 100 && actions.all(KEY::matches)
                })
                require(manifest.featureRoutes.size <= 64 && manifest.featureRoutes.all {
                    it.length in 1..100 && Regex("[a-z][a-z0-9_/-]*").matches(it)
                })
                require(manifest.features.isEmpty() && manifest.featureRoutes.isEmpty() || "feature.invoke" in manifest.capabilities)
                require(manifest.concurrentReadOperations.size <= CONCURRENT_READ_OPERATIONS.size && manifest.concurrentReadOperations.all {
                    it in CONCURRENT_READ_OPERATIONS && it in manifest.capabilities
                })
                require(manifest.concurrentReadFeatures.size <= 32 && manifest.concurrentReadFeatures.all { (feature, actions) ->
                    actions.isNotEmpty() && actions.size <= 100 && actions.all { it in manifest.features[feature].orEmpty() }
                })
                require(manifest.sessionKeys.size <= 32 && manifest.sessionKeys.all(KEY::matches))
                require(manifest.sessionKeys.isEmpty() || manifest.permissions.storage)
                require(manifest.catalogBatchSize in 1..1000 && (manifest.catalogBatchSize == 30 ||
                    manifest.apiVersion >= 2 && "catalog.tracks" in manifest.capabilities))
                require(manifest.discardStorageKeys.size <= 32 && manifest.discardStorageKeys.all(KEY::matches) &&
                    manifest.discardStorageKeys.intersect(manifest.sessionKeys).isEmpty())
                require(manifest.sessionCookieNames.size <= 16 && manifest.sessionCookieNames.all { (key, names) ->
                    key in manifest.sessionKeys && names.isNotEmpty() && names.size <= 16 && names.all {
                        Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}").matches(it)
                    }
                })
                require(manifest.permissions.nativeSdks.all { it in setOf("netease.transport", "netease.urs",
                    "netease.securityHeaders", "netease.ursPuzzle", "netease.ursCaptcha", "netease.history") })
                require(manifest.permissions.effects.all { it in com.ljyh.mei.source.api.SourceHostEffect.supportedTypes && it != "source.continue" })
                require(manifest.linkHosts.size <= 20 && manifest.linkHosts.all {
                    ScriptNetworkPolicy.isHostAllowed(it, manifest.permissions.network)
                })
                manifest.linkHosts.forEach(ScriptNetworkPolicy::validateDomain)
                require(manifest.linkHosts.isEmpty() || "links.resolve" in manifest.capabilities)
                val types = ContentType.entries.map { it.wireName }.toSet()
                require(manifest.searchTypes.isNotEmpty() && manifest.searchTypes.all { it in types })
                require(manifest.collectionTypes.all { it in types && it != "track" })
                if (manifest.capabilities.any { it.startsWith("collection.") }) require(manifest.collectionTypes.isNotEmpty())
                require(manifest.permissions.network.size <= 32)
                manifest.permissions.network.forEach(ScriptNetworkPolicy::validateGrant)
                require(manifest.config.size <= 32 && manifest.config.map { it.key }.distinct().size == manifest.config.size)
                manifest.config.forEach { require(KEY.matches(it.key) && it.label.isNotBlank() && it.label.length <= 100) }
                return manifest
            } catch (error: SourceException) { throw error
            } catch (_: Exception) { invalid() }
        }

        private fun invalid(): Nothing = throw SourceException(SourceErrorCode.INVALID_RESPONSE, "Invalid source manifest")
    }
}
