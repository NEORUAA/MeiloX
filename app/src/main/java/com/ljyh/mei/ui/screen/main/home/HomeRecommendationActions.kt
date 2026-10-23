package com.ljyh.mei.ui.screen.main.home

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.data.model.eapi.HomePageResourceShow
import com.ljyh.mei.data.model.eapi.HomePageResourceShow.Data.Block.DslData.BlockResource.Resource as HomeCardResource
import java.net.URI
import java.net.URLDecoder

internal val HomePlaylistBlockCodes = setOf(
    "PAGE_RECOMMEND_RADAR",
    "PAGE_RECOMMEND_SPECIAL_CLOUD_VILLAGE_PLAYLIST",
    "PAGE_RECOMMEND_MIXED_ARTIST_PLAYLIST",
    "PAGE_RECOMMEND_RANK",
    "PAGE_RECOMMEND_MY_SHEET",
    "PAGE_RECOMMEND_COMBINATION",
    "PAGE_RECOMMEND_FEELING_PLAYLIST_LOCATION",
    "PAGE_RECOMMEND_SCENE_PLAYLIST_LOCATION",
    "PAGE_RECOMMEND_MONTH_YEAR_PLAYLIST",
)

internal val HomeSongBlockCodes = setOf(
    "PAGE_RECOMMEND_PRIVATE_RCMD_SONG",
    "PAGE_RECOMMEND_RED_SIMILAR_SONG",
)

/** The server's cover label is stored in tags, separately from artist metadata. */
internal fun homeArtistPlaylistCoverText(resource: HomeCardResource): String? =
    resource.tags.orEmpty().filterIsInstance<String>().filter(String::isNotBlank)
        .joinToString("").takeIf(String::isNotBlank)

/** Remove only the first caption watermark, retaining artwork, resizing and other overlays. */
internal fun homeArtistPlaylistCoverWithoutCaption(coverImg: String): String {
    val operations = coverImg.split(Regex("(?i)%7c|\\|"))
    val captionIndex = operations.indexOfFirst {
        it.startsWith("watermark&") || it.startsWith("imageView=1&watermark&")
    }
    if (captionIndex < 1) return coverImg
    return operations.filterIndexed { index, _ -> index != captionIndex }.joinToString("%7C")
}

/** Non-rendering blocks must not create lazy items: even empty items reserve section spacing. */
internal fun homeBlockData(block: HomePageResourceShow.Data.Block): JsonObject? {
    val data = block.dslData?.takeIf { it.isJsonObject }?.asJsonObject
        ?.let(::selectSpecialField) ?: return null
    val hasContent = when (block.positionCode) {
        "PAGE_RECOMMEND_DAILY_RECOMMEND" ->
            data.get("resources")?.takeIf { it.isJsonArray }?.asJsonArray?.isEmpty == false
        in HomePlaylistBlockCodes -> {
            val resources = data.get("resources") ?: if (block.positionCode == "PAGE_RECOMMEND_RADAR") {
                data.get("blockResource")
            } else null
            val minimumCount = if (block.positionCode == "PAGE_RECOMMEND_MY_SHEET") 2 else 1
            (resources?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0) >= minimumCount
        }
        in HomeSongBlockCodes -> data.get("content")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("items")?.takeIf { it.isJsonArray }?.asJsonArray?.any { page ->
                page.takeIf { it.isJsonObject }?.asJsonObject?.get("items")
                    ?.takeIf { it.isJsonArray }?.asJsonArray?.isEmpty == false
            } == true
        else -> false
    }
    return data.takeIf { hasContent }
}

fun selectSpecialField(jsonObject: JsonObject): JsonObject? {
    if (jsonObject.get("blockResource")?.isJsonObject == true) {
        return jsonObject.getAsJsonObject("blockResource")
    }
    val candidate = jsonObject.entrySet()
        .filter { it.value.isJsonObject }
        .maxByOrNull { it.key.length }
        ?.value?.asJsonObject ?: return null
    return if (candidate.get("blockResource")?.isJsonObject == true) {
        candidate.getAsJsonObject("blockResource")
    } else candidate
}

/** Stable identities survive card reordering, while repeated server entries remain valid lazy keys. */
internal fun homeResourceKeys(resources: List<HomeCardResource>): List<String> {
    val occurrences = mutableMapOf<String, Int>()
    return resources.map { resource ->
        val id = resource.resourceId.orEmpty().takeUnless { it == "null" }.orEmpty()
        val action = resource.action.orEmpty()
        val fields = listOf(resource.resourceType.orEmpty(), id, action) +
            if (id.isBlank() && action.isBlank()) listOf(resource.coverImg.orEmpty(), resource.title.orEmpty())
            else emptyList()
        val identity = fields.joinToString("") { "${it.length}:$it" }
        val occurrence = occurrences[identity] ?: 0
        occurrences[identity] = occurrence + 1
        "$identity#$occurrence"
    }
}

internal fun parseRecommendationSongIds(value: String?): List<String> = runCatching {
    val array = value?.let(JsonParser::parseString)?.takeIf(JsonElement::isJsonArray)
        ?.asJsonArray ?: return@runCatching emptyList()
    array.mapNotNull { element ->
        element.takeIf(JsonElement::isJsonPrimitive)?.asJsonPrimitive
            ?.takeIf { it.isString || it.isNumber }
            ?.asString?.positiveId()?.toString()
    }.distinct()
}.getOrDefault(emptyList())

internal fun parseRecommendationPlaylistId(action: String?, playBtnData: Any?): Long? =
    playlistIdFromUri(action) ?: playlistIdFromUri(playButtonDetailUrl(playBtnData))

private fun playlistIdFromUri(value: String?): Long? = runCatching {
    val uri = value?.trim()?.takeIf(String::isNotEmpty)?.let(::URI) ?: return@runCatching null
    if (!uri.scheme.equals("orpheus", ignoreCase = true) || uri.userInfo != null || uri.port != -1) {
        return@runCatching null
    }
    when {
        uri.host.equals("playlist", ignoreCase = true) -> {
            val path = uri.path.orEmpty()
            if (!path.startsWith('/')) return@runCatching null
            path.removePrefix("/").removeSuffix("/").positiveId()
        }
        uri.host.equals("nm", ignoreCase = true) && uri.path?.removeSuffix("/") == "/playlist/detail" -> {
            uri.rawQuery.orEmpty().split('&').mapNotNull { parameter ->
                val pair = parameter.split('=', limit = 2)
                if (pair.size == 2 && pair[0].decodeQuery() == "id") pair[1].decodeQuery() else null
            }.singleOrNull()?.positiveId()
        }
        else -> null
    }
}.getOrNull()

private fun playButtonDetailUrl(value: Any?): String? = runCatching {
    val details = when (value) {
        is String -> JsonParser.parseString(value).takeIf(JsonElement::isJsonObject)?.asJsonObject
        is JsonObject -> value
        is Map<*, *> -> return@runCatching value["detailUrl"] as? String
        else -> null
    }
    details?.get("detailUrl")?.takeIf(JsonElement::isJsonPrimitive)?.asJsonPrimitive
        ?.takeIf { it.isString }?.asString
}.getOrNull()

private fun String.decodeQuery(): String = URLDecoder.decode(this, "UTF-8")

private fun String.positiveId(): Long? = trim()
    .takeIf { it.isNotEmpty() && it.all { character -> character in '0'..'9' } }
    ?.toLongOrNull()?.takeIf { it > 0 }
