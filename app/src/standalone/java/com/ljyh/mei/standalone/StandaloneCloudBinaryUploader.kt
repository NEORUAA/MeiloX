package com.ljyh.mei.standalone

import com.google.gson.JsonParser
import com.ljyh.mei.data.repository.CloudBinaryUploader
import com.ljyh.mei.data.repository.CloudUploadAuthorization
import com.ljyh.mei.data.repository.CloudUploadFile
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

internal class StandaloneCloudBinaryUploader internal constructor(
    private val sessions: SessionStore,
    private val client: OkHttpClient,
) : CloudBinaryUploader {
    @Inject constructor(sessions: SessionStore) : this(sessions, OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(200, TimeUnit.SECONDS)
        .writeTimeout(200, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build())

    override suspend fun upload(file: CloudUploadFile, authorization: CloudUploadAuthorization, owner: SessionStamp, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        fun checkOwner() { context.ensureActive(); sessions.requireCurrent(owner) }
        checkOwner()
        val lookup = "https://wanproxy.127.net/lbs".toHttpUrl().newBuilder()
            .addQueryParameter("version", "1.0").addQueryParameter("bucketname", authorization.bucket).build()
        suspend fun execute(request: Request): String {
            checkOwner()
            val call = client.newCall(request)
            val invalidation = sessions.onInvalidated { if (it > owner.generation) call.cancel() }
            try {
                // Await cancellation closes the socket, including a blocked response read.
                return kotlinx.coroutines.coroutineScope {
                    val cancellation = launchCancellation { call.cancel() }
                    try {
                        checkOwner()
                        call.execute().use { response ->
                            checkOwner()
                            check(response.isSuccessful) { "Cloud file transfer failed (${response.code})" }
                            val source = response.body.source()
                            check(!source.request(65_537)) { "Cloud transfer response is too large" }
                            source.readUtf8().also { checkOwner() }
                        }
                    } finally { cancellation.cancel() }
                }
            } catch (error: Exception) {
                checkOwner()
                throw error
            } finally { invalidation.close() }
        }
        val json = execute(Request.Builder().url(lookup).build())
        val host = JsonParser.parseString(json).asJsonObject.getAsJsonArray("upload")?.firstOrNull()?.asString
            ?: throw IOException("Cloud upload server is unavailable")
        val base = host.toHttpUrl()
        require(base.isHttps && base.host.endsWith(".127.net") && base.username.isEmpty() && base.password.isEmpty() &&
            base.port == 443 && base.encodedPath == "/" && base.query == null && base.fragment == null) { "Invalid cloud upload server" }
        val url = base.newBuilder().addPathSegment(authorization.bucket).addPathSegment(authorization.objectKey)
            .addQueryParameter("offset", "0").addQueryParameter("complete", "true").addQueryParameter("version", "1.0").build()
        val body = object : RequestBody() {
            override fun contentType() = file.mimeType.toMediaTypeOrNull()
            override fun contentLength() = file.size
            override fun writeTo(sink: BufferedSink) {
                checkOwner()
                var sent = 0L
                val digest = MessageDigest.getInstance("MD5")
                file.file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkOwner()
                        val read = input.read(buffer)
                        if (read < 0) break
                        sent += read
                        check(sent <= file.size) { "Cloud upload file changed" }
                        digest.update(buffer, 0, read)
                        sink.write(buffer, 0, read)
                        sink.flush()
                        checkOwner()
                        onProgress(sent, file.size)
                    }
                }
                check(sent == file.size) { "Cloud upload file changed" }
                check(digest.digest().joinToString("") { "%02x".format(it) } == file.md5) { "Cloud upload file changed" }
            }
        }
        val receipt = execute(Request.Builder().url(url).header("x-nos-token", authorization.token)
            .header("Content-MD5", file.md5).post(body).build())
        val offset = JsonParser.parseString(receipt).asJsonObject.get("offset")
        check(offset?.isJsonPrimitive == true && offset.asString.toLongOrNull() == file.size) {
            "Cloud transfer did not acknowledge the complete file"
        }
        checkOwner()
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchCancellation(cancel: () -> Unit) =
    launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
        try { kotlinx.coroutines.awaitCancellation() } finally { cancel() }
    }
