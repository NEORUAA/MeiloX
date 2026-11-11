package com.ljyh.mei.source.script

import com.google.gson.JsonObject
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.api.SourceResponseSizeException
import java.net.IDN
import java.net.InetAddress
import java.io.InterruptedIOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
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
    init { domains.forEach(::validateGrant) }

    // Slow cover downloads must not consume the API slots used by browsing and playback.
    private val apiConcurrency = Semaphore(API_CONCURRENCY)
    private val artworkConcurrency = Semaphore(ARTWORK_CONCURRENCY)
    private val artworkDispatcher = Dispatcher().apply {
        maxRequests = ARTWORK_CONCURRENCY
        maxRequestsPerHost = ARTWORK_CONCURRENCY
    }
    private val audioDispatcher = Dispatcher().apply {
        maxRequests = AUDIO_CONCURRENCY
        maxRequestsPerHost = AUDIO_CONCURRENCY
    }
    val client: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            maxRequests = API_CONCURRENCY
            maxRequestsPerHost = API_CONCURRENCY
        })
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true).retryOnConnectionFailure(false)
        .proxy(java.net.Proxy.NO_PROXY)
        .dns(Dns { host ->
            Dns.SYSTEM.lookup(host).also(::validateResolvedAddresses)
        })
        .addInterceptor { chain ->
            validateUrl(chain.request().url.toString())
            chain.proceed(chain.request())
        }.build()

    // Wrappers share the owning policy's connection pool, but contain no account or request state.
    // Keeping the base identity also makes replacement clients take effect immediately.
    private var derivedClients: DerivedClients? = null
    private data class DerivedClients(val base: OkHttpClient, val artwork: OkHttpClient,
        val audio: OkHttpClient, val sdk: OkHttpClient)
    private class SdkCall(val interceptor: Interceptor, val ensureCurrent: () -> Unit)
    private class NetworkCancellation(val cancellation: CancellationException) : IOException("Source request cancelled")

    private fun transports(): DerivedClients = synchronized(this) {
        val base = client
        derivedClients?.takeIf { it.base === base } ?: DerivedClients(
            base = base,
            artwork = base.newBuilder().dispatcher(artworkDispatcher).build(),
            // Progressive audio has no whole-transfer deadline; read/connect stalls remain bounded.
            audio = base.newBuilder().dispatcher(audioDispatcher).callTimeout(0, TimeUnit.MILLISECONDS).build(),
            sdk = base.newBuilder().addInterceptor { chain ->
                val request = chain.request().tag(SdkCall::class.java) ?: denied()
                sdkGuard {
                    request.ensureCurrent()
                    request.interceptor.intercept(chain)
                }
            }.addInterceptor { chain ->
                val request = chain.request().tag(SdkCall::class.java) ?: denied()
                sdkGuard {
                    request.ensureCurrent()
                    validateUrl(chain.request().url.toString())
                    if ((chain.request().body?.contentLength() ?: 0) > MAX_REQUEST_BYTES) denied()
                    val response = chain.proceed(chain.request())
                    try {
                        response.body?.let { payload ->
                            val source = payload.source()
                            if (source.request(MAX_RESPONSE_BYTES + 1L)) throw SourceResponseSizeException()
                        }
                        response
                    } catch (error: Throwable) {
                        response.close()
                        throw error
                    }
                }
            }.build(),
        ).also { derivedClients = it }
    }

    internal val audioClient: OkHttpClient get() = transports().audio

    fun validateUrl(value: String): HttpUrl = validateTransportUrl(value, requireGrantedHost = true)

    /** Redirect targets may move to the canonical host selected by the service. */
    internal fun validateRedirectUrl(value: String): HttpUrl = validateTransportUrl(value, requireGrantedHost = false)

    private fun validateTransportUrl(value: String, requireGrantedHost: Boolean): HttpUrl {
        if (value.length > 8192) denied()
        val url = value.toHttpUrlOrNull() ?: denied()
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) denied()
        if (requireGrantedHost) {
            if (url.scheme != "https" || url.port != 443) denied()
            validateHost(url.host)
        } else if (url.scheme !in setOf("http", "https")) denied()
        return url
    }

    fun validateHeaders(headers: Map<String, String>) {
        if (headers.size > 32 || headers.entries.sumOf { it.key.length + it.value.length } > 16_384) denied()
        headers.forEach { (key, value) ->
            if (!Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+").matches(key) || value.any { it == '\r' || it == '\n' || it == '\u0000' } ||
                key.lowercase() in setOf("host", "connection", "content-length", "transfer-encoding", "proxy-authorization", "proxy-connection")) denied()
        }
    }

    suspend fun artwork(url: String, directory: java.io.File, ensureCurrent: () -> Unit = {}): String = networkDeadline(ensureCurrent) {
        ensureCurrent()
        val checked = validateUrl(url)
        val file = artworkFile(url, directory)
        withContext(Dispatchers.IO) {
            fun cached(): String? {
                ensureCurrent()
                if (!file.isFile) return null
                file.setLastModified(System.currentTimeMillis())
                ensureCurrent()
                return artworkUri(file)
            }
            cached()?.let { return@withContext it }
            artworkConcurrency.withPermit {
                cached()?.let { return@withPermit it }
                exchange(transports().artwork.newCall(Request.Builder().url(checked).build()), ensureCurrent) { response, guard ->
                    cacheArtwork(response, file, guard)
                }
            }
        }
    }

    /** Artwork streams directly into the host cache and never enters the script response buffer. */
    internal fun cacheArtwork(response: Response, file: java.io.File, ensureCurrent: () -> Unit): String {
        if (!response.isSuccessful) throw SourceException(
            if (response.code in setOf(404, 410)) SourceErrorCode.NOT_FOUND else SourceErrorCode.NETWORK,
        )
        val body = response.body ?: invalidResponse()
        ensureCurrent()
        val parent = file.parentFile ?: invalidResponse()
        java.nio.file.Files.createDirectories(parent.toPath())
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
            if (bytesRemaining > MAX_ARTWORK_CACHE_BYTES || countRemaining > 256) {
                val size = old.length()
                if (old.delete()) { bytesRemaining -= size; countRemaining-- }
            }
        }
        ensureCurrent()
        return artworkUri(file)
    }

    suspend fun request(request: JsonObject, ensureCurrent: () -> Unit = {}): JsonObject = networkDeadline(ensureCurrent) {
        apiConcurrency.withPermit { performRequest(request, ensureCurrent) }
    }

    private suspend fun performRequest(request: JsonObject, ensureCurrent: () -> Unit): JsonObject {
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
            val codec = ScriptPlatformSdk.interceptor(sdkName, configuration().toMap(), arguments.toMap())
            builder.tag(SdkCall::class.java, SdkCall(codec, ensureCurrent))
            transports().sdk
        }
        return exchange(requestClient.newCall(builder.build()), ensureCurrent) { response, _ -> responsePayload(response, responseEncoding) }
    }

    /** The deadline includes admission, OkHttp dispatch, SDK work and consuming the body. */
    private suspend fun <T> networkDeadline(ensureCurrent: () -> Unit, block: suspend () -> T): T = try {
        withTimeout(REQUEST_TIMEOUT_MS) { block() }
    } catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        ensureCurrent()
        throw SourceException(SourceErrorCode.TIMEOUT)
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
        val responseBody = response.body
        if (responseBody != null && responseBody.contentLength() > MAX_RESPONSE_BYTES) throw SourceResponseSizeException()
        val source = responseBody?.source()
        if (source != null && source.request(MAX_RESPONSE_BYTES + 1L)) throw SourceResponseSizeException()
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

    private fun networkFailure(error: IOException): Throwable = when (error) {
        is NetworkCancellation -> error.cancellation
        is SourceException -> error
        else -> SourceException(if (error is InterruptedIOException) SourceErrorCode.TIMEOUT else SourceErrorCode.NETWORK)
    }

    /** OkHttp dispatchers accept IO failures; unchecked codec/guard failures must return to the caller. */
    private inline fun <T> sdkGuard(block: () -> T): T = try { block()
    } catch (cancelled: CancellationException) { throw NetworkCancellation(cancelled)
    } catch (error: IOException) { throw error
    } catch (_: Exception) { throw SourceException(SourceErrorCode.INVALID_RESPONSE) }

    private fun validateHost(host: String) {
        if (!isHostAllowed(host, domains)) denied()
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
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        internal const val MAX_ARTWORK_CACHE_BYTES = 128L * 1024 * 1024
        internal const val API_CONCURRENCY = 4
        internal const val ARTWORK_CONCURRENCY = 2
        internal const val AUDIO_CONCURRENCY = 2
        internal const val REQUEST_TIMEOUT_MS = 15_000L

        /** A wildcard grants subdomains only and cannot target a public suffix. */
        fun validateGrant(grant: String) {
            if (grant.startsWith("*.")) {
                if (grant.length > 253) denied()
                val suffix = grant.removePrefix("*.")
                validateDomain(suffix)
                if ("https://$suffix".toHttpUrlOrNull()?.topPrivateDomain() == null) denied()
            } else validateDomain(grant)
        }

        /** Callers validate grants once when accepting the manifest or web recipe. */
        internal fun isHostAllowed(host: String, grants: Collection<String>): Boolean {
            if (runCatching { validateDomain(host) }.isFailure) return false
            return grants.any { grant ->
                host == grant || grant.startsWith("*.") && host.endsWith(grant.removePrefix("*"))
            }
        }

        /** Web pages may request a narrower grant, never broaden their owner's authority. */
        internal fun isGrantCovered(grant: String, grants: Collection<String>): Boolean {
            if (runCatching { validateGrant(grant) }.isFailure) return false
            if (!grant.startsWith("*.")) return isHostAllowed(grant, grants)
            val suffix = grant.removePrefix("*.")
            return grants.any { approved ->
                approved.startsWith("*.") && (grant == approved || suffix.endsWith(approved.removePrefix("*")))
            }
        }

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
