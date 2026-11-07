package com.ljyh.mei.source.shared

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.ljyh.mei.data.model.melox.*
import com.ljyh.mei.source.api.*

/** Provider identities stay opaque. Only counters and timestamps are parsed as numbers. */
internal class SourceOnlineParser(val providerId: String, private val instanceId: ProviderInstanceId) {
    private val gson = Gson()

    suspend fun daily(value: JsonElement, parseTrack: suspend (JsonObject) -> Track): OnlineDailyRecommendations {
        val page = value.featureObject()
        val tracks = page.featureList("tracks", 1000).map { parseTrack(it.featureObject()) }
        if (tracks.any { it.ref.providerId != providerId }) invalidFeature()
        val refs = tracks.map { it.ref }.toSet()
        val recommendations = page.get("recommendations")?.featureList(1000).orEmpty().map { entry ->
            val row = entry.featureObject()
            OnlineDailyRecommendation(TrackRef(providerId, row.text("trackId")), row.optional("reason").orEmpty())
        }.filter { it.ref in refs }
        return OnlineDailyRecommendations(page.optional("title").orEmpty(), tracks, page.optional("creatorName").orEmpty(),
            page.optional("description"), page.optionalLong("updatedAtMs"), recommendations)
    }

    fun profile(value: JsonElement): OnlineAccountProfile = value.featureObject().let { p -> OnlineAccountProfile(
        p.text("id"), p.text("nickname"), p.optional("avatarUrl"), p.optional("backgroundUrl"), p.optional("signature"),
        p.optionalInt("follows"), p.optionalInt("followers"), p.optionalInt("eventCount"), p.optionalInt("playlistCount"),
        p.optionalInt("playlistSubscribedCount"), p.optional("artistId"), p.featureBoolean("hasVerifiedIdentity"), providerId, instanceId,
    ) }
    fun detail(value: JsonElement): OnlineAccountDetail = value.featureObject().let {
        OnlineAccountDetail(profile(it.get("profile") ?: invalidFeature()), it.int("level"), it.optionalInt("listenSongs"), it.optionalInt("createDays"))
    }
    fun playlist(value: JsonElement): OnlineAccountPlaylist = value.featureObject().let {
        OnlineAccountPlaylist(it.text("id"), it.text("name"), it.optional("coverUrl"), it.int("trackCount"), it.optional("creatorId"), it.optional("creatorName"), providerId)
    }
    fun song(value: JsonElement): OnlineAccountSong = value.featureObject().let { p ->
        val names = p.featureList("artists", 50).map { string(it) }
        val ids = p.get("artistIds")?.featureList(50).orEmpty().map { string(it) }
        val albumId = p.optional("albumId")?.takeUnless { it == "0" }
        OnlineAccountSong(Track(TrackRef(providerId, p.text("id")), p.text("name"),
            names.mapIndexed { index, name -> SourceArtist(name,
                ids.getOrNull(index)?.takeIf { ids.size == names.size }?.let { ArtistRef(providerId, it) }) },
            SourceAlbum(p.optional("album").orEmpty(), albumId?.let { AlbumRef(providerId, it) }),
            p.featureLong("durationMs"), p.optional("coverUrl").orEmpty()), p.optionalLong("playedAt"))
    }
    fun cloud(value: JsonElement): OnlineCloudSong = value.featureObject().let { p -> OnlineCloudSong(
        p.text("id"), p.text("name"), p.optional("artist").orEmpty(), p.optional("album").orEmpty(), p.optional("coverUrl"),
        p.featureLong("durationMs"), p.featureLong("fileSize"), p.int("bitrate"), p.featureLong("addTime"), providerId,
    ) }
    fun cloudPage(value: JsonElement, offset: Int): OnlineCloudPage = value.featureObject().let { page ->
        OnlineCloudPage(page.featureList("songs", 200).map(::cloud), page.int("count"), page.featureLong("usedSize"),
            page.featureLong("maxSize"), page.featureBoolean("hasMore"), page.int("nextOffset")).also {
            if (it.nextOffset < offset || it.hasMore && it.nextOffset <= offset) invalidFeature()
        }
    }
    fun podcast(value: JsonElement): OnlinePodcast = value.featureObject().let { p -> OnlinePodcast(
        p.text("id"), p.text("name"), p.optional("picUrl"), p.optional("description"), p.optional("recommendation"),
        p.optional("categoryId"), p.optional("category"), p.optional("secondCategory"), p.int("programCount"),
        p.featureLong("subscriberCount"), p.featureLong("playCount"), host(p.get("host")), p.featureBoolean("isSubscribed"), p.optionalInt("feeType"), providerId,
    ) }
    fun program(value: JsonElement): OnlinePodcastProgram = value.featureObject().let { p -> OnlinePodcastProgram(
        p.text("id"), p.text("name"), p.optional("coverUrl"), p.optional("description"), p.optionalLong("createTime"),
        p.featureLong("durationMs"), p.featureLong("listenerCount"), p.featureLong("likedCount"), p.featureLong("commentCount"), p.optionalInt("serialNumber"),
        p.text("radioId"), p.text("radioName"), host(p.get("host")), p.optional("mainSongId"), providerId,
    ) }
    private fun host(value: JsonElement?): OnlinePodcastHost? = value?.takeUnless { it.isJsonNull }?.featureObject()?.let {
        OnlinePodcastHost(it.text("id"), it.text("nickname"), it.optional("avatarUrl"))
    }
    fun wiki(value: JsonElement): OnlineSongWiki = value.featureObject().let { p ->
        OnlineSongWiki(decode(p.get("memories") ?: invalidFeature()), decode(p.get("tagGroups") ?: invalidFeature()),
            decode(p.get("attributes") ?: invalidFeature()), decode(p.get("associationGroups") ?: invalidFeature()), decode(p.get("reviews") ?: invalidFeature()),
            p.featureList("similarSongs").map { it.featureObject().let { row -> OnlineWikiSong(row.text("id"), row.text("title"), row.optional("artist"), row.optional("note"), row.optional("artworkUrl"), providerId) } }.distinctBy { it.id },
            p.featureList("relatedPlaylists").map { it.featureObject().let { row -> OnlineWikiPlaylist(row.text("id"), row.text("title"), row.optional("artworkUrl"), row.featureLong("playCount"), providerId) } }.distinctBy { it.id },
            safeContributionUrl(p.optional("contributionUrl")))
    }
    fun comment(value: JsonElement): OnlineComment = value.featureObject().let { p ->
        val user = p.get("user")?.featureObject() ?: invalidFeature()
        val author = OnlineCommentUser(user.optional("id") ?: user.text("userId"), user.text("nickname"), user.optional("avatarUrl"))
        val replies = p.get("showFloorComment")?.takeUnless { it.isJsonNull }?.featureObject()?.int("replyCount") ?: p.int("replyCount")
        OnlineComment(p.text("commentId"), author, p.featureText("content", optional = true, maximum = 20_000).orEmpty(),
            p.featureLong("time"), p.optional("timeStr").orEmpty(), p.featureBoolean("liked"), p.int("likedCount"), replies)
    }
    fun session(value: JsonElement): OnlineSessionState = value.featureObject().let {
        val authenticated = it.featureBoolean("authenticated")
        val kind = if (!authenticated) OnlineSessionKind.None else when (it.optional("kind")?.lowercase()) {
            "mobile" -> OnlineSessionKind.Mobile
            "web" -> OnlineSessionKind.Web
            else -> OnlineSessionKind.Other
        }
        OnlineSessionState(providerId, authenticated, kind)
    }
    private inline fun <reified T> decode(value: JsonElement): T = try {
        gson.fromJson<T>(value, object : TypeToken<T>() {}.type) ?: invalidFeature()
    } catch (_: Exception) { invalidFeature() }
    private fun string(value: JsonElement): String {
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString || value.asString.isBlank() || value.asString.length > 2048) invalidFeature()
        return value.asString
    }

    private fun safeContributionUrl(value: String?): String? = value?.let { target -> runCatching {
        val uri = java.net.URI(target)
        if (uri.scheme !in setOf("http", "https") || uri.rawUserInfo != null || uri.port !in setOf(-1, 443) || uri.host == null) return@runCatching null
        com.ljyh.mei.source.script.ScriptNetworkPolicy.validateDomain(uri.host)
        if (uri.scheme == "http") target.replaceFirst("http://", "https://") else target
    }.getOrNull() }
}

internal fun JsonObject.text(key: String): String = featureText(key).orEmpty()
internal fun JsonObject.optional(key: String): String? = featureText(key, optional = true, maximum = 20_000)
internal fun JsonObject.int(key: String): Int = featureLong(key).takeIf { it <= Int.MAX_VALUE }?.toInt() ?: invalidFeature()
internal fun JsonObject.optionalLong(key: String): Long? = get(key)?.takeUnless { it.isJsonNull }?.let { featureLong(key) }
internal fun JsonObject.optionalInt(key: String): Int? = get(key)?.takeUnless { it.isJsonNull }?.let { int(key) }

/** An SMS request can complete a full outgoing-SMS login instead of sending a code. */
internal fun sourceSmsLoginAuthenticated(value: JsonElement): Boolean {
    val result = value.featureObject()
    if (result.featureBoolean("authenticated")) return true
    if (result.featureText("status") != "sent") invalidFeature()
    return false
}
