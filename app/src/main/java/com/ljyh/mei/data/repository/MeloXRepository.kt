package com.ljyh.mei.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.data.model.melox.CloudMusicPage
import com.ljyh.mei.data.model.melox.ListenTogetherCommand
import com.ljyh.mei.data.model.melox.ListenTogetherRoom
import com.ljyh.mei.data.model.melox.ListenTogetherPlaybackCommand
import com.ljyh.mei.data.model.melox.ListenTogetherPlaybackSnapshot
import com.ljyh.mei.data.model.melox.ListenTogetherStatus
import com.ljyh.mei.data.model.melox.ListenTogetherUser
import com.ljyh.mei.data.model.melox.MessageContact
import com.ljyh.mei.data.model.melox.Podcast
import com.ljyh.mei.data.model.melox.PodcastCategory
import com.ljyh.mei.data.model.melox.PodcastDetail
import com.ljyh.mei.data.model.melox.PodcastHome
import com.ljyh.mei.data.model.melox.PodcastHost
import com.ljyh.mei.data.model.melox.PodcastPage
import com.ljyh.mei.data.model.melox.PodcastProgram
import com.ljyh.mei.data.model.melox.PodcastProgramPage
import com.ljyh.mei.data.model.melox.PrivateConversation
import com.ljyh.mei.data.model.melox.PrivateMessage
import com.ljyh.mei.data.model.melox.PrivateMessagePayload
import com.ljyh.mei.data.model.melox.SearchDiscovery
import com.ljyh.mei.data.model.melox.SearchDiscoveryPlaylist
import com.ljyh.mei.data.model.melox.SongWiki
import com.ljyh.mei.data.model.melox.SongWikiAssociationDetail
import com.ljyh.mei.data.model.melox.SongWikiAssociationGroup
import com.ljyh.mei.data.model.melox.SongWikiAttribute
import com.ljyh.mei.data.model.melox.SongWikiMemoryItem
import com.ljyh.mei.data.model.melox.SongWikiMemoryKind
import com.ljyh.mei.data.model.melox.SongWikiPlaylistReference
import com.ljyh.mei.data.model.melox.SongWikiReview
import com.ljyh.mei.data.model.melox.SongWikiSongReference
import com.ljyh.mei.data.model.melox.SongWikiTagGroup
import com.ljyh.mei.data.model.melox.ShareResource
import com.ljyh.mei.data.model.melox.ShareResourceKind
import com.ljyh.mei.data.model.melox.AccountDetail
import com.ljyh.mei.data.model.melox.AccountPlaylist
import com.ljyh.mei.data.model.melox.AccountProfile
import com.ljyh.mei.data.model.melox.AccountSong
import com.ljyh.mei.data.model.melox.UserPlayRecord
import com.ljyh.mei.data.network.api.MeloXDirectService
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.di.MAX_PLAYBACK_HISTORY_RESPONSE_BYTES
import com.ljyh.mei.di.NETEASE_EAPI_PROFILE_HEADER
import com.ljyh.mei.di.PLAYBACK_HISTORY_PROFILE
import com.ljyh.mei.di.PlaybackResponseBodyException
import com.ljyh.mei.di.readBoundedPlaybackResponseBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import org.json.JSONObject
import java.security.MessageDigest
import java.io.File
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import com.ljyh.mei.di.ApplicationContext
import com.ljyh.mei.runtime.MeloXRequestPolicy

internal const val PLAYBACK_HISTORY_DIAGNOSTIC_ENDPOINT =
    "https://interface.music.163.com/eapi/feedback/weblog"

data class PlaybackLogResponse(
    val httpAccepted: Boolean,
    val code: Int?,
    val message: String?,
    val httpStatus: Int? = null,
    val exceptionType: String? = null,
    val failureReason: String? = null,
    val endpoint: String? = null,
    val hostAccepted: Boolean = false,
) {
    val businessAccepted: Boolean
        get() = (httpAccepted || hostAccepted) && code?.let { it in 200..299 } == true
}

@Singleton
class MeloXRepository @Inject constructor(
    @Named("MeloXEapi") private val eapi: MeloXDirectService,
    @Named("MeloXWeapi") private val weapi: MeloXDirectService,
    @ApplicationContext private val context: Context,
    private val sessions: SessionStore,
    private val cloudUploads: CloudUploadCoordinator,
    private val cloudLibrary: CloudLibraryBackend,
) : PodcastSource, CloudMusicSource, SocialSource, ListenTogetherSource {
    private val uploadDirectory by lazy { prepareCloudUploadDirectory(context.cacheDir) }

    override suspend fun podcastHome(session: SessionStamp): PodcastHome = coroutineScope {
        val categories = async {
            request("/api/djradio/category/get", session = session).array("categories").mapNotNull(::parsePodcastCategory)
        }
        val featured = async {
            request("/api/djradio/recommend/v1", session = session).array("djRadios").mapNotNull(::parsePodcast)
        }
        val personalized = async {
            request("/api/djradio/personalize/rcmd", mapOf("limit" to 12), session)
                .array("data").mapNotNull(::parsePodcast)
        }
        PodcastHome(categories.await(), featured.await(), personalized.await())
    }

    override suspend fun podcasts(session: SessionStamp, categoryId: Long, offset: Int, limit: Int): List<Podcast> =
        request(
            "/api/djradio/hot",
            mapOf("cateId" to categoryId, "offset" to offset, "limit" to limit.coerceIn(1, 50)),
            session,
        ).array("djRadios").mapNotNull(::parsePodcast)

    override suspend fun podcastDetail(session: SessionStamp, id: Long, offset: Int, limit: Int): PodcastDetail =
        coroutineScope {
            val podcastResponse = async { request("/api/djradio/v2/get", mapOf("id" to id), session) }
            val programsResponse = async { podcastPrograms(session, id, offset, limit) }
            val podcast = parsePodcast(podcastResponse.await().objectOrNull("data"))
                ?: error("Podcast $id was not returned by NetEase")
            val programs = programsResponse.await()
            PodcastDetail(
                podcast = podcast,
                programs = programs.programs,
                hasMore = programs.hasMore,
                totalCount = programs.totalCount,
                nextOffset = offset + programs.fetchedCount,
            )
        }

    override suspend fun podcastPrograms(session: SessionStamp, id: Long, offset: Int, limit: Int): PodcastProgramPage {
        val response = request(
            "/api/dj/program/byradio",
            mapOf("radioId" to id, "offset" to offset, "limit" to limit.coerceIn(1, 50), "asc" to false),
            session,
        )
        val programs = response.array("programs").mapNotNull(::parseProgram)
        val totalCount = response.int("count") ?: (offset + programs.size)
        return PodcastProgramPage(
            programs = programs,
            hasMore = response.boolean("more") ?: (offset + programs.size < totalCount),
            totalCount = totalCount,
            fetchedCount = response.array("programs").size,
        )
    }

    override suspend fun subscribedPodcasts(session: SessionStamp, offset: Int, limit: Int): PodcastPage {
        check(session.identity.authenticated) { "Official sign-in required" }
        val response = request(
            "/api/djradio/get/subed",
            mapOf("offset" to offset, "limit" to limit.coerceIn(1, 100), "total" to true),
            session,
        )
        val podcasts = response.array("djRadios").mapNotNull(::parsePodcast)
        val totalCount = response.int("count") ?: response.int("total") ?: (offset + podcasts.size)
        return PodcastPage(
            podcasts = podcasts,
            hasMore = response.boolean("hasMore")
                ?: response.boolean("more")
                ?: (offset + podcasts.size < totalCount),
            fetchedCount = response.array("djRadios").size,
            totalCount = totalCount,
        )
    }

    suspend fun searchDiscovery(session: SessionStamp): SearchDiscovery {
        val response = requestEapi(
            "/api/personalized/playlist",
            mapOf("limit" to 10, "total" to true, "n" to 1_000),
            session,
        )
        val recommendations = response.array("result").mapNotNull { element ->
            val value = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
            val id = value.long("id")?.takeIf { it > 0 } ?: return@mapNotNull null
            val name = value.string("name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            SearchDiscoveryPlaylist(
                id = id,
                name = name,
                artworkUrl = value.string("picUrl"),
                copywriter = value.string("copywriter"),
                creatorNickname = value.objectOrNull("creator")?.string("nickname"),
            )
        }
        return SearchDiscovery(recommendations)
    }

    suspend fun accountProfile(session: SessionStamp): AccountProfile {
        val response = runCatching { accountRequest(session, "/api/w/nuser/account/get", emptyMap(), useEapi = true) }
            .getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (error is com.ljyh.mei.data.session.SessionChangedException) throw error
                accountRequest(session, "/api/nuser/account/get", emptyMap(), useEapi = true)
            }
        return parseAccountProfile(response.objectOrNull("profile"))
            ?: error("NetEase account profile is unavailable")
    }

    suspend fun accountDetail(userId: Long, session: SessionStamp): AccountDetail {
        require(userId > 0)
        val response = try {
            requestOwned(weapi, "/weapi/v1/user/detail/$userId", emptyMap(), session)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (error is com.ljyh.mei.data.session.SessionChangedException) throw error
            requestEapi(
                "/api/w/v1/user/detail/$userId",
                mapOf("all" to true, "userId" to userId),
                session,
            )
        }
        val profile = parseAccountProfile(response.objectOrNull("profile"))
            ?: error("NetEase account details are unavailable")
        return AccountDetail(
            profile = profile,
            level = response.int("level") ?: 0,
            listenSongs = response.int("listenSongs") ?: 0,
            createDays = response.int("createDays"),
        )
    }

    suspend fun accountPlaylists(userId: Long, session: SessionStamp, limit: Int = 2_000): List<AccountPlaylist> =
        request(
            "/api/user/playlist",
            mapOf("uid" to userId, "limit" to limit.coerceIn(1, 2_000), "offset" to 0, "includeVideo" to true),
            session,
        ).array("playlist").mapNotNull(::parseAccountPlaylist)

    suspend fun userPlayRecords(userId: Long, allTime: Boolean, session: SessionStamp): List<UserPlayRecord> {
        val response = request(
            "/api/v1/play/record",
            mapOf("uid" to userId, "type" to if (allTime) 0 else 1),
            session,
        )
        return response.array(if (allTime) "allData" else "weekData")
            .mapNotNull(::parseUserPlayRecord)
            .filter { it.song.id > 0 }
    }

    suspend fun recentSongs(session: SessionStamp, limit: Int = 100): List<AccountSong> {
        val response = request(
            "/api/play-record/song/list",
            mapOf("limit" to limit.coerceIn(1, 100)),
            session,
        )
        return response.objectOrNull("data")
            ?.array("list")
            .orEmpty()
            .mapNotNull(::parseRecentHistorySong)
            .filter { it.id > 0 }
            .deduplicateRecentSongs()
    }

    override suspend fun setPodcastSubscribed(session: SessionStamp, id: Long, subscribed: Boolean) {
        check(session.identity.authenticated) { "Official sign-in required" }
        request(if (subscribed) "/api/djradio/sub" else "/api/djradio/unsub", mapOf("id" to id), session)
    }

    override suspend fun cloudSongs(session: SessionStamp): CloudMusicPage = cloudLibrary.songs(session)

    override suspend fun deleteCloudSong(session: SessionStamp, id: Long) = cloudLibrary.delete(session, id)

    override suspend fun uploadCloudSong(session: SessionStamp, uri: String, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        check(session.identity.authenticated && !session.identity.anonymous && session.identity.userId > 0) { "Sign-in required" }
        val operation = currentCoroutineContext()
        operation.ensureActive()
        sessions.requireCurrent(session)
        val file = prepareCloudUploadFile(Uri.parse(uri)) {
            operation.ensureActive()
            sessions.requireCurrent(session)
            check(!sessions.recoveryRequired.value) { "Session recovery is required" }
        }
        try { cloudUploads.upload(file, session, onProgress) }
        finally { file.file.delete() }
    }

    private suspend fun prepareCloudUploadFile(uri: Uri, checkCurrent: () -> Unit): CloudUploadFile = suspendCancellableCoroutine { continuation ->
        val resolver = context.contentResolver
        val signal = CancellationSignal()
        val opened = AtomicReference<Closeable?>()
        // Provider IPC and the opened descriptor belong to this preparation job.
        continuation.invokeOnCancellation {
            signal.cancel()
            runCatching { opened.get()?.close() }
        }
        fun verify() {
            if (!continuation.isActive) throw CancellationException("Cloud file preparation canceled")
            signal.throwIfCanceled()
            checkCurrent()
        }
        var snapshot: File? = null
        try {
            verify()
            var filename: String? = null
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, signal)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { filename = cursor.getString(it) }
                }
            }
            verify()
            val safeFilename = filename?.takeIf(String::isNotBlank) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "music.mp3"
            val copy = File.createTempFile("upload-", ".bin", uploadDirectory).also { snapshot = it }
            val digest = MessageDigest.getInstance("MD5")
            resolver.openAssetFileDescriptor(uri, "r", signal)?.use { asset ->
                opened.set(asset)
                verify()
                asset.createInputStream().use { input ->
                    opened.set(input)
                    copy.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            verify()
                            val read = input.read(buffer)
                            verify()
                            if (read < 0) break
                            if (read > 0) { digest.update(buffer, 0, read); output.write(buffer, 0, read) }
                        }
                    }
                }
            } ?: error("The selected audio file cannot be opened")
            opened.set(null)
            check(copy.length() > 0) { "The selected audio file is empty or unavailable" }
            verify()
            val fallbackName = safeFilename.substringBeforeLast('.', safeFilename)
            val retriever = MediaMetadataRetriever()
            val metadata = try {
                retriever.setDataSource(copy.absolutePath)
                Triple(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            } finally { retriever.release() }
            verify()
            val mime = resolver.getType(uri) ?: "audio/mpeg"
            verify()
            val prepared = CloudUploadFile(
                file = copy,
                filename = safeFilename,
                extension = safeFilename.substringAfterLast('.', "mp3").lowercase(),
                normalizedStem = fallbackName.filterNot(Char::isWhitespace).replace('.', '_').ifEmpty { "music" },
                size = copy.length(),
                md5 = digest.digest().joinToString("") { "%02x".format(it) },
                songName = metadata?.first?.takeIf(String::isNotBlank) ?: fallbackName,
                artist = metadata?.second?.takeIf(String::isNotBlank) ?: "Unknown artist",
                album = metadata?.third?.takeIf(String::isNotBlank) ?: "Unknown album",
                mimeType = mime,
            )
            // Prompt cancellation may reject the result after the blocking snapshot has completed.
            continuation.resume(prepared) { _, rejected, _ -> rejected.file.delete() }
        } catch (error: Throwable) {
            snapshot?.delete()
            continuation.resumeWithException(error)
        } finally { opened.set(null) }
    }

    override suspend fun privateConversations(session: SessionStamp, offset: Int, limit: Int): List<PrivateConversation> =
        accountRequest(
            session,
            "/api/msg/private/users",
            mapOf("offset" to offset, "limit" to limit, "total" to "true"),
        ).array("msgs").mapNotNull(::parseConversation)

    override suspend fun privateMessages(session: SessionStamp, userId: Long, before: Long, limit: Int): List<PrivateMessage> =
        accountRequest(
            session,
            "/api/msg/private/history",
            mapOf("userId" to userId, "time" to before, "limit" to limit, "total" to "true"),
        ).array("msgs").mapNotNull(::parsePrivateMessage).sortedBy(PrivateMessage::time)

    override suspend fun sendPrivateText(session: SessionStamp, message: String, userIds: List<Long>) {
        require(userIds.isNotEmpty() && userIds.all { it > 0 }) { "Valid recipients are required" }
        accountRequest(
            session,
            "/api/msg/private/send",
            mapOf(
                "type" to "text",
                "msg" to message,
                "userIds" to userIds.distinct().sorted().joinToString(",", "[", "]"),
            ),
            useEapi = true,
        )
    }

    override suspend fun messageContacts(
        session: SessionStamp,
        pageSize: Int,
        maximumCount: Int,
    ): List<MessageContact> {
        requireAccountSession(session)
        require(pageSize > 0 && maximumCount > 0) { "Positive contact limits are required" }
        val contacts = mutableListOf<MessageContact>()
        val loadedIds = mutableSetOf<Long>()
        var offset = 0
        var hasMore = true
        while (hasMore && contacts.size < maximumCount) {
            val response = accountRequest(
                session,
                "/api/user/getfollows/${session.identity.userId}",
                mapOf(
                    "offset" to offset,
                    "limit" to minOf(pageSize, maximumCount - contacts.size),
                    "order" to true,
                ),
            )
            val page = response.array("follow").mapNotNull { parseContact(it.objectValue()) }
            page.forEach { if (loadedIds.add(it.id)) contacts += it }
            offset += page.size
            hasMore = response.boolean("more") == true && page.isNotEmpty()
        }
        return contacts
    }

    override suspend fun sendPrivateResource(
        session: SessionStamp,
        resource: ShareResource,
        userIds: List<Long>,
        message: String,
    ) {
        require(userIds.isNotEmpty() && userIds.all { it > 0 }) { "Valid recipients are required" }
        accountRequest(
            session,
            "/api/msg/private/send",
            mapOf(
                "id" to resource.id,
                "msg" to message,
                "type" to resource.kind.wireValue,
                "userIds" to userIds.distinct().sorted().joinToString(",", "[", "]"),
            ),
            useEapi = true,
        )
    }

    override suspend fun shareToTimeline(session: SessionStamp, resource: ShareResource, message: String) {
        require(resource.kind != ShareResourceKind.Album) { "Albums cannot be shared to the NetEase timeline" }
        accountRequest(
            session,
            "/api/share/friends/resource",
            mapOf("type" to resource.kind.wireValue, "msg" to message, "id" to resource.id),
            useEapi = true,
        )
    }

    private fun requireAccountSession(session: SessionStamp): SessionStamp = session.also {
        check(it.identity.authenticated && !it.identity.anonymous && it.identity.userId > 0) { "Sign-in required" }
        sessions.requireCurrent(it)
        check(!sessions.recoveryRequired.value) { "Session recovery is required" }
    }

    private suspend fun accountRequest(
        session: SessionStamp, path: String, body: Map<String, Any>, useEapi: Boolean = false,
    ): JsonObject {
        currentCoroutineContext().ensureActive()
        requireAccountSession(session)
        val response = if (useEapi) requestEapi(path, body, session) else request(path, body, session)
        currentCoroutineContext().ensureActive()
        requireAccountSession(session)
        return response
    }

    suspend fun songWiki(songId: Long, session: SessionStamp): SongWiki {
        require(songId > 0) { "A valid song ID is required" }
        currentCoroutineContext().ensureActive()
        sessions.requireCurrent(session)
        check(!sessions.recoveryRequired.value) { "Session recovery is required" }
        val response = requestEapi(
            "/api/song/play/about/block/page",
            mapOf("songId" to songId),
            session,
        )
        currentCoroutineContext().ensureActive()
        sessions.requireCurrent(session)
        check(!sessions.recoveryRequired.value) { "Session recovery is required" }
        val blocks = response.objectOrNull("data")?.array("blocks")
            .orEmpty()
            .mapNotNull { it.objectValue() }
        val basicBlockCodes = setOf(
            "SONG_PLAY_ABOUT_SONG_BASIC",
            "SONG_PLAY_ABOUT_MUSIC_SONG_GRADE",
        )
        val basicBlocks = blocks.filter { it.string("code") in basicBlockCodes }

        val tags = mutableListOf<SongWikiTagGroup>()
        val attributes = mutableListOf<SongWikiAttribute>()
        val associations = mutableListOf<SongWikiAssociationGroup>()
        val reviews = mutableListOf<SongWikiReview>()
        basicBlocks.forEachIndexed { blockIndex, block ->
            block.array("creatives").mapNotNull { it.objectValue() }
                .forEachIndexed { creativeIndex, creative ->
                    val id = "${block.string("code")}-$blockIndex-$creativeIndex"
                    val ui = creative.objectOrNull("uiElement")
                    val title = ui.mainTitle()
                    val resources = creative.array("resources").mapNotNull { it.objectValue() }
                    when (creative.string("creativeType")?.lowercase()) {
                        "songtag", "songbiztag" -> {
                            val values = uniqueStrings(
                                resources.mapNotNull { it.objectOrNull("uiElement").mainTitle() } + ui.textValues(),
                            )
                            if (values.isNotEmpty()) tags += SongWikiTagGroup(id, title, values)
                        }
                        "songcomment" -> resources.forEachIndexed { index, resource ->
                            val resourceUi = resource.objectOrNull("uiElement")
                            resourceUi.descriptionValues().firstOrNull()?.let { body ->
                                reviews += SongWikiReview("$id-$index", resourceUi.mainTitle(), body)
                            }
                        }
                        "sheet" -> {
                            val value = ui.buttonValues().firstOrNull()
                                ?: resources.takeIf { it.isNotEmpty() }?.size?.toString()
                            value?.let { attributes += SongWikiAttribute(id, title, it) }
                        }
                        else -> {
                            val details = resources.mapIndexedNotNull { index, resource ->
                                resource.objectOrNull("uiElement").associationDetail("$id-$index")
                            }
                            if (details.isNotEmpty()) {
                                associations += SongWikiAssociationGroup(
                                    id = id,
                                    title = title,
                                    countText = ui.buttonValues().firstOrNull(),
                                    details = details,
                                )
                            } else {
                                uniqueStrings(ui.textValues() + ui.buttonValues())
                                    .takeIf { it.isNotEmpty() }
                                    ?.let { values ->
                                        attributes += SongWikiAttribute(id, title, values.joinToString("、"))
                                    }
                            }
                        }
                    }
                }
        }

        val memories = blocks
            .filter { it.string("code") == "SONG_PLAY_ABOUT_MUSIC_MEMORY" }
            .flatMap(JsonObject::blockResources)
            .mapIndexedNotNull { index, resource ->
                val extension = resource.objectOrNull("resourceExt")
                    ?: resource.objectOrNull("resourceExtInfo")
                when (resource.string("resourceType")?.uppercase()) {
                    "FIRST_LISTEN" -> extension?.objectOrNull("musicFirstListenDto")
                        ?.string("date")?.nonEmpty()?.let {
                            SongWikiMemoryItem("first-listen-$index", SongWikiMemoryKind.FirstListen, date = it)
                        }
                    "TOTAL_PLAY" -> extension?.objectOrNull("musicTotalPlayDto")?.let { total ->
                        SongWikiMemoryItem(
                            id = "total-play-$index",
                            kind = SongWikiMemoryKind.TotalPlay,
                            playCount = total.long("playCount"),
                            durationMinutes = total.long("duration"),
                            text = total.string("text")?.nonEmpty(),
                        ).takeIf { it.playCount != null || it.durationMinutes != null || it.text != null }
                    }
                    else -> null
                }
            }

        val similarSongs = blocks
            .filter { it.string("code") == "SONG_PLAY_ABOUT_SIMILAR_SONG" }
            .flatMap(JsonObject::blockResources)
            .mapNotNull { resource ->
                val ui = resource.objectOrNull("uiElement")
                val id = resource.long("resourceId") ?: return@mapNotNull null
                val title = ui.mainTitle() ?: return@mapNotNull null
                if (!resource.string("resourceType").equals("song", ignoreCase = true)) return@mapNotNull null
                SongWikiSongReference(
                    id = id,
                    title = title,
                    artist = ui.subtitleValues().joinToString(" / ").nonEmpty(),
                    note = ui.descriptionValues().firstOrNull(),
                    artworkUrl = ui.firstImageUrl(),
                )
            }.distinctBy(SongWikiSongReference::id)

        val relatedPlaylists = blocks
            .filter { it.string("code") == "SONG_PLAY_ABOUT_RELATED_PLAYLIST" }
            .flatMap(JsonObject::blockResources)
            .mapNotNull { resource ->
                val ui = resource.objectOrNull("uiElement")
                val id = resource.long("resourceId") ?: return@mapNotNull null
                val title = ui.mainTitle() ?: return@mapNotNull null
                if (!resource.string("resourceType").equals("playlist", ignoreCase = true)) return@mapNotNull null
                val extension = resource.objectOrNull("resourceExt")
                    ?: resource.objectOrNull("resourceExtInfo")
                SongWikiPlaylistReference(id, title, ui.firstImageUrl(), extension?.long("playCount") ?: 0)
            }.distinctBy(SongWikiPlaylistReference::id)

        val contributionUrl = basicBlocks.asSequence()
            .mapNotNull { it.objectOrNull("uiElement") }
            .flatMap { ui -> ui.array("textLinks").asSequence() }
            .mapNotNull { it.objectValue()?.string("url")?.officialHttpsUrl() }
            .firstOrNull()

        return SongWiki(
            memories = memories,
            tagGroups = tags,
            attributes = attributes,
            associationGroups = associations,
            reviews = reviews,
            similarSongs = similarSongs,
            relatedPlaylists = relatedPlaylists,
            contributionUrl = contributionUrl,
        ).also {
            currentCoroutineContext().ensureActive()
            sessions.requireCurrent(session)
            check(!sessions.recoveryRequired.value) { "Session recovery is required" }
        }
    }

    override suspend fun listenTogetherStatus(session: SessionStamp): ListenTogetherStatus {
        val data = accountRequest(session, "/api/listen/together/status/get", emptyMap()).objectOrNull("data")
        return ListenTogetherStatus(
            isInRoom = data?.boolean("inRoom") ?: false,
            room = data?.objectOrNull("roomInfo")?.let(::parseRoom),
            status = data?.string("status"),
        )
    }

    override suspend fun createListenTogetherRoom(session: SessionStamp): ListenTogetherRoom {
        val response = accountRequest(session, "/api/listen/together/room/create", mapOf("refer" to "songplay_more"), useEapi = true)
        return response.objectOrNull("data")?.objectOrNull("roomInfo")?.let(::parseRoom)
            ?: error("NetEase did not return a Listen Together room")
    }

    override suspend fun checkListenTogetherRoom(session: SessionStamp, roomId: String): Pair<Boolean, String?> {
        val data = accountRequest(
            session,
            "/api/listen/together/room/check",
            mapOf("roomId" to roomId),
            useEapi = true,
        ).objectOrNull("data")
        return (data?.boolean("joinable") ?: false) to data?.string("status")
    }

    override suspend fun acceptListenTogetherRoom(session: SessionStamp, roomId: String, inviterId: String): ListenTogetherRoom {
        val response = accountRequest(
            session,
            "/api/listen/together/play/invitation/accept",
            mapOf("refer" to "inbox_invite", "roomId" to roomId, "inviterId" to inviterId),
            useEapi = true,
        )
        return response.objectOrNull("data")?.objectOrNull("roomInfo")?.let(::parseRoom)
            ?: error("NetEase did not return the accepted room")
    }

    override suspend fun reportListenTogetherCommand(
        session: SessionStamp,
        roomId: String,
        command: ListenTogetherCommand,
        progressMs: Long,
        isPlaying: Boolean,
        formerSongId: Long?,
        targetSongId: Long,
        clientSequence: Long,
    ) {
        val commandInfo = JSONObject(
            mapOf(
                "commandType" to command.wireValue,
                "progress" to progressMs.coerceAtLeast(0),
                "playStatus" to if (isPlaying) "PLAY" else "PAUSE",
                "formerSongId" to (formerSongId ?: -1).toString(),
                "targetSongId" to targetSongId.toString(),
                "clientSeq" to clientSequence,
            ),
        ).toString()
        accountRequest(
            session,
            "/api/listen/together/play/command/report",
            mapOf("roomId" to roomId, "commandInfo" to commandInfo),
            useEapi = true,
        )
    }

    override suspend fun listenTogetherPlayback(session: SessionStamp, roomId: String): ListenTogetherPlaybackSnapshot {
        val data = accountRequest(
            session,
            "/api/listen/together/sync/playlist/get",
            mapOf("roomId" to roomId),
            useEapi = true,
        ).objectOrNull("data")
        val playlist = data?.objectOrNull("playlist")
        val playMode = playlist?.string("playMode")
        val randomMode = playMode?.uppercase()?.let { "RANDOM" in it || "SHUFFLE" in it } == true
        val songList = playlist?.objectOrNull(if (randomMode) "randomList" else "displayList")
        val ids = songList?.array("result").orEmpty().mapNotNull { it.identifierLong() }.distinct()
        val commandValue = data?.objectOrNull("playCommand")
        val commandType = commandValue?.string("commandType")?.uppercase()
        val playStatus = commandValue?.string("playStatus")?.uppercase()
        val isPlaying = when (playStatus) {
            "PLAY", "PLAYING" -> true
            "PAUSE", "PAUSED" -> false
            else -> when (commandType) {
                "PLAY", "GOTO", "NEXT", "PREV" -> true
                "PAUSE" -> false
                else -> null
            }
        }
        return ListenTogetherPlaybackSnapshot(
            songIds = ids,
            playMode = playMode,
            command = commandValue?.let {
                ListenTogetherPlaybackCommand(
                    commandType = commandType,
                    targetSongId = it.element("targetSongId")?.identifierLong(),
                    formerSongId = it.element("formerSongId")?.identifierLong(),
                    progressMs = it.long("progress") ?: 0,
                    isPlaying = isPlaying,
                    clientSequence = it.long("clientSeq") ?: 0,
                    serverSequence = it.long("serverSeq") ?: 0,
                )
            },
        )
    }

    override suspend fun reportListenTogetherPlaylist(
        session: SessionStamp,
        roomId: String,
        version: Long,
        displaySongIds: List<Long>,
        randomSongIds: List<Long>,
    ) {
        val playlist = JSONObject(
            mapOf(
                "commandType" to "REPLACE",
                "version" to listOf(mapOf("userId" to session.identity.userId, "version" to version)),
                "anchorSongId" to "",
                "anchorPosition" to -1,
                "randomList" to randomSongIds.map(Long::toString),
                "displayList" to displaySongIds.map(Long::toString),
            ),
        ).toString()
        accountRequest(
            session,
            "/api/listen/together/sync/list/command/report",
            mapOf("roomId" to roomId, "playlistParam" to playlist),
            useEapi = true,
        )
    }

    override suspend fun sendListenTogetherHeartbeat(
        session: SessionStamp,
        roomId: String,
        songId: Long,
        isPlaying: Boolean,
        progressMs: Long,
    ): Int? = accountRequest(
        session,
        "/api/listen/together/heartbeat",
        mapOf(
            "roomId" to roomId,
            "songId" to songId,
            "playStatus" to if (isPlaying) "PLAY" else "PAUSE",
            "progress" to progressMs.coerceAtLeast(0),
        ),
        useEapi = true,
    ).objectOrNull("data")?.int("timeSpan")

    override suspend fun endListenTogetherRoom(session: SessionStamp, roomId: String) {
        accountRequest(session, "/api/listen/together/end/v2", mapOf("roomId" to roomId), useEapi = true)
    }

    private suspend fun request(
        path: String, body: Map<String, Any> = emptyMap(), session: SessionStamp,
    ): JsonObject {
        return MeloXRequestPolicy.request(
            { requestOwned(weapi, path, body, session) },
            { requestOwned(eapi, path, body, session) },
        )
    }

    private suspend fun requestEapi(
        path: String, body: Map<String, Any> = emptyMap(), session: SessionStamp,
    ): JsonObject = requestOwned(eapi, path, body, session)

    private suspend fun requestOwned(service: MeloXDirectService, path: String, body: Map<String, Any>,
        owner: SessionStamp): JsonObject {
        currentCoroutineContext().ensureActive()
        sessions.requireCurrent(owner)
        check(!sessions.recoveryRequired.value) { "Session recovery is required" }
        val response = service.post(path, body, expectedSession = owner)
        currentCoroutineContext().ensureActive()
        sessions.requireCurrent(owner)
        check(!sessions.recoveryRequired.value) { "Session recovery is required" }
        return validate(response)
    }

    private fun validate(response: JsonObject): JsonObject {
        val code = response.int("code") ?: 200
        check(code in 200..299) {
            response.string("message") ?: response.string("msg") ?: "NetEase request failed ($code)"
        }
        return response
    }

}

internal data class PlaybackBodyParseResult(
    val code: Int?,
    val message: String?,
    val exceptionType: String? = null,
    val failureReason: String? = null,
)

internal suspend fun submitPlaybackHistoryLog(
    service: MeloXDirectService,
    action: String,
    fields: Map<String, Any>,
): PlaybackLogResponse {
    val endpoint = PLAYBACK_HISTORY_DIAGNOSTIC_ENDPOINT
    val response = try {
        val logs = Gson().toJson(
            listOf(
                mapOf(
                    "action" to action,
                    "json" to fields,
                ),
            ),
        )
        service.postPlaybackRaw(
            path = PLAYBACK_HISTORY_PATH,
            body = mapOf("logs" to logs),
            headers = mapOf(
                "X-Netease-Crypto" to "eapi",
                NETEASE_EAPI_PROFILE_HEADER to PLAYBACK_HISTORY_PROFILE,
            ),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        return playbackTransportFailure(endpoint, error)
    }

    val hostEnvelope = response.headers()["X-MeiloX-Transport"] == "official-json"
    val parsed = readPlaybackHistoryBody(
        body = response.body() ?: response.errorBody(),
        httpStatus = response.code(),
    )
    val statusReason = "HTTP status ${response.code()}"
    val failureReason = when {
        parsed.failureReason != null && !response.isSuccessful ->
            "$statusReason; ${parsed.failureReason}"
        parsed.failureReason != null -> parsed.failureReason
        !response.isSuccessful -> statusReason
        else -> null
    }
    return PlaybackLogResponse(
        httpAccepted = !hostEnvelope && response.isSuccessful,
        httpStatus = response.code().takeUnless { hostEnvelope },
        hostAccepted = hostEnvelope && response.isSuccessful,
        code = parsed.code,
        message = parsed.message,
        exceptionType = parsed.exceptionType
            ?: response.takeUnless { it.isSuccessful }?.let { "HttpStatus" },
        failureReason = failureReason,
        endpoint = endpoint,
    )
}

internal suspend fun submitPlaybackHistoryStart(
    songId: Long,
    sourceId: Long,
    source: String,
    startedAtMs: Long,
    submit: suspend (String, Map<String, Any>) -> PlaybackLogResponse,
): PlaybackLogResponse {
    require(songId > 0L) { "songId must be positive" }
    // A play event closes a session; sending play(time=0) here creates a false completion.
    return submit(
        "startplay",
        playbackHistoryBaseFields(songId, sourceId, source, startedAtMs, startedAtMs),
    )
}

internal fun playbackHistoryPlayFields(
    songId: Long,
    sourceId: Long,
    source: String,
    timeSeconds: Long,
    startedAtMs: Long,
    endedAtMs: Long,
    endReason: String,
): Map<String, Any> = playbackHistoryBaseFields(songId, sourceId, source, startedAtMs, endedAtMs) + mapOf(
    "download" to 0,
    "end" to endReason,
    "time" to timeSeconds.coerceAtLeast(0L),
    "wifi" to 0,
)

private fun playbackHistoryBaseFields(
    songId: Long,
    sourceId: Long,
    source: String,
    startedAtMs: Long,
    loggedAtMs: Long,
): Map<String, Any> {
    require(songId > 0L) { "songId must be positive" }
    val knownSource = source.takeIf { it in PLAYBACK_SOURCES }
    val hasReliableSource = sourceId > 0L && knownSource != null
    val safeSourceId = if (hasReliableSource) sourceId else songId
    val safeSource = knownSource.takeIf { hasReliableSource } ?: "track"
    return mapOf(
        "id" to songId.toString(),
        "type" to "song",
        // Native BI uses epoch seconds, unlike the car OpenAPI's millisecond startLogTime.
        // Convert the captured start only; logtime keeps the event's epoch milliseconds.
        "startlogtime" to startedAtMs / 1000L,
        "logtime" to loggedAtMs,
        "sourceId" to safeSourceId.toString(),
        "source" to safeSource,
        "sourcetype" to safeSource,
        "mainsite" to "1",
        "mainsiteWeb" to "1",
        "content" to "id=$safeSourceId",
    )
}

internal fun parsePlaybackHistoryBody(body: String?): PlaybackBodyParseResult {
    if (body.isNullOrBlank()) {
        return PlaybackBodyParseResult(
            code = null,
            message = null,
            exceptionType = "EmptyResponseBody",
            failureReason = "response body is empty",
        )
    }
    if (body.length > MAX_PLAYBACK_HISTORY_RESPONSE_BYTES) {
        return playbackBodyTooLarge()
    }
    val response = try {
        JsonParser.parseString(body).asJsonObject
    } catch (error: Exception) {
        return PlaybackBodyParseResult(
            code = null,
            message = null,
            exceptionType = playbackExceptionType(error),
            failureReason = "response JSON parse failed: " +
                (sanitizePlaybackDiagnosticText(error.message) ?: "invalid JSON"),
        )
    }
    return PlaybackBodyParseResult(
        code = response.int("code"),
        message = sanitizePlaybackDiagnosticText(
            response.string("message") ?: response.string("msg"),
        ),
    )
}

private fun readPlaybackHistoryBody(
    body: ResponseBody?,
    httpStatus: Int,
): PlaybackBodyParseResult {
    if (body == null) return parsePlaybackHistoryBody(null)
    return try {
        readBoundedPlaybackResponseBody(body, httpStatus)
            .toString(Charsets.UTF_8)
            .let(::parsePlaybackHistoryBody)
    } catch (error: CancellationException) {
        throw error
    } catch (error: PlaybackResponseBodyException) {
        val causeReason = error.cause?.let(::playbackExceptionReason)
        PlaybackBodyParseResult(
            code = null,
            message = null,
            exceptionType = error.cause?.let(::playbackExceptionType) ?: error.failureKind,
            failureReason = if (causeReason != null && causeReason != "no message") {
                "${error.failureReason}: $causeReason"
            } else {
                error.failureReason
            },
        )
    } catch (error: Exception) {
        PlaybackBodyParseResult(
            code = null,
            message = null,
            exceptionType = playbackExceptionType(error),
            failureReason = "response body read failed: ${playbackExceptionReason(error)}",
        )
    }
}

private fun playbackTransportFailure(endpoint: String, error: Throwable): PlaybackLogResponse {
    val responseBodyError = error as? PlaybackResponseBodyException
    val root = responseBodyError?.cause?.let(::playbackRootCause)
        ?: playbackRootCause(error)
    val httpStatus = responseBodyError?.httpStatus
    val causeReason = responseBodyError?.cause?.let(::playbackExceptionReason)
    return PlaybackLogResponse(
        httpAccepted = httpStatus?.let { it in 200..299 } == true,
        httpStatus = httpStatus,
        code = null,
        message = null,
        exceptionType = responseBodyError?.cause?.let(::playbackExceptionType)
            ?: responseBodyError?.failureKind
            ?: playbackExceptionType(root),
        failureReason = if (responseBodyError != null &&
            causeReason != null &&
            causeReason != "no message"
        ) {
            "${responseBodyError.failureReason}: $causeReason"
        } else {
            responseBodyError?.failureReason
                ?: sanitizePlaybackDiagnosticText(root.message)
        },
        endpoint = endpoint,
    )
}

private fun playbackBodyTooLarge() = PlaybackBodyParseResult(
    code = null,
    message = null,
    exceptionType = "ResponseBodyTooLarge",
    failureReason = "response body exceeds ${MAX_PLAYBACK_HISTORY_RESPONSE_BYTES} bytes",
)

internal fun PlaybackLogResponse.diagnosticSummary(): String = buildString {
    append("httpAccepted=").append(httpAccepted)
    append(" httpStatus=").append(httpStatus ?: "null")
    append(" hostAccepted=").append(hostAccepted)
    append(" businessCode=").append(code ?: "null")
    append(" exceptionType=").append(exceptionType ?: "none")
    sanitizePlaybackDiagnosticText(message)?.let { append(" businessMessage=").append(it) }
    val reason = sanitizePlaybackDiagnosticText(failureReason)
        ?: if ((httpAccepted || hostAccepted) && !businessAccepted) "business response rejected" else "none"
    append(" reason=").append(reason)
    if (endpoint == PLAYBACK_HISTORY_DIAGNOSTIC_ENDPOINT) {
        append(" endpoint=").append(endpoint)
    } else {
        sanitizePlaybackDiagnosticText(endpoint)?.let { append(" endpoint=").append(it) }
    }
}

internal fun sanitizePlaybackDiagnosticText(value: String?): String? {
    if (value.isNullOrBlank()) return null
    var text = value.trim()
    text = PLAYBACK_AUTHORIZATION_PATTERN.replace(text) {
        "${it.groupValues[1]}=<redacted>"
    }
    text = PLAYBACK_COOKIE_HEADER_PATTERN.replace(text) {
        "${it.groupValues[1]}=<redacted>"
    }
    text = PLAYBACK_SENSITIVE_VALUE_PATTERN.replace(text) {
        "${it.groupValues[1]}=<redacted>"
    }
    text = PLAYBACK_QUERY_SENSITIVE_PATTERN.replace(text) {
        "${it.groupValues[1]}<redacted>"
    }
    text = PLAYBACK_URL_CREDENTIAL_PATTERN.replace(text) {
        "${it.groupValues[1]}<redacted>@"
    }
    text = text.replace(Regex("[\\r\\n\\t]+"), " ").trim()
    if (text.contains('{') || text.contains('[')) {
        text = text.substringBefore('{').substringBefore('[').trim()
            .let { prefix -> if (prefix.isEmpty()) "<redacted>" else "$prefix <redacted>" }
    }
    return text.take(MAX_PLAYBACK_DIAGNOSTIC_LENGTH).trimEnd().ifEmpty { null }
}

internal fun playbackRootCause(error: Throwable): Throwable {
    var root = error
    var depth = 0
    while (depth < MAX_PLAYBACK_CAUSE_DEPTH) {
        val cause = root.cause ?: break
        if (cause === root) break
        root = cause
        depth++
    }
    return root
}

internal fun playbackExceptionType(error: Throwable): String =
    playbackRootCause(error)::class.simpleName ?: "Exception"

internal fun playbackExceptionReason(error: Throwable): String =
    sanitizePlaybackDiagnosticText(playbackRootCause(error).message) ?: "no message"

private const val PLAYBACK_HISTORY_PATH = "/api/feedback/weblog"
private const val MAX_PLAYBACK_DIAGNOSTIC_LENGTH = 240
private const val MAX_PLAYBACK_CAUSE_DEPTH = 8
private val PLAYBACK_SOURCES = setOf("list", "album", "artist", "track")
private val PLAYBACK_AUTHORIZATION_PATTERN = Regex(
    "(?i)(?<![?&])([\"']?authorization[\"']?)\\s*[:=]\\s*" +
        "(?:[A-Za-z][A-Za-z0-9_-]*\\s+)?" +
        "(\"[^\"]*\"|'[^']*'|[^\\s,;}&\\]]+)",
)
private val PLAYBACK_COOKIE_HEADER_PATTERN = Regex(
    "(?i)(?<![?&])([\"']?(?:cookie|set-cookie)[\"']?)\\s*[:=]\\s*" +
        "(\"[^\"]*\"|'[^']*'|[^\\r\\n]+)",
)
private val PLAYBACK_SENSITIVE_VALUE_PATTERN = Regex(
    "(?i)([\"']?(?:authorization|cookie|set-cookie|music[_-]?u|music[_-]?a|csrf|__csrf|" +
        "check[_-]?token|x-anticheattoken|token|password|passwd|secret|" +
        "logs?|payload|body|header|stack(?:trace)?)[\"']?)" +
        "\\s*[:=]\\s*(?:bearer\\s+)?(\"[^\"]*\"|'[^']*'|[^\\s,;}&\\]]+)",
)
private val PLAYBACK_QUERY_SENSITIVE_PATTERN = Regex(
    "(?i)([?&](?:cookie|music[_-]?u|music[_-]?a|authorization|csrf|__csrf|" +
        "check[_-]?token|x-anticheattoken|token|password|passwd|secret)=)[^&\\s]+",
)
private val PLAYBACK_URL_CREDENTIAL_PATTERN = Regex("(?i)(https?://)[^/@\\s:]+:[^/@\\s]+@")

private fun JsonElement.identifierLong(): Long? = runCatching {
    when {
        isJsonPrimitive && asJsonPrimitive.isNumber -> asLong
        isJsonPrimitive -> asString.toLongOrNull()
        isJsonObject -> asJsonObject.element("id")?.identifierLong()
            ?: asJsonObject.element("value")?.identifierLong()
        else -> null
    }
}.getOrNull()?.takeIf { it > 0 }

private fun JsonElement.objectValue(): JsonObject? = takeIf(JsonElement::isJsonObject)?.asJsonObject

private fun JsonObject.blockResources(): List<JsonObject> = array("creatives")
    .mapNotNull { it.objectValue() }
    .flatMap { it.array("resources") }
    .mapNotNull { it.objectValue() }

private fun JsonObject?.mainTitle(): String? = this?.objectOrNull("mainTitle")
    ?.string("title")?.nonEmpty()

private fun JsonObject?.subtitleValues(): List<String> {
    val value = this ?: return emptyList()
    val many = value.array("subTitles")
        .mapNotNull { it.objectValue()?.string("title")?.nonEmpty() }
    if (many.isNotEmpty()) return many
    return listOfNotNull(value.objectOrNull("subTitle")?.string("title")?.nonEmpty())
}

private fun JsonObject?.textValues(): List<String> = this?.array("textLinks").orEmpty()
    .mapNotNull { it.objectValue()?.string("text")?.nonEmpty() }

private fun JsonObject?.descriptionValues(): List<String> = this?.array("descriptions").orEmpty()
    .mapNotNull { it.objectValue()?.string("description")?.nonEmpty() }

private fun JsonObject?.buttonValues(): List<String> = this?.array("buttons").orEmpty()
    .mapNotNull { it.objectValue()?.string("text")?.nonEmpty() }

private fun JsonObject?.firstImageUrl(): String? = this?.array("images")?.firstOrNull()
    ?.objectValue()?.string("imageUrl")?.nonEmpty()

private fun JsonObject?.associationDetail(id: String): SongWikiAssociationDetail? {
    val title = mainTitle()
    val subtitle = uniqueStrings(subtitleValues() + textValues()).joinToString(" · ").nonEmpty()
    val body = descriptionValues().joinToString("\n").nonEmpty()
    if (title == null && subtitle == null && body == null) return null
    return SongWikiAssociationDetail(id, title, subtitle, body)
}

private fun uniqueStrings(values: List<String>): List<String> = buildList {
    val seen = mutableSetOf<String>()
    values.forEach { value -> if (seen.add(value)) add(value) }
}

private fun String.nonEmpty(): String? = trim().takeIf(String::isNotEmpty)

private fun String.officialHttpsUrl(): String? = runCatching {
    val uri = java.net.URI(this)
    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
    if (uri.scheme == "https") this else java.net.URI(
        "https", uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment,
    ).toString()
}.getOrNull()

private fun parsePodcastCategory(element: JsonElement?): PodcastCategory? {
    val value = element?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return null
    val id = value.long("id") ?: return null
    if (id <= 0) return null
    return PodcastCategory(id, value.string("name") ?: "Podcast", value.string("pic96x96Url") ?: value.string("pic56x56Url"))
}

private fun parsePodcast(element: JsonElement?): Podcast? {
    val value = element?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return null
    val id = value.long("id") ?: return null
    if (id <= 0) return null
    return Podcast(
        id = id,
        name = value.string("name") ?: "Unknown podcast",
        picUrl = value.string("picUrl"),
        description = value.string("desc"),
        recommendation = value.string("rcmdText") ?: value.string("rcmdtext"),
        categoryId = value.long("categoryId"),
        category = value.string("category"),
        secondCategory = value.string("secondCategory"),
        programCount = value.int("programCount") ?: 0,
        subscriberCount = value.long("subCount") ?: 0,
        playCount = value.long("playCount") ?: 0,
        host = value.objectOrNull("dj")?.let(::parseHost),
        isSubscribed = value.boolean("subed") ?: false,
        feeType = value.int("radioFeeType"),
    )
}

private fun parseProgram(element: JsonElement?): PodcastProgram? {
    val value = element?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return null
    val id = value.long("id") ?: return null
    val radio = value.objectOrNull("radio")
    return PodcastProgram(
        id = id,
        name = value.string("name") ?: "Unknown episode",
        coverUrl = value.string("coverUrl") ?: radio?.string("picUrl"),
        description = value.string("description"),
        createTime = value.long("createTime"),
        durationMs = value.long("duration") ?: 0,
        listenerCount = value.long("listenerCount") ?: 0,
        likedCount = value.long("likedCount") ?: 0,
        commentCount = value.long("commentCount") ?: 0,
        serialNumber = value.int("serialNum"),
        radioId = radio?.long("id") ?: 0,
        radioName = radio?.string("name") ?: "Podcast",
        host = value.objectOrNull("dj")?.let(::parseHost),
        mainSongId = value.objectOrNull("mainSong")?.long("id"),
    )
}

private fun parseAccountProfile(value: JsonObject?): AccountProfile? = value?.let {
    val id = it.long("userId") ?: return null
    AccountProfile(
        id = id,
        nickname = it.string("nickname") ?: "NetEase user",
        avatarUrl = it.string("avatarUrl"),
        backgroundUrl = it.string("backgroundUrl"),
        signature = it.string("signature"),
        follows = it.int("follows"),
        followers = it.int("followeds"),
        eventCount = it.int("eventCount"),
        playlistCount = it.int("playlistCount"),
        playlistSubscribedCount = it.int("playlistBeSubscribedCount"),
    )
}

private fun parseAccountPlaylist(element: JsonElement?): AccountPlaylist? {
    val value = element?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return null
    val id = value.long("id") ?: return null
    val creator = value.objectOrNull("creator")
    return AccountPlaylist(
        id = id,
        name = value.string("name") ?: "Playlist",
        coverUrl = value.string("coverImgUrl") ?: value.string("picUrl"),
        trackCount = value.int("trackCount") ?: 0,
        creatorId = creator?.long("userId"),
        creatorName = creator?.string("nickname"),
    )
}

internal fun parseRecentHistorySong(element: JsonElement?): AccountSong? {
    val item = element?.objectValue() ?: return null
    val song = parseAccountSong(item.objectOrNull("data")) ?: return null
    return song.copy(playedAt = normalizeCloudPlayTime(item.long("playTime")))
}

internal fun normalizeCloudPlayTime(value: Long?): Long? {
    if (value == null || value <= 0L) return null
    return if (value < CLOUD_MILLIS_TIMESTAMP_THRESHOLD) value * 1_000L else value
}

private fun List<AccountSong>.deduplicateRecentSongs(): List<AccountSong> {
    val unique = LinkedHashMap<Long, AccountSong>()
    for (song in this) {
        val existing = unique[song.id]
        if (existing == null || song.playedAt.isAfter(existing.playedAt)) {
            unique[song.id] = song
        }
    }
    return unique.values.toList()
}

private fun Long?.isAfter(other: Long?): Boolean = when {
    this == null -> false
    other == null -> true
    else -> this > other
}

private const val CLOUD_MILLIS_TIMESTAMP_THRESHOLD = 100_000_000_000L

private fun parseAccountSong(value: JsonObject?): AccountSong? = value?.let {
    val id = it.long("id") ?: return null
    val album = it.objectOrNull("al") ?: it.objectOrNull("album")
    val artistValues = it.array("ar").ifEmpty { it.array("artists") }
        .mapNotNull(JsonElement::objectValue)
    val artists = artistValues.mapNotNull { artist -> artist.string("name") }
    AccountSong(
        id = id,
        name = it.string("name") ?: "Unknown song",
        artists = artists,
        album = album?.string("name") ?: "Unknown album",
        coverUrl = album?.string("picUrl"),
        durationMs = it.long("dt") ?: it.long("duration") ?: 0,
        artistIds = artistValues.mapNotNull { artist -> artist.long("id") },
        albumId = album?.long("id") ?: 0,
    )
}

private fun parseUserPlayRecord(element: JsonElement?): UserPlayRecord? {
    val value = element?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return null
    val song = parseAccountSong(value.objectOrNull("song")) ?: return null
    return UserPlayRecord(
        song = song,
        playCount = value.int("playCount") ?: 0,
        score = value.int("score"),
    )
}

private fun parseHost(value: JsonObject): PodcastHost = PodcastHost(
    id = value.long("userId") ?: 0,
    nickname = value.string("nickname") ?: "NetEase host",
    avatarUrl = value.string("avatarUrl"),
)

private fun parseContact(value: JsonObject?): MessageContact? = value?.let {
    MessageContact(
        id = it.long("userId") ?: return null,
        nickname = it.string("nickname") ?: "NetEase user",
        avatarUrl = it.string("avatarUrl"),
        signature = it.string("signature"),
        remarkName = it.string("remarkName"),
    )
}

private fun parseConversation(element: JsonElement?): PrivateConversation? {
    val value = element?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return null
    val from = parseContact(value.objectOrNull("fromUser"))
    val to = parseContact(value.objectOrNull("toUser"))
    return PrivateConversation(
        id = listOf(from?.id ?: 0, to?.id ?: 0).sorted().joinToString("-"),
        fromUser = from,
        toUser = to,
        lastMessageTime = value.long("lastMsgTime") ?: 0,
        summary = decodeMessagePayload(value.string("lastMsg")).summary,
        unreadCount = value.int("newMsgCount") ?: 0,
    )
}

private fun parsePrivateMessage(element: JsonElement?): PrivateMessage? {
    val value = element?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return null
    val time = value.long("time") ?: 0
    return PrivateMessage(
        id = value.long("id") ?: time,
        fromUser = parseContact(value.objectOrNull("fromUser")),
        toUser = parseContact(value.objectOrNull("toUser")),
        time = time,
        payload = decodeMessagePayload(value.string("msg")),
    )
}

private fun decodeMessagePayload(serialized: String?): PrivateMessagePayload {
    if (serialized.isNullOrBlank()) return PrivateMessagePayload("", null)
    return runCatching {
        val wire = JsonParser.parseString(serialized).asJsonObject
        val text = wire.string("msg")?.trim().orEmpty()
        val resource = when {
            wire.objectOrNull("song") != null -> parseShareResource(
                ShareResourceKind.Song,
                wire.objectOrNull("song")!!,
            )
            wire.objectOrNull("playlist") != null -> parseShareResource(
                ShareResourceKind.Playlist,
                wire.objectOrNull("playlist")!!,
            )
            wire.objectOrNull("album") != null -> parseShareResource(
                ShareResourceKind.Album,
                wire.objectOrNull("album")!!,
            )
            else -> null
        }
        PrivateMessagePayload(text, resource)
    }.getOrElse { PrivateMessagePayload(serialized, null) }
}

private fun parseShareResource(kind: ShareResourceKind, value: JsonObject): ShareResource? {
    val id = value.long("id") ?: return null
    val album = value.objectOrNull("al") ?: value.objectOrNull("album")
    val artists = value.array("ar").ifEmpty { value.array("artists") }
        .mapNotNull { it.objectValue()?.string("name") }
    val creator = value.objectOrNull("creator")
    return ShareResource(
        kind = kind,
        id = id,
        title = value.string("name") ?: kind.wireValue,
        subtitle = when (kind) {
            ShareResourceKind.Song, ShareResourceKind.Album -> artists.joinToString(" / ").nonEmpty()
                ?: value.objectOrNull("artist")?.string("name")
            ShareResourceKind.Playlist -> creator?.string("nickname")
        },
        artworkUrl = when (kind) {
            ShareResourceKind.Song -> album?.string("picUrl")
            ShareResourceKind.Playlist -> value.string("coverImgUrl") ?: value.string("picUrl")
            ShareResourceKind.Album -> value.string("picUrl")
        },
    )
}

private fun parseRoom(value: JsonObject): ListenTogetherRoom = ListenTogetherRoom(
    id = value.string("roomId") ?: "",
    creatorId = value.string("creatorId") ?: "",
    users = value.array("roomUsers").mapNotNull { element ->
        val user = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
        ListenTogetherUser(user.string("userId") ?: return@mapNotNull null, user.string("nickname") ?: "NetEase user", user.string("avatarUrl"))
    },
    createTime = value.long("roomCreateTime"),
    effectiveDurationMs = value.long("effectiveDurationMs"),
)

private fun JsonObject.array(name: String): List<JsonElement> =
    get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.toList().orEmpty()

private fun JsonObject.objectOrNull(name: String): JsonObject? =
    get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

private fun JsonObject.element(name: String): JsonElement? =
    get(name)?.takeUnless(JsonElement::isJsonNull)

private fun JsonObject.string(name: String): String? {
    val value = get(name)?.takeUnless(JsonElement::isJsonNull) ?: return null
    return runCatching { value.asString }.getOrNull()
}

private fun JsonObject.long(name: String): Long? = string(name)?.toLongOrNull()
private fun JsonObject.int(name: String): Int? = string(name)?.toIntOrNull()
private fun JsonObject.boolean(name: String): Boolean? = string(name)?.let {
    when (it.lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> null
    }
}
