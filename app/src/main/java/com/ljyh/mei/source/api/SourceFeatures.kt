package com.ljyh.mei.source.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Shared feature screens depend on domain actions, never platform endpoints or SDK objects. */
interface SourceFeatureCapability {
    val features: Map<String, Set<String>>
    suspend fun invokeFeature(feature: String, action: String, parameters: JsonObject): JsonElement
}

/** Session checks and commits share the same lock as the source invocation. */
interface AccountScopedFeatureCapability : SourceFeatureCapability {
    suspend fun invokeFeature(
        feature: String,
        action: String,
        parameters: JsonObject,
        expectedAccountRevision: String?,
        onSessionCommitted: ((String?) -> Unit)?,
    ): JsonElement
}

/** Raw core segments let the same host-effect executor mediate catalog/account mutations. */
interface SourceCoreEffectCapability {
    suspend fun invokeCoreOperation(operation: String, parameters: JsonObject, expectedAccountRevision: String?): JsonElement
}

sealed interface SourceLinkTarget {
    data class Content(val ref: ContentRef) : SourceLinkTarget
    data class Feature(val route: String, val parameters: JsonObject) : SourceLinkTarget
}

interface SourceLinkCapability {
    val linkHosts: Set<String>
    suspend fun resolveLink(url: String): SourceLinkTarget?
}

/** Shared feature adapters validate catalog/media values through the owning source boundary. */
interface SourceFeatureContentCapability {
    suspend fun parseFeatureTrack(value: JsonObject): Track
    suspend fun featureArtwork(url: String): String
}
