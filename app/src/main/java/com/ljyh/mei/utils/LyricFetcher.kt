package com.ljyh.mei.utils

import com.ljyh.mei.data.model.Lyric
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.di.AppGraph
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.playback.requireDownloadOwner
import com.ljyh.mei.playback.withCancellableResponse
import kotlinx.coroutines.CancellationException
import com.ljyh.mei.playback.DownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit

object LyricFetcher {

    private val amllClient = DownloadWorker.getDownloadClient().newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .connectionPool(DownloadWorker.getDownloadClient().connectionPool)
        .build()

    suspend fun fetchBestLyric(sourceKey: String, owner: SessionStamp): String? = withContext(Dispatchers.IO) {
        val sessions = AppGraph.component.sessions()
        val source = SongSourceIdentity.fromKey(sourceKey)
        source.requireAccount(owner.identity)
        sessions.requireDownloadOwner(owner)
        val amll = fetchAMLL(source.songId.toString())
        sessions.requireDownloadOwner(owner)
        if (!amll.isNullOrBlank()) return@withContext amll

        val netease = fetchNeteaseLyric(source.key, owner)
        sessions.requireDownloadOwner(owner)
        val yrc = netease?.yrc?.lyric
        if (!yrc.isNullOrBlank()) return@withContext yrc

        val lrc = netease?.lrc?.lyric
        if (!lrc.isNullOrBlank()) return@withContext lrc

        val karaoke = netease?.klyric?.lyric
        if (!karaoke.isNullOrBlank()) return@withContext karaoke

        null
    }

    private suspend fun fetchAMLL(songId: String): String? {
        return try {
            val url = "https://amlldb.bikonoo.com/ncm-lyrics/$songId.ttml"
            val request = Request.Builder().url(url).build()
            amllClient.newCall(request).withCancellableResponse { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank() && body != "歌词不存在") body else null
                } else null
            }
        } catch (error: CancellationException) { throw error }
        catch (e: Exception) {
            Timber.tag("LyricFetcher").w("AMLL fetch failed: %s", e.javaClass.simpleName)
            null
        }
    }

    private suspend fun fetchNeteaseLyric(songId: String, owner: SessionStamp): Lyric? {
        return try {
            AppGraph.component.songLyrics().lyrics(songId, owner)
        } catch (error: CancellationException) { throw error }
        catch (e: Exception) {
            AppGraph.component.sessions().requireDownloadOwner(owner)
            Timber.tag("LyricFetcher").w("Official lyric fetch failed: %s", e.javaClass.simpleName)
            null
        }
    }
}
