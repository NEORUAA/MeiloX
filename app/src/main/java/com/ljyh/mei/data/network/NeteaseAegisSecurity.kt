package com.ljyh.mei.data.network

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.annotation.Keep
import com.aegis.sdk.AegisNative
import com.google.common.reflect.TypeToken
import com.google.gson.Gson
import com.ljyh.mei.constants.DeviceIdKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.data.network.api.MeloXDirectService
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.getDeviceId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class NeteaseAegisSecurity @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:Named("MeloXEapi") private val eapi: MeloXDirectService,
    private val loginSecurity: NeteaseLoginSecurity,
) {
    init {
        activeInstance = this
    }

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engineMutex = Mutex()
    private val pendingKeyUpdate = AtomicReference<CompletableDeferred<Unit>?>(null)
    private val pendingKeyRequestStarted = AtomicReference<CompletableDeferred<Unit>?>(null)
    private val appliedSession = AtomicReference<AegisSession?>(null)
    private val networkLayer = AegisNetworkLayer(::handleKeyRequest)
    private val publicKeyFile = File(context.filesDir, "aegissdk/public_key")

    @Volatile
    private var engineReady = false

    suspend fun prepareForPcQrLogin() = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            initializeEngine()
            if (!publicKeyFile.isFile || publicKeyFile.length() == 0L) {
                updatePublicKey()
            }
            check(publicKeyFile.isFile && publicKeyFile.length() > 0L) {
                "NetEase Aegis public key is unavailable"
            }
            Log.i(TAG, "Aegis security ready publicKeyBytes=${publicKeyFile.length()}")
        }
    }

    fun hasActiveSession(): Boolean = appliedSession.get() != null

    private suspend fun initializeEngine() {
        if (engineReady) return
        publicKeyFile.parentFile?.mkdirs()
        val preferences = context.dataStore.data.first()
        val deviceId = preferences[DeviceIdKey].orEmpty().ifBlank(::getDeviceId)
        val userAgent = officialUserAgent()
        Log.i(
            TAG,
            "Aegis identity ready deviceId=${deviceId.length} userAgent=${userAgent.length}",
        )
        hardenNativeLogging()
        val completion = CompletableDeferred<Unit>()
        val requestStarted = CompletableDeferred<Unit>()
        pendingKeyUpdate.set(completion)
        pendingKeyRequestStarted.set(requestStarted)
        val code = initializeEngine(deviceId, userAgent)
        if (code == AEGIS_PUBLIC_KEY_PENDING) {
            Log.i(TAG, "Aegis public key pending; waiting for bootstrap")
            try {
                withTimeout(PUBLIC_KEY_TIMEOUT_MS) { completion.await() }
            } finally {
                pendingKeyUpdate.compareAndSet(completion, null)
                pendingKeyRequestStarted.compareAndSet(requestStarted, null)
            }
            val retryCode = initializeEngine(deviceId, userAgent)
            check(retryCode == 0) {
                "NetEase Aegis initialization retry failed ($retryCode)"
            }
            engineReady = true
            applyPendingSession()
            Log.i(TAG, "Aegis SDK initialized code=$retryCode after bootstrap")
            return
        }
        check(code == 0) {
            pendingKeyUpdate.compareAndSet(completion, null)
            pendingKeyRequestStarted.compareAndSet(requestStarted, null)
            "NetEase Aegis initialization failed ($code)"
        }
        try {
            val refreshStarted = withTimeoutOrNull(KEY_REQUEST_START_GRACE_MS) {
                requestStarted.await()
                true
            } == true
            if (refreshStarted) {
                Log.i(TAG, "Aegis public key refresh started; waiting for completion")
                withTimeout(PUBLIC_KEY_TIMEOUT_MS) { completion.await() }
            }
        } finally {
            pendingKeyUpdate.compareAndSet(completion, null)
            pendingKeyRequestStarted.compareAndSet(requestStarted, null)
        }
        engineReady = true
        applyPendingSession()
        Log.i(TAG, "Aegis SDK initialized code=$code")
    }

    private fun initializeEngine(deviceId: String, userAgent: String): Int =
        AegisNative.initializeEngine(
            publicKeyFile.absolutePath,
            decodeMaterial(OBFUSCATED_STATIC_KEY),
            deviceId,
            "android",
            userAgent,
            decodeMaterial(OBFUSCATED_SIGN_KEY),
            networkLayer,
            AEGIS_UPDATE_INTERVAL_MINUTES,
        )

    private fun hardenNativeLogging() {
        if (nativeLoggingHardened) return
        synchronized(NeteaseAegisSecurity::class.java) {
            if (nativeLoggingHardened) return
            NeteaseNativeLogPolicy.installSecurityFilter()
            nativeLoggingHardened = true
        }
    }

    private suspend fun updatePublicKey() {
        val completion = CompletableDeferred<Unit>()
        val requestStarted = CompletableDeferred<Unit>()
        pendingKeyUpdate.set(completion)
        pendingKeyRequestStarted.set(requestStarted)
        val code = AegisNative.updatePublicKey(false)
        Log.i(TAG, "Aegis public key update requested code=$code")
        try {
            withTimeout(PUBLIC_KEY_TIMEOUT_MS) { completion.await() }
        } finally {
            pendingKeyUpdate.compareAndSet(completion, null)
            pendingKeyRequestStarted.compareAndSet(requestStarted, null)
        }
    }

    private fun handleKeyRequest(type: Int, data: String, callbackHandle: Long) {
        if (type != AEGIS_KEY_REQUEST_TYPE) return
        pendingKeyRequestStarted.get()?.complete(Unit)
        scope.launch {
            runCatching {
                val payloadType = object : TypeToken<MutableMap<String, Any>>() {}.type
                val payload = gson.fromJson<MutableMap<String, Any>>(data, payloadType)
                    ?: mutableMapOf()
                val preferences = context.dataStore.data.first()
                val userId = preferences[UserIdKey]?.toLongOrNull()?.takeIf { it > 0 }
                    ?: ANONYMOUS_USER_ID
                val deviceId = preferences[DeviceIdKey].orEmpty().ifBlank(::getDeviceId)
                val checkToken = loginSecurity.freshCheckToken()
                val ydDeviceToken = loginSecurity.ydDeviceToken()
                val securityCookies = loginSecurity.securityCookies()
                payload["t1"] = checkToken
                payload["t2"] = ydDeviceToken
                payload["os"] = "android"
                payload["appVersion"] = NeteaseLoginSecurity.OFFICIAL_VERSION_NAME
                payload["deviceId"] = deviceId
                payload["uid"] = userId
                val response = eapi.post(
                    path = "$INTERFACE3_BASE_URL/api/bsr/sk/get",
                    body = payload,
                    cryptoMode = "eapi",
                    nmcid = securityCookies.nmcid,
                    nmdi = securityCookies.nmdi,
                    nmtid = securityCookies.nmtid.takeIf(String::isNotBlank),
                )
                val nativeCode = AegisNative.onNetworkResponse(
                    callbackHandle,
                    AEGIS_NETWORK_SUCCESS,
                    response.toString(),
                )
                Log.i(
                    TAG,
                    "Aegis public key response nativeCode=$nativeCode " +
                        "publicKeyBytes=${publicKeyFile.length()}",
                )
                pendingKeyUpdate.get()?.complete(Unit)
            }.onFailure { error ->
                runCatching { AegisNative.onNetworkResponse(callbackHandle, -1, "") }
                pendingKeyUpdate.get()?.completeExceptionally(error)
                Log.e(TAG, "Aegis public key update failed", error)
            }
        }
    }

    private fun encrypt(data: String): String {
        check(engineReady) { "NetEase Aegis engine is not prepared" }
        val encrypted = AegisNative.encrypt(data)
        check(!encrypted.isNullOrBlank()) { "NetEase Aegis returned an empty request" }
        check(encrypted.contains("C=") && encrypted.contains("S=") && encrypted.contains("R=")) {
            "NetEase Aegis returned an invalid request"
        }
        val fields = encrypted.split('&').associate { field ->
            field.substringBefore('=') to field.substringAfter('=', "")
        }
        Log.i(
            TAG,
            "Generated XEAPI body total=${encrypted.length} c=${fields["C"]?.length ?: 0} " +
                "s=${fields["S"]?.length ?: 0} r=${fields["R"]?.length ?: 0}",
        )
        return encrypted
    }

    private fun applyPendingSession() {
        pendingSession.get()?.let(::applySession)
    }

    private fun applySession(session: AegisSession) {
        if (!engineReady || appliedSession.get() == session) return
        AegisNative.setSession(session.id, session.key)
        appliedSession.set(session)
        Log.i(
            TAG,
            "Aegis session applied sessionId=${session.id.length} sessionKey=${session.key.length}",
        )
    }

    private fun decodeMaterial(encoded: String): String =
        Base64.decode(encoded, Base64.NO_WRAP)
            .map { (it.toInt() xor MATERIAL_MASK).toByte() }
            .toByteArray()
            .toString(Charsets.UTF_8)

    private fun officialUserAgent(): String =
        "${NeteaseLoginSecurity.OFFICIAL_ANDROID_USER_AGENT} " +
            "(Linux; U; Android ${Build.VERSION.RELEASE}; ${Build.MODEL} Build/${Build.ID})"

    @Keep
    internal class AegisNetworkLayer(
        private val callback: (Int, String, Long) -> Unit,
    ) {
        @Keep
        @Suppress("unused")
        fun requireKey(type: Int, data: String, callbackHandle: Long) {
            callback(type, data, callbackHandle)
        }
    }

    internal companion object {
        @Volatile
        private var activeInstance: NeteaseAegisSecurity? = null

        @Volatile
        private var nativeLoggingHardened = false

        private val pendingSession = AtomicReference<AegisSession?>(null)
        fun encryptActive(data: String): String =
            activeInstance?.encrypt(data)
                ?: error("NetEase Aegis security is unavailable")

        fun acceptSession(sessionId: String?, sessionKey: String?) {
            val id = sessionId?.takeIf(String::isNotBlank) ?: return
            val key = sessionKey?.takeIf(String::isNotBlank) ?: return
            val session = AegisSession(id, key)
            pendingSession.set(session)
            Log.i(TAG, "Aegis session received sessionId=${id.length} sessionKey=${key.length}")
            activeInstance?.applySession(session)
        }

        private const val TAG = "PcQrLogin"
        private const val INTERFACE3_BASE_URL = "https://interface3.music.163.com"
        private const val AEGIS_KEY_REQUEST_TYPE = 0
        private const val AEGIS_NETWORK_SUCCESS = 200
        private const val AEGIS_PUBLIC_KEY_PENDING = -201
        private const val AEGIS_UPDATE_INTERVAL_MINUTES = 1
        private const val ANONYMOUS_USER_ID = 0L
        private const val PUBLIC_KEY_TIMEOUT_MS = 20_000L
        private const val KEY_REQUEST_START_GRACE_MS = 500L
        private const val MATERIAL_MASK = 0x6D
        private const val OBFUSCATED_STATIC_KEY =
            "HBVcDDwaVB8eKAJCLAgKCV41JlQGOlwOWDcoBgQeKAIOOAoqXEIHWipZPFA="
        private const val OBFUSCATED_SIGN_KEY =
            "ADglLho7IzonDxgDIBwsJQtYICQAGAQfOVsdARseWzs+KzpbXyAqJR4ZKzwVBS8q" +
                "CSgCJAUhJBklXgkHDllGKy9CIiYZFF5GASFfHyoIAisvHTsIWApQUA=="

        private data class AegisSession(
            val id: String,
            val key: String,
        )
    }
}
