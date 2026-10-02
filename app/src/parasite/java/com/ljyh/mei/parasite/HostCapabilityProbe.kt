package com.ljyh.mei.parasite

import com.ljyh.mei.data.session.SessionStamp
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** Opt-in, read-only qualification probe. Never logs response bodies, identifiers or credentials. */
internal class HostCapabilityProbe(
    private val requests: HostRequestBridge,
    private val report: (String) -> Unit,
    private val onPlayable: (String, SessionStamp) -> Unit = { _, _ -> },
) {
    fun run() {
        try {
            probe()
        } catch (error: Throwable) {
            report("probe_aborted type=${error.javaClass.name}")
        }
    }

    private fun probe() {
        val session = requests.sessions.snapshot()
        val userId = session.identity.userId
        fun isCurrentSession(): Boolean = session.identity.authenticated &&
            runCatching { requests.sessions.requireCurrent(session) }.isSuccess
        report("session authenticated=${isCurrentSession()}")
        if (!isCurrentSession()) return

        fun request(name: String, path: String, params: Map<String, String> = emptyMap()): JSONObject? {
            check(isCurrentSession())
            return try {
                val response = JSONObject(requests.newCall(path, params, session).execute().body)
                check(isCurrentSession())
                report("probe=$name code=${response.optInt("code", -1)}")
                response
            } catch (error: IOException) {
                report("probe=$name failed type=${error.javaClass.name}")
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
                onPlayable(source.getString("url"), session)
            }
        }
        verifyCancellation(session)
        report("probe_complete session_unchanged=${isCurrentSession()}")
    }

    private fun verifyCancellation(session: SessionStamp) {
        val call = requests.newCall("search/get", mapOf("s" to "music", "type" to "1", "limit" to "1", "offset" to "1"), session)
        val failure = AtomicReference<Throwable?>()
        val worker = Thread({
            try { call.execute() } catch (error: Throwable) { failure.set(error) }
        }, "MeiloX-cancel-probe").apply { isDaemon = true }
        worker.start()
        val deadline = System.nanoTime() + 2_000_000_000L
        while (worker.isAlive && !call.isRunning && System.nanoTime() < deadline) Thread.sleep(1)
        val observed = call.isRunning
        call.cancel()
        worker.join(20_000)
        report("request_cancel observed_running=$observed worker_finished=${!worker.isAlive} rejected=${failure.get() is IOException}")
        check(!worker.isAlive) { "Cancellation probe did not finish" }
    }
}
