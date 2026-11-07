package com.ljyh.mei.source.shared

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.core.BoundSourceFeatures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async

internal fun invalidFeature(): Nothing = throw SourceException(SourceErrorCode.INVALID_RESPONSE)
internal fun JsonElement.featureObject(): JsonObject = takeIf { isJsonObject }?.asJsonObject ?: invalidFeature()
internal fun JsonObject.featureText(key: String, optional: Boolean = false, maximum: Int = 2048): String? {
    val value = get(key)?.takeUnless { it.isJsonNull } ?: return if (optional) null else invalidFeature()
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) invalidFeature()
    return value.asString.also { if ((!optional && it.isBlank()) || it.length > maximum || '\u0000' in it) invalidFeature() }
}
internal fun JsonObject.featureLong(key: String, default: Long = 0): Long = get(key)?.takeUnless { it.isJsonNull }?.let {
    val value = runCatching { it.asString.toLong() }.getOrElse { invalidFeature() }
    if (value < 0) invalidFeature()
    value
} ?: default
internal fun JsonObject.featureBoolean(key: String, default: Boolean = false): Boolean = get(key)?.takeUnless { it.isJsonNull }?.let {
    if (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean) invalidFeature()
    it.asBoolean
} ?: default
internal fun JsonElement.featureList(maximum: Int = 1000): List<JsonElement> {
    if (!isJsonArray || asJsonArray.size() > maximum) invalidFeature()
    return asJsonArray.toList()
}
internal fun JsonObject.featureList(key: String, maximum: Int = 1000): List<JsonElement> = get(key)?.featureList(maximum) ?: invalidFeature()
internal fun featureParameters(block: JsonObject.() -> Unit): JsonObject = JsonObject().apply(block)

/** Revocation also stops host capture and player polling while no HTTP call is in progress. */
internal fun BoundSourceFeatures.guard(scope: CoroutineScope, onInvalidated: () -> Unit) = scope.launch {
    try {
        awaitInvalidation()
    } catch (cancelled: CancellationException) {
        if (currentCoroutineContext().isActive) onInvalidated()
        throw cancelled
    }
}

internal suspend fun <T> BoundSourceFeatures.whileCurrent(block: suspend () -> T): T = kotlinx.coroutines.coroutineScope {
    ensureCurrent()
    val request = async(start = kotlinx.coroutines.CoroutineStart.LAZY) { block() }
    val watcher = guard(this) { request.cancel(CancellationException("Source owner changed")) }
    try { request.start(); request.await().also { ensureCurrent() } }
    finally { watcher.cancel(); request.cancel() }
}
