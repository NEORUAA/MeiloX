package com.ljyh.mei.source.platform

import android.content.Context
import android.net.Uri
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ljyh.mei.data.network.SourceNativeSdk
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.ProviderRegistry
import com.ljyh.mei.source.script.ScriptMusicProvider
import com.ljyh.mei.source.script.ScriptNetworkPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The host exposes bounded native capabilities only to the invoking, approved source instance. */
@Singleton
class SourcePlatformEffects @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: ProviderRegistry,
) : SourceHostEffectHandler {
    val web = SourceWebEffects()
    private val interactive = Mutex()
    private val mutablePending = MutableStateFlow<SourcePlatformRequest?>(null)
    val pending = mutablePending.asStateFlow()
    private val files = ConcurrentHashMap<String, SelectedSourceFile>()

    override suspend fun release(executionId: String) = withContext(Dispatchers.IO) {
        files.entries.removeIf { (_, file) ->
            if (file.executionId == executionId) { file.snapshot.close(); true } else false
        }
        Unit
    }

    override suspend fun handle(effect: SourceHostEffect, scope: SourceEffectScope): SourceHostEffectResult {
        scope.ensureCurrent()
        val source = registry.instance(scope.instanceId) as? ScriptMusicProvider ?: platformDenied()
        if (registry.provider(scope.providerId) !== source || source.version != scope.providerVersion ||
            effect.type !in source.manifest.permissions.effects) platformDenied()
        return when (effect.type) {
            "sdk.call" -> {
                val name = effect.parameters.text("name")
                if (name !in source.manifest.permissions.nativeSdks) platformDenied()
                if (name == "netease.ursCaptcha") request(effect, scope)
                else SourceHostEffectResult.Success(SourceNativeSdk.call(context, name,
                    effect.parameters.getAsJsonObject("arguments") ?: JsonObject(), source.platformConfiguration(), { scope.isActive }).also { scope.ensureCurrent() })
            }
            "files.pick", "qr.scan", "sms.compose" -> request(effect, scope)
            "files.upload" -> SourceHostEffectResult.Success(upload(effect.parameters, scope, source))
            "web.auth", "web.page" -> {
                val headers = effect.parameters.get("cookieHeaders")?.let { value ->
                    if (!value.isJsonObject || value.asJsonObject.size() > 20) platformDenied()
                    value.asJsonObject.entrySet().associate { (host, cookie) ->
                        if (runCatching { ScriptNetworkPolicy.validateDomain(host) }.isFailure ||
                            !ScriptNetworkPolicy.isHostAllowed(host, source.manifest.permissions.network) ||
                            !cookie.isJsonPrimitive || !cookie.asJsonPrimitive.isString) platformDenied()
                        host to cookie.asString
                    }
                }.orEmpty()
                val account = if ("account.get" in source.manifest.capabilities) try { source.accountWithoutEffects(scope.accountRevision) }
                    catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null } else null
                lateinit var owner: SourceWebOwner
                owner = SourceWebOwner(source.manifest.permissions.network.toSet(), headers,
                    account, scope.instanceId.value, source.features, navigate = { target ->
                        scope.ensureCurrent()
                        // A declared Web route resolves to a recipe, then stays in this WebView's history.
                        // It must not recursively wait for a second modal Web effect.
                        if (target.feature != "web" || target.action != "openPage") platformDenied()
                        val response = source.invokeFeature(target.feature, target.action, target.parameters,
                            scope.accountRevision, null)
                        scope.ensureCurrent()
                        val specification = response.takeIf { it.isJsonObject }?.asJsonObject
                            ?.get("hostEffect")?.takeIf { it.isJsonObject }?.asJsonObject ?: platformDenied()
                        val kind = specification.text("type")
                        if (kind != "web.page") platformDenied()
                        val parameters = specification.getAsJsonObject("parameters") ?: platformDenied()
                        SourceWebRecipe.parse(SourceHostEffect(kind, parameters), owner).url
                    }, login = {
                        if ("account/login" !in source.featureRoutes) false else web.requestLogin(scope)
                    })
                web.handle(effect, scope, owner)
            }
            else -> SourceHostEffectResult.Failure(SourceErrorCode.UNSUPPORTED)
        }
    }

    private suspend fun request(effect: SourceHostEffect, scope: SourceEffectScope): SourceHostEffectResult = interactive.withLock {
        scope.ensureCurrent()
        val request = SourcePlatformRequest(effect, scope)
        try {
            mutablePending.value = request
            request.result.await().also { scope.ensureCurrent() }
        } finally {
            withContext(NonCancellable) {
                request.result.cancel()
                if (mutablePending.value === request) mutablePending.value = null
            }
        }
    }

    internal suspend fun selected(request: SourcePlatformRequest, uri: Uri): JsonObject {
        var registeredHandle: String? = null
        var prepared: CloudUploadFile? = null
        try {
            return withContext(Dispatchers.IO) {
                request.scope.ensureCurrent()
                val snapshot = prepareCloudUploadFile(context, uri, MAX_FILE_BYTES) {
                    if (!request.active) throw CancellationException("Source file selection was cancelled")
                }.also { prepared = it }
                request.scope.ensureCurrent()
                files.entries.removeIf {
                    if (System.currentTimeMillis() - it.value.createdAt > FILE_TTL) {
                        it.value.snapshot.close()
                        true
                    } else false
                }
                if (files.size >= 8) platformDenied()
                val handle = UUID.randomUUID().toString() + UUID.randomUUID().toString()
                registeredHandle = handle
                files[handle] = SelectedSourceFile(snapshot, request.scope, System.currentTimeMillis())
                // release() can race the Activity's picker callback; never register after retirement.
                request.scope.ensureCurrent()
                if (!request.active) throw CancellationException("Source file selection ended")
                JsonObject().apply {
                    addProperty("handle", handle); addProperty("fileName", snapshot.filename.take(1000))
                    addProperty("extension", snapshot.extension)
                    addProperty("size", snapshot.size); addProperty("md5", snapshot.md5)
                    addProperty("title", snapshot.songName.take(1000))
                    addProperty("artist", snapshot.artist.take(1000)); addProperty("album", snapshot.album.take(1000))
                }
            }
        } catch (error: Throwable) {
            registeredHandle?.let { files.remove(it)?.snapshot?.close() }
            prepared?.close()
            throw error
        }
    }

    private suspend fun upload(parameters: JsonObject, scope: SourceEffectScope, source: ScriptMusicProvider): JsonObject {
        val file = files.remove(parameters.text("handle")) ?: platformDenied()
        try {
        if (!file.belongsTo(scope) || System.currentTimeMillis() - file.createdAt > FILE_TTL) platformDenied()
        if (parameters.get("offset")?.asLong != 0L || parameters.get("length")?.asLong != file.size) platformDenied()
        val method = parameters.text("method").uppercase()
        if (method !in setOf("POST", "PUT")) platformDenied()
        val policy = ScriptNetworkPolicy(source.manifest.permissions.network)
        val target = policy.validateUrl(parameters.text("url"))
        val headers = parameters.getAsJsonObject("headers")?.entrySet()?.associate { it.key to it.value.asString }.orEmpty()
        policy.validateHeaders(headers)
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
            override fun contentLength() = file.size
            override fun writeTo(sink: BufferedSink) {
                file.snapshot.file.inputStream().use { input ->
                    val buffer = ByteArray(65_536)
                    var written = 0L
                    while (true) {
                        if (!scope.isActive) throw java.io.InterruptedIOException("Source upload was cancelled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > file.size) throw java.io.IOException("Source file changed")
                        sink.write(buffer, 0, read)
                    }
                    if (written != file.size) throw java.io.IOException("Source file changed")
                }
            }
        }
        val builder = Request.Builder().url(target).method(method, body)
        headers.forEach(builder::header)
        scope.ensureCurrent()
        val client = policy.client.newBuilder().callTimeout(10, java.util.concurrent.TimeUnit.MINUTES)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
        val call = client.newCall(builder.build())
        return try {
            suspendCancellableCoroutine<JsonObject> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, error: java.io.IOException) {
                        if (continuation.isActive) continuation.resumeWithException(SourceException(SourceErrorCode.NETWORK))
                    }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val result = response.use {
                                if (!scope.isActive || !continuation.isActive) throw CancellationException("Source upload ended")
                                if (it.body?.source()?.request(65_537) == true) platformDenied()
                                val body = it.body?.string().orEmpty()
                                if (!scope.isActive || !continuation.isActive) throw CancellationException("Source upload ended")
                                JsonObject().apply { addProperty("status", it.code); addProperty("body", body) }
                            }
                            if (continuation.isActive) continuation.resume(result)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(
                                if (error is SourceException || error is CancellationException) error else SourceException(SourceErrorCode.NETWORK))
                        }
                    }
                })
            }
        } finally {
            call.cancel()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        } finally { file.snapshot.close() }
    }

    private class SelectedSourceFile(val snapshot: CloudUploadFile, scope: SourceEffectScope, val createdAt: Long) {
        val size: Long get() = snapshot.size
        val executionId = scope.executionId
        private val owner = scope.instanceId
        private val version = scope.providerVersion
        private val account = scope.accountRevision
        private val screen = scope.scopeId
        private val feature = scope.feature
        private val action = scope.action
        fun belongsTo(scope: SourceEffectScope) = executionId == scope.executionId && owner == scope.instanceId && version == scope.providerVersion &&
            account == scope.accountRevision && screen == scope.scopeId && feature == scope.feature && action == scope.action
    }
    private companion object { const val MAX_FILE_BYTES = 1_073_741_824L; const val FILE_TTL = 600_000L }
}

class SourcePlatformRequest internal constructor(val effect: SourceHostEffect, internal val scope: SourceEffectScope) {
    internal val result = CompletableDeferred<SourceHostEffectResult>()
    internal val active get() = scope.isActive && !result.isCompleted
    internal fun finish(value: JsonElement) { if (active) result.complete(SourceHostEffectResult.Success(value)) }
    internal fun fail(code: SourceErrorCode) { if (active) result.complete(SourceHostEffectResult.Failure(code)) }
    override fun toString() = "SourcePlatformRequest(<redacted>)"
}

private fun JsonObject.text(key: String): String = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    ?.takeIf { it.isNotBlank() && it.length <= 8192 && '\u0000' !in it } ?: platformDenied()
private fun platformDenied(): Nothing = throw SourceException(SourceErrorCode.PERMISSION_DENIED)
