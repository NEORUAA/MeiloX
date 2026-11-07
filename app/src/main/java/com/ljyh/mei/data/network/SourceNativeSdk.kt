package com.ljyh.mei.data.network

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.script.ScriptNetworkPolicy
import com.ljyh.mei.data.network.netease.NcblCodec
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.json.JSONObject

/** Closed native primitives. Source scripts own all service requests and business decisions. */
internal object SourceNativeSdk {
    private val security = ConcurrentHashMap<String, SecurityState>()
    // The vendor WatchMan runtime is process-wide; its calls must not overlap across owners.
    private val vendorMutex = Mutex()
    private var securityOwner: String? = null

    suspend fun call(context: Context, name: String, arguments: JsonObject, config: Map<String, String>, isActive: () -> Boolean): JsonElement =
        when (name) {
            "netease.securityHeaders" -> vendorMutex.withLock {
                withTimeout(90_000) { withContext(Dispatchers.IO) {
                    val namespace = config["sdkInstance"] ?: denied()
                    // This vendor exposes process-global statics and no verified reset API.
                    // Never return one source's device proof through another owner's context.
                    if (securityOwner != null && securityOwner != namespace) throw SourceException(SourceErrorCode.UNSUPPORTED)
                    securityOwner = namespace
                    val state = security.computeIfAbsent(namespace) { SecurityState(context, it) }
                    state.networkAllowed = isActive
                    try {
                    val identity = config["deviceId"].orEmpty().ifBlank {
                        state.deviceId ?: NeteaseOfficialDeviceId.create(state.context, state.localId).encoded.also { state.deviceId = it }
                    }
                    val yd = state.sdk.prepareForPcQrLogin()
                    val check = state.sdk.pcQrCheckToken()
                    val antiCheat = state.sdk.freshCheckToken()
                    val cookies = state.sdk.securityCookies()
                    JsonObject().apply {
                        addProperty("deviceId", identity)
                        addProperty("sDeviceId", config["sDeviceId"].orEmpty())
                        addProperty("model", Build.MODEL)
                        addProperty("ydDeviceToken", yd)
                        addProperty("checkToken", check)
                        addProperty("headerAntiCheatToken", antiCheat)
                        add("securityCookies", JsonObject().apply {
                            addProperty("nmcid", cookies.nmcid); addProperty("nmdi", cookies.nmdi); addProperty("nmtid", cookies.nmtid)
                        })
                    }
                    } finally { state.networkAllowed = { false } }
                } }
            }
            "netease.ursPuzzle" -> withTimeout(21_000) { withContext(Dispatchers.Default) {
                val data = arguments.getAsJsonObject("data") ?: denied()
                if (data.toString().length > 16_384) denied()
                val answer = NeteaseUrsPuzzle.solve(JSONObject(data.toString()))
                JsonObject().apply {
                    addProperty("id", answer.id); addProperty("question", answer.question)
                    add("values", JsonParser.parseString(JSONObject(answer.values).toString()))
                }
            } }
            "netease.history" -> withContext(Dispatchers.Default) {
                val meta = arguments.getAsJsonObject("meta")?.toString()?.toByteArray() ?: denied()
                val record = runCatching { Base64.getDecoder().decode(arguments.get("recordBase64").asString) }.getOrElse { denied() }
                if (meta.size > 16_384 || record.size > 65_536) denied()
                val packet = NcblCodec.encode(meta, record)
                if (packet.size > 196_608) denied()
                JsonObject().apply { addProperty("bodyBase64", Base64.getEncoder().encodeToString(packet)) }
            }
            else -> throw SourceException(SourceErrorCode.UNSUPPORTED)
        }

    private class SecurityState(base: Context, namespace: String) {
        val key = MessageDigest.getInstance("SHA-256").digest(namespace.toByteArray()).joinToString("") { "%02x".format(it) }
        private val directory = File(base.noBackupFilesDir, "source-sdk/$key").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(directory, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("source-$key-$name", mode)
        }
        private val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            File(directory, "security.preferences_pb")
        }
        private val client = OkHttpClient.Builder()
            .proxy(java.net.Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
            .dns(Dns { host ->
                if (host !in setOf("ac.dun.163yun.com", "ac.dun.163.com")) denied()
                NeteaseSecurityDns.lookup(host).also { if (it.isEmpty() || it.any { address -> !ScriptNetworkPolicy.isPublicAddress(address) }) denied() }
            }).addInterceptor { chain ->
                val request = chain.request()
                if (request.url.scheme != "https" || request.url.port != 443 || request.url.username.isNotEmpty() || request.url.password.isNotEmpty()) denied()
                if ((request.body?.contentLength() ?: 0) > 262_144) denied()
                chain.proceed(request).also { response ->
                    if (response.code in 300..399 || response.body?.source()?.request(1_048_577) == true) { response.close(); denied() }
                }
            }.build()
        val localId = File(directory, "installation-id").let { file ->
            if (file.isFile) file.readText() else java.util.UUID.randomUUID().toString().replace("-", "").take(16).also(file::writeText)
        }
        @Volatile var networkAllowed: () -> Boolean = { false }
        val sdk = NeteaseLoginSecurity(context, client, store, networkAllowed = { networkAllowed() })
        var deviceId: String? = null
    }

    private fun denied(): Nothing = throw SourceException(SourceErrorCode.PERMISSION_DENIED)
}
