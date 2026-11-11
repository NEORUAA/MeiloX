package com.ljyh.mei.source.lyrics

import com.google.gson.JsonParser
import com.ljyh.mei.data.model.Lyric
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.qq.u.LyricResult
import com.ljyh.mei.data.model.qq.u.SearchResult
import com.ljyh.mei.data.model.toSourceTrack
import com.ljyh.mei.data.network.QQMusicUApiService
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.source.api.ProviderIds
import com.ljyh.mei.source.api.SourceAlbum
import com.ljyh.mei.source.api.SourceArtist
import com.ljyh.mei.source.api.Track
import com.ljyh.mei.source.api.TrackRef
import com.ljyh.mei.source.core.TrackMatcher
import com.ljyh.mei.playback.readDownloadResponse
import com.google.gson.Gson
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Host lyric lookup never reads a content provider's account or selected-source preference. */
@Singleton
class LyricRepository @Inject constructor(qqApi: QQMusicUApiService) {
    private val qq = QQLyricsExtension(qqApi)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val matches = LinkedHashMap<TrackRef, Pair<Track, String?>>()
    private val matchMutex = Mutex()

    suspend fun searchNew(keyword: String): Resource<SearchResult> = qq.search(keyword)
    suspend fun getLyricNew(title: String, album: String, artist: String, duration: Long, id: Long): Resource<LyricResult> =
        qq.getLyrics(title, album, artist, duration, id, wordTimed = true)
    suspend fun getLyricLrc(title: String, album: String, artist: String, duration: Long, id: Long): Resource<LyricResult> =
        qq.getLyrics(title, album, artist, duration, id, wordTimed = false)

    /** Foreign numeric IDs are never sent to a lyric service as NetEase IDs. */
    suspend fun neteaseId(metadata: MediaMetadata): String? {
        nativeLyricIdentity(metadata)?.let { return it }
        val track = metadata.toSourceTrack()
        if (track.title.isBlank() || track.durationMs <= 0 || track.artists.none { it.name.isNotBlank() }) return null
        return matchMutex.withLock {
            matches[track.ref]?.takeIf { it.first == track }?.let { return@withLock it.second }
            var result: String? = null
            val artist = track.artists.joinToString(" ") { it.name }
            val queries = listOf("${track.title} $artist".trim(), track.title).distinct()
            for (query in queries) {
                val response = request(Request.Builder().url("https://interface.music.163.com/api/search/get/")
                    .post(FormBody.Builder().add("s", query).add("type", "1").add("limit", "30").add("offset", "0").build())
                    .header("Referer", "https://music.163.com/").build())
                if (response !is Resource.Success) continue
                result = matchingLyricIdentity(track, parseLyricMatches(response.data))
                if (result != null) break
            }
            if (result != null) matches[track.ref] = track to result
            while (matches.size > 32) matches.remove(matches.entries.first().key)
            result
        }
    }

    suspend fun getLyricV1(id: String): Resource<Lyric> {
        if (!positiveLyricId(id)) return Resource.Error("No lyric identity")
        val body = FormBody.Builder().add("id", id).add("cp", "false").add("tv", "0")
            .add("lv", "0").add("rv", "0").add("kv", "0").add("yv", "0").add("ytv", "0").add("yrv", "0").build()
        return when (val response = request(Request.Builder().url("https://interface.music.163.com/api/song/lyric/v1")
            .post(body).header("Referer", "https://music.163.com/").build())) {
            is Resource.Success -> try {
                val lyric = gson.fromJson(response.data, Lyric::class.java)
                if (lyric.code == 200) Resource.Success(lyric) else Resource.Error("Lyrics unavailable")
            } catch (_: Exception) { Resource.Error("Invalid lyric response") }
            is Resource.Error -> response
            Resource.Loading -> Resource.Loading
        }
    }

    suspend fun getAMLLyric(id: String): Resource<String> {
        if (!positiveLyricId(id)) return Resource.Error("No lyric identity")
        return when (val response = request(Request.Builder().url("https://amlldb.bikonoo.com/ncm-lyrics/$id.ttml").build())) {
            is Resource.Success -> if (response.data.isNotBlank() && response.data != "歌词不存在") response else Resource.Error("Lyrics unavailable")
            else -> response
        }
    }

    private suspend fun request(request: Request): Resource<String> = withContext(Dispatchers.IO) {
        try {
            readDownloadResponse(client, request) { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) Resource.Error("Lyrics unavailable")
                else {
                    val source = body.source()
                    source.request(MAX_RESPONSE_BYTES + 1L)
                    if (source.buffer.size > MAX_RESPONSE_BYTES) Resource.Error("Invalid lyric response")
                    else Resource.Success(source.readUtf8())
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { Resource.Error("Lyric request failed") }
    }

    private companion object { const val MAX_RESPONSE_BYTES = 1_048_576L }
}

internal fun positiveLyricId(id: String): Boolean = id.toLongOrNull()?.let { it > 0 } == true
internal fun nativeLyricIdentity(metadata: MediaMetadata): String? = metadata.trackRef
    .takeIf { it.providerId == ProviderIds.NETEASE && positiveLyricId(it.trackId) }?.trackId

internal fun matchingLyricIdentity(origin: Track, candidates: List<Track>): String? {
    if (origin.title.isBlank() || origin.artists.none { it.name.isNotBlank() } || origin.durationMs <= 0) return null
    val matcher = TrackMatcher()
    return matcher.automatic(matcher.rank(origin, candidates))?.track?.ref?.trackId
}

/** Only the public fields needed to identify a recording cross the lyric boundary. */
internal fun parseLyricMatches(json: String): List<Track> = runCatching {
    val response = JsonParser.parseString(json).asJsonObject
    if (response.get("code")?.asInt != 200) return@runCatching emptyList()
    response.getAsJsonObject("result")?.getAsJsonArray("songs")?.take(30).orEmpty().mapNotNull { element ->
        runCatching {
            val song = element.asJsonObject
            val id = song.get("id").asString.takeIf(::positiveLyricId) ?: return@runCatching null
            val artists = (song.getAsJsonArray("artists") ?: song.getAsJsonArray("ar"))?.mapNotNull { value ->
                value.asJsonObject.get("name")?.asString?.takeIf(String::isNotBlank)?.let(::SourceArtist)
            }.orEmpty()
            val title = song.get("name").asString.takeIf(String::isNotBlank) ?: return@runCatching null
            Track(TrackRef(ProviderIds.NETEASE, id), title, artists,
                SourceAlbum((song.getAsJsonObject("album") ?: song.getAsJsonObject("al"))?.get("name")?.asString.orEmpty()),
                durationMs = (song.get("duration") ?: song.get("dt"))?.asLong ?: 0)
        }.getOrNull()
    }
}.getOrDefault(emptyList())
