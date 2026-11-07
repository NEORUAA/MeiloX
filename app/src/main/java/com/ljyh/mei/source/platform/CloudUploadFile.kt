package com.ljyh.mei.source.platform

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.webkit.MimeTypeMap
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class CloudUploadFile(
    val file: File,
    val filename: String,
    val extension: String,
    val normalizedStem: String,
    val size: Long,
    val md5: String,
    val songName: String,
    val artist: String,
    val album: String,
    val mimeType: String,
) : Closeable {
    override fun close() {
        file.delete()
    }
}

/** Freeze the provider bytes once so metadata, MD5, length and transfer share one source. */
internal suspend fun prepareCloudUploadFile(context: Context, uri: Uri, maximumBytes: Long = 1_073_741_824L,
    ensureSelected: () -> Unit = {}): CloudUploadFile {
    var prepared: CloudUploadFile? = null
    try {
        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val resolver = context.contentResolver
                val signal = CancellationSignal()
                val opened = AtomicReference<Closeable?>()
                continuation.invokeOnCancellation {
                    signal.cancel()
                    runCatching { opened.get()?.close() }
                }
                fun verify() {
                    ensureSelected()
                    if (!continuation.isActive) throw CancellationException("Cloud file preparation canceled")
                    signal.throwIfCanceled()
                }
                var snapshot: File? = null
                try {
                    verify()
                    var filename: String? = null
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, signal)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let {
                                filename = cursor.getString(it)
                            }
                        }
                    }
                    verify()
                    val safeFilename = filename?.takeIf(String::isNotBlank)
                        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
                        ?: "music.mp3"
                    val directory = File(context.cacheDir, "cloud-uploads")
                    check(directory.isDirectory || directory.mkdirs()) { "Cloud upload cache is unavailable" }
                    val copy = File.createTempFile("upload-", ".bin", directory).also { snapshot = it }
                    val digest = MessageDigest.getInstance("MD5")
                    resolver.openAssetFileDescriptor(uri, "r", signal)?.use { asset ->
                        opened.set(asset)
                        verify()
                        asset.createInputStream().use { input ->
                            opened.set(input)
                            copy.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var copied = 0L
                                val poll = StructPollfd().apply {
                                    fd = asset.fileDescriptor
                                    events = OsConstants.POLLIN.toShort()
                                }
                                while (true) {
                                    verify()
                                    // A provider may return a pipe whose next bytes are still pending.
                                    // Polling keeps cancellation responsive even if closing that fd
                                    // would not interrupt a read already blocked in the kernel.
                                    if (Os.poll(arrayOf(poll), 100) == 0) continue
                                    verify()
                                    val read = input.read(buffer)
                                    verify()
                                    if (read < 0) break
                                    if (read > 0) {
                                        copied += read
                                        check(copied <= maximumBytes) { "Selected file is too large" }
                                        digest.update(buffer, 0, read)
                                        output.write(buffer, 0, read)
                                    }
                                }
                            }
                        }
                    } ?: error("The selected audio file cannot be opened")
                    opened.set(null)
                    check(copy.length() > 0) { "The selected audio file is empty or unavailable" }
                    verify()
                    val fallbackName = safeFilename.substringBeforeLast('.', safeFilename)
                    val extension = safeFilename.substringAfterLast('.', "mp3").lowercase(Locale.ROOT)
                    val retriever = MediaMetadataRetriever()
                    val metadata = try {
                        retriever.setDataSource(copy.absolutePath)
                        listOf(
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                        )
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        emptyList()
                    } finally {
                        retriever.release()
                    }
                    verify()
                    val result = CloudUploadFile(
                        file = copy,
                        filename = safeFilename,
                        extension = extension,
                        normalizedStem = fallbackName.filterNot(Char::isWhitespace).replace('.', '_').ifEmpty { "music" },
                        size = copy.length(),
                        md5 = digest.digest().joinToString("") { "%02x".format(it) },
                        songName = metadata.getOrNull(0)?.takeIf(String::isNotBlank) ?: fallbackName,
                        artist = metadata.getOrNull(1)?.takeIf(String::isNotBlank) ?: "Unknown artist",
                        album = metadata.getOrNull(2)?.takeIf(String::isNotBlank) ?: "Unknown album",
                        mimeType = metadata.getOrNull(3)?.takeIf(String::isNotBlank)
                            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                            ?: "audio/mpeg",
                    )
                    prepared = result
                    continuation.resume(result) { _, rejected, _ -> rejected.close() }
                } catch (error: Throwable) {
                    snapshot?.delete()
                    continuation.resumeWithException(error)
                } finally {
                    opened.set(null)
                }
            }
        }
    } catch (error: Throwable) {
        // withContext may reject a successfully prepared result while dispatching back.
        prepared?.close()
        throw error
    }
}

/** Cancellation closes the call during lookup, request-body writes and response reads. */
internal suspend fun executeCloudUploadRequest(client: OkHttpClient, request: Request): String =
    withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            try {
                if (!continuation.isActive) throw CancellationException("Cloud transfer canceled")
                val body = call.execute().use { response ->
                    check(response.isSuccessful) { "NOS request failed (${response.code})" }
                    val source = response.body.source()
                    check(!source.request(65_537)) { "NOS response is too large" }
                    source.readUtf8()
                }
                continuation.resume(body) { _, _, _ -> }
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
            }
        }
    }
