package com.ljyh.mei.source.shared

import androidx.media3.common.MediaItem
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ljyh.mei.data.model.melox.ShareResourceKind
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.BoundSourceFeatures
import com.ljyh.mei.source.core.SourceFeatureClient
import com.ljyh.mei.source.core.SourceRouter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Public user identity is scoped to its provider, independent of the signed-in account. */
data class SourceMessageContact(val providerId: String, val id: String, val nickname: String,
    val avatarUrl: String?, val signature: String?, val remarkName: String?, val avatarBadgeUrl: String? = null) {
    val displayName get() = remarkName?.takeIf(String::isNotBlank) ?: nickname
}
data class SourceShareResource(val ref: ContentRef, val kind: ShareResourceKind, val title: String,
    val subtitle: String?, val artworkUrl: String?) { val id get() = ref.contentId }
data class SourceMessagePayload(val text: String, val resource: SourceShareResource?) {
    val summary get() = text.takeIf(String::isNotBlank) ?: resource?.title ?: "Private message"
}
data class SourcePrivateConversation(val providerId: String, val id: String, val fromUser: SourceMessageContact?,
    val toUser: SourceMessageContact?, val lastMessageTime: Long, val summary: String, val unreadCount: Int,
    val isOfficialGroup: Boolean = false) {
    fun participant(currentUserId: String): SourceMessageContact = fromUser?.takeIf { it.id != currentUserId }
        ?: toUser?.takeIf { it.id != currentUserId } ?: fromUser ?: toUser ?: invalidFeature()
}
data class SourceConversationPage(val conversations: List<SourcePrivateConversation>, val hasMore: Boolean, val nextTime: Long)
data class SourcePrivateMessage(val providerId: String, val id: String, val fromUser: SourceMessageContact?,
    val toUser: SourceMessageContact?, val time: Long, val payload: SourceMessagePayload)

@Singleton
class SourceSocialRepository @Inject constructor(private val client: SourceFeatureClient, private val router: SourceRouter) {
    suspend fun open(providerId: String? = null): SourceSocialSession {
        val scopeId = "messages:${UUID.randomUUID()}"
        val owner = if (providerId == null) client.current(scopeId) else client.bind(providerId, scopeId)
        val account = router.account(owner.providerId)
        owner.ensureCurrent()
        return SourceSocialSession(owner, account?.ref?.accountId?.value.orEmpty(), router)
    }
}

class SourceSocialSession internal constructor(val owner: BoundSourceFeatures, val userId: String, private val router: SourceRouter) {
    val providerId get() = owner.providerId
    suspend fun conversations(official: Boolean, before: Long = -1, offset: Int = 0): SourceConversationPage {
        val result = owner.invoke("messages", if (official) "officialConversations" else "privateConversations",
            featureParameters { addProperty("before", before); addProperty("offset", offset); addProperty("limit", 50) })
        if (!official) return SourceConversationPage(result.featureList().map { parseSourceConversation(providerId, it) }, false, -1)
        val value = result.featureObject()
        val rows = value.featureList("conversations").map { parseSourceConversation(providerId, it) }
        val next = value.get("nextTime")?.asString?.toLongOrNull() ?: -1
        return SourceConversationPage(rows, value.featureBoolean("hasMore") && rows.isNotEmpty(), next)
    }
    suspend fun messages(userId: String): List<SourcePrivateMessage> = owner.invoke("messages", "privateMessages",
        featureParameters { addProperty("userId", userId) }).featureList().map { parseSourceMessage(providerId, it) }
        .distinctBy { it.id }.sortedBy { it.time }
    suspend fun contacts(): List<SourceMessageContact> {
        if (userId.isBlank()) throw SourceException(SourceErrorCode.AUTH_REQUIRED)
        val contacts = linkedMapOf<String, SourceMessageContact>()
        var offset = 0
        repeat(10) {
            val page = owner.invoke("messages", "messageContacts", featureParameters {
                addProperty("userId", userId); addProperty("offset", offset); addProperty("pageSize", 100)
            }).featureObject()
            page.featureList("contacts", 100).map { parseSourceContact(providerId, it) }.forEach { contacts[it.id] = it }
            if (!page.featureBoolean("hasMore")) return contacts.values.toList()
            val next = page.featureLong("nextOffset").toInt()
            if (next <= offset || next > 1000) invalidFeature()
            offset = next
        }
        return contacts.values.toList()
    }
    suspend fun sendText(userId: String, text: String) = owner.requireTrue("messages", "sendPrivateText", featureParameters {
        addProperty("message", text); add("userIds", JsonArray().apply { add(userId) })
    })
    suspend fun sendResource(resource: SourceShareResource, recipients: Set<String>, message: String) {
        requireResource(resource)
        owner.requireTrue("messages", "sendPrivateResource", featureParameters {
            add("resource", resourceJson(resource)); add("userIds", JsonArray().apply { recipients.forEach(::add) }); addProperty("message", message)
        })
    }
    suspend fun shareTimeline(resource: SourceShareResource, message: String) {
        requireResource(resource)
        owner.requireTrue("social", "shareToTimeline", featureParameters { add("resource", resourceJson(resource)); addProperty("message", message) })
    }
    suspend fun shareLink(resource: SourceShareResource): String? {
        requireResource(resource)
        if (!owner.supports("social", "shareLink")) return null
        val result = owner.invoke("social", "shareLink", featureParameters { add("resource", resourceJson(resource)) })
        if (result.isJsonNull) return null
        if (!result.isJsonPrimitive || !result.asJsonPrimitive.isString) invalidFeature()
        return result.asString.takeIf { it.startsWith("https://") && it.length <= 8192 } ?: invalidFeature()
    }
    suspend fun media(resource: SourceShareResource): MediaItem {
        requireResource(resource)
        val ref = resource.ref as? TrackRef ?: invalidFeature()
        owner.ensureCurrent()
        val track = owner.whileCurrent { router.getTrack(ref) } ?: throw SourceException(SourceErrorCode.NOT_FOUND)
        owner.ensureCurrent()
        return track.toMediaMetadata().toMediaItem()
    }
    private fun requireResource(resource: SourceShareResource) {
        if (resource.ref.providerId != providerId || when (resource.kind) {
            ShareResourceKind.Song -> resource.ref !is TrackRef
            ShareResourceKind.Playlist -> resource.ref !is PlaylistRef
            ShareResourceKind.Album -> resource.ref !is AlbumRef
        }) invalidFeature()
    }
    private fun resourceJson(resource: SourceShareResource) = featureParameters {
        addProperty("id", resource.ref.contentId); addProperty("kind", resource.kind.name); addProperty("title", resource.title)
    }
}

internal fun parseSourceContact(providerId: String, value: JsonElement): SourceMessageContact {
    val row = value.featureObject()
    return SourceMessageContact(providerId, row.featureText("id")!!, row.featureText("nickname", maximum = 1000)!!,
        row.featureText("avatarUrl", true, 8192), row.featureText("signature", true, 10000), row.featureText("remarkName", true, 1000),
        row.featureText("avatarBadgeUrl", true, 8192))
}
private fun JsonObject.sourceContact(providerId: String, key: String): SourceMessageContact? = get(key)?.takeUnless { it.isJsonNull }?.let { parseSourceContact(providerId, it) }
internal fun parseSourceResource(providerId: String, value: JsonElement): SourceShareResource {
    val row = value.featureObject()
    val kind = ShareResourceKind.entries.firstOrNull { it.name.equals(row.featureText("kind"), true) } ?: invalidFeature()
    val id = row.featureText("id")!!
    val ref = when (kind) { ShareResourceKind.Song -> TrackRef(providerId, id); ShareResourceKind.Playlist -> PlaylistRef(providerId, id); ShareResourceKind.Album -> AlbumRef(providerId, id) }
    return SourceShareResource(ref, kind, row.featureText("title", maximum = 1000)!!, row.featureText("subtitle", true, 1000), row.featureText("artworkUrl", true, 8192))
}
internal fun parseSourceConversation(providerId: String, value: JsonElement): SourcePrivateConversation {
    val row = value.featureObject()
    val from = row.sourceContact(providerId, "fromUser"); val to = row.sourceContact(providerId, "toUser")
    if (from == null && to == null) invalidFeature()
    return SourcePrivateConversation(providerId, row.featureText("id")!!, from, to, row.featureLong("lastMessageTime"), row.featureText("summary", true, 20000).orEmpty(),
        row.featureLong("unreadCount").coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), row.featureBoolean("isOfficialGroup"))
}
internal fun parseSourceMessage(providerId: String, value: JsonElement): SourcePrivateMessage {
    val row = value.featureObject(); val payload = row.get("payload")?.featureObject() ?: invalidFeature()
    return SourcePrivateMessage(providerId, row.featureText("id")!!, row.sourceContact(providerId, "fromUser"), row.sourceContact(providerId, "toUser"), row.featureLong("time"),
        SourceMessagePayload(payload.featureText("text", true, 20000).orEmpty(), payload.get("resource")?.takeUnless { it.isJsonNull }?.let { parseSourceResource(providerId, it) }))
}
