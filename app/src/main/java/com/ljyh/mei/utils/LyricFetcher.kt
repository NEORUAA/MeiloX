package com.ljyh.mei.utils

import com.ljyh.mei.data.model.Lyric
import com.ljyh.mei.data.model.api.GetLyricV1
import com.ljyh.mei.di.AppGraph
import com.ljyh.mei.parasite.HostSessionStamp
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

    suspend fun fetchBestLyric(songId: String, owner: HostSessionStamp): String? = withContext(Dispatchers.IO) {
        val sessions = AppGraph.component.hostRequests().sessions
        sessions.requireDownloadOwner(owner)
        val amll = fetchAMLL(songId)
        sessions.requireDownloadOwner(owner)
        if (!amll.isNullOrBlank()) return@withContext amll

        val netease = fetchNeteaseLyric(songId, owner)
        sessions.requireDownloadOwner(owner)
        val yrc = netease?.yrc?.lyric
        if (!yrc.isNullOrBlank()) return@withContext yrc

        val lrc = netease?.lrc?.lyric
        if (!lrc.isNullOrBlank()) return@withContext lrc

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

    private suspend fun fetchNeteaseLyric(songId: String, owner: HostSessionStamp): Lyric? {
        return try {
            AppGraph.component.apiService().getLyricV1(GetLyricV1(songId), owner)
        } catch (error: CancellationException) { throw error }
        catch (e: Exception) {
            AppGraph.component.hostRequests().sessions.requireDownloadOwner(owner)
            Timber.tag("LyricFetcher").w("Official lyric fetch failed: %s", e.javaClass.simpleName)
            null
        }
    }
}
