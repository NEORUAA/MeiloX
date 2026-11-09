package com.ljyh.mei.source.update

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.script.PluginManifest
import java.io.StringReader
import java.net.URI

data class SourceMarketListing(
    val packageId: String,
    val providerId: String,
    val name: String,
    val repository: String,
    val packagePath: String,
    val apiVersion: Int,
)

data class SourceMarketEntry(
    val packageId: String,
    val providerId: String,
    val name: String,
    val version: String,
    val repository: String,
    val packageUrl: String,
    val sha256: String,
    val apiVersion: Int,
    val packagePath: String = inferPackagePath(packageUrl),
) {
    internal fun matches(manifest: PluginManifest): Boolean = manifest.id == packageId &&
        manifest.providerId == providerId && manifest.version == version && manifest.apiVersion == apiVersion
}

private fun inferPackagePath(url: String): String = runCatching {
    URI(url).rawPath.removePrefix("/").split('/').drop(3).joinToString("/")
}.getOrDefault("")

data class SourceMarketItem(
    val entry: SourceMarketEntry,
    val installedVersion: String?,
    val enabled: Boolean?,
    val updateAvailable: Boolean,
    val trustedOrigin: Boolean,
    val requiresReview: Boolean,
)

data class SourceUpdateState(
    val checking: Boolean = false,
    val autoUpdates: Boolean = true,
    val items: List<SourceMarketItem> = emptyList(),
    val busyPackageId: String? = null,
    val message: String? = null,
)

enum class SourceUpdateReviewReason {
    UNKNOWN_ORIGIN, REPOSITORY_CHANGED, NETWORK, STORAGE, NATIVE_SDK, EFFECTS,
    LINKS, SESSION, CAPABILITIES, REQUIRED_CONFIGURATION,
}

internal object SourceMarketIndex {
    const val URL = "https://raw.githubusercontent.com/NEORUAA/MeiloX-Source-Registry/main/index.json"
    const val MAX_BYTES = 256 * 1024
    private val fields = setOf("packageId", "providerId", "name", "repository", "packagePath", "apiVersion")
    private val id = Regex("[a-z][a-z0-9._-]{2,79}")
    private val repositoryPattern = Regex("https://github\\.com/([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))/([A-Za-z0-9._-]{1,100})")
    private val packagePathPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._-]*)*\\.meisource")

    fun parse(json: String): List<SourceMarketListing> {
        try {
            require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val reader = JsonReader(StringReader(json))
            val seen = mutableSetOf<String>()
            var schema: Int? = null
            var entries: List<SourceMarketListing>? = null
            reader.beginObject()
            while (reader.hasNext()) {
                val field = reader.nextName()
                require(seen.add(field))
                when (field) {
                    "schemaVersion" -> schema = integer(reader)
                    "sources" -> {
                        val values = mutableListOf<SourceMarketListing>()
                        reader.beginArray()
                        while (reader.hasNext()) {
                            require(values.size < 200)
                            val valuesByName = mutableMapOf<String, String>()
                            var api: Int? = null
                            val entryFields = mutableSetOf<String>()
                            reader.beginObject()
                            while (reader.hasNext()) {
                                val key = reader.nextName()
                                require(key in fields && entryFields.add(key))
                                if (key == "apiVersion") api = integer(reader)
                                else {
                                    require(reader.peek() == JsonToken.STRING)
                                    valuesByName[key] = reader.nextString().also { require(it.length <= 2048) }
                                }
                            }
                            reader.endObject()
                            require(entryFields == fields)
                            fun value(key: String) = valuesByName.getValue(key)
                            values += SourceMarketListing(value("packageId"), value("providerId"), value("name"),
                                value("repository"), value("packagePath"), requireNotNull(api)).also(::validate)
                        }
                        reader.endArray()
                        entries = values
                    }
                    else -> error("Unknown registry field")
                }
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT && seen == setOf("schemaVersion", "sources") && schema == 2)
            return requireNotNull(entries).also {
                require(it.map(SourceMarketListing::packageId).distinct().size == it.size)
                require(it.map(SourceMarketListing::providerId).distinct().size == it.size)
            }
        } catch (_: Exception) { throw SourceException(SourceErrorCode.INVALID_RESPONSE, "音源市场索引无效") }
    }

    private fun integer(reader: JsonReader): Int {
        require(reader.peek() == JsonToken.NUMBER)
        return reader.nextString().also { require(Regex("[1-9][0-9]{0,5}").matches(it)) }.toInt()
    }

    internal fun repositoryParts(repository: String): Pair<String, String> {
        val match = requireNotNull(repositoryPattern.matchEntire(repository))
        val name = match.groupValues[2]
        require(name !in setOf(".", "..") && !name.endsWith(".git"))
        return match.groupValues[1] to name
    }

    fun validate(listing: SourceMarketListing) {
        require(id.matches(listing.packageId) && id.matches(listing.providerId) && listing.providerId != "local")
        require(listing.name.isNotBlank() && listing.name.length <= 100 && listing.name.none(Char::isISOControl))
        require(listing.repository.length <= 250 && listing.packagePath.length <= 512)
        require(listing.apiVersion in 1..2)
        val (_, repositoryName) = repositoryParts(listing.repository)
        require(repositoryName !in setOf(".", "..") && !repositoryName.endsWith(".git"))
        require(packagePathPattern.matches(listing.packagePath))
        require(listing.packagePath.split('/').none { it in setOf(".", "..") })
    }

    fun validate(entry: SourceMarketEntry) {
        validate(SourceMarketListing(entry.packageId, entry.providerId, entry.name, entry.repository, entry.packagePath, entry.apiVersion))
        require(SourceVersion.parse(entry.version) != null && entry.version.length <= 50)
        require(Regex("[a-f0-9]{64}").matches(entry.sha256) && entry.sha256.any { it != '0' })
        val (owner, name) = repositoryParts(entry.repository)
        val url = URI(entry.packageUrl)
        require(url.scheme == "https" && url.rawAuthority == "raw.githubusercontent.com" && url.rawQuery == null && url.rawFragment == null)
        val parts = url.rawPath.removePrefix("/").split('/')
        require(parts.size >= 4 && parts[0] == owner && parts[1] == name &&
            (Regex("[a-f0-9]{40}").matches(parts[2]) || parts[2] == "HEAD"))
        require(parts.drop(3).joinToString("/") == entry.packagePath)
    }
}

internal data class SourceVersion(private val core: List<String>, private val pre: List<String>) : Comparable<SourceVersion> {
    override fun compareTo(other: SourceVersion): Int {
        core.zip(other.core).forEach { (a, b) -> compareNumeric(a, b).takeIf { it != 0 }?.let { return it } }
        if (pre.isEmpty() || other.pre.isEmpty()) return when {
            pre.isEmpty() && other.pre.isEmpty() -> 0
            pre.isEmpty() -> 1
            else -> -1
        }
        pre.zip(other.pre).forEach { (a, b) ->
            val numericA = a.all(Char::isDigit)
            val numericB = b.all(Char::isDigit)
            val result = when {
                numericA && numericB -> compareNumeric(a, b)
                numericA -> -1
                numericB -> 1
                else -> a.compareTo(b)
            }
            if (result != 0) return result
        }
        return pre.size.compareTo(other.pre.size)
    }

    companion object {
        private val pattern = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?")
        fun parse(value: String): SourceVersion? {
            val match = pattern.matchEntire(value.takeIf { it.length <= 50 } ?: return null) ?: return null
            val pre = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.').orEmpty()
            if (pre.any { it.length > 1 && it.all(Char::isDigit) && it.startsWith('0') }) return null
            return SourceVersion(match.groupValues.subList(1, 4), pre)
        }
        private fun compareNumeric(a: String, b: String): Int = a.length.compareTo(b.length).takeIf { it != 0 } ?: a.compareTo(b)
    }
}

internal fun sourceUpdateReviewReasons(previous: PluginManifest?, next: PluginManifest, origin: String?, repository: String): Set<SourceUpdateReviewReason> = buildSet {
    if (previous == null || origin == null) add(SourceUpdateReviewReason.UNKNOWN_ORIGIN)
    else if (origin != repository) add(SourceUpdateReviewReason.REPOSITORY_CHANGED)
    if (previous == null) return@buildSet
    if (!previous.permissions.network.containsAll(next.permissions.network)) add(SourceUpdateReviewReason.NETWORK)
    if (!previous.permissions.storage && next.permissions.storage) add(SourceUpdateReviewReason.STORAGE)
    if (!previous.permissions.nativeSdks.containsAll(next.permissions.nativeSdks)) add(SourceUpdateReviewReason.NATIVE_SDK)
    if (!previous.permissions.effects.containsAll(next.permissions.effects)) add(SourceUpdateReviewReason.EFFECTS)
    if (!previous.linkHosts.containsAll(next.linkHosts)) add(SourceUpdateReviewReason.LINKS)
    if (previous.sessionKeys != next.sessionKeys || previous.sessionCookieNames != next.sessionCookieNames) add(SourceUpdateReviewReason.SESSION)
    if (!previous.capabilities.containsAll(next.capabilities) || !previous.featureRoutes.containsAll(next.featureRoutes) ||
        !previous.searchTypes.containsAll(next.searchTypes) || !previous.collectionTypes.containsAll(next.collectionTypes) ||
        next.features.any { (key, actions) -> !previous.features[key].orEmpty().containsAll(actions) }) add(SourceUpdateReviewReason.CAPABILITIES)
    if (next.config.any { field -> field.required && previous.config.none { it.key == field.key && it.required && it.secret == field.secret } }) {
        add(SourceUpdateReviewReason.REQUIRED_CONFIGURATION)
    }
}
