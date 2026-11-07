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
class ScriptNetworkPolicy(
    private val domains: List<String>,
    private val nativeSdks: Set<String> = emptySet(),
    private val configuration: () -> Map<String, String> = { emptyMap() },
) {
    private val concurrency = Semaphore(2)
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .proxy(java.net.Proxy.NO_PROXY)
        .dns(Dns { host ->
            validateHost(host)
            Dns.SYSTEM.lookup(host).also(::validateResolvedAddresses)
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
        val file = artworkFile(url, directory)
        withContext(Dispatchers.IO) {
            ensureCurrent()
            if (file.isFile) {
                file.setLastModified(System.currentTimeMillis())
                return@withContext artworkUri(file)
            }
            exchange(client.newCall(Request.Builder().url(checked).build()), ensureCurrent) { response, guard ->
                cacheArtwork(response, file, guard)
            }
        }
    }

    /** Artwork streams directly into the host cache and never enters the script response buffer. */
    internal fun cacheArtwork(response: Response, file: java.io.File, ensureCurrent: () -> Unit): String {
        if (response.code in 300..399) throw SourceException(SourceErrorCode.NETWORK)
        if (!response.isSuccessful) throw SourceException(
            if (response.code in setOf(404, 410)) SourceErrorCode.NOT_FOUND else SourceErrorCode.NETWORK,
        )
        val body = response.body ?: invalidResponse()
        ensureCurrent()
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
        val temporary = java.io.File.createTempFile(".artwork-", ".tmp", file.parentFile)
        try {
            body.byteStream().use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(65_536)
                    while (true) {
                        ensureCurrent()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            ensureCurrent()
            java.nio.file.Files.move(temporary.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
        val cached = file.parentFile?.listFiles().orEmpty().filter { it.isFile && it.name.matches(Regex("[a-f0-9]{64}")) }
            .sortedBy { it.lastModified() }
        var bytesRemaining = cached.sumOf { it.length() }
        var countRemaining = cached.size
        cached.filter { it != file }.forEach { old ->
            if (bytesRemaining > 16L * 1024 * 1024 || countRemaining > 256) {
                val size = old.length()
                if (old.delete()) { bytesRemaining -= size; countRemaining-- }
            }
        }
        ensureCurrent()
        return artworkUri(file)
    }

    suspend fun request(request: JsonObject, ensureCurrent: () -> Unit = {}): JsonObject = concurrency.withPermit {
        ensureCurrent()
        val responseEncoding = responseEncoding(request)
        val url = validateUrl(request.get("url")?.asString ?: denied())
        val method = request.get("method")?.asString?.uppercase() ?: "GET"
        if (method !in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) denied()
        val headers = request.getAsJsonObject("headers")?.entrySet()?.associate { it.key to it.value.asString }.orEmpty()
        validateHeaders(headers)
        val encoding = request.get("bodyEncoding")?.takeUnless { it.isJsonNull }?.asString ?: "utf8"
        if (encoding !in setOf("utf8", "base64")) denied()
        val body = request.get("body")?.takeUnless { it.isJsonNull }?.asString?.let { value ->
            if (value.length > MAX_REQUEST_BYTES * 2) denied()
            if (encoding == "base64") runCatching { Base64.getDecoder().decode(value) }.getOrElse { denied() }
            else value.toByteArray(Charsets.UTF_8)
        }
        if ((body?.size ?: 0) > MAX_REQUEST_BYTES || method in setOf("GET", "HEAD") && body != null) denied()
        val builder = Request.Builder().url(url)
        headers.forEach(builder::header)
        val requestBody = if (method in setOf("POST", "PUT", "PATCH")) (body ?: byteArrayOf()).toRequestBody() else body?.toRequestBody()
        builder.method(method, requestBody)
        val sdkName = request.get("sdk")?.takeUnless { it.isJsonNull }?.asString
        val requestClient = if (sdkName == null) client else {
            if (sdkName !in nativeSdks) denied()
            val arguments = request.getAsJsonObject("sdkConfig")?.entrySet()?.associate {
                if (!it.value.isJsonPrimitive || !it.value.asJsonPrimitive.isString) denied()
                it.key to it.value.asString
            }.orEmpty()
            if (arguments.size > 16 || arguments.values.sumOf { it.length } > 65_536 ||
                arguments.values.any { '\u0000' in it }) denied()
            client.newBuilder()
                .addInterceptor(ScriptPlatformSdk.interceptor(sdkName, configuration(), arguments))
                .addInterceptor { chain ->
                    ensureCurrent()
                    validateUrl(chain.request().url.toString())
                    if ((chain.request().body?.contentLength() ?: 0) > MAX_REQUEST_BYTES) denied()
                    chain.proceed(chain.request()).let { response ->
                        response.body?.let { payload ->
                            val source = payload.source()
                            if (source.request(MAX_RESPONSE_BYTES + 1L)) { response.close(); invalidResponse() }
                        }
                        response
                    }
                }.build()
        }
        exchange(requestClient.newCall(builder.build()), ensureCurrent) { response, _ -> responsePayload(response, responseEncoding) }
    }

    /** Cancellation owns the socket until the response body has been consumed. */
    internal suspend fun <T> exchange(call: Call, ensureCurrent: () -> Unit, consume: (Response, () -> Unit) -> T): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(networkFailure(error))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val guard = {
                            if (!continuation.isActive) throw CancellationException("Source network request ended")
                            ensureCurrent()
                        }
                        val value = response.use { guard(); consume(it, guard).also { guard() } }
                        continuation.resume(value) { _, _, _ -> }
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (error is IOException && error !is SourceException) networkFailure(error) else error,
                        )
                    }
                }
            })
        }

    internal fun responseEncoding(request: JsonObject): String {
        val value = request.get("responseEncoding") ?: return "utf8"
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString || value.asString !in setOf("utf8", "base64")) denied()
        return value.asString
    }

    internal fun responsePayload(response: Response, encoding: String): JsonObject = transport {
        if (encoding !in setOf("utf8", "base64")) denied()
        if (response.code in 300..399) throw SourceException(SourceErrorCode.NETWORK)
        val responseBody = response.body
        if (responseBody != null && responseBody.contentLength() > MAX_RESPONSE_BYTES) invalidResponse()
        val source = responseBody?.source()
        if (source != null && source.request(MAX_RESPONSE_BYTES + 1L)) invalidResponse()
        val bytes = source?.readByteArray() ?: byteArrayOf()
        JsonObject().apply {
            addProperty("status", response.code)
            // Cookies from one response are not installed in a shared cookie jar.
            add("headers", JsonObject().apply { response.headers.names().forEach { name -> addProperty(name, response.header(name)) } })
            add("setCookies", com.google.gson.JsonArray().apply { response.headers.values("Set-Cookie").forEach(::add) })
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
        /** System DNS may return a local proxy's synthetic address; TLS still authenticates the declared hostname. */
        internal fun validateResolvedAddresses(addresses: List<InetAddress>) {
            if (addresses.isEmpty() || addresses.any { address ->
                    val bytes = address.address
                    val fakeIp = bytes.size == 4 && (bytes[0].toInt() and 255) == 198 &&
                        (bytes[1].toInt() and 255) in 18..19
                    !isPublicAddress(address) && !fakeIp
                }) throw SourceException(SourceErrorCode.NETWORK)
        }

        internal fun artworkFile(url: String, directory: java.io.File): java.io.File {
            val key = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return java.io.File(directory, "artwork/$key")
        }
        internal fun artworkUri(file: java.io.File): String = java.net.URI("file", "", file.absolutePath, null).toASCIIString()

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
        private fun invalidResponse(): Nothing = throw SourceException(SourceErrorCode.INVALID_RESPONSE, "Source response exceeded its size limit")
    }
}
