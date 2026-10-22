package com.ljyh.mei.utils

import com.google.gson.Gson
import com.ljyh.mei.data.model.Lyric
import com.ljyh.mei.di.NeteaseInterceptor
import com.ljyh.mei.playback.DownloadWorker
import com.ljyh.mei.playback.readDownloadResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit

object LyricFetcher {

    private val neteaseClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(NeteaseInterceptor())
        .build()

    private val amllClient = DownloadWorker.getDownloadClient().newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .connectionPool(DownloadWorker.getDownloadClient().connectionPool)
        .build()

    private val gson = Gson()

    suspend fun fetchBestLyric(songId: String): String? = withContext(Dispatchers.IO) {
        val netease = fetchNeteaseLyric(songId)
        DownloadLyrics.plainNetEase(netease)?.let { return@withContext it }
        currentCoroutineContext().ensureActive()
        DownloadLyrics.select(netease, fetchAMLL(songId))
    }

    private suspend fun fetchAMLL(songId: String): String? {
        return try {
            val url = "https://amlldb.bikonoo.com/ncm-lyrics/$songId.ttml"
            val request = Request.Builder().url(url).build()
            readDownloadResponse(amllClient, request) { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank() && body != "歌词不存在") body else null
                } else null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Timber.tag("LyricFetcher").w(e, "AMLL fetch failed for $songId")
            null
        }
    }

    private suspend fun fetchNeteaseLyric(songId: String): Lyric? {
        return try {
            val body = FormBody.Builder()
                .add("id", songId)
                .add("cp", "false")
                .add("tv", "0")
                .add("lv", "0")
                .add("rv", "0")
                .add("kv", "0")
                .add("yv", "0")
                .add("ytv", "0")
                .add("yrv", "0")
                .build()

            val request = Request.Builder()
                .url("https://interface.music.163.com/api/song/lyric/v1")
                .post(body)
                .build()

            readDownloadResponse(neteaseClient, request) { response ->
                if (response.isSuccessful) {
                    gson.fromJson(response.body?.string(), Lyric::class.java)
                } else null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Timber.tag("LyricFetcher").w(e, "Netease lyric fetch failed for $songId")
            null
        }
    }
}
