package com.ljyh.mei.parasite

import org.json.JSONObject
import java.lang.reflect.InvocationTargetException

/** Opt-in, read-only qualification probe. Never logs response bodies, identifiers or credentials. */
internal class HostCapabilityProbe(
    private val hostLoader: ClassLoader,
    private val report: (String) -> Unit,
    private val onPlayable: (String) -> Unit = {},
) {
    fun run() {
        try {
            probe()
        } catch (error: Throwable) {
            report("probe_aborted type=${cause(error).javaClass.name}")
        }
    }

    private fun probe() {
        // Check the live Application before touching Session's static initializer.
        hostLoader.loadClass("com.netease.cloudmusic.NeteaseMusicApplication")
            .getMethod("getInstance").invoke(null)
        val core = hostLoader.loadClass("com.netease.cloudmusic.core.b")
        val sessionType = hostLoader.loadClass("com.netease.cloudmusic.r0.a")
        val session = sessionType.getMethod("c").invoke(null)
        val userIdMethod = sessionType.getMethod("e")
        val userId = userIdMethod.invoke(session) as Long
        fun isCurrentSession(): Boolean = core.getMethod("d").invoke(null) == true &&
            core.getMethod("c").invoke(null) == false && userId > 0 &&
            userIdMethod.invoke(session) == userId
        report("session authenticated=${isCurrentSession()}")
        if (!isCurrentSession()) return

        val factory = hostLoader.loadClass("com.netease.cloudmusic.network.f")
            .getMethod("c", String::class.java, Map::class.java)
        val requestType = hostLoader.loadClass("com.netease.cloudmusic.network.v.e.a")
        val execute = requestType.getMethod("k")
        fun request(name: String, path: String, params: Map<String, String> = emptyMap()): JSONObject? {
            check(isCurrentSession())
            return try {
                val request = factory.invoke(null, path, params)
                requestType.getMethod("d", Int::class.javaPrimitiveType).invoke(request, 10_000)
                requestType.getMethod("i0", Int::class.javaPrimitiveType).invoke(request, 15_000)
                val response = execute.invoke(request) as JSONObject
                check(isCurrentSession())
                report("probe=$name code=${response.optInt("code", -1)}")
                response
            } catch (error: InvocationTargetException) {
                report("probe=$name failed type=${cause(error).javaClass.name}")
                null
            }
        }

        val account = request("account", "nuser/account/get")
        val accountMatches = account?.optJSONObject("profile")?.optLong("userId") == userId
        report("account_matches_session=$accountMatches")
        if (!accountMatches) return

        val playlists = request("playlists", "user/playlist", mapOf(
            "uid" to userId.toString(), "limit" to "2", "offset" to "0",
        ))
        report("playlists_present=${(playlists?.optJSONArray("playlist")?.length() ?: 0) > 0}")
        val playlistId = playlists?.optJSONArray("playlist")?.optJSONObject(0)?.optLong("id") ?: 0
        val playlist = if (playlistId > 0) request("playlist_detail", "v6/playlist/detail", mapOf(
            "id" to playlistId.toString(), "n" to "1", "s" to "0",
        )) else null
        request("cloud", "v1/cloud/get", mapOf("limit" to "1", "offset" to "0"))
        request("listen_together", "listen/together/status/get")
        request("podcast_categories", "djradio/category/get")
        val search = request("search", "search/get", mapOf(
            "s" to "music", "type" to "1", "limit" to "1", "offset" to "0",
        ))
        val songId = playlist?.optJSONObject("playlist")?.optJSONArray("trackIds")
            ?.optJSONObject(0)?.optLong("id")?.takeIf { it > 0 }
            ?: search?.optJSONObject("result")?.optJSONArray("songs")?.optJSONObject(0)?.optLong("id")
        if (songId != null && songId > 0) {
            val detail = request("song_detail", "v3/song/detail", mapOf("c" to "[{\"id\":$songId}]"))
            report("song_detail_present=${(detail?.optJSONArray("songs")?.length() ?: 0) > 0}")
            val lyrics = request("lyrics", "song/lyric/v1", mapOf(
                "id" to songId.toString(), "lv" to "-1", "kv" to "-1", "tv" to "-1", "yv" to "-1",
            ))
            report("lyrics_present=${lyrics?.optJSONObject("lrc")?.optString("lyric").orEmpty().isNotBlank()}")
            val source = request("playback_url", "song/enhance/player/url/v1", mapOf(
                "ids" to "[$songId]", "level" to "standard", "encodeType" to "aac",
            ))?.optJSONArray("data")?.optJSONObject(0)
            val hasUrl = source?.optString("url").orEmpty().let { it.startsWith("https://") || it.startsWith("http://") }
            report("playback code=${source?.optInt("code", -1)} url_present=$hasUrl trial=${source?.isNull("freeTrialInfo") == false}")
            if (hasUrl && source?.optInt("code") == 200 && source.isNull("freeTrialInfo")) {
                onPlayable(source.getString("url"))
            }
        }
        report("probe_complete session_unchanged=${isCurrentSession()}")
    }

    private fun cause(error: Throwable): Throwable =
        if (error is InvocationTargetException) error.targetException else error
}
