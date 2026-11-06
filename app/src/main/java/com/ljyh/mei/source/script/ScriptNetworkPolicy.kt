package com.ljyh.mei.source.script

import com.google.gson.JsonObject
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import java.net.IDN
import java.net.InetAddress
import java.io.InterruptedIOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The only network path exposed to scripts and their audio resources. */
class ScriptNetworkPolicy(private val domains: List<String>) {
    private val concurrency = Semaphore(2)
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .proxy(java.net.Proxy.NO_PROXY)
        .dns(Dns { host ->
            validateHost(host)
            Dns.SYSTEM.lookup(host).also { addresses ->
                if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) denied()
            }
        })
        .addInterceptor { chain ->
            validateUrl(chain.request().url.toString())
            chain.proceed(chain.request())
        }.build()

    fun validateUrl(value: String): HttpUrl {
        if (value.length > 8192) denied()
        val url = value.toHttpUrlOrNull() ?: denied()
        if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) denied()
        validateHost(url.host)
        return url
    }

    fun validateHeaders(headers: Map<String, String>) {
        if (headers.size > 32 || headers.entries.sumOf { it.key.length + it.value.length } > 16_384) denied()
        headers.forEach { (key, value) ->
            if (!Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+").matches(key) || value.any { it == '\r' || it == '\n' || it == '\u0000' } ||
                key.lowercase() in setOf("host", "connection", "content-length", "transfer-encoding", "proxy-authorization", "proxy-connection")) denied()
        }
    }

    suspend fun artwork(url: String, directory: java.io.File, ensureCurrent: () -> Unit = {}): String = concurrency.withPermit {
        ensureCurrent()
        val checked = validateUrl(url)
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = java.io.File(directory, "artwork/$key")
        withContext(Dispatchers.IO) {
            transport {
                ensureCurrent()
                if (file.isFile) {
                    file.setLastModified(System.currentTimeMillis())
                    return@transport android.net.Uri.fromFile(file).toString()
                }
                client.newCall(Request.Builder().url(checked).build()).execute().use { response ->
                ensureCurrent()
                if (response.code in 300..399) denied()
                if (!response.isSuccessful) throw SourceException(
                    if (response.code in setOf(404, 410)) SourceErrorCode.NOT_FOUND else SourceErrorCode.NETWORK,
                )
                val body = response.body ?: denied()
                if (body.contentLength() > MAX_RESPONSE_BYTES) denied()
                val source = body.source()
                if (source.request(MAX_RESPONSE_BYTES + 1L)) denied()
                val bytes = source.readByteArray()
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                val cached = file.parentFile?.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.lastModified() }
                var bytesRemaining = cached.sumOf { it.length() }
                var countRemaining = cached.size
                cached.filter { it != file }.forEach { old ->
                    if (bytesRemaining > 16L * 1024 * 1024 || countRemaining > 256) {
                        val size = old.length()
                        if (old.delete()) { bytesRemaining -= size; countRemaining-- }
                    }
                }
                ensureCurrent()
                android.net.Uri.fromFile(file).toString()
                }
            }
        }
    }

    suspend fun request(request: JsonObject, ensureCurrent: () -> Unit = {}): JsonObject = concurrency.withPermit {
        ensureCurrent()
        val responseEncoding = responseEncoding(request)
        val url = validateUrl(request.get("url")?.asString ?: denied())
        val method = request.get("method")?.asString?.uppercase() ?: "GET"
        if (method !in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) denied()
        val headers = request.getAsJsonObject("headers")?.entrySet()?.associate { it.key to it.value.asString }.orEmpty()
        validateHeaders(headers)
        val body = request.get("body")?.takeUnless { it.isJsonNull }?.asString?.toByteArray(Charsets.UTF_8)
        if ((body?.size ?: 0) > MAX_REQUEST_BYTES || method in setOf("GET", "HEAD") && body != null) denied()
        val builder = Request.Builder().url(url)
        headers.forEach(builder::header)
        val requestBody = if (method in setOf("POST", "PUT", "PATCH")) (body ?: byteArrayOf()).toRequestBody() else body?.toRequestBody()
        builder.method(method, requestBody)
        val call = client.newCall(builder.build())
        val response = suspendCancellableCoroutine<Response> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(networkFailure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            })
        }
        val payload = withContext(Dispatchers.IO) {
            transport { response.use { ensureCurrent(); responsePayload(it, responseEncoding) } }
        }
        ensureCurrent()
        payload
    }

    internal fun responseEncoding(request: JsonObject): String {
        val value = request.get("responseEncoding") ?: return "utf8"
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString || value.asString !in setOf("utf8", "base64")) denied()
        return value.asString
    }

    internal fun responsePayload(response: Response, encoding: String): JsonObject = transport {
        if (encoding !in setOf("utf8", "base64") || response.code in 300..399) denied()
        val responseBody = response.body
        if (responseBody != null && responseBody.contentLength() > MAX_RESPONSE_BYTES) denied()
        val source = responseBody?.source()
        if (source != null && source.request(MAX_RESPONSE_BYTES + 1L)) denied()
        val bytes = source?.readByteArray() ?: byteArrayOf()
        JsonObject().apply {
            addProperty("status", response.code)
            // Cookies from one response are not installed in a shared cookie jar.
            add("headers", JsonObject().apply { response.headers.names().forEach { name -> addProperty(name, response.header(name)) } })
            addProperty("body", if (encoding == "utf8") bytes.toString(Charsets.UTF_8) else "")
            if (encoding == "base64") addProperty("bodyBase64", Base64.getEncoder().encodeToString(bytes))
        }
    }

    private inline fun <T> transport(block: () -> T): T = try { block()
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (error: SourceException) { throw error
    } catch (error: IOException) { throw networkFailure(error) }

    private fun networkFailure(error: IOException) = if (error is SourceException) error else
        SourceException(if (error is InterruptedIOException) SourceErrorCode.TIMEOUT else SourceErrorCode.NETWORK)

    private fun validateHost(host: String) {
        if (domains.none { it.equals(host, ignoreCase = true) }) denied()
        validateDomain(host)
    }

    companion object {
        const val MAX_REQUEST_BYTES = 256 * 1024
        const val MAX_RESPONSE_BYTES = 1024 * 1024
        fun validateDomain(domain: String) {
            if (domain.length !in 4..253 || domain != domain.lowercase() || domain.endsWith('.') ||
                !domain.contains('.') || domain.contains('*') || domain.contains(':') ||
                runCatching { IDN.toASCII(domain) }.getOrNull() != domain ||
                !Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?").matches(domain) ||
                domain.split('.').any { it.isBlank() || it.length > 63 || it.startsWith('-') || it.endsWith('-') } ||
                domain.endsWith(".local") || domain.endsWith(".localhost") || domain.endsWith(".internal") ||
                domain.all { it.isDigit() || it == '.' }) denied()
        }

        fun isPublicAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress) return false
            val bytes = address.address.map { it.toInt() and 255 }
            if (bytes.size == 4) return bytes[0] !in setOf(0, 10, 127) && bytes[0] < 224 &&
                !(bytes[0] == 169 && bytes[1] == 254) && !(bytes[0] == 172 && bytes[1] in 16..31) &&
                !(bytes[0] == 192 && bytes[1] in setOf(0, 168)) && !(bytes[0] == 100 && bytes[1] in 64..127) &&
                !(bytes[0] == 198 && bytes[1] in 18..19)
            // Permit global unicast only; reject ULA, mapped IPv4, transition and scoped addresses.
            return bytes.size == 16 && bytes[0] in 0x20..0x3f && address.hostAddress?.contains('%') != true &&
                !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0 && bytes[3] == 0) &&
                !(bytes[0] == 0x20 && bytes[1] == 0x02)
        }

        private fun denied(): Nothing = throw SourceException(SourceErrorCode.PLUGIN, "Source network permission denied")
    }
}
