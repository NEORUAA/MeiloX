package com.ljyh.mei.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.data.model.melox.CloudMusicPage
import com.ljyh.mei.data.model.melox.CloudSong
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

@Singleton
class MeloXRepository @Inject constructor(
    @Named("MeloXEapi") private val eapi: MeloXDirectService,
    @Named("MeloXWeapi") private val weapi: MeloXDirectService,
    @ApplicationContext private val context: Context,
    @Named("CloudUploadClient") private val cloudUploadClient: OkHttpClient,
) {
    suspend fun podcastHome(): PodcastHome = coroutineScope {
        val categories = async {
            request("/api/djradio/category/get").array("categories").mapNotNull(::parsePodcastCategory)
        }
        val featured = async {
            request("/api/djradio/recommend/v1").array("djRadios").mapNotNull(::parsePodcast)
        }
        val personalized = async {
            request("/api/djradio/personalize/rcmd", mapOf("limit" to 12))
                .array("data").mapNotNull(::parsePodcast)
        }
        PodcastHome(categories.await(), featured.await(), personalized.await())
    }

    suspend fun podcasts(categoryId: Long, offset: Int = 0, limit: Int = 30): List<Podcast> =
        request(
            "/api/djradio/hot",
            mapOf("cateId" to categoryId, "offset" to offset, "limit" to limit.coerceIn(1, 50)),
        ).array("djRadios").mapNotNull(::parsePodcast)

    suspend fun podcastDetail(id: Long, offset: Int = 0, limit: Int = 50): PodcastDetail =
        coroutineScope {
            val podcastResponse = async { request("/api/djradio/v2/get", mapOf("id" to id)) }
            val programsResponse = async { podcastPrograms(id, offset, limit) }
            val podcast = parsePodcast(podcastResponse.await().objectOrNull("data"))
                ?: error("Podcast $id was not returned by NetEase")
            val programs = programsResponse.await()
            PodcastDetail(
                podcast = podcast,
                programs = programs.programs,
                hasMore = programs.hasMore,
                totalCount = programs.totalCount,
            )
        }

    suspend fun podcastPrograms(id: Long, offset: Int = 0, limit: Int = 50): PodcastProgramPage {
        val response = request(
            "/api/dj/program/byradio",
            mapOf("radioId" to id, "offset" to offset, "limit" to limit.coerceIn(1, 50), "asc" to false),
        )
        val programs = response.array("programs").mapNotNull(::parseProgram)
        val totalCount = response.int("count") ?: (offset + programs.size)
        return PodcastProgramPage(
            programs = programs,
            hasMore = response.boolean("more") ?: (offset + programs.size < totalCount),
            totalCount = totalCount,
        )
    }

    suspend fun subscribedPodcasts(offset: Int = 0, limit: Int = 50): PodcastPage {
        val response = request(
            "/api/djradio/get/subed",
            mapOf("offset" to offset, "limit" to limit.coerceIn(1, 100), "total" to true),
        )
        val podcasts = response.array("djRadios").mapNotNull(::parsePodcast)
        val totalCount = response.int("count") ?: response.int("total") ?: (offset + podcasts.size)
        return PodcastPage(
            podcasts = podcasts,
            hasMore = response.boolean("hasMore")
                ?: response.boolean("more")
                ?: (offset + podcasts.size < totalCount),
            totalCount = totalCount,
        )
    }

    suspend fun searchDiscovery(): SearchDiscovery {
        val response = requestEapi(
            "/api/personalized/playlist",
            mapOf("limit" to 10, "total" to true, "n" to 1_000),
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

    suspend fun accountProfile(): AccountProfile {
        val response = runCatching { requestEapi("/api/w/nuser/account/get") }
            .getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                requestEapi("/api/nuser/account/get")
            }
        return parseAccountProfile(response.objectOrNull("profile"))
            ?: error("NetEase account profile is unavailable")
    }

    suspend fun accountDetail(userId: Long): AccountDetail {
        val response = try {
            validate(weapi.post("/weapi/v1/user/detail/$userId"))
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            requestEapi(
                "/api/w/v1/user/detail/$userId",
                mapOf("all" to true, "userId" to userId),
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

    suspend fun accountPlaylists(userId: Long, limit: Int = 2_000): List<AccountPlaylist> =
        request(
            "/api/user/playlist",
            mapOf("uid" to userId, "limit" to limit.coerceIn(1, 2_000), "offset" to 0, "includeVideo" to true),
        ).array("playlist").mapNotNull(::parseAccountPlaylist)

    suspend fun userPlayRecords(userId: Long, allTime: Boolean): List<UserPlayRecord> {
        val response = request(
            "/api/v1/play/record",
            mapOf("uid" to userId, "type" to if (allTime) 0 else 1),
        )
        return response.array(if (allTime) "allData" else "weekData")
            .mapNotNull(::parseUserPlayRecord)
            .filter { it.song.id > 0 }
    }

    suspend fun setPodcastSubscribed(id: Long, subscribed: Boolean) {
        request(if (subscribed) "/api/djradio/sub" else "/api/djradio/unsub", mapOf("id" to id))
    }

    suspend fun setArtistFollowed(id: Long, followed: Boolean) {
        request(
            if (followed) "/api/artist/sub" else "/api/artist/unsub",
            mapOf("artistId" to id, "artistIds" to "[$id]"),
        )
    }

    suspend fun cloudSongs(offset: Int = 0, limit: Int = 200): CloudMusicPage {
        val response = request("/api/v1/cloud/get", mapOf("offset" to offset, "limit" to limit))
        val songs = response.array("data").mapNotNull(::parseCloudSong)
        val count = response.int("count") ?: songs.size
        return CloudMusicPage(
            songs = songs,
            count = count,
            usedSize = response.long("size") ?: 0,
            maxSize = response.long("maxSize") ?: 0,
            hasMore = response.boolean("hasMore") ?: (offset + songs.size < count),
        )
    }

    suspend fun deleteCloudSong(id: Long) {
        request("/api/cloud/del", mapOf("songIds" to listOf(id)))
    }

    suspend fun uploadCloudSong(uri: Uri, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val file = prepareCloudUploadFile(uri)
        val bitrate = 999_000
        val check = requestEapi(
            "/api/cloud/upload/check",
            mapOf(
                "bitrate" to bitrate.toString(), "ext" to "", "length" to file.size,
                "md5" to file.md5, "songId" to "0", "version" to 1,
            ),
        )
        val metadataToken = requestEapi(
            "/api/nos/token/alloc",
            mapOf(
                "bucket" to "", "ext" to file.extension, "filename" to file.normalizedStem,
                "local" to false, "nos_product" to 3, "type" to "audio", "md5" to file.md5,
            ),
        ).objectOrNull("result") ?: error("NetEase did not return a cloud resource token")

        if (check.boolean("needUpload") == true) {
            val bucket = "jd-musicrep-privatecloud-audio-public"
            val tokenData = mapOf<String, Any>(
                "bucket" to bucket, "ext" to file.extension, "filename" to file.normalizedStem,
                "local" to false, "nos_product" to 3, "type" to "audio", "md5" to file.md5,
            )
            val uploadToken = request("/api/nos/token/alloc", tokenData).objectOrNull("result")
                ?: error("NetEase did not return a NOS upload token")
            uploadToNos(
                file = file,
                bucket = bucket,
                objectKey = uploadToken.string("objectKey") ?: error("NOS object key is missing"),
                token = uploadToken.string("token") ?: error("NOS token is missing"),
                onProgress = onProgress,
            )
        }

        val info = requestEapi(
            "/api/upload/cloud/info/v2",
            mapOf(
                "md5" to file.md5,
                "songid" to (check.long("songId") ?: 0),
                "filename" to file.filename,
                "song" to file.songName,
                "album" to file.album,
                "artist" to file.artist,
                "bitrate" to bitrate.toString(),
                "resourceId" to (metadataToken.string("resourceId") ?: error("Cloud resource ID is missing")),
            ),
        )
        requestEapi("/api/cloud/pub/v2", mapOf("songid" to (info.long("songId") ?: 0)))
        onProgress(file.size, file.size)
    }

    private suspend fun prepareCloudUploadFile(uri: Uri): CloudUploadFile = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var filename: String? = null
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { filename = cursor.getString(it) }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = cursor.getLong(it) }
            }
        }
        val safeFilename = filename?.takeIf(String::isNotBlank) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "music.mp3"
        if (size <= 0) size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
        check(size > 0) { "The selected audio file is empty or unavailable" }
        val digest = MessageDigest.getInstance("MD5")
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(1_048_576)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        } ?: error("The selected audio file cannot be opened")
        val fallbackName = safeFilename.substringBeforeLast('.', safeFilename)
        val retriever = MediaMetadataRetriever()
        val metadata = runCatching {
            retriever.setDataSource(context, uri)
            Triple(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            )
        }.getOrNull()
        retriever.release()
        CloudUploadFile(
            uri = uri,
            filename = safeFilename,
            extension = safeFilename.substringAfterLast('.', "mp3").lowercase(),
            normalizedStem = fallbackName.filterNot(Char::isWhitespace).replace('.', '_').ifEmpty { "music" },
            size = size,
            md5 = digest.digest().joinToString("") { "%02x".format(it) },
            songName = metadata?.first?.takeIf(String::isNotBlank) ?: fallbackName,
            artist = metadata?.second?.takeIf(String::isNotBlank) ?: "Unknown artist",
            album = metadata?.third?.takeIf(String::isNotBlank) ?: "Unknown album",
            mimeType = resolver.getType(uri) ?: "audio/mpeg",
        )
    }

    private suspend fun uploadToNos(
        file: CloudUploadFile,
        bucket: String,
        objectKey: String,
        token: String,
        onProgress: (Long, Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val lbsRequest = Request.Builder()
            .url("https://wanproxy.127.net/lbs?version=1.0&bucketname=$bucket")
            .get()
            .build()
        val uploadHost = cloudUploadClient.newCall(lbsRequest).execute().use { response ->
            check(response.isSuccessful) { "NOS server lookup failed (${response.code})" }
            val body = response.body.string()
            JsonParser.parseString(body).asJsonObject.getAsJsonArray("upload")?.firstOrNull()?.asString
                ?: error("NetEase did not return an available upload server")
        }
        val encodedObjectKey = java.net.URLEncoder.encode(objectKey, Charsets.UTF_8.name()).replace("+", "%20")
        val uploadUrl = "${uploadHost.trimEnd('/')}/$bucket/$encodedObjectKey?offset=0&complete=true&version=1.0"
        val body = object : RequestBody() {
            override fun contentType() = file.mimeType.toMediaTypeOrNull()
            override fun contentLength() = file.size
            override fun writeTo(sink: BufferedSink) {
                context.contentResolver.openInputStream(file.uri)?.use { input ->
                    val source = input.source()
                    var sent = 0L
                    while (true) {
                        val read = source.read(sink.buffer, 64 * 1024L)
                        if (read < 0) break
                        sent += read
                        sink.flush()
                        onProgress(sent, file.size)
                    }
                } ?: error("The selected audio file cannot be reopened")
            }
        }
        val request = Request.Builder()
            .url(uploadUrl)
            .header("x-nos-token", token)
            .header("Content-MD5", file.md5)
            .post(body)
            .build()
        cloudUploadClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                response.body.string().takeIf(String::isNotBlank) ?: "NOS upload failed (${response.code})"
            }
        }
    }

    suspend fun privateConversations(offset: Int = 0, limit: Int = 50): List<PrivateConversation> =
        request(
            "/api/msg/private/users",
            mapOf("offset" to offset, "limit" to limit, "total" to "true"),
        ).array("msgs").mapNotNull(::parseConversation)

    suspend fun privateMessages(userId: Long, before: Long = -1, limit: Int = 100): List<PrivateMessage> =
        request(
            "/api/msg/private/history",
            mapOf("userId" to userId, "time" to before, "limit" to limit, "total" to "true"),
        ).array("msgs").mapNotNull(::parsePrivateMessage).sortedBy(PrivateMessage::time)

    suspend fun sendPrivateText(message: String, userIds: List<Long>) {
        require(userIds.isNotEmpty()) { "At least one recipient is required" }
        requestEapi(
            "/api/msg/private/send",
            mapOf(
                "type" to "text",
                "msg" to message,
                "userIds" to userIds.distinct().sorted().joinToString(",", "[", "]"),
            ),
        )
    }

    suspend fun messageContacts(
        userId: Long,
        pageSize: Int = 100,
        maximumCount: Int = 1_000,
    ): List<MessageContact> {
        require(userId > 0) { "A valid NetEase user ID is required" }
        val contacts = mutableListOf<MessageContact>()
        val loadedIds = mutableSetOf<Long>()
        var offset = 0
        var hasMore = true
        while (hasMore && contacts.size < maximumCount) {
            val response = request(
                "/api/user/getfollows/$userId",
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

    suspend fun sendPrivateResource(
        resource: ShareResource,
        userIds: List<Long>,
        message: String = "",
    ) {
        require(userIds.isNotEmpty()) { "At least one recipient is required" }
        requestEapi(
            "/api/msg/private/send",
            mapOf(
                "id" to resource.id,
                "msg" to message,
                "type" to resource.kind.wireValue,
                "userIds" to userIds.distinct().sorted().joinToString(",", "[", "]"),
            ),
        )
    }

    suspend fun shareToTimeline(resource: ShareResource, message: String = "") {
        require(resource.kind != ShareResourceKind.Album) { "Albums cannot be shared to the NetEase timeline" }
        requestEapi(
            "/api/share/friends/resource",
            mapOf("type" to resource.kind.wireValue, "msg" to message, "id" to resource.id),
        )
    }

    suspend fun songWiki(songId: Long): SongWiki {
        require(songId > 0) { "A valid song ID is required" }
        val response = requestEapi(
            "/api/song/play/about/block/page",
            mapOf("songId" to songId),
        )
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
        )
    }

    suspend fun listenTogetherStatus(): ListenTogetherStatus {
        val data = request("/api/listen/together/status/get").objectOrNull("data")
        return ListenTogetherStatus(
            isInRoom = data?.boolean("inRoom") ?: false,
            room = data?.objectOrNull("roomInfo")?.let(::parseRoom),
            status = data?.string("status"),
        )
    }

    suspend fun createListenTogetherRoom(): ListenTogetherRoom {
        val response = requestEapi("/api/listen/together/room/create", mapOf("refer" to "songplay_more"))
        return response.objectOrNull("data")?.objectOrNull("roomInfo")?.let(::parseRoom)
            ?: error("NetEase did not return a Listen Together room")
    }

    suspend fun checkListenTogetherRoom(roomId: String): Pair<Boolean, String?> {
        val data = requestEapi(
            "/api/listen/together/room/check",
            mapOf("roomId" to roomId),
        ).objectOrNull("data")
        return (data?.boolean("joinable") ?: false) to data?.string("status")
    }

    suspend fun acceptListenTogetherRoom(roomId: String, inviterId: String): ListenTogetherRoom {
        val response = requestEapi(
            "/api/listen/together/play/invitation/accept",
            mapOf("refer" to "inbox_invite", "roomId" to roomId, "inviterId" to inviterId),
        )
        return response.objectOrNull("data")?.objectOrNull("roomInfo")?.let(::parseRoom)
            ?: error("NetEase did not return the accepted room")
    }

    suspend fun reportListenTogetherCommand(
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
        requestEapi(
            "/api/listen/together/play/command/report",
            mapOf("roomId" to roomId, "commandInfo" to commandInfo),
        )
    }

    suspend fun listenTogetherPlayback(roomId: String): ListenTogetherPlaybackSnapshot {
        val data = requestEapi(
            "/api/listen/together/sync/playlist/get",
            mapOf("roomId" to roomId),
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

    suspend fun reportListenTogetherPlaylist(
        roomId: String,
        userId: Long,
        version: Long,
        displaySongIds: List<Long>,
        randomSongIds: List<Long>,
    ) {
        val playlist = JSONObject(
            mapOf(
                "commandType" to "REPLACE",
                "version" to listOf(mapOf("userId" to userId, "version" to version)),
                "anchorSongId" to "",
                "anchorPosition" to -1,
                "randomList" to randomSongIds.map(Long::toString),
                "displayList" to displaySongIds.map(Long::toString),
            ),
        ).toString()
        requestEapi(
            "/api/listen/together/sync/list/command/report",
            mapOf("roomId" to roomId, "playlistParam" to playlist),
        )
    }

    suspend fun sendListenTogetherHeartbeat(
        roomId: String,
        songId: Long,
        isPlaying: Boolean,
        progressMs: Long,
    ): Int? = requestEapi(
        "/api/listen/together/heartbeat",
        mapOf(
            "roomId" to roomId,
            "songId" to songId,
            "playStatus" to if (isPlaying) "PLAY" else "PAUSE",
            "progress" to progressMs.coerceAtLeast(0),
        ),
    ).objectOrNull("data")?.int("timeSpan")

    suspend fun endListenTogetherRoom(roomId: String) {
        requestEapi("/api/listen/together/end/v2", mapOf("roomId" to roomId))
    }

    private suspend fun request(path: String, body: Map<String, Any> = emptyMap()): JsonObject =
        try {
            validate(weapi.post(path.replaceFirst("/api/", "/weapi/"), body))
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            requestEapi(path, body)
        }

    private suspend fun requestEapi(path: String, body: Map<String, Any> = emptyMap()): JsonObject =
        validate(eapi.post(path.replaceFirst("/api/", "/eapi/"), body))

    private fun validate(response: JsonObject): JsonObject {
        val code = response.int("code") ?: 200
        check(code in 200..299) {
            response.string("message") ?: response.string("msg") ?: "NetEase request failed ($code)"
        }
        return response
    }
}

private data class CloudUploadFile(
    val uri: Uri,
    val filename: String,
    val extension: String,
    val normalizedStem: String,
    val size: Long,
    val md5: String,
    val songName: String,
    val artist: String,
    val album: String,
    val mimeType: String,
)

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

private fun parseCloudSong(element: JsonElement?): CloudSong? {
    val value = element?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return null
    val simple = value.objectOrNull("simpleSong")
    val album = simple?.objectOrNull("al") ?: simple?.objectOrNull("album")
    val artists = simple?.array("ar")?.ifEmpty { simple.array("artists") }
    val id = value.long("songId") ?: simple?.long("id") ?: return null
    return CloudSong(
        id = id,
        name = value.string("songName") ?: simple?.string("name") ?: "Unknown song",
        artist = value.string("artist") ?: artists?.joinToString(" / ") { it.asJsonObject.string("name") ?: "" }.orEmpty(),
        album = value.string("album") ?: album?.string("name") ?: "Unknown album",
        coverUrl = album?.string("picUrl"),
        durationMs = simple?.long("dt") ?: simple?.long("duration") ?: 0,
        fileSize = value.long("fileSize") ?: 0,
        bitrate = value.int("bitrate") ?: 0,
        addTime = value.long("addTime") ?: 0,
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

private fun parseAccountSong(value: JsonObject?): AccountSong? = value?.let {
    val id = it.long("id") ?: return null
    val album = it.objectOrNull("al") ?: it.objectOrNull("album")
    val artists = it.array("ar").ifEmpty { it.array("artists") }
        .mapNotNull { artist -> artist.takeIf(JsonElement::isJsonObject)?.asJsonObject?.string("name") }
    AccountSong(
        id = id,
        name = it.string("name") ?: "Unknown song",
        artists = artists,
        album = album?.string("name") ?: "Unknown album",
        coverUrl = album?.string("picUrl"),
        durationMs = it.long("dt") ?: it.long("duration") ?: 0,
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
