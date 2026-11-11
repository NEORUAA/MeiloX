package com.ljyh.mei.source.platform

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.google.gson.JsonObject
import com.ljyh.mei.source.script.ScriptNetworkPolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okio.BufferedSink
import java.util.UUID
import java.io.RandomAccessFile
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.resume

/** Chromium never opens sockets. Both resource loads and the fixed network shim use this path. */
internal class SourceWebTransport(
    private val page: SourceWebRequest,
    private val cookies: CookieManager,
    client: OkHttpClient? = null,
) : AutoCloseable {
    private val policy = ScriptNetworkPolicy(page.recipe.domains.toList())
    private val networkClient = client ?: policy.client
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val slots = Semaphore(2)
    private val closed = AtomicBoolean(false)
    private data class SelectedFile(val snapshot: CloudUploadFile, val origin: String, val createdAt: Long)
    private val selectedFiles = ConcurrentHashMap<String, SelectedFile>()
    private val fileSelections = Mutex()
    @Volatile private var chooserOrigin: Pair<String, Long>? = null

    fun prepareFileChooser(origin: String) {
        ensureCurrent()
        chooserOrigin = originOf(page.recipe.checkedPageUrl(origin)) to System.nanoTime()
    }

    fun fileChooserOrigin(fallback: String): String {
        ensureCurrent()
        val pending = chooserOrigin
        chooserOrigin = null
        return pending?.takeIf { System.nanoTime() - it.second in 0..2_000_000_000L }?.first
            ?: originOf(page.recipe.checkedPageUrl(fallback))
    }

    suspend fun registerSelectedFiles(context: Context, uris: List<Uri>, origin: String) = fileSelections.withLock {
        page.scope.ensureCurrent()
        expireFiles()
        if (uris.isEmpty() || uris.size > 8 || selectedFiles.size + uris.size > 8 ||
            uris.any { it.scheme != "content" }) denied()
        val owner = originOf(page.recipe.checkedPageUrl(origin))
        val snapshots = mutableListOf<CloudUploadFile>()
        try {
            var remaining = MAX_UPLOAD_BYTES - selectedFiles.values.sumOf { it.snapshot.size }
            for (uri in uris) {
                if (remaining <= 0) denied()
                val file = prepareCloudUploadFile(context, uri, remaining) {
                    if (!page.isActive) throw CancellationException("Web file selection owner changed")
                }
                snapshots += file
                remaining -= file.size
                page.scope.ensureCurrent()
            }
            synchronized(selectedFiles) {
                ensureCurrent()
                snapshots.forEach { file ->
                    val same = selectedFiles.values.any { it.origin == owner && it.snapshot.filename == file.filename &&
                        it.snapshot.size == file.size && it.snapshot.md5 == file.md5 }
                    if (same) file.close()
                    else selectedFiles[UUID.randomUUID().toString()] = SelectedFile(file, owner, System.currentTimeMillis())
                }
                snapshots.clear()
            }
        } finally { snapshots.forEach(CloudUploadFile::close) }
    }

    private fun expireFiles() {
        selectedFiles.entries.removeIf { entry ->
            if (System.currentTimeMillis() - entry.value.createdAt > 600_000L) { entry.value.snapshot.close(); true } else false
        }
    }

    private fun resolveSelectedFile(parameters: JsonObject, origin: String): JsonObject {
        ensureCurrent()
        expireFiles()
        val name = parameters.string("name")
        val size = parameters.get("size")?.asLong ?: denied()
        val matches = selectedFiles.entries.filter { it.value.origin == originOf(origin) &&
            it.value.snapshot.filename == name && it.value.snapshot.size == size }
        val match = matches.singleOrNull() ?: denied()
        return JsonObject().apply { addProperty("handle", match.key); addProperty("size", size) }
    }

    private fun selectedBody(parameters: JsonObject, origin: String): RequestBody {
        ensureCurrent()
        expireFiles()
        if (parameters.keySet().any { it !in setOf("handle", "offset", "length", "mimeType") }) denied()
        val selected = selectedFiles[parameters.string("handle")]?.takeIf { it.origin == originOf(origin) } ?: denied()
        val offset = parameters.get("offset")?.let { runCatching { it.asBigDecimal.longValueExact() }.getOrElse { denied() } } ?: 0L
        val length = parameters.get("length")?.let { runCatching { it.asBigDecimal.longValueExact() }.getOrElse { denied() } } ?: selected.snapshot.size
        if (offset < 0 || length < 0 || offset > selected.snapshot.size || length > selected.snapshot.size - offset) denied()
        val type = parameters.optionalString("mimeType", 200)?.toMediaTypeOrNull()
            ?: selected.snapshot.mimeType.toMediaTypeOrNull()
        return object : RequestBody() {
            override fun contentType() = type
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) {
                ensureCurrent()
                RandomAccessFile(selected.snapshot.file, "r").use { file ->
                    file.seek(offset)
                    var remaining = length
                    val buffer = ByteArray(65_536)
                    while (remaining > 0) {
                        ensureCurrent()
                        val read = file.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) throw java.io.IOException("Selected file changed")
                        sink.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
        }
    }

    suspend fun installCookies() {
        for ((host, header) in page.owner.cookieHeaders) {
            ScriptNetworkPolicy.validateDomain(host)
            if (!ScriptNetworkPolicy.isHostAllowed(host, page.recipe.domains) ||
                header.length > 16_384 || header.any { it == '\r' || it == '\n' || it == '\u0000' }) denied()
            page.scope.ensureCurrent()
            for (part in header.split(';')) {
                val name = part.substringBefore('=').trim()
                val value = part.substringAfter('=', "").trim()
                if (!Regex("[A-Za-z0-9_.-]{1,80}").matches(name) || value.length > 8192 || value.contains(';')) denied()
                setCookie("https://$host/", "$name=$value; Path=/; Secure")
            }
        }
        cookies.flush()
    }

    suspend fun selectedCookies(): JsonObject {
        page.scope.ensureCurrent()
        val selected = linkedMapOf<String, String>()
        for (host in page.recipe.cookieHosts) {
            val header = cookies.getCookie("https://$host/").orEmpty()
            for (part in header.split(';')) {
                val name = part.substringBefore('=').trim()
                val value = part.substringAfter('=', "").trim()
                if (name in page.recipe.cookieNames && value.isNotEmpty() && value.length <= 8192) {
                    val existing = selected.putIfAbsent(name, value)
                    if (existing != null && existing != value) denied()
                }
            }
        }
        val header = selected.entries.joinToString("; ") { "${it.key}=${it.value}" }
        if (header.length > 16_384) denied()
        return JsonObject().apply {
            add("cookies", JsonObject().apply { selected.forEach { (name, value) -> addProperty(name, value) } })
            addProperty("cookie", header)
            addProperty("closed", true)
        }
    }

    fun intercept(request: WebResourceRequest, navigate: (String) -> Unit): WebResourceResponse { return try {
        if (request.method !in setOf("GET", "HEAD")) return blocked()
        val referer = request.requestHeaders.entries.firstOrNull { it.key.equals("Referer", true) }?.value
        val response = exchange(request.url.toString(), request.method, request.requestHeaders, null, MAX_RESOURCE_BYTES,
            referer = referer)
        if (request.isForMainFrame && response.url.substringBefore('#') != request.url.toString().substringBefore('#')) {
            navigate(page.recipe.checkedRedirectUrl(response.url))
            return WebResourceResponse("text/plain", "UTF-8", 204, "No Content", emptyMap(), ByteArrayInputStream(byteArrayOf()))
        }
        val headers = response.headers.toMutableMap()
        // Network APIs are supplied by the host shim. Frames receive the same document-start guard.
        val fence = "connect-src 'none'; worker-src 'none'; object-src 'none'; form-action 'none'"
        val old = headers.entries.firstOrNull { it.key.equals("Content-Security-Policy", true) }
        old?.let { headers.remove(it.key) }
        headers["Content-Security-Policy"] = listOfNotNull(old?.value, fence).joinToString(", ")
        WebResourceResponse(response.mime, response.charset, response.code, response.reason, headers, ByteArrayInputStream(response.bytes))
    } catch (_: Exception) { blocked() } }

    suspend fun fetch(parameters: JsonObject, origin: String): JsonObject = withContext(Dispatchers.IO) {
        page.scope.ensureCurrent()
        parameters.get("resolveFile")?.let { return@withContext resolveSelectedFile(it.asJsonObject, origin) }
        val url = page.recipe.checkedUrl(parameters.string("url"), origin)
        val method = parameters.optionalString("method", 10)?.uppercase() ?: "GET"
        if (method !in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")) denied()
        val headers = parameters.get("headers")?.let {
            if (!it.isJsonObject || it.asJsonObject.size() > 32) denied()
            it.asJsonObject.entrySet().associate { (key, value) ->
                if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString || value.asString.length > 8192) denied()
                key to value.asString
            }
        }.orEmpty()
        val body = sourceWebRequestBody(parameters, headers) { selectedBody(it, origin) }
        if (method in setOf("GET", "HEAD") && body != null) denied()
        val fileUpload = parameters.has("file") || parameters.getAsJsonArray("multipart")?.any {
            it.isJsonObject && it.asJsonObject.has("file")
        } == true
        val effectiveHeaders = headers.toMutableMap()
        if (parameters.has("multipart")) {
            effectiveHeaders.keys.filter { it.equals("Content-Type", true) }.forEach(effectiveHeaders::remove)
            body?.contentType()?.let { effectiveHeaders["Content-Type"] = it.toString() }
        } else if (effectiveHeaders.keys.none { it.equals("Content-Type", true) }) {
            body?.contentType()?.let { effectiveHeaders["Content-Type"] = it.toString() }
        }
        // A page may use its own origins, but cannot read another allowed origin through this shim.
        val credentials = parameters.optionalString("credentials", 16) ?: "same-origin"
        if (credentials !in setOf("omit", "same-origin", "include")) denied()
        if (originOf(url) != originOf(origin)) {
            val unusualHeaders = effectiveHeaders.keys.map { it.lowercase() }.filter { it !in setOf("accept", "accept-language", "content-language", "content-type") }
            val contentType = effectiveHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.substringBefore(';')?.trim()?.lowercase()
            if (method !in setOf("GET", "HEAD", "POST") || unusualHeaders.isNotEmpty() || contentType != null && contentType !in setOf("application/x-www-form-urlencoded", "multipart/form-data", "text/plain")) {
                val requestedHeaders = effectiveHeaders.keys.map { it.lowercase() }.sorted()
                val preflight = exchange(url, "OPTIONS", mapOf("Access-Control-Request-Method" to method,
                    "Access-Control-Request-Headers" to requestedHeaders.joinToString(", ")), null, 16_384, origin, "omit")
                fun header(name: String) = preflight.headers.entries.firstOrNull { it.key.equals(name, true) }?.value.orEmpty()
                if (method !in header("Access-Control-Allow-Methods").split(',').map { it.trim().uppercase() }) denied()
                val allowedHeaders = header("Access-Control-Allow-Headers").split(',').map { it.trim().lowercase() }
                if (requestedHeaders.any { it !in allowedHeaders } && !(credentials != "include" && "*" in allowedHeaders)) denied()
            }
        }
        val response = exchange(url, method, effectiveHeaders, body, MAX_FETCH_BYTES, origin, credentials, fileUpload,
            referer = originOf(origin) + "/")
        page.scope.ensureCurrent()
        JsonObject().apply {
            addProperty("url", response.url)
            addProperty("status", response.code)
            addProperty("statusText", response.reason)
            add("headers", JsonObject().apply { response.headers.filterKeys { !it.equals("Set-Cookie", true) }.forEach { (name, value) -> addProperty(name, value) } })
            addProperty("body", Base64.getEncoder().encodeToString(response.bytes))
        }
    }

    private fun exchange(
        initialUrl: String,
        method: String,
        headers: Map<String, String>,
        body: RequestBody?,
        maximum: Int,
        origin: String? = null,
        credentials: String = "include",
        fileUpload: Boolean = false,
        referer: String? = null,
    ): WebReply {
        slots.acquire()
        try {
            ensureCurrent()
            var target = page.recipe.checkedUrl(initialUrl)
            var currentMethod = method
            var currentBody = body
            var currentReferer = referer?.let { runCatching { page.recipe.checkedRedirectUrl(it) }.getOrNull() }
            var initialHop = true
            while (true) {
                ensureCurrent()
                val checked = if (initialHop) policy.validateUrl(target.substringBefore('#'))
                else policy.validateRedirectUrl(target.substringBefore('#'))
                val sameOrigin = origin == null || originOf(origin) == originOf(target)
                val useCookies = credentials == "include" || credentials == "same-origin" && sameOrigin
                val builder = Request.Builder().url(checked)
                val safeHeaders = headers.filterKeys { it.lowercase() !in FORBIDDEN_HEADERS }
                policy.validateHeaders(safeHeaders)
                safeHeaders.forEach(builder::header)
                if (origin != null) builder.header("Origin", originOf(origin))
                currentReferer?.let { builder.header("Referer", it) }
                if (useCookies) cookies.getCookie(target)?.takeIf { it.length <= 16_384 }?.let { builder.header("Cookie", it) }
                val contentType = safeHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.toMediaTypeOrNull()
                val requestBody = if (currentMethod in setOf("POST", "PUT", "PATCH")) currentBody ?: byteArrayOf().toRequestBody(contentType)
                    else currentBody
                builder.method(currentMethod, requestBody)
                val client = if (fileUpload) networkClient.newBuilder()
                    .callTimeout(10, java.util.concurrent.TimeUnit.MINUTES).writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
                    else networkClient
                val call = client.newCall(builder.build())
                calls.add(call)
                try {
                    ensureCurrent()
                    call.execute().use { response ->
                        ensureCurrent()
                        if (origin != null && !sameOrigin) {
                            val allowed = response.header("Access-Control-Allow-Origin")
                            if (allowed != originOf(origin) && !(allowed == "*" && credentials != "include") ||
                                credentials == "include" && response.header("Access-Control-Allow-Credentials") != "true") denied()
                        }
                        if (useCookies) response.headers.values("Set-Cookie").take(64).forEach { value ->
                            if (value.length > 8192) denied()
                            // Browsers ignore rejected server cookies without failing the page load.
                            runBlocking { setCookie(target, value, requireAccepted = false) }
                        }
                        if (response.code in 300..399) {
                            val next = page.recipe.checkedRedirectUrl(response.header("Location") ?: denied(), target)
                            initialHop = false
                            val becomesGet = response.code == 303 || response.code in setOf(301, 302) && currentMethod == "POST"
                            currentReferer = currentReferer ?: target
                            target = next
                            if (becomesGet) {
                                currentMethod = "GET"
                                currentBody = null
                            }
                            return@use
                        }
                        val stream = response.body?.byteStream()
                        val bytes = ByteArrayOutputStream()
                        if ((response.body?.contentLength() ?: 0) > maximum) denied()
                        stream?.use {
                            val chunk = ByteArray(16 * 1024)
                            while (true) {
                                ensureCurrent()
                                val count = it.read(chunk)
                                if (count < 0) break
                                if (bytes.size() + count > maximum) denied()
                                bytes.write(chunk, 0, count)
                            }
                        }
                        ensureCurrent()
                        val type = response.body?.contentType()
                        return WebReply(target, response.code, response.message.ifBlank { "Response" },
                            response.headers.names().filter { !it.equals("Set-Cookie", true) && !it.equals("Content-Encoding", true) && !it.equals("Content-Length", true) }
                                .associateWith { response.headers.values(it).joinToString(", ") },
                            type?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream", type?.charset()?.name() ?: "UTF-8", bytes.toByteArray())
                    }
                } finally { calls.remove(call) }
            }
            denied()
        } finally { slots.release() }
    }

    private suspend fun setCookie(url: String, value: String, requireAccepted: Boolean = true) = withContext(Dispatchers.Main.immediate) {
        page.scope.ensureCurrent()
        if (closed.get() || !page.isActive) denied()
        suspendCancellableCoroutine<Unit> { continuation ->
            cookies.setCookie(url, value) { accepted ->
                if (continuation.isActive) {
                    if (accepted || !requireAccepted) continuation.resume(Unit) else continuation.cancel()
                }
            }
        }
    }

    private fun ensureCurrent() {
        if (closed.get() || !page.isActive) denied()
        runBlocking { page.scope.ensureCurrent() }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            calls.forEach(Call::cancel)
            synchronized(selectedFiles) {
                selectedFiles.values.forEach { it.snapshot.close() }
                selectedFiles.clear()
            }
            chooserOrigin = null
            networkClient.dispatcher.executorService.shutdown()
            networkClient.connectionPool.evictAll()
        }
    }

    private class WebReply(val url: String, val code: Int, val reason: String, val headers: Map<String, String>, val mime: String, val charset: String, val bytes: ByteArray)

    companion object {
        private const val MAX_UPLOAD_BYTES = 1_073_741_824L
        private const val MAX_RESOURCE_BYTES = 4 * 1024 * 1024
        private const val MAX_FETCH_BYTES = 1024 * 1024
        private val FORBIDDEN_HEADERS = setOf("host", "cookie", "origin", "referer", "connection", "content-length", "transfer-encoding", "proxy-authorization", "proxy-connection", "authorization", "accept-encoding")
        fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
        private fun originOf(value: String): String = URI(value).let { "${it.scheme}://${it.host}" }
    }
}

/** Multipart structure is bounded before a selected file is opened; file payloads remain streams. */
internal fun sourceWebRequestBody(parameters: JsonObject, headers: Map<String, String>, selectedFile: (JsonObject) -> RequestBody): RequestBody? {
    if (listOf("body", "file", "multipart").count { parameters.has(it) && !parameters.get(it).isJsonNull } > 1) denied()
    val type = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.toMediaTypeOrNull()
    parameters.get("file")?.takeUnless { it.isJsonNull }?.let {
        if (!it.isJsonObject) denied()
        return selectedFile(it.asJsonObject).also { body -> if (body.contentLength() !in 0L..1_073_741_824L) denied() }
    }
    parameters.get("multipart")?.takeUnless { it.isJsonNull }?.let { value ->
        if (!value.isJsonArray || value.asJsonArray.size() > 128) denied()
        if (value.asJsonArray.size() == 0) {
            val boundary = UUID.randomUUID().toString()
            return "--$boundary--\r\n".toByteArray().toRequestBody("multipart/form-data; boundary=$boundary".toMediaTypeOrNull())
        }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
        var inlineBytes = 0
        var selectedBytes = 0L
        value.asJsonArray.forEach { element ->
            if (!element.isJsonObject) denied()
            val part = element.asJsonObject
            val name = part.optionalString("name", 2048) ?: denied()
            inlineBytes += name.toByteArray().size
            if (part.has("file") || part.has("bytes")) {
                if (part.has("file") && part.has("bytes")) denied()
                val filename = part.optionalString("filename", 1000) ?: "blob"
                val body = if (part.has("file")) {
                    val file = part.get("file")?.takeIf { it.isJsonObject }?.asJsonObject ?: denied()
                    selectedFile(file).also { body ->
                        val length = body.contentLength()
                        if (length < 0 || length > 1_073_741_824L - selectedBytes) denied()
                        selectedBytes += length
                    }
                } else {
                    val encoded = part.optionalString("bytes", 350_000) ?: denied()
                    val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrElse { denied() }
                    inlineBytes += bytes.size
                    bytes.toRequestBody(part.optionalString("mimeType", 200)?.toMediaTypeOrNull())
                }
                multipart.addFormDataPart(name, filename, body)
            } else {
                val text = part.optionalString("value", 262_144) ?: denied()
                inlineBytes += text.toByteArray().size
                multipart.addFormDataPart(name, text)
            }
            if (inlineBytes > 262_144) denied()
        }
        return multipart.build()
    }
    val encoded = parameters.optionalString("body", 350_000) ?: return null
    val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrElse { denied() }
    if (bytes.size > 262_144) denied()
    return bytes.toRequestBody(type)
}
