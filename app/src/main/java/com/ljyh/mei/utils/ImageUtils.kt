package com.ljyh.mei.utils


import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import com.ljyh.mei.playback.DownloadWorker
import com.ljyh.mei.playback.readDownloadResponse
import kotlinx.coroutines.CancellationException
import okhttp3.ResponseBody
import timber.log.Timber

fun String.smallImage(): String {
    if (this.startsWith("/")) return this
    return "$this?param=100y100"
}

fun String.middleImage(): String {
    if (this.startsWith("/")) return this
    return "$this?param=300y300"
}

fun String.largeImage(): String {
    if (this.startsWith("/")) return this
    return "$this?param=500y500"
}

fun String.size1600():String{
    return "$this?param=1600y1600"
}

class CoilImageLoader {
    companion object {
        suspend fun loadImageDrawable(context: Context, imageUrl: String): ImageBitmap? =
            withContext(Dispatchers.IO) {
                val loader = ImageLoader(context)
                val request = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .allowHardware(false) // 避免返回硬件加速的 Bitmap
                    .build()
                loader.execute(request).image?.toBitmap()?.asImageBitmap()
            }
    }
}

object ImageUtils {
    internal const val MAX_COVER_BYTES = 8L * 1024 * 1024
    private val httpClient = DownloadWorker.getDownloadClient().newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun downloadImageBytes(imageUrl: String): ByteArray? = downloadImageBytes(imageUrl, httpClient)

    internal suspend fun downloadImageBytes(imageUrl: String, client: OkHttpClient): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(imageUrl).build()
            readDownloadResponse(client, request) { response ->
                if (response.isSuccessful) readCoverBytes(response.body) else null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Timber.w(e, "Cover download failed")
            null
        }
    }

    internal fun readCoverBytes(body: ResponseBody): ByteArray? {
        if (body.contentLength() > MAX_COVER_BYTES) return null
        val source = body.source()
        source.request(MAX_COVER_BYTES + 1)
        val size = source.buffer.size
        return if (size in 1..MAX_COVER_BYTES) source.buffer.readByteArray() else null
    }
}
