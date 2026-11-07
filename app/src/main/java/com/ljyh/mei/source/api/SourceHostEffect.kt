package com.ljyh.mei.source.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** A bounded request for a host-owned capability; no Android objects cross the script boundary. */
class SourceHostEffect(val type: String, val parameters: JsonObject) {
    override fun toString(): String = "SourceHostEffect(<redacted>)"

    companion object {
        val supportedTypes: Set<String> = setOf(
            "source.continue", "files.pick", "files.upload", "sdk.call", "web.auth", "web.page", "qr.scan",
            "audio.capture", "audio.fingerprint", "sms.compose",
        )
    }
}

sealed interface SourceHostEffectResult {
    class Success(val result: JsonElement) : SourceHostEffectResult {
        override fun toString(): String = "SourceHostEffectResult.Success(<redacted>)"
    }
    data class Failure(val code: SourceErrorCode) : SourceHostEffectResult
}

/** Scope IDs come from the calling host screen, never from plugin parameters. */
class SourceEffectScope internal constructor(
    val providerId: String,
    val instanceId: ProviderInstanceId,
    val providerVersion: String,
    val accountRevision: String?,
    val feature: String,
    val action: String,
    val scopeId: String,
    val token: String,
    val executionId: String = token,
    private val active: () -> Boolean,
    private val validate: suspend () -> Unit,
) {
    /** A lifecycle hint only. Handlers must call [ensureCurrent] before performing side effects. */
    val isActive: Boolean get() = active()
    suspend fun ensureCurrent() = validate()
    override fun toString(): String = "SourceEffectScope(<redacted>)"
}

fun interface SourceHostEffectHandler {
    suspend fun handle(effect: SourceHostEffect, scope: SourceEffectScope): SourceHostEffectResult
    suspend fun release(executionId: String) {}

    companion object {
        val Unsupported = SourceHostEffectHandler { _, _ ->
            SourceHostEffectResult.Failure(SourceErrorCode.UNSUPPORTED)
        }
    }
}
