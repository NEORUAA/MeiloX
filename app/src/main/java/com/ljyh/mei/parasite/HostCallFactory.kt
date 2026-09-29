package com.ljyh.mei.parasite

import com.google.gson.JsonParser
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.AsyncTimeout
import okio.Buffer
import okio.ForwardingSource
import okio.Timeout
import okio.buffer

/** Retrofit compatibility only: this factory never opens a module-owned network connection. */
@Singleton
class HostCallFactory internal constructor(
    private val bridge: HostRequestBridge,
    private val executor: Executor,
) : Call.Factory {
    @Inject constructor(bridge: HostRequestBridge) : this(bridge, workers)

    override fun newCall(request: Request): Call {
        val url = request.url
        require(url.scheme == "https" && url.host in HOSTS && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) {
            "Unsupported official business origin"
        }
        require(request.method in setOf("GET", "POST")) { "Unsupported official business method" }
        require(request.header("Cookie") == null && request.header("Authorization") == null) {
            "Authentication headers belong to the host"
        }
        val prefix = listOf("/api/", "/weapi/", "/eapi/").firstOrNull(url.encodedPath::startsWith)
        requireNotNull(prefix) { "Expected an official business path" }
        val path = url.encodedPath.removePrefix(prefix).trimEnd('/')
        val parameters = linkedMapOf<String, String>()
        for (index in 0 until url.querySize) {
            val key = url.queryParameterName(index)
            require(!parameters.containsKey(key)) { "Duplicate official business parameter" }
            parameters[key] = url.queryParameterValue(index).orEmpty()
        }
        request.body?.let { body ->
            require(!body.isDuplex() && !body.isOneShot()) { "Streaming uploads require the official upload adapter" }
            val contentType = body.contentType()
            require(body.contentLength() == 0L || (contentType?.type == "application" && contentType.subtype == "json")) {
                "Expected a JSON business body"
            }
            val buffer = Buffer()
            body.writeTo(buffer)
            if (buffer.size > 0) {
                val json = JsonParser.parseString(buffer.readUtf8())
                require(json.isJsonObject) { "Expected a JSON business object" }
                json.asJsonObject.entrySet().forEach { (key, value) ->
                    require(!parameters.containsKey(key)) { "Duplicate official business parameter" }
                    parameters[key] = if (value.isJsonPrimitive) value.asString else value.toString()
                }
            }
        }
        return HostCall(request, bridge.newCall(path, parameters))
    }

    private inner class HostCall(
        private val original: Request,
        private val hostCall: HostRequestBridge.Call,
    ) : Call {
        private val executed = AtomicBoolean()
        private val completed = AtomicBoolean()
        private val cancellationReported = AtomicBoolean()
        private val listeners = CopyOnWriteArrayList<EventListener>()
        private val tags = ConcurrentHashMap<KClass<*>, Any>()
        private val callTimeout = object : AsyncTimeout() {
            override fun timedOut() { this@HostCall.cancel() }
        }.apply { timeout(30, TimeUnit.SECONDS) }

        override fun request(): Request = original
        override fun timeout(): Timeout = callTimeout
        override fun isExecuted(): Boolean = executed.get()
        override fun isCanceled(): Boolean = hostCall.isCanceled
        override fun cancel() {
            hostCall.cancel()
            if (cancellationReported.compareAndSet(false, true)) emit { canceled(this@HostCall) }
        }
        override fun clone(): Call = HostCall(original, hostCall.copy())
        override fun addEventListener(eventListener: EventListener) { listeners += eventListener }
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> tag(type: KClass<T>): T? = (tags[type] as? T) ?: original.tag(type)
        @Suppress("UNCHECKED_CAST")
        override fun <T> tag(type: Class<out T>): T? = (tags[type.kotlin] as? T) ?: original.tag(type)
        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T {
            tag(type)?.let { return it }
            val value = computeIfAbsent()
            @Suppress("UNCHECKED_CAST")
            return (tags.putIfAbsent(type, value) as? T) ?: value
        }
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = tag(type.kotlin, computeIfAbsent)

        override fun execute(): Response {
            claim()
            return exchange()
        }

        override fun enqueue(responseCallback: Callback) {
            claim()
            val started = AtomicBoolean()
            try {
                executor.execute {
                    started.set(true)
                    val response = try { exchange() } catch (error: IOException) {
                        responseCallback.onFailure(this, error)
                        return@execute
                    }
                    try {
                        hostCall.requireCurrent()
                    } catch (error: IOException) {
                        failed(error)
                        response.close()
                        responseCallback.onFailure(this, error)
                        return@execute
                    }
                    // Callback failures must not cause a second terminal callback.
                    responseCallback.onResponse(this, response)
                }
            } catch (error: RejectedExecutionException) {
                if (started.get()) throw error
                val failure = IOException("Official request queue is full")
                failed(failure)
                responseCallback.onFailure(this, failure)
            }
        }

        private fun claim() {
            check(executed.compareAndSet(false, true)) { "Request has already been executed" }
            emit { callStart(this@HostCall) }
        }

        private fun exchange(): Response {
            callTimeout.enter()
            var response: Response? = null
            var failure: IOException? = null
            try {
                callTimeout.throwIfReached()
                val result = hostCall.execute()
                response = Response.Builder()
                    .request(original.newBuilder().tag(HostSessionStamp::class.java, result.session).build())
                    .protocol(Protocol.HTTP_1_1)
                    // The host returns JSON, not HTTP metadata. This is a synthetic Retrofit envelope.
                    .code(200)
                    .message("Official host JSON")
                    .header("X-MeiloX-Transport", "official-json")
                    .body(guardedBody(result.body))
                    .build()
            } catch (error: IOException) {
                failure = error
            } finally {
                if (callTimeout.exit()) {
                    failure = InterruptedIOException("Official request timed out")
                    failed(failure)
                    response?.close()
                }
            }
            failure?.let { failed(it); throw it }
            return requireNotNull(response)
        }

        private fun guardedBody(json: String): ResponseBody {
            val buffer = Buffer().writeUtf8(json)
            val size = buffer.size
            val source = object : ForwardingSource(buffer) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    try {
                        hostCall.requireCurrent()
                        return super.read(sink, byteCount).also {
                            hostCall.requireCurrent()
                            if (it == -1L) finished()
                        }
                    } catch (error: IOException) { failed(error); throw error }
                }
                override fun close() { super.close(); finished() }
            }.buffer()
            return object : ResponseBody() {
                override fun contentType() = JSON
                override fun contentLength() = size
                override fun source() = source
            }
        }

        private fun finished() {
            if (completed.compareAndSet(false, true)) emit { callEnd(this@HostCall) }
        }

        private fun failed(error: IOException) {
            if (completed.compareAndSet(false, true)) emit { callFailed(this@HostCall, error) }
        }

        private fun emit(event: EventListener.() -> Unit) {
            listeners.forEach { runCatching { it.event() } }
        }
    }

    companion object {
        private val HOSTS = setOf("music.163.com", "interface.music.163.com", "interface3.music.163.com")
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val workers = ThreadPoolExecutor(
            4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(128),
            { task -> Thread(task, "MeiloX-host-request").apply { isDaemon = true } },
        ).apply { allowCoreThreadTimeOut(true) }
    }
}
