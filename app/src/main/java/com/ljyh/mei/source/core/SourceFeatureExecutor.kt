package com.ljyh.mei.source.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonNull
import com.google.gson.stream.JsonWriter
import com.ljyh.mei.source.api.*
import java.io.Writer
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first

/** Host UI/SDK waits happen after each feature invocation has released the isolated runtime. */
@Singleton
class SourceFeatureExecutor @Inject constructor(private val router: SourceRouter) {
    private val entropy = SecureRandom()
    private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()

    suspend fun executeCore(owner: MusicProvider, operation: String, parameters: JsonObject,
        handler: SourceHostEffectHandler, expectedAccountRevision: String?): JsonElement = execute(owner.id, "core", operation, parameters,
        scopeId = "core:${java.util.UUID.randomUUID()}", handler = handler,
        expectedProvider = owner, expectedAccountRevision = expectedAccountRevision,
        coreOperation = operation)

    /** Only an explicit login/logout host flow may allow a same-provider final session change. */
    suspend fun execute(
        providerId: String,
        feature: String,
        action: String,
        parameters: JsonObject = JsonObject(),
        scopeId: String = "",
        handler: SourceHostEffectHandler = SourceHostEffectHandler.Unsupported,
        allowSessionChange: Boolean = false,
        onSessionCommitted: ((String?) -> Unit)? = null,
        expectedProvider: MusicProvider? = null,
        expectedAccountRevision: String? = null,
        coreOperation: String? = null,
    ): JsonElement {
        if (parameters.has(CONTINUATION_KEY) || scopeId.length > 256) invalid()
        // Complete shuffle IDs and playback history are inputs, not a larger response/effect allowance.
        val completeShuffle = coreOperation == null && feature == "discovery" && action == "officialShuffle"
        val inputLimit = if (completeShuffle) MAX_SHUFFLE_INPUT_BYTES else MAX_INPUT_BYTES
        val completeCatalog = coreOperation in setOf("playlist.get", "catalog.tracks") ||
            coreOperation == "library.likedTracks" && parameters.get("metadataOnly")?.let {
                it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean
            } == true
        val outputLimit = if (completeCatalog) MAX_CATALOG_RESULT_BYTES else MAX_INPUT_BYTES
        val original = boundedCopy(parameters, inputLimit).asJsonObject
        val executionContext = currentCoroutineContext()
        executionContext.ensureActive()
        router.registry.awaitInitialized()
        val owner = router.registry.requireProvider(providerId)
        val instanceId = owner.instanceId
        val version = owner.version
        val accountFlow = (owner as? AccountRevisionCapability)?.accountRevision
        val revision = accountFlow?.first()
        if (expectedProvider != null && (owner !== expectedProvider || revision != expectedAccountRevision)) {
            throw CancellationException("Source changed before feature execution")
        }
        val running = AtomicBoolean(true)
        val executionId = ByteArray(32).also(entropy::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        fun ownerIsCurrent(checkAccount: Boolean = true): Boolean = running.get() && executionContext[Job]?.isActive != false &&
            router.registry.provider(providerId) === owner && router.registry.instance(instanceId) === owner &&
            owner.version == version && (!checkAccount || accountFlow !is StateFlow<*> || accountFlow.value == revision)

        suspend fun ensureOwner(checkAccount: Boolean = true) {
            executionContext.ensureActive()
            currentCoroutineContext().ensureActive()
            if (!ownerIsCurrent(checkAccount) || checkAccount && accountFlow?.first() != revision) {
                throw CancellationException("Source feature scope changed")
            }
        }

        suspend fun <T> whileCurrent(checkAccount: Boolean = true, block: suspend () -> T): T = coroutineScope {
            val pending = async(start = CoroutineStart.LAZY) { block() }
            val changes: Flow<String?> = accountFlow ?: flowOf(null)
            val invalidation = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    combine(router.registry.revision, changes) { _, account -> account }.collect { account ->
                        if (!ownerIsCurrent(checkAccount) || checkAccount && account != revision) {
                            pending.cancel(CancellationException("Source feature owner changed"))
                        }
                    }
                } catch (cancelled: CancellationException) {
                    if (currentCoroutineContext().isActive) {
                        pending.cancel(CancellationException("Source feature owner unavailable"))
                    }
                    throw cancelled
                } catch (_: Exception) {
                    pending.cancel(CancellationException("Source feature owner unavailable"))
                }
            }
            try {
                ensureOwner(checkAccount)
                pending.start()
                pending.await()
            } finally {
                withContext(NonCancellable) {
                    invalidation.cancelAndJoin()
                    pending.cancelAndJoin()
                }
            }
        }

        try {
            var input = original.deepCopy()
            var handled = 0
            while (true) {
                ensureOwner()
                val response = whileCurrent(checkAccount = !allowSessionChange) {
                    if (coreOperation != null) {
                        ensureOwner()
                        (owner as? SourceCoreEffectCapability ?: throw SourceException(SourceErrorCode.UNSUPPORTED))
                            .invokeCoreOperation(coreOperation, input, revision)
                    } else router.feature(providerId, feature, action, input, allowSessionChange = allowSessionChange,
                        expectedProvider = owner, expectedAccountRevision = revision,
                        onSessionCommitted = onSessionCommitted?.let { committed -> { next ->
                            if (!ownerIsCurrent(checkAccount = false)) throw CancellationException("Source feature owner changed")
                            committed(next)
                        } })
                }
                val envelope = response.takeIf(JsonElement::isJsonObject)?.asJsonObject
                if (envelope?.has("hostEffect") != true) {
                    ensureOwner(checkAccount = !allowSessionChange)
                    if (envelope?.has("continuation") == true) invalid()
                    return boundedCopy(response, outputLimit)
                }
                ensureOwner()
                if (handled++ >= MAX_EFFECTS) throw SourceException(SourceErrorCode.PLUGIN, "Source host-effect limit exceeded")
                if (envelope.keySet() != setOf("hostEffect", "continuation")) invalid()
                val specification = envelope.get("hostEffect")?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: invalid()
                if (specification.keySet() != setOf("type", "parameters")) invalid()
                boundedCopy(specification, MAX_SPEC_BYTES)
                val kind = specification.get("type")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: invalid()
                if (kind !in SourceHostEffect.supportedTypes) throw SourceException(SourceErrorCode.UNSUPPORTED)
                val arguments = specification.get("parameters")?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: invalid()
                val continuation = boundedCopy(envelope.get("continuation"), MAX_SPEC_BYTES)
                val effect = SourceHostEffect(kind, arguments.deepCopy())
                val stepActive = AtomicBoolean(true)
                val token = ByteArray(64).also(entropy::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
                val outcome = if (kind == "source.continue") SourceHostEffectResult.Success(JsonNull.INSTANCE) else whileCurrent {
                    val handlerContext = currentCoroutineContext()
                    val scope = SourceEffectScope(providerId, instanceId, version, revision, feature, action, scopeId, token,
                        executionId = executionId,
                        active = { stepActive.get() && handlerContext[Job]?.isActive != false && ownerIsCurrent() },
                        validate = {
                            if (!stepActive.get()) throw CancellationException("Source host effect ended")
                            handlerContext.ensureActive()
                            ensureOwner()
                        })
                    try {
                        scope.ensureCurrent()
                        handler.handle(effect, scope)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: SourceException) {
                        SourceHostEffectResult.Failure(error.code)
                    } catch (_: Exception) {
                        SourceHostEffectResult.Failure(SourceErrorCode.UNAVAILABLE)
                    } finally {
                        stepActive.set(false)
                    }
                }
                stepActive.set(false)
                ensureOwner()
                val effectResult = JsonObject().apply {
                    when (outcome) {
                        is SourceHostEffectResult.Success -> {
                            addProperty("ok", true)
                            add("result", boundedCopy(outcome.result, MAX_RESULT_BYTES))
                        }
                        is SourceHostEffectResult.Failure -> {
                            addProperty("ok", false)
                            addProperty("code", outcome.code.name)
                        }
                    }
                }
                input = original.deepCopy().apply {
                    add(CONTINUATION_KEY, JsonObject().apply {
                        add("state", continuation)
                        add("effect", effectResult)
                        addProperty("token", token)
                    })
                }
                boundedCopy(input, inputLimit)
                ensureOwner()
            }
        } finally {
            running.set(false)
            withContext(NonCancellable) { runCatching { handler.release(executionId) } }
        }
    }

    private fun boundedCopy(value: JsonElement?, maximumBytes: Int): JsonElement {
        if (value == null) invalid()
        // Bound structure before Gson serialization/deepCopy, including native provider responses.
        val pending = ArrayDeque<Pair<JsonElement, Int>>()
        pending.add(value to 0)
        var nodes = 0
        while (pending.isNotEmpty()) {
            val (element, depth) = pending.removeLast()
            if (++nodes > maximumBytes || depth > 64) invalid()
            when {
                element.isJsonObject -> {
                    if (element.asJsonObject.size() > maximumBytes) invalid()
                    element.asJsonObject.entrySet().forEach { (name, child) ->
                        if (name.length > maximumBytes || pending.size >= maximumBytes) invalid()
                        pending.add(child to depth + 1)
                    }
                }
                element.isJsonArray -> {
                    if (element.asJsonArray.size() > maximumBytes) invalid()
                    element.asJsonArray.forEach { child ->
                        if (pending.size >= maximumBytes) invalid()
                        pending.add(child to depth + 1)
                    }
                }
                element.isJsonPrimitive -> {
                    if (element.asJsonPrimitive.asString.length > maximumBytes) invalid()
                    if (element.asJsonPrimitive.isNumber && element.asString in setOf("NaN", "Infinity", "-Infinity")) invalid()
                }
            }
        }
        try {
            val writer = JsonByteLimitWriter(maximumBytes)
            gson.toJson(value, JsonWriter(writer))
            writer.finish()
        } catch (_: JsonLimitExceeded) {
            invalid()
        }
        return value.deepCopy()
    }

    private fun invalid(): Nothing = throw SourceException(SourceErrorCode.INVALID_RESPONSE)

    private class JsonLimitExceeded : RuntimeException(null, null, false, false)
    private class JsonByteLimitWriter(private val maximum: Int) : Writer() {
        private var bytes = 0
        private var highSurrogate = false
        private fun character(value: Char) {
            if (highSurrogate) {
                highSurrogate = false
                if (value.isLowSurrogate()) {
                    bytes += 4
                    if (bytes > maximum) throw JsonLimitExceeded()
                    return
                }
                bytes++
            }
            if (value.isHighSurrogate()) highSurrogate = true
            else bytes += when {
                value.isLowSurrogate() -> 1
                value.code <= 0x7f -> 1
                value.code <= 0x7ff -> 2
                else -> 3
            }
            if (bytes > maximum) throw JsonLimitExceeded()
        }
        override fun write(buffer: CharArray, offset: Int, length: Int) {
            for (index in offset until offset + length) character(buffer[index])
        }
        override fun write(value: String, offset: Int, length: Int) {
            for (index in offset until offset + length) character(value[index])
        }
        override fun write(value: Int) = character(value.toChar())
        fun finish() { if (highSurrogate) { highSurrogate = false; bytes++ }; if (bytes > maximum) throw JsonLimitExceeded() }
        override fun flush() = Unit
        override fun close() = finish()
    }

    companion object {
        private const val CONTINUATION_KEY = "\$continuation"
        private const val MAX_EFFECTS = 8
        private const val MAX_SPEC_BYTES = 64 * 1024
        private const val MAX_RESULT_BYTES = 128 * 1024
        private const val MAX_INPUT_BYTES = 256 * 1024
        private const val MAX_SHUFFLE_INPUT_BYTES = 2 * 1024 * 1024
        private const val MAX_CATALOG_RESULT_BYTES = 2 * 1024 * 1024
    }
}
