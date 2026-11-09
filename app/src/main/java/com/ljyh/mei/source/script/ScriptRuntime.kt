package com.ljyh.mei.source.script

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.EvaluationFailedException
import androidx.javascriptengine.EvaluationResultSizeLimitExceededException
import androidx.javascriptengine.MemoryLimitExceededException
import androidx.javascriptengine.SandboxUnsupportedException
import androidx.javascriptengine.TerminationInfo
import com.google.common.util.concurrent.ListenableFuture
import androidx.javascriptengine.JavaScriptSandbox
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.api.SourceResponseSizeException
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ScriptInvocation(val result: JsonElement, val storage: Map<String, String>? = null)

/** No app objects or JavaScript interfaces are bound into this isolated V8 process. */
@Singleton
class ScriptRuntime @Inject constructor(@ApplicationContext private val context: Context) {
    internal val catalogCache = SourceCatalogCache()
    // Android permits a single sandbox process. A fresh process per invocation also bounds
    // malicious global state and lets cancellation terminate CPU loops unconditionally.
    val supported: Boolean get() = JavaScriptSandbox.isSupported()

    suspend fun invoke(
        script: String,
        manifest: PluginManifest,
        operation: String,
        params: JsonObject,
        configuration: Map<String, String>,
        storage: Map<String, String>,
        network: ScriptNetworkPolicy,
        ensureCurrent: () -> Unit = {},
    ): ScriptInvocation = invoke(script, manifest, operation, params, configuration, storage,
        request = { network.request(it, ensureCurrent) }, ensureCurrent = ensureCurrent)

    internal suspend fun invoke(
        script: String, manifest: PluginManifest, operation: String, params: JsonObject,
        configuration: Map<String, String>, storage: Map<String, String>, network: ScriptNetworkPolicy,
        verifiedUserId: String?, ensureCurrent: () -> Unit = {},
    ): ScriptInvocation = invoke(script, manifest, operation, params, configuration, storage,
        request = { network.request(it, ensureCurrent) }, verifiedUserId = verifiedUserId, ensureCurrent = ensureCurrent)

    internal suspend fun invoke(
        script: String,
        manifest: PluginManifest,
        operation: String,
        params: JsonObject,
        configuration: Map<String, String>,
        storage: Map<String, String>,
        request: suspend (JsonObject) -> JsonObject,
        verifiedUserId: String? = null,
        ensureCurrent: () -> Unit = {},
    ): ScriptInvocation = execution.withLock {
        ensureCurrent()
        if (!supported) throw SourceException(SourceErrorCode.UNSUPPORTED, "Update Android System WebView to run sources")
        val resultLimit = scriptResultLimit(manifest, operation, params)
        var phase = ScriptRuntimePhase.CONNECTION
        var requestSent = false
        val memoryLimited = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            withTimeout(20_000) {
                connections.connect { JavaScriptSandbox.createConnectedInstanceAsync(context.applicationContext) }.use { lease ->
                    val sandbox = lease.value
                    phase = ScriptRuntimePhase.INITIALIZATION
                    if (!sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE) ||
                        !sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)) {
                        throw SourceException(SourceErrorCode.UNSUPPORTED, "Source resource limits are unavailable on this device")
                    }
                    val settings = IsolateStartupParameters().apply {
                        maxHeapSizeBytes = MAX_HEAP_BYTES
                        maxEvaluationReturnSizeBytes = resultLimit
                    }
                    sandbox.createIsolate(settings).use { isolate ->
                        isolate.addOnTerminatedCallback(java.util.concurrent.Executor { it.run() }) {
                            if (it.status == TerminationInfo.STATUS_MEMORY_LIMIT_EXCEEDED) memoryLimited.set(true)
                        }
                        ensureCurrent()
                        phase = ScriptRuntimePhase.SCRIPT
                        withTimeout(3_000) { isolate.evaluateJavaScriptAsync(script + "\n;'loaded';").await() }
                        val gson = Gson()
                        val entropy = SecureRandom()
                        var response: JsonObject? = null
                        var state: JsonElement? = null
                        repeat(5) { hop ->
                            ensureCurrent()
                            val pluginContext = newContext(manifest, configuration, storage, entropy, verifiedUserId).apply {
                                add("device", JsonObject().apply {
                                    addProperty("installationId", ScriptPlatformSdk.installationDeviceId(context, "script:${manifest.id}:default"))
                                    addProperty("resolution", context.resources.displayMetrics.let { "${it.widthPixels}x${it.heightPixels}" })
                                    addProperty("osVersion", android.os.Build.VERSION.RELEASE)
                                    addProperty("model", android.os.Build.MODEL)
                                    addProperty("brand", android.os.Build.BRAND)
                                    addProperty("processName", context.applicationInfo.processName ?: context.packageName)
                                    addProperty("pid", android.os.Process.myPid())
                                    addProperty("processEpoch", processEpoch)
                                    addProperty("buildId", android.os.Build.ID)
                                })
                            }
                            val expression = if (response == null) {
                                "__p[${gson.toJson(operation)}](${params},${pluginContext})"
                            } else {
                                "__p.onResponse(${gson.toJson(operation)},${response},${state ?: "null"},${pluginContext})"
                            }
                            val invocation = """(function(){
                                "use strict";
                                const __p = typeof sourcePlugin !== "undefined" ? sourcePlugin : globalThis.sourcePlugin;
                                if (!__p) throw new Error("Missing sourcePlugin");
                                const __r = $expression;
                                if (__r && typeof __r.then === "function") throw new Error("Use the host request protocol");
                                return JSON.stringify(__r === undefined ? null : __r);
                            })()""".trimIndent()
                            ensureCurrent()
                            phase = ScriptRuntimePhase.OPERATION
                            val raw = withTimeout(3_000) { isolate.evaluateJavaScriptAsync(invocation).await() }
                            ensureCurrent()
                            phase = ScriptRuntimePhase.RESULT
                            val result = parseScriptResult(raw, resultLimit)
                            val envelope = result.takeIf(JsonElement::isJsonObject)?.asJsonObject
                            if (envelope?.has("request") == true) {
                                if (hop == 4) throw SourceException(SourceErrorCode.PLUGIN, "Source request limit exceeded")
                                ensureCurrent()
                                val frame = envelope.getAsJsonObject("request")
                                val recovery = frame.get("errorResponse")?.let {
                                    if (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean) throw SourceException(SourceErrorCode.INVALID_RESPONSE)
                                    it.asBoolean
                                } ?: false
                                phase = ScriptRuntimePhase.REQUEST
                                requestSent = true
                                response = try { request(frame) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (error: SourceException) {
                                    val recovered = scriptErrorResponse(error, recovery) ?: throw error
                                    ensureCurrent()
                                    recovered
                                }
                                ensureCurrent()
                                state = envelope.get("state")
                            } else {
                                if (envelope?.has("error") == true) {
                                    val code = runCatching { SourceErrorCode.valueOf(envelope.get("error").asString) }.getOrDefault(SourceErrorCode.PLUGIN)
                                    throw SourceException(code)
                                }
                                val updated = envelope?.getAsJsonObject("storage")?.let { values ->
                                    if (!manifest.permissions.storage) throw SourceException(SourceErrorCode.PLUGIN)
                                    if (values.size() > 64 || values.toString().toByteArray().size > 64 * 1024) {
                                        throw SourceException(SourceErrorCode.INVALID_RESPONSE, "Source storage limit exceeded")
                                    }
                                    values.entrySet().associate { it.key to it.value.asString }
                                }
                                ensureCurrent()
                                return@withTimeout ScriptInvocation(envelope?.get("result") ?: result, updated)
                            }
                        }
                        throw SourceException(SourceErrorCode.PLUGIN)
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            ensureCurrent()
            throw SourceException(SourceErrorCode.TIMEOUT)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: SourceException) { ensureCurrent(); throw error
        } catch (error: Exception) {
            ensureCurrent()
            var cause: Throwable = error
            repeat(8) { (cause as? java.util.concurrent.ExecutionException)?.cause?.let { cause = it } }
            if (resultLimit == MAX_CATALOG_RESULT_BYTES && cause is EvaluationResultSizeLimitExceededException) {
                throw SourceResponseSizeException()
            }
            val failure = scriptRuntimeFailure(error, phase, memoryLimited.get(), requestSent)
            if (failure.code in setOf(SourceErrorCode.UNAVAILABLE, SourceErrorCode.PLUGIN, SourceErrorCode.INVALID_RESPONSE)) {
                // Engine exception messages may contain script data. Record fixed metadata only.
                val kind = (error as? java.util.concurrent.ExecutionException)?.cause?.javaClass?.simpleName ?: error.javaClass.simpleName
                android.util.Log.w("ScriptRuntime", "Sandbox failure code=${failure.code.name} phase=${phase.name} kind=$kind")
            }
            throw failure
        }
    }

    companion object {
        const val MAX_HEAP_BYTES = 32L * 1024 * 1024
        const val MAX_CATALOG_RESULT_BYTES = 2 * 1024 * 1024
        const val MAX_RESULT_BYTES = 256 * 1024
        const val SECURE_RANDOM_BYTES = 256
        private val execution = Mutex()
        private val connections = ScriptConnectionGate()
        private val processEpoch = java.util.UUID.randomUUID().toString()

        internal fun newContext(
            manifest: PluginManifest,
            configuration: Map<String, String>,
            storage: Map<String, String>,
            entropy: SecureRandom = SecureRandom(),
            verifiedUserId: String? = null,
        ): JsonObject = JsonObject().apply {
            val gson = Gson()
            add("account", JsonObject().apply { verifiedUserId?.let { addProperty("userId", it) } })
            addProperty("apiVersion", manifest.apiVersion)
            add("config", gson.toJsonTree(configuration))
            add("storage", gson.toJsonTree(storage.takeIf { manifest.permissions.storage }.orEmpty()))
            addProperty("secureRandomBase64", Base64.getEncoder().encodeToString(
                ByteArray(SECURE_RANDOM_BYTES).also(entropy::nextBytes)))
        }
    }
}

internal fun scriptResultLimit(manifest: PluginManifest, operation: String, parameters: JsonObject): Int {
    val bulk = operation in manifest.capabilities && (operation == "playlist.get" || operation == "catalog.tracks" ||
        operation == "library.likedTracks" && parameters.get("metadataOnly")?.let {
            it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean
        } == true)
    return if (bulk) ScriptRuntime.MAX_CATALOG_RESULT_BYTES else ScriptRuntime.MAX_RESULT_BYTES
}

internal fun parseScriptResult(raw: String, maximumBytes: Int = ScriptRuntime.MAX_RESULT_BYTES): JsonElement {
    if (maximumBytes !in setOf(ScriptRuntime.MAX_RESULT_BYTES, ScriptRuntime.MAX_CATALOG_RESULT_BYTES)) {
        throw SourceException(SourceErrorCode.INVALID_RESPONSE)
    }
    if (raw.toByteArray(Charsets.UTF_8).size > maximumBytes) {
        if (maximumBytes == ScriptRuntime.MAX_CATALOG_RESULT_BYTES) throw SourceResponseSizeException()
        throw SourceException(SourceErrorCode.INVALID_RESPONSE)
    }
    val result = JsonParser.parseString(raw)
    val envelope = result.takeIf(JsonElement::isJsonObject)?.asJsonObject
    val returned = envelope?.get("result") ?: result
    val hostEffect = returned.takeIf(JsonElement::isJsonObject)?.asJsonObject?.has("hostEffect") == true
    if ((envelope?.has("request") == true || hostEffect) && raw.toByteArray(Charsets.UTF_8).size > ScriptRuntime.MAX_RESULT_BYTES) {
        throw SourceException(SourceErrorCode.INVALID_RESPONSE)
    }
    if (envelope?.get("request")?.takeIf(JsonElement::isJsonObject)
            ?.asJsonObject?.has("cache") == true) throw SourceException(SourceErrorCode.INVALID_RESPONSE)
    for (key in listOf("state", "storage")) envelope?.get(key)?.let { state ->
        val pending = ArrayDeque<Pair<JsonElement, Int>>()
        pending.add(state to 0)
        var nodes = 0
        while (pending.isNotEmpty()) {
            val (value, depth) = pending.removeLast()
            if (depth > 64 || ++nodes > 65_536) throw SourceException(SourceErrorCode.INVALID_RESPONSE)
            if (value.isJsonObject) value.asJsonObject.entrySet().forEach { pending.add(it.value to depth + 1) }
            else if (value.isJsonArray) value.asJsonArray.forEach { pending.add(it to depth + 1) }
        }
        if (state.toString().toByteArray(Charsets.UTF_8).size > 65_536) throw SourceException(SourceErrorCode.INVALID_RESPONSE)
    }
    return result
}

internal fun scriptErrorResponse(error: SourceException, allowed: Boolean): JsonObject? {
    if (!allowed) return null
    val code = when {
        error is SourceResponseSizeException -> "RESPONSE_TOO_LARGE"
        error.code in setOf(SourceErrorCode.NETWORK, SourceErrorCode.TIMEOUT) -> error.code.name
        else -> return null
    }
    return JsonObject().apply { addProperty("status", 0); addProperty("body", ""); addProperty("error", code) }
}

internal enum class ScriptRuntimePhase { CONNECTION, INITIALIZATION, SCRIPT, OPERATION, RESULT, REQUEST }

/** Runtime availability is separate from script/resource/permission failures. */
internal fun scriptRuntimeFailure(error: Throwable, phase: ScriptRuntimePhase,
    memoryLimited: Boolean = false, requestSent: Boolean = false): SourceException {
    var cause = error
    repeat(8) {
        val next = (cause as? java.util.concurrent.ExecutionException)?.cause
        if (next != null && next !== cause) cause = next
    }
    if (cause is CancellationException) throw cause as CancellationException
    if (cause is SourceException) return cause as SourceException
    val code = when {
        memoryLimited || cause is MemoryLimitExceededException -> SourceErrorCode.PLUGIN
        cause is EvaluationResultSizeLimitExceededException -> SourceErrorCode.INVALID_RESPONSE
        cause is SandboxUnsupportedException -> SourceErrorCode.UNSUPPORTED
        cause is ScriptConnectionCloseException -> SourceErrorCode.UNAVAILABLE
        cause is EvaluationFailedException -> if (phase == ScriptRuntimePhase.SCRIPT) SourceErrorCode.PLUGIN else SourceErrorCode.INVALID_RESPONSE
        phase in setOf(ScriptRuntimePhase.RESULT, ScriptRuntimePhase.REQUEST) &&
            cause !is androidx.javascriptengine.JavaScriptException && cause !is android.os.RemoteException -> SourceErrorCode.INVALID_RESPONSE
        else -> SourceErrorCode.UNAVAILABLE
    }
    return SourceException(code, outcomeUnknown = code == SourceErrorCode.UNAVAILABLE && requestSent)
}

/** AndroidX permits one binding. A cancelled caller must retire its late connection first. */
internal class ScriptConnectionGate {
    private val acquisition = Mutex()
    private var retirement: CompletableDeferred<Unit>? = null

    suspend fun <T : AutoCloseable> connect(create: () -> ListenableFuture<T>): ScriptConnectionLease<T> = acquisition.withLock {
        retirement?.await()
        val settled = CompletableDeferred<Unit>()
        retirement = settled
        val connection = try { create() } catch (error: Throwable) { settled.complete(Unit); throw error }
        suspendCancellableCoroutine<ScriptConnectionLease<T>> { continuation ->
            // Cancelling the future does not unbind AndroidX's service. Keep the listener alive
            // and block later acquisitions until a late result has actually been closed.
            connection.addListener({
                try {
                    val lease = ScriptConnectionLease(connection.get()) { failure ->
                        if (failure == null) settled.complete(Unit) else settled.completeExceptionally(failure)
                    }
                    continuation.resume(lease) { _, abandoned, _ ->
                        runCatching { abandoned.close() }
                    }
                } catch (error: Exception) {
                    settled.complete(Unit)
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }, java.util.concurrent.Executor { it.run() })
        }
    }
}

/** The binding remains leased until its single close has proven retirement successful. */
internal class ScriptConnectionLease<T : AutoCloseable>(val value: T, private val retire: (Throwable?) -> Unit) : AutoCloseable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { value.close() }
        catch (_: Throwable) {
            // Do not expose framework exception text or retry a partially completed unbind.
            val failure = ScriptConnectionCloseException()
            retire(failure)
            throw failure
        }
        retire(null)
    }
}

private class ScriptConnectionCloseException : Exception("Sandbox retirement failed")
