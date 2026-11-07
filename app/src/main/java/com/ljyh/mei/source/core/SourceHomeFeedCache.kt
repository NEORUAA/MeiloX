package com.ljyh.mei.source.core

import android.content.Context
import com.google.gson.*
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.script.ScriptContentParser
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** No raw account/configuration value appears in cache paths or records. */
data class SourceHomeCacheOwner(val providerId: String, val instanceId: ProviderInstanceId, val version: String, val accountRevision: String?) {
    internal val key: String get() {
        val identity = JsonArray().apply { add(providerId); add(instanceId.value); add(version); add(accountRevision) }.toString()
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    override fun toString() = "SourceHomeCacheOwner(<redacted>)"
}

/** The complete normalized Home feed is published atomically, independently for each source account. */
@Singleton
class SourceHomeFeedCache internal constructor(
    private val directory: File,
    private val artworkRoots: List<File>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "source-home-cache"), listOf(context.cacheDir))
    private val mutex = Mutex()
    private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()

    suspend fun read(owner: SourceHomeCacheOwner, provider: MusicProvider, ensureCurrent: suspend () -> Unit = {}): SourceFeedPage? = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureCurrent()
            currentCoroutineContext().ensureActive()
            try {
                val file = file(owner)
                if (!file.isFile || file.length() !in 1..MAX_BYTES.toLong()) return@withLock null
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(file.readBytes())).toString()
                checkJsonDepth(text)
                val root = JsonParser.parseString(text).asJsonObject
                if (root.get("version")?.asInt != 1 || root.get("owner")?.asString != owner.key ||
                    !isFresh(root.get("fetchedAt")?.asLong ?: return@withLock null, now())) return@withLock null
                if (provider.id != owner.providerId || provider.instanceId != owner.instanceId || provider.version != owner.version) return@withLock null
                val page = decode(root.getAsJsonObject("feed"), owner, provider)
                ensureCurrent()
                currentCoroutineContext().ensureActive()
                file.setLastModified(now())
                page
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }

    suspend fun write(owner: SourceHomeCacheOwner, page: SourceFeedPage, ensureCurrent: suspend () -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureCurrent()
            currentCoroutineContext().ensureActive()
            if (page.hasMore) return@withLock false
            var temporary: File? = null
            try {
                val root = JsonObject().apply {
                    addProperty("version", 1); addProperty("owner", owner.key); addProperty("fetchedAt", now())
                    add("feed", encode(page, owner.providerId))
                }
                val bytes = gson.toJson(root).toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_BYTES) return@withLock false
                check(directory.isDirectory || directory.mkdirs())
                temporary = File(directory, ".${owner.key}-${UUID.randomUUID()}.tmp")
                temporary.outputStream().use { output -> output.write(bytes); output.fd.sync() }
                ensureCurrent()
                currentCoroutineContext().ensureActive()
                Files.move(temporary.toPath(), file(owner).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                prune(owner)
                true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            finally { temporary?.delete() }
        }
    }

    /** Lookup never causes a source request or refresh. */
    suspend fun cachedArtwork(owner: SourceHomeCacheOwner, provider: MusicProvider, ref: ContentRef,
        ensureCurrent: suspend () -> Unit = {}): String? {
        if (ref.providerId != owner.providerId) return null
        val page = read(owner, provider, ensureCurrent) ?: return null
        return page.sections.firstNotNullOfOrNull { section ->
            section.items.firstOrNull { it.ref == ref }?.artworkUrl?.takeIf(String::isNotBlank)
                ?: section.cards.firstOrNull { (it.target as? SourceFeedTarget.Content)?.ref == ref }?.artworkUrl?.takeIf(String::isNotBlank)
        }
    }

    internal fun file(owner: SourceHomeCacheOwner) = File(directory, "${owner.key}.json")

    private fun prune(owner: SourceHomeCacheOwner) {
        val records = directory.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}\\.json")) }
            .sortedBy { it.lastModified() }
        var bytes = records.sumOf { it.length() }
        var count = records.size
        records.filter { it != file(owner) }.forEach {
            if (bytes > MAX_BYTES * 4L || count > 16) {
                val size = it.length()
                if (it.delete()) { bytes -= size; count-- }
            }
        }
        directory.listFiles().orEmpty().filter { it.name.startsWith('.') && it.name.endsWith(".tmp") &&
            now() - it.lastModified() > 3_600_000 }.forEach { it.delete() }
    }

    private suspend fun decode(value: JsonObject, owner: SourceHomeCacheOwner, provider: MusicProvider): SourceFeedPage {
        val sections = value.getAsJsonArray("sections").also { require(it.size() <= 300) }
        val parser = ScriptContentParser(owner.providerId, 2, (provider as? PlaylistEditCapability)?.playlistEditActions.orEmpty(),
            (provider as? SourceFeatureCapability)?.features.orEmpty(), (provider as? FeatureContribution)?.featureRoutes.orEmpty()) { artwork ->
            val uri = URI(artwork)
            require(uri.scheme == "file" && uri.rawAuthority.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null)
            val path = File(uri).canonicalFile.toPath()
            require(artworkRoots.any { path.startsWith(it.canonicalFile.toPath()) })
            artwork
        }
        val decoded = sections.map { record ->
            val section = record.asJsonObject
            val items = section.getAsJsonArray("items").also { require(it.size() <= 1000) }
            val cards = section.getAsJsonArray("cards").also { require(it.size() <= 1000) }
            val cardChunk = if (section.get("layout").asString.equals("HERO", true)) 30 else 100
            val chunks = maxOf(1, (items.size() + 99) / 100, (cards.size() + cardChunk - 1) / cardChunk)
            val rows = (0 until chunks).map { chunk ->
                val part = JsonObject().apply {
                    add("id", section.get("id")); add("title", section.get("title")); add("layout", section.get("layout"))
                    add("items", JsonArray().apply { items.drop(chunk * 100).take(100).forEach(::add) })
                    add("cards", JsonArray().apply { cards.drop(chunk * cardChunk).take(cardChunk).forEach(::add) })
                }
                parser.feed(JsonObject().apply { add("sections", JsonArray().apply { add(part) }) }).sections.single()
            }
            val combined = rows.first().copy(items = rows.flatMap { it.items }, cards = rows.flatMap { it.cards })
            require(combined.cards.map { it.id }.distinct().size == combined.cards.size)
            combined
        }
        require(decoded.map { it.id }.distinct().size == decoded.size)
        val categories = value.getAsJsonArray("categories").also { require(it.size() <= 100) }.map { raw ->
            val category = raw.asJsonObject
            SourceCategory(category.get("id").asString.also { require(it.isNotBlank() && it.length <= 2048) },
                category.get("title").asString.also { require(it.isNotBlank() && it.length <= 1000) })
        }
        require(categories.map { it.id }.distinct().size == categories.size)
        return SourceFeedPage(decoded, categories, hasMore = false)
    }

    private fun encode(page: SourceFeedPage, providerId: String): JsonObject {
        require(page.sections.size <= 300 && page.categories.size <= 100)
        val budget = Budget()
        fun obj(vararg entries: Pair<String, Any?>) = JsonObject().apply {
            budget.add(2)
            entries.forEach { (key, value) ->
                budget.add(key.length + 4)
                when (value) {
                    null -> Unit
                    is JsonElement -> add(key, value)
                    is String -> { budget.text(value); addProperty(key, value) }
                    is Number -> { budget.add(24); addProperty(key, value) }
                    is Boolean -> { budget.add(5); addProperty(key, value) }
                    else -> error("Unsupported cache value")
                }
            }
        }
        fun <T> array(values: List<T>, block: (T) -> JsonElement) = JsonArray().apply {
            budget.add(values.size + 2)
            values.forEach { add(block(it)) }
        }
        fun strings(values: List<String>) = array(values) { budget.text(it); JsonPrimitive(it) }
        fun reference(ref: ContentRef): JsonObject {
            require(ref.providerId == providerId)
            return obj("providerId" to ref.providerId, "type" to ref.type.wireName, "contentId" to ref.contentId)
        }
        fun artists(values: List<SourceArtist>) = array(values) { obj("name" to it.name, "artistId" to it.ref?.artistId) }
        fun target(value: SourceFeedTarget): JsonObject = when (value) {
            is SourceFeedTarget.Content -> obj("kind" to "content", "contentRef" to reference(value.ref))
            is SourceFeedTarget.Route -> obj("kind" to "route", "route" to value.route, "parameters" to obj(*value.parameters.map { it.key to it.value }.toTypedArray()))
            is SourceFeedTarget.Radio -> obj("kind" to "radio", "seedIds" to strings(value.seedIds), "similar" to value.similar)
            is SourceFeedTarget.Queue -> {
                val parameters = value.parameters.toString()
                budget.text(parameters)
                obj("kind" to "queue", "feature" to value.feature, "action" to value.action, "parameters" to value.parameters.deepCopy())
            }
        }
        fun item(value: SourceItem): JsonObject {
            require(value.ref.providerId == providerId)
            val content = when (value) {
                is SourceItem.TrackItem -> value.track.let { obj("trackId" to it.ref.trackId, "title" to it.title,
                    "artists" to artists(it.artists), "album" to obj("title" to it.album.title, "albumId" to it.album.ref?.albumId),
                    "durationMs" to it.durationMs, "artworkUrl" to it.artworkUrl, "isrc" to it.isrc, "version" to it.version, "explicit" to it.explicit) }
                is SourceItem.PlaylistItem -> value.playlist.let { obj("playlistId" to it.ref.playlistId, "title" to it.title,
                    "artworkUrl" to it.artworkUrl, "creatorName" to it.creatorName, "trackCount" to it.trackCount, "description" to it.description, "playCount" to it.playCount, "subtitle" to it.subtitle,
                    "editableActions" to strings(it.editableActions.map { action -> action.wireName })) }
                is SourceItem.AlbumItem -> value.album.let { obj("albumId" to it.ref.albumId, "title" to it.title, "artworkUrl" to it.artworkUrl,
                    "artists" to artists(it.artists), "trackCount" to it.trackCount, "description" to it.description, "publishedAtMs" to it.publishedAtMs) }
                is SourceItem.ArtistItem -> value.artist.let { obj("artistId" to it.ref.artistId, "name" to it.name, "artworkUrl" to it.artworkUrl,
                    "description" to it.description, "alternateNames" to strings(it.alternateNames), "trackCount" to it.trackCount,
                    "albumCount" to it.albumCount, "musicVideoCount" to it.musicVideoCount, "identities" to strings(it.identities),
                    "expertise" to array(it.expertise) { expertise -> obj("name" to expertise.name, "count" to expertise.count) },
                    "translatedNames" to strings(it.translatedNames), "aliases" to strings(it.aliases)) }
            }
            return obj("type" to value.ref.type.wireName, value.ref.type.wireName to content)
        }
        return obj("sections" to array(page.sections) { section ->
            require(section.items.size <= 1000 && section.cards.size <= if (section.layout == SourceFeedLayout.HERO) 300 else 1000)
            obj("id" to section.id, "title" to section.title, "layout" to section.layout.name,
                "items" to array(section.items, ::item), "cards" to array(section.cards) { card ->
                    obj("id" to card.id, "title" to card.title, "artworkUrl" to card.artworkUrl,
                        "subtitle" to card.subtitle, "iconUrl" to card.iconUrl, "target" to target(card.target),
                        "coverCaptions" to strings(card.coverCaptions), "detail" to card.detail, "titleMaxLines" to card.titleMaxLines)
                })
        }, "categories" to array(page.categories) { obj("id" to it.id, "title" to it.title) })
    }

    private class Budget {
        private var bytes = 1024
        fun add(count: Int) { bytes += count; require(bytes <= MAX_BYTES) }
        fun text(value: String) {
            add(2)
            value.forEach { character -> add(when {
                character == '"' || character == '\\' -> 2
                character.code < 0x20 -> 6
                character.code <= 0x7f -> 1
                character.code <= 0x7ff -> 2
                else -> 3
            }) }
        }
    }
    private fun checkJsonDepth(value: String) {
        var depth = 0; var string = false; var escaped = false
        value.forEach { character ->
            if (string) {
                if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') string = false
            } else when (character) {
                '"' -> string = true
                '{', '[' -> { depth++; require(depth <= 64) }
                '}', ']' -> depth--
            }
        }
    }
    companion object {
        private const val MAX_BYTES = 8 * 1024 * 1024
        private val ZONE = ZoneId.of("Asia/Shanghai")
        internal fun isFresh(fetchedAt: Long, current: Long): Boolean {
            val local = Instant.ofEpochMilli(current).atZone(ZONE)
            val today = local.toLocalDate().atTime(LocalTime.of(6, 0)).atZone(ZONE)
            val boundary = if (local.isBefore(today)) today.minusDays(1) else today
            return fetchedAt in boundary.toInstant().toEpochMilli()..current
        }
    }
}
