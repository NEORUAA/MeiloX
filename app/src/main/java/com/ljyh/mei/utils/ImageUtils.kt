package com.ljyh.mei.utils


import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
    if (!isNeteaseArtwork()) return this
    return "$this?param=100y100"
}

/** Remove server-added playlist labels/logos while retaining the underlying artwork operations. */
internal fun playlistCoverWithoutOverlays(cover: String): String {
    var changed = false
    val operations = cover.split(Regex("(?i)%7c|\\|"))
    val artwork = operations.mapIndexedNotNull { index, operation ->
        val queryStart = if (index == 0) operation.indexOf('?') else -1
        val processor = if (index == 0) {
            if (queryStart < 0) return@mapIndexedNotNull operation
            operation.substring(queryStart + 1)
        } else operation
        val parameters = processor.split('&')
        val watermark = parameters.indexOfFirst {
            it.substringBefore('=').equals("watermark", ignoreCase = true)
        }
        if (watermark < 0) return@mapIndexedNotNull operation
        changed = true
        if (index > 0) return@mapIndexedNotNull null
        val retained = parameters.take(watermark).joinToString("&")
        operation.substring(0, queryStart) + if (retained.isEmpty()) "" else "?$retained"
    }
    return if (changed) artwork.joinToString("%7C") else cover
}

fun String.middleImage(): String {
    if (!isNeteaseArtwork()) return this
    return "$this?param=300y300"
}

fun String.largeImage(): String {
    if (!isNeteaseArtwork()) return this
    return "$this?param=500y500"
}

fun String.size1600():String{
    if (!isNeteaseArtwork()) return this
    return "$this?param=1600y1600"
}

class CoilImageLoader {
    companion object {
        suspend fun loadImageDrawable(context: Context, imageUrl: String): ImageBitmap? =
            withContext(Dispatchers.IO) {
                val loader = context.imageLoader
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
            if (com.ljyh.mei.source.script.SourceArtworkRegistry.owns(imageUrl)) {
                val local = com.ljyh.mei.source.script.SourceArtworkRegistry.resolve(imageUrl)
                val file = java.io.File(java.net.URI(local))
                if (!file.isFile || file.length() > MAX_COVER_BYTES) return@withContext null
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                return@withContext file.inputStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(65_536)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size().toLong() + count > MAX_COVER_BYTES) return@withContext null
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            }
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

/** Platform resize parameters must not alter plugin files or another provider's signed URL. */
private fun String.isNeteaseArtwork(): Boolean {
    val host = runCatching { java.net.URI(this).host }.getOrNull() ?: return false
    return host.equals("music.126.net", ignoreCase = true) || host.endsWith(".music.126.net", ignoreCase = true)
}
