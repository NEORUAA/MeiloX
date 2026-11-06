package com.ljyh.mei.source.script

import androidx.annotation.Keep
import com.google.gson.Gson
import com.ljyh.mei.source.api.ProviderIds
import com.ljyh.mei.source.api.SourceCapability
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException

@Keep
data class PluginPermissions(val network: List<String> = emptyList(), val storage: Boolean = false)

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
) {
    val supported: Boolean get() = apiVersion == 1
    val sourceCapabilities: Set<SourceCapability> get() = capabilities.mapNotNull(CAPABILITIES::get).toSet()

    companion object {
        private val ID = Regex("[a-z][a-z0-9._-]{2,79}")
        private val KEY = Regex("[A-Za-z][A-Za-z0-9._-]{0,63}")
        val CAPABILITIES = mapOf(
            "catalog.search" to SourceCapability.SEARCH,
            "catalog.track" to SourceCapability.TRACK,
            "playback.resolve" to SourceCapability.PLAYBACK,
            "lyrics.get" to SourceCapability.LYRICS,
        )

        fun parse(json: String): PluginManifest {
            try {
                val raw = com.google.gson.JsonParser.parseString(json).asJsonObject
                val id = raw.get("id")?.asString ?: invalid()
                if (!raw.has("providerId")) raw.addProperty("providerId", id)
                if (!raw.has("entry")) raw.addProperty("entry", "index.js")
                if (!raw.has("permissions")) raw.add("permissions", com.google.gson.JsonObject())
                val permissions = raw.getAsJsonObject("permissions")
                require(permissions.keySet().all { it in setOf("network", "storage") })
                if (!permissions.has("network")) permissions.add("network", com.google.gson.JsonArray())
                if (!raw.has("config")) raw.add("config", com.google.gson.JsonArray())
                val manifest = Gson().fromJson(raw, PluginManifest::class.java)
                require(ID.matches(manifest.id) && ID.matches(manifest.providerId))
                require(manifest.providerId !in setOf(ProviderIds.NETEASE, ProviderIds.LOCAL))
                require(manifest.name.isNotBlank() && manifest.name.length <= 100)
                require(manifest.author.isNotBlank() && manifest.author.length <= 100)
                require(manifest.version.isNotBlank() && manifest.version.length <= 50)
                require(manifest.apiVersion > 0 && manifest.entry == "index.js")
                require(manifest.capabilities.isNotEmpty() && manifest.capabilities.all(CAPABILITIES::containsKey))
                require(manifest.permissions.network.size <= 20)
                manifest.permissions.network.forEach(ScriptNetworkPolicy::validateDomain)
                require(manifest.config.size <= 32 && manifest.config.map { it.key }.distinct().size == manifest.config.size)
                manifest.config.forEach { require(KEY.matches(it.key) && it.label.isNotBlank() && it.label.length <= 100) }
                return manifest
            } catch (error: SourceException) { throw error
            } catch (_: Exception) { invalid() }
        }

        private fun invalid(): Nothing = throw SourceException(SourceErrorCode.INVALID_RESPONSE, "Invalid source manifest")
    }
}
