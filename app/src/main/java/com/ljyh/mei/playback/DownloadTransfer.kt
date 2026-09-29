package com.ljyh.mei.playback

import com.ljyh.mei.data.model.DownloadSource
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response

/** Closing a canceled call must not wait for a blocked socket read to return. */
internal suspend fun <T> Call.withCancellableResponse(block: suspend (Response) -> T): T = coroutineScope {
    val call = this@withCancellableResponse
    val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { call.cancel() }
    }
    try {
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            try { call.execute().use { block(it) } }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                throw error
            }
        }
    } finally { cancellation.cancel() }
}

/** Validate the original media bytes before any metadata changes. Never append across grants. */
internal suspend fun transferOfficialDownload(
    client: Call.Factory,
    source: DownloadSource,
    file: File,
    requireOwner: suspend () -> Unit,
    progress: suspend (Int) -> Unit,
) {
    requireOwner()
    if (source.expiresAtMs != null && source.expiresAtMs <= System.currentTimeMillis()) {
        throw IOException("Official download grant expired")
    }
    client.newCall(Request.Builder().url(source.url).build()).withCancellableResponse { response ->
        if (response.code != 200) throw IOException("Media response failed (${response.code})")
        val body = response.body
        if (body.contentLength() >= 0 && body.contentLength() != source.size) throw IOException("Media size mismatch")
        val digest = MessageDigest.getInstance("MD5")
        var count = 0L
        var lastProgress = -1
        body.byteStream().use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read.toLong() > source.size - count) throw IOException("Media exceeds authorized size")
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    count += read
                    val percentage = (count.toDouble() / source.size * 100).toInt().coerceIn(0, 99)
                    if (percentage != lastProgress) {
                        requireOwner()
                        progress(percentage)
                        lastProgress = percentage
                    }
                }
            }
        }
        requireOwner()
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (count != source.size || hash != source.md5) throw IOException("Media integrity mismatch")
    }
}
