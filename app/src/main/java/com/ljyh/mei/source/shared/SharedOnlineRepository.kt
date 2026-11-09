package com.ljyh.mei.source.shared

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.ljyh.mei.data.model.api.CommentResourceType
import com.ljyh.mei.data.model.api.CommentSortType
import com.ljyh.mei.data.model.melox.*
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.BoundSourceFeatures
import com.ljyh.mei.source.core.SourceFeatureClient
import com.ljyh.mei.source.core.SourceFeatureSessionIdentity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Source-neutral online operations. A screen captures its provider and account exactly once. */
@Singleton
class SharedOnlineRepository @Inject constructor(private val client: SourceFeatureClient) {
    private val cloudPages = SourceCloudPageCache()
    fun screen(name: String, providerId: String? = null): SharedOnlineSession =
        SharedOnlineSession(client, "$name:${UUID.randomUUID()}", providerId, cloudPages)
}

/** Presentation records preserve opaque identities for every provider. */
class SharedOnlineSession internal constructor(
    private val client: SourceFeatureClient,
    private val scopeId: String,
    private val requestedProviderId: String?,
    private val cloudPages: SourceCloudPageCache = SourceCloudPageCache(),
) {
    private val mutex = Mutex()
    private var bound: BoundSourceFeatures? = null
    private val gson = Gson()
    val providerId: String? get() = bound?.providerId ?: requestedProviderId

    suspend fun features(): BoundSourceFeatures = mutex.withLock {
        bound ?: (requestedProviderId?.let { client.bind(it, scopeId) } ?: client.current(scopeId)).also { bound = it }
    }

    val sessionChanges: Flow<String> = flow {
        try {
            val source = features()
            emit(source.providerId)
            source.awaitInvalidation()
        } catch (cancelled: CancellationException) {
            if (currentCoroutineContext().isActive) emit("")
            throw cancelled
        } catch (_: Exception) { emit("") }
    }

    suspend fun invoke(feature: String, action: String, parameters: JsonObject = JsonObject(), allowSessionChange: Boolean = false): JsonElement {
        val source = features()
        if (allowSessionChange) cloudPages.invalidate(source)
        try {
            return source.invoke(feature, action, parameters, allowSessionChange)
        } catch (error: SourceException) {
            if (error.code in setOf(SourceErrorCode.AUTH_REQUIRED, SourceErrorCode.AUTH_EXPIRED)) cloudPages.invalidate(source)
            throw error
        } finally {
            if (allowSessionChange) cloudPages.invalidate(source)
        }
    }

    private suspend fun parser(): SourceOnlineParser = features().let { SourceOnlineParser(it.providerId, it.instanceId) }

    suspend fun accountProfileRecord(): OnlineAccountProfile = parser().profile(invoke("account", "accountProfile"))
    suspend fun accountProfile(): OnlineAccountProfile = accountProfileRecord()
    suspend fun accountDetail(userId: String): OnlineAccountDetail = parser().detail(invoke("account", "accountDetail", params("userId" to userId)))
    suspend fun accountPlaylistRecords(userId: String, limit: Int = 2000): List<OnlineAccountPlaylist> {
        require(userId.isNotBlank() && limit in 1..2000)
        val parse = parser()
        val result = mutableListOf<OnlineAccountPlaylist>()
        var offset = 0
        repeat(20) {
            val page = invoke("account", "accountPlaylists", params("userId" to userId, "offset" to offset, "limit" to minOf(100, limit - offset)))
            val rows = if (page.isJsonArray) page.featureList(2000) else page.featureObject().featureList("playlists", 100)
            result += rows.map(parse::playlist).take(limit - result.size)
            if (page.isJsonArray || result.size >= limit) return result.distinctBy { it.ref }
            val objectPage = page.featureObject()
            if (!objectPage.featureBoolean("hasMore")) return result.distinctBy { it.ref }
            val next = objectPage.featureLong("nextOffset").takeIf { it > offset && it <= Int.MAX_VALUE }?.toInt() ?: invalidFeature()
            if (next >= limit) return result.distinctBy { it.ref }
            offset = next
        }
        invalidFeature()
    }
    suspend fun accountPlaylists(userId: String, limit: Int = 2000): List<OnlineAccountPlaylist> = accountPlaylistRecords(userId, limit)
    suspend fun accountConnections(userId: String, type: AccountConnectionType, offset: Int = 0, limit: Int = 30): OnlineAccountConnectionPage {
        val parse = parser()
        val page = invoke("account", "accountConnections", params("userId" to userId, "type" to type.name, "offset" to offset, "pageSize" to limit)).featureObject()
        return OnlineAccountConnectionPage(page.featureList("users", 100).map(parse::profile).distinctBy { it.ref }, page.featureBoolean("hasMore"), page.int("nextOffset"))
    }
    suspend fun setArtistFollowed(id: String, followed: Boolean) = requireTrue("account", "setArtistFollowed", params("id" to id, "followed" to followed))

    suspend fun summary(): AccountSecuritySummary = decode(invoke("security", "summary"))
    suspend fun musician(): MusicianAccount = decode(invoke("security", "musician"))
    suspend fun devices(): List<AccountDevice> = decode(invoke("security", "devices"))
    suspend fun authorizations(history: Boolean, cursor: String = ""): AccountAuthorizationPage = decode(invoke("security", "authorizations", params("history" to history, "cursor" to cursor)))
    suspend fun removeDevice(key: String, captcha: String = "") = requireTrue("security", "removeDevice", params("key" to key, "captcha" to captcha))
    suspend fun revokeAuthorization(clientId: String) = requireTrue("security", "revokeAuthorization", params("clientId" to clientId))
    suspend fun sendDeviceVerificationCode(countryCode: String = "86") = requireTrue("security", "sendDeviceVerificationCode", params("countryCode" to countryCode))

    suspend fun cloudSongs(offset: Int = 0, limit: Int = 200, refresh: Boolean = false): OnlineCloudPage {
        val source = features()
        if (refresh) cloudPages.invalidate(source)
        if (offset == 0) cloudPages.get(source, limit)?.let { return it }
        val ticket = cloudPages.ticket(source)
        val page = parser().cloudPage(invoke("cloud", "cloudSongs", params("offset" to offset, "limit" to limit)), offset)
        if (offset == 0) cloudPages.put(source, ticket, limit, page)
        return page
    }
    suspend fun deleteCloudSong(id: String) {
        val source = features()
        cloudPages.invalidate(source)
        try { requireTrue("cloud", "deleteCloudSong", params("id" to id)) }
        finally { cloudPages.invalidate(source) }
    }
    suspend fun uploadCloudSong() {
        val source = features()
        cloudPages.invalidate(source)
        try { source.requireStatus("cloud", "uploadCloudSong", "completed") }
        finally { cloudPages.invalidate(source) }
    }

    suspend fun podcastHome(): OnlinePodcastHome {
        val parse = parser()
        val value = invoke("podcasts", "podcastHome").featureObject()
        return OnlinePodcastHome(value.featureList("categories").map { row -> row.featureObject().let { OnlinePodcastCategory(it.text("id"), it.text("name"), it.optional("picUrl")) } },
            value.featureList("featured").map(parse::podcast), value.featureList("personalized").map(parse::podcast))
    }
    suspend fun podcasts(categoryId: String, offset: Int = 0, limit: Int = 30): List<OnlinePodcast> =
        parser().let { parse -> invoke("podcasts", "podcasts", params("categoryId" to categoryId, "offset" to offset, "limit" to limit)).featureList(100).map(parse::podcast) }
    suspend fun podcastDetail(id: String, offset: Int = 0, limit: Int = 50): OnlinePodcastDetail {
        val parse = parser()
        val page = invoke("podcasts", "podcastDetail", params("id" to id, "offset" to offset, "limit" to limit)).featureObject()
        return OnlinePodcastDetail(parse.podcast(page.get("podcast") ?: invalidFeature()), page.featureList("programs", 100).map(parse::program).distinctBy { it.id }, page.featureBoolean("hasMore"), page.int("totalCount"), page.int("nextOffset"))
    }
    suspend fun podcastProgram(id: String): OnlinePodcastProgram = parser().program(invoke("podcasts", "podcastProgram", params("id" to id)))
    suspend fun podcastPrograms(id: String, offset: Int = 0, limit: Int = 50): OnlinePodcastProgramPage {
        val parse = parser()
        val page = invoke("podcasts", "podcastPrograms", params("id" to id, "offset" to offset, "limit" to limit)).featureObject()
        return OnlinePodcastProgramPage(page.featureList("programs", 100).map(parse::program), page.featureBoolean("hasMore"), page.int("totalCount"), page.int("fetchedCount"))
    }
    suspend fun subscribedPodcasts(offset: Int = 0, limit: Int = 50): OnlinePodcastPage {
        val parse = parser()
        val page = invoke("podcasts", "subscribedPodcasts", params("offset" to offset, "limit" to limit)).featureObject()
        return OnlinePodcastPage(page.featureList("podcasts", 100).map(parse::podcast), page.featureBoolean("hasMore"), page.int("totalCount"), page.int("fetchedCount"))
    }
    suspend fun searchPodcasts(query: String, offset: Int = 0, limit: Int = 30): OnlinePodcastPage {
        val parse = parser()
        val page = invoke("podcasts", "search", params("query" to query, "offset" to offset, "limit" to limit)).featureObject()
        return OnlinePodcastPage(page.featureList("podcasts", 100).map(parse::podcast), page.featureBoolean("hasMore"), page.int("totalCount"), page.int("fetchedCount"))
    }
    suspend fun setPodcastSubscribed(id: String, subscribed: Boolean) = requireTrue("podcasts", "setPodcastSubscribed", params("id" to id, "subscribed" to subscribed))
    suspend fun userPlayRecords(userId: String, allTime: Boolean): List<OnlineUserPlayRecord> {
        val parse = parser()
        return invoke("stats", "userPlayRecords", params("userId" to userId, "allTime" to allTime)).featureList().map { row -> row.featureObject().let {
            OnlineUserPlayRecord(parse.song(it.get("song") ?: invalidFeature()), it.optionalInt("playCount"), it.optionalInt("score"))
        } }
    }
    suspend fun listeningStatsSummary(): ListeningStatsSummary = decode(invoke("stats", "listeningStatsSummary"))
    suspend fun listeningStatsReport(period: ListeningStatsPeriod, endTimeMs: Long? = null): ListeningStatsReport =
        decode(invoke("stats", "listeningStatsReport", params("period" to period.name, "endTimeMs" to endTimeMs)))
    suspend fun listeningStatsYears(): List<ListeningStatsYear> = decode(invoke("stats", "listeningStatsYears"))
    suspend fun recentSongRecords(limit: Int = 100): List<OnlineAccountSong> = parser().let { parse -> invoke("stats", "recentSongs", params("limit" to limit)).featureList(100).map(parse::song) }
    suspend fun songWiki(songId: String): OnlineSongWiki = parser().wiki(invoke("social", "songWiki", params("songId" to songId)))
    suspend fun dailyRecommendations(): OnlineDailyRecommendations {
        val source = features()
        return parser().daily(invoke("discovery", "dailySongs"), source::track)
    }
    suspend fun sessionState(): OnlineSessionState = parser().session(invoke("login", "sessionState"))
    suspend fun openWebPage(page: String): Boolean {
        val result = invoke("web", "openPage", params("page" to page), allowSessionChange = true).featureObject()
        return when (result.text("status")) {
            "closed" -> true
            "login_required" -> false
            else -> invalidFeature()
        }
    }

    suspend fun importCookie(cookie: String): Boolean {
        val result = invoke("login", "importCookie", params("cookie" to cookie), allowSessionChange = true)
        requireAuthenticated(result)
        return true
    }
    suspend fun loginWithMobilePassword(phone: String, countryCode: String, password: String) {
        requireAuthenticated(invoke("login", "loginWithMobilePassword", params("phone" to phone, "countryCode" to countryCode, "password" to password), true))
    }
    suspend fun requestMobileSmsCode(phone: String, countryCode: String): Boolean {
        return sourceSmsLoginAuthenticated(invoke("login", "requestMobileSmsCode", params("phone" to phone, "countryCode" to countryCode), allowSessionChange = true))
    }
    suspend fun webLogin() { requireAuthenticated(invoke("login", "webLogin", allowSessionChange = true)) }
    suspend fun loginWithMobileSms(phone: String, countryCode: String, code: String) {
        requireAuthenticated(invoke("login", "loginWithMobileSms", params("phone" to phone, "countryCode" to countryCode, "code" to code), true))
    }
    suspend fun loginWithUpSms(phone: String, countryCode: String) {
        requireAuthenticated(invoke("login", "loginWithUpSms", params("phone" to phone, "countryCode" to countryCode), true))
    }
    suspend fun preparePcQrLoginSecurity() {
        if (!invoke("login", "preparePcQrLoginSecurity").featureObject().featureBoolean("ready")) invalidFeature()
    }
    suspend fun markPcQrScanned(key: String, clientTraceId: String?): String =
        invoke("login", "markPcQrScanned", params("key" to key, "clientTraceId" to clientTraceId)).let { if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) invalidFeature() else it.asString }
    suspend fun authorizePcQrLogin(key: String, clientTraceId: String?) = requireTrue("login", "authorizePcQrLogin", params("key" to key, "clientTraceId" to clientTraceId))
    suspend fun cancelPcQrLogin(key: String, clientTraceId: String?) = requireTrue("login", "cancelPcQrLogin", params("key" to key, "clientTraceId" to clientTraceId))
    suspend fun logout() { invoke("login", "logout", allowSessionChange = true).also { if (!it.isJsonPrimitive || !it.asBoolean) invalidFeature() } }

    suspend fun getComment(id: String, resourceType: CommentResourceType = CommentResourceType.SONG, sortType: CommentSortType = CommentSortType.RECOMMEND,
        pageNo: Int = 1, pageSize: Int = 20, cursor: String = ""): Resource<OnlineCommentPage> = resource {
        val parse = parser()
        val result = invoke("social", "comments", contentParams(id, resourceType).apply {
            addProperty("sortType", when (sortType) { CommentSortType.RECOMMEND -> "recommended"; CommentSortType.HOT -> "hot"; CommentSortType.TIME -> "time" })
            addProperty("pageNo", pageNo); addProperty("pageSize", pageSize); addProperty("cursor", cursor)
        }).featureObject()
        val page = result.get("data")?.takeUnless { it.isJsonNull }?.featureObject() ?: result
        if (!page.has("hasMore")) invalidFeature()
        OnlineCommentPage(page.featureList("comments", 100).map(parse::comment), page.featureBoolean("hasMore"), page.optional("cursor"), page.int("totalCount"))
    }
    suspend fun getFloorComment(parentCommentId: String, id: String, resourceType: CommentResourceType = CommentResourceType.SONG, limit: Int = 20, time: Long = -1): Resource<OnlineFloorPage> = resource {
        val parse = parser()
        val result = invoke("social", "floorComments", contentParams(id, resourceType).apply {
            addProperty("parentCommentId", parentCommentId); addProperty("limit", limit); addProperty("time", time)
        }).featureObject()
        val page = result.get("data")?.takeUnless { it.isJsonNull }?.featureObject() ?: result
        if (!page.has("hasMore")) invalidFeature()
        val time = page.get("time")?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString.toLong() }.getOrElse { invalidFeature() } } ?: 0L
        OnlineFloorPage(page.featureList("comments", 100).map(parse::comment), page.featureBoolean("hasMore"), time, page.int("totalCount"))
    }
    suspend fun publishComment(id: String, content: String, replyToCommentId: String? = null,
        resourceType: CommentResourceType = CommentResourceType.SONG): Resource<Unit> = resource {
        requireTrue("social", "sendComment", contentParams(id, resourceType).apply {
            addProperty("content", content.trim()); addProperty("reply", replyToCommentId != null)
            replyToCommentId?.let { addProperty("commentId", it) }
        })
    }
    suspend fun setCommentLiked(id: String, commentId: String, liked: Boolean,
        resourceType: CommentResourceType = CommentResourceType.SONG): Resource<Unit> = resource {
        requireTrue("social", "likeComment", contentParams(id, resourceType).apply { addProperty("commentId", commentId); addProperty("liked", liked) })
    }
    private fun contentParams(id: String, type: CommentResourceType): JsonObject = params("contentId" to id, "contentType" to when (type) {
        CommentResourceType.SONG -> "track"
        CommentResourceType.ALBUM -> "album"
        CommentResourceType.PLAYLIST -> "playlist"
        CommentResourceType.RADIO, CommentResourceType.DJ -> "program"
        else -> throw SourceException(SourceErrorCode.UNSUPPORTED)
    })

    private suspend fun requireTrue(feature: String, action: String, parameters: JsonObject) = features().requireTrue(feature, action, parameters)
    private fun requireAuthenticated(value: JsonElement) {
        val result = value.featureObject()
        if (!result.featureBoolean("authenticated")) invalidFeature()
    }
    private inline fun <reified T> decode(value: JsonElement): T = try { gson.fromJson<T>(value, object : TypeToken<T>() {}.type) ?: invalidFeature() }
        catch (_: Exception) { invalidFeature() }
    private suspend fun <T> resource(block: suspend () -> T): Resource<T> = try { Resource.Success(block()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: SourceException) { Resource.Error(error.code.name) }

    private fun params(vararg values: Pair<String, Any?>): JsonObject = featureParameters {
        values.forEach { (key, value) -> when (value) {
            null -> Unit
            is String -> addProperty(key, value)
            is Number -> addProperty(key, value)
            is Boolean -> addProperty(key, value)
            else -> invalidFeature()
        } }
    }

    override fun toString(): String = "SharedOnlineSession(<redacted>)"
}

/** A small typed first-page cache; raw service responses and credentials never enter it. */
internal class SourceCloudPageCache(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    class Ticket internal constructor(internal val identity: SourceFeatureSessionIdentity, internal val generation: Long)
    private data class Entry(val identity: SourceFeatureSessionIdentity, val instanceId: ProviderInstanceId,
        val limit: Int, val page: OnlineCloudPage, val expiresAtMs: Long, val bytes: Long)
    private val lock = Any()
    private val entries = mutableListOf<Entry>()
    private var generation = 0L

    suspend fun ticket(source: BoundSourceFeatures): Ticket {
        source.ensureCurrent()
        return synchronized(lock) { Ticket(source.sessionIdentity(), generation) }
    }

    suspend fun get(source: BoundSourceFeatures, limit: Int): OnlineCloudPage? {
        source.ensureCurrent()
        val identity = source.sessionIdentity()
        val (page, observedGeneration) = synchronized(lock) {
            entries.removeAll { it.expiresAtMs <= nowMs() }
            val entry = entries.firstOrNull { it.limit == limit && it.identity.sameSession(identity) }
            if (entry != null) { entries.remove(entry); entries.add(entry) }
            entry?.page?.let(::copyPage) to generation
        }
        ensureIdentity(source, identity)
        return synchronized(lock) { page.takeIf { generation == observedGeneration } }
    }

    suspend fun put(source: BoundSourceFeatures, ticket: Ticket, limit: Int, page: OnlineCloudPage) {
        ensureIdentity(source, ticket.identity)
        if (limit !in 1..30 || page.songs.size > limit) return
        val value = copyPage(page)
        val bytes = 128L + value.songs.sumOf { row ->
            128L + listOf(row.id, row.name, row.artist, row.album, row.coverUrl, row.providerId).sumOf { 40L + (it?.length ?: 0) * 2L }
        }
        synchronized(lock) {
            if (generation != ticket.generation) return
            entries.removeAll { it.identity.sameSession(ticket.identity) && it.limit == limit || it.expiresAtMs <= nowMs() }
            if (bytes > MAX_BYTES) return
            entries += Entry(ticket.identity, source.instanceId, limit, value, nowMs() + TTL_MS, bytes)
            while (entries.size > MAX_PAGES || entries.sumOf { it.bytes } > MAX_BYTES) entries.removeAt(0)
        }
    }

    fun invalidate(source: BoundSourceFeatures) = synchronized(lock) {
        generation++
        entries.removeAll { it.instanceId == source.instanceId }
    }

    private suspend fun ensureIdentity(source: BoundSourceFeatures, identity: SourceFeatureSessionIdentity) {
        source.ensureCurrent()
        if (!identity.sameSession(source.sessionIdentity())) throw CancellationException("Source cache session changed")
    }

    private fun copyPage(page: OnlineCloudPage) = page.copy(songs = page.songs.toList())
    companion object {
        const val TTL_MS = 60_000L
        const val MAX_PAGES = 4
        const val MAX_BYTES = 1024 * 1024
    }
}
