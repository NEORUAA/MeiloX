package com.ljyh.mei.source.script

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ljyh.mei.source.api.*

/** Validates untrusted catalog values before the UI, database, or navigation can consume them. */
internal class ScriptContentParser(
    private val providerId: String,
    private val apiVersion: Int,
    private val playlistActions: Set<PlaylistEditAction>,
    private val features: Map<String, Set<String>> = emptyMap(),
    private val featureRoutes: Set<String> = emptySet(),
    private val resolveArtwork: suspend (String) -> String,
) {
    suspend fun track(value: JsonObject): Track {
        if (apiVersion == 1) return legacyTrack(value)
        val artists = optionalArray(value, "artists", 50).map { element ->
            if (element.isJsonPrimitive) SourceArtist(string(element, 1000)) else {
                val artist = element.asJsonObject
                SourceArtist(text(artist, "name", 1000), optionalId(artist, "artistId")?.let { ArtistRef(providerId, it) })
            }
        }
        val album = value.get("album")?.takeUnless { it.isJsonNull }?.let { element ->
            if (element.isJsonPrimitive) SourceAlbum(string(element, 1000, allowEmpty = true)) else {
                val info = element.asJsonObject
                SourceAlbum(text(info, "title", 1000, allowEmpty = true), optionalId(info, "albumId")?.let { AlbumRef(providerId, it) })
            }
        } ?: SourceAlbum("")
        return Track(TrackRef(providerId, text(value, "trackId", 2048)), text(value, "title", 1000), artists, album,
            optionalLong(value, "durationMs") ?: 0L, artwork(value), optionalText(value, "isrc", 100),
            optionalText(value, "version", 1000), optionalBoolean(value, "explicit") ?: false)
    }

    private suspend fun legacyTrack(value: JsonObject): Track {
        val trackId = value.get("trackId")?.asString?.takeIf { it.isNotEmpty() && it.length <= 2048 } ?: invalid()
        val title = value.get("title")?.asString?.takeIf { it.isNotBlank() && it.length <= 1000 } ?: invalid()
        val artists = value.getAsJsonArray("artists")?.take(50)?.map { element ->
            if (element.isJsonPrimitive) element.asString else element.asJsonObject.get("name")?.asString ?: invalid()
        }.orEmpty()
        val album = value.get("album")?.let { if (it.isJsonPrimitive) it.asString else it.asJsonObject.get("title")?.asString }.orEmpty()
        val duration = value.get("durationMs")?.asLong ?: 0L
        if (duration < 0 || artists.any { it.length > 1000 }) invalid()
        return Track(TrackRef(providerId, trackId), title, artists.map { SourceArtist(it) }, SourceAlbum(album), duration,
            artwork(value), value.get("isrc")?.asString, value.get("version")?.asString, value.get("explicit")?.asBoolean ?: false)
    }

    suspend fun album(value: JsonObject): SourceAlbumInfo = SourceAlbumInfo(
        AlbumRef(providerId, text(value, "albumId", 2048)), text(value, "title", 1000), artwork(value),
        optionalArray(value, "artists", 50).map { element ->
            if (element.isJsonPrimitive) SourceArtist(string(element, 1000)) else {
                val artist = element.asJsonObject
                SourceArtist(text(artist, "name", 1000), optionalId(artist, "artistId")?.let { ArtistRef(providerId, it) })
            }
        }, count(value, "trackCount"), optionalText(value, "description", 10_000),
        value.get("publishedAtMs")?.takeUnless { it.isJsonNull }?.asLong?.also { if (it < 0) invalid() },
    )

    suspend fun artist(value: JsonObject): SourceArtistInfo = SourceArtistInfo(
        ArtistRef(providerId, text(value, "artistId", 2048)), text(value, "name", 1000), artwork(value), optionalText(value, "description", 10_000),
        optionalArray(value, "alternateNames", 20).map { string(it, 1000) },
        optionalInt(value, "trackCount"), optionalInt(value, "albumCount"), optionalInt(value, "musicVideoCount"),
        optionalArray(value, "identities", 50).map { string(it, 1000) },
        optionalArray(value, "expertise", 50).map { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: invalid()
            SourceArtistExpertise(text(item, "name", 1000), optionalInt(item, "count"))
        },
        optionalArray(value, "translatedNames", 20).map { string(it, 1000) },
        optionalArray(value, "aliases", 50).map { string(it, 1000) },
    )

    private fun optionalInt(value: JsonObject, key: String): Int? = optionalLong(value, key)?.let {
        if (it > Int.MAX_VALUE) invalid()
        it.toInt()
    }

    suspend fun playlist(value: JsonObject): SourcePlaylist {
        val actions = optionalArray(value, "editableActions", PlaylistEditAction.entries.size).map { element ->
            PlaylistEditAction.entries.firstOrNull { it.wireName == string(element, 50) } ?: invalid()
        }.toSet()
        if (!playlistActions.containsAll(actions)) invalid()
        return SourcePlaylist(PlaylistRef(providerId, text(value, "playlistId", 2048)), text(value, "title", 1000), artwork(value),
            optionalText(value, "creatorName", 1000).orEmpty(), count(value, "trackCount"), optionalText(value, "description", 10_000), actions,
            optionalLong(value, "playCount"), optionalText(value, "subtitle", 1000))
    }

    suspend fun item(value: JsonObject, expected: ContentType? = null): SourceItem {
        val type = ContentType.entries.firstOrNull { it.wireName == text(value, "type", 30) } ?: invalid()
        if (expected != null && type != expected) invalid()
        val payload = value.get(type.wireName)?.takeIf { it.isJsonObject }?.asJsonObject ?: invalid()
        return when (type) {
            ContentType.TRACK -> SourceItem.TrackItem(track(payload))
            ContentType.PLAYLIST -> SourceItem.PlaylistItem(playlist(payload))
            ContentType.ALBUM -> SourceItem.AlbumItem(album(payload))
            ContentType.ARTIST -> SourceItem.ArtistItem(artist(payload))
        }
    }

    suspend fun feed(value: JsonObject, request: PageRequest = PageRequest()): SourceFeedPage {
        val sections = array(value, "sections", 30).map { element ->
            val section = element.asJsonObject
            val layout = SourceFeedLayout.entries.firstOrNull { it.name.equals(text(section, "layout", 30), true) } ?: invalid()
            val expected = when (layout) {
                SourceFeedLayout.PLAYLISTS -> ContentType.PLAYLIST
                SourceFeedLayout.TRACKS -> ContentType.TRACK
                SourceFeedLayout.ALBUMS -> ContentType.ALBUM
                SourceFeedLayout.ARTISTS -> ContentType.ARTIST
                else -> null
            }
            SourceFeedSection(text(section, "id", 2048), text(section, "title", 1000, allowEmpty = true), layout,
                array(section, "items", 100).map { item(it.asJsonObject, expected) },
                optionalArray(section, "cards", if (layout == SourceFeedLayout.HERO) 30 else 100)
                    .map { card(it.asJsonObject, layout, expected) }.also { cards ->
                        if (cards.isNotEmpty() && layout == SourceFeedLayout.TRACKS || cards.map { it.id }.distinct().size != cards.size) invalid()
                    })
        }
        if (sections.map { it.id }.distinct().size != sections.size) invalid()
        val categories = optionalArray(value, "categories", 100).map { element ->
            val category = element.asJsonObject
            SourceCategory(text(category, "id", 2048), text(category, "title", 1000))
        }
        if (categories.map { it.id }.distinct().size != categories.size) invalid()
        val hasMore = optionalBoolean(value, "hasMore") ?: false
        val cursor = cursor(value)
        if (hasMore && (cursor == request.cursor || cursor == null && sections.isEmpty())) invalid()
        return SourceFeedPage(sections, categories, hasMore, cursor)
    }

    private suspend fun card(value: JsonObject, layout: SourceFeedLayout, expected: ContentType?): SourceFeedCard {
        val raw = value.get("target")?.takeIf { it.isJsonObject }?.asJsonObject ?: invalid()
        val target = when (text(raw, "kind", 30)) {
            "content" -> {
                val ref = raw.get("contentRef")?.takeIf { it.isJsonObject }?.asJsonObject ?: invalid()
                if (ref.has("providerId") && text(ref, "providerId", 2048) != providerId) invalid()
                val type = ContentType.entries.firstOrNull { it.wireName == text(ref, "type", 30) } ?: invalid()
                val key = text(ref, "contentId", 2048)
                SourceFeedTarget.Content(when (type) {
                    ContentType.TRACK -> TrackRef(providerId, key)
                    ContentType.ALBUM -> AlbumRef(providerId, key)
                    ContentType.ARTIST -> ArtistRef(providerId, key)
                    ContentType.PLAYLIST -> PlaylistRef(providerId, key)
                })
            }
            "route" -> {
                val route = text(raw, "route", 200).also { if (it !in featureRoutes) invalid() }
                val fields = parameters(raw)
                if (fields.size() > 10) invalid()
                SourceFeedTarget.Route(route, fields.entrySet().associate { (key, entry) ->
                    if (!key.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,39}")) || key == "providerId") invalid()
                    key to string(entry, 2048, allowEmpty = true)
                })
            }
            "radio" -> {
                if ("personalRadio" !in features["discovery"].orEmpty()) invalid()
                val seeds = optionalArray(raw, "seedIds", 100).map { string(it, 2048) }
                if (seeds.distinct().size != seeds.size) invalid()
                val similar = optionalBoolean(raw, "similar") ?: false
                if (similar && seeds.isEmpty()) invalid()
                SourceFeedTarget.Radio(seeds, similar)
            }
            "queue" -> {
                val feature = text(raw, "feature", 80)
                val action = text(raw, "action", 80)
                if (action !in features[feature].orEmpty()) invalid()
                SourceFeedTarget.Queue(feature, action, parameters(raw))
            }
            else -> invalid()
        }
        if (layout != SourceFeedLayout.HERO) {
            val ref = (target as? SourceFeedTarget.Content)?.ref ?: invalid()
            if (expected != null && ref.type != expected) invalid()
        }
        val titleMaxLines = (optionalLong(value, "titleMaxLines") ?: 2L).also { if (it !in 1L..2L) invalid() }.toInt()
        return SourceFeedCard(text(value, "id", 2048), text(value, "title", 1000, allowEmpty = layout != SourceFeedLayout.HERO), artwork(value),
            optionalText(value, "subtitle", 1000).orEmpty(), artwork(value, "iconUrl"), target,
            optionalArray(value, "coverCaptions", 10).map { string(it, 1000, allowEmpty = true) },
            optionalText(value, "detail", 1000), titleMaxLines)
    }

    private fun parameters(value: JsonObject): JsonObject = value.get("parameters")?.takeUnless { it.isJsonNull }?.let {
        if (!it.isJsonObject || it.toString().length > 32_768 || it.asJsonObject.has("$" + "continuation")) invalid()
        it.asJsonObject.deepCopy()
    } ?: JsonObject()

    suspend fun account(value: JsonObject, instanceId: ProviderInstanceId, version: String): SourceAccount {
        val accountId = text(value, "accountId", 2048)
        val revision = optionalText(value, "sessionRevision", 4096).orEmpty()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest("$version|$accountId|$revision".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return SourceAccount(SourceAccountRef(instanceId, ProviderAccountId(accountId)), text(value, "displayName", 1000), artwork(value, "avatarUrl"), digest)
    }

    suspend fun <T> page(value: JsonObject, request: PageRequest, parse: suspend (JsonObject) -> T): SourcePage<T> {
        val values = array(value, "items", request.limit)
        val hasMore = optionalBoolean(value, "hasMore") ?: false
        val cursor = cursor(value)
        if (hasMore && (values.size() == 0 || cursor != null && cursor == request.cursor)) invalid()
        return SourcePage(values.map { parse(it.asJsonObject) }, hasMore, cursor)
    }

    fun boolean(value: JsonElement): Boolean = value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: invalid()

    private suspend fun artwork(value: JsonObject, key: String = "artworkUrl"): String {
        val url = optionalText(value, key, 8192).orEmpty()
        if (url.isEmpty()) return ""
        return try { resolveArtwork(url) } catch (error: SourceException) {
            if (error.code in setOf(SourceErrorCode.NOT_FOUND, SourceErrorCode.NETWORK, SourceErrorCode.TIMEOUT)) ""
            else throw error
        }
    }
    private fun cursor(value: JsonObject): String? = optionalText(value, "nextCursor", 4096)?.also { if (it.isEmpty()) invalid() }
    private fun count(value: JsonObject, key: String): Int = (optionalLong(value, key) ?: 0L).also { if (it > Int.MAX_VALUE) invalid() }.toInt()
    private fun optionalLong(value: JsonObject, key: String): Long? = value.get(key)?.takeUnless { it.isJsonNull }?.let {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isNumber) invalid()
        try { it.asBigDecimal.toBigIntegerExact().longValueExact().also { number -> if (number < 0) invalid() } }
        catch (_: ArithmeticException) { invalid() }
    }
    private fun optionalBoolean(value: JsonObject, key: String): Boolean? = value.get(key)?.takeUnless { it.isJsonNull }?.let(::boolean)
    private fun text(value: JsonObject, key: String, max: Int, allowEmpty: Boolean = false): String = string(value.get(key) ?: invalid(), max, allowEmpty)
    private fun optionalText(value: JsonObject, key: String, max: Int): String? = value.get(key)?.takeUnless { it.isJsonNull }?.let { string(it, max, allowEmpty = true) }
    private fun optionalId(value: JsonObject, key: String): String? = optionalText(value, key, 2048)?.also { if (it.isBlank()) invalid() }
    private fun string(value: JsonElement, max: Int, allowEmpty: Boolean = false): String {
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) invalid()
        return value.asString.also { if (it.length > max || !allowEmpty && it.isBlank()) invalid() }
    }
    private fun array(value: JsonObject, key: String, max: Int): JsonArray =
        value.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.also { if (it.size() > max) invalid() } ?: invalid()
    private fun optionalArray(value: JsonObject, key: String, max: Int): JsonArray =
        if (value.get(key)?.isJsonNull != false) JsonArray() else array(value, key, max)
    private fun invalid(): Nothing = throw SourceException(SourceErrorCode.INVALID_RESPONSE, "Source returned invalid data")
}
