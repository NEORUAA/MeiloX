package com.ljyh.mei.source.script

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptSandbox
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ScriptInvocation(val result: JsonElement, val storage: Map<String, String>? = null)

/** No app objects or JavaScript interfaces are bound into this isolated V8 process. */
@Singleton
class ScriptRuntime @Inject constructor(@ApplicationContext private val context: Context) {
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
    ): ScriptInvocation = execution.withLock {
        if (!supported) throw SourceException(SourceErrorCode.UNSUPPORTED, "Update Android System WebView to run sources")
        try {
            withTimeout(20_000) {
                val sandbox = connect()
                try {
                    if (!sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE) ||
                        !sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)) {
                        throw SourceException(SourceErrorCode.UNSUPPORTED, "Source resource limits are unavailable on this device")
                    }
                    val settings = IsolateStartupParameters().apply {
                        maxHeapSizeBytes = 16L * 1024 * 1024
                        maxEvaluationReturnSizeBytes = MAX_RESULT_BYTES
                    }
                    val isolate = sandbox.createIsolate(settings)
                    try {
                        withTimeout(3_000) { isolate.evaluateJavaScriptAsync(script + "\n;'loaded';").await() }
                        val gson = Gson()
                        val pluginContext = JsonObject().apply {
                            addProperty("apiVersion", 1)
                            add("config", gson.toJsonTree(configuration))
                            add("storage", gson.toJsonTree(storage.takeIf { manifest.permissions.storage }.orEmpty()))
                        }
                        var response: JsonObject? = null
                        var state: JsonElement? = null
                        repeat(5) { hop ->
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
                            val raw = withTimeout(3_000) { isolate.evaluateJavaScriptAsync(invocation).await() }
                            if (raw.toByteArray(Charsets.UTF_8).size > MAX_RESULT_BYTES) throw SourceException(SourceErrorCode.PLUGIN)
                            val result = JsonParser.parseString(raw)
                            val envelope = result.takeIf(JsonElement::isJsonObject)?.asJsonObject
                            if (envelope?.has("request") == true) {
                                if (hop == 4) throw SourceException(SourceErrorCode.PLUGIN, "Source request limit exceeded")
                                response = network.request(envelope.getAsJsonObject("request"))
                                state = envelope.get("state")
                            } else {
                                if (envelope?.has("error") == true) {
                                    val code = runCatching { SourceErrorCode.valueOf(envelope.get("error").asString) }.getOrDefault(SourceErrorCode.PLUGIN)
                                    throw SourceException(code)
                                }
                                val updated = envelope?.getAsJsonObject("storage")?.let { values ->
                                    if (!manifest.permissions.storage || values.size() > 64 || values.toString().toByteArray().size > 64 * 1024) {
                                        throw SourceException(SourceErrorCode.PLUGIN, "Source storage limit exceeded")
                                    }
                                    values.entrySet().associate { it.key to it.value.asString }
                                }
                                return@withTimeout ScriptInvocation(envelope?.get("result") ?: result, updated)
                            }
                        }
                        throw SourceException(SourceErrorCode.PLUGIN)
                    } finally { isolate.close() }
                } finally { sandbox.close() }
            }
        } catch (_: TimeoutCancellationException) {
            throw SourceException(SourceErrorCode.TIMEOUT)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: SourceException) { throw error
        } catch (_: Exception) {
            // Evaluation errors can contain plugin-generated credential strings.
            throw SourceException(SourceErrorCode.PLUGIN, "Source execution failed")
        }
    }

    private suspend fun connect(): JavaScriptSandbox = suspendCancellableCoroutine { continuation ->
        val connection = JavaScriptSandbox.createConnectedInstanceAsync(context)
        // Do not cancel the connection future: once binding completes, close the sandbox
        // even when this caller has already been cancelled and never acquired it.
        connection.addListener({
            try {
                val sandbox = connection.get()
                continuation.resume(sandbox) { _, abandoned, _ -> abandoned.close() }
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }, java.util.concurrent.Executor { it.run() })
    }

    companion object {
        const val MAX_RESULT_BYTES = 256 * 1024
        private val execution = Mutex()
    }
}
