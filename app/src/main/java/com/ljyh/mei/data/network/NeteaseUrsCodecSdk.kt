package com.ljyh.mei.data.network

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import com.ljyh.mei.AppContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject

/** Native key access and wire encoding only. Endpoints, login state and retries belong to sources. */
internal object NeteaseUrsCodecSdk {
    private val codecs = ConcurrentHashMap<String, Codec>()

    fun interceptor(configuration: Map<String, String>): Interceptor {
        val namespace = configuration["sdkInstance"] ?: error("Missing SDK namespace")
        val codec = codecs.computeIfAbsent(namespace) { Codec(AppContext.instance, it) }
        return Interceptor { chain ->
            val incoming = chain.request()
            require(incoming.url.host in setOf("sdk.reg.163.com", "sdk2.reg.163.com"))
            val source = Buffer()
            incoming.body?.writeTo(source)
            val envelope = JSONObject(source.readUtf8())
            val parameters = envelope.optJSONObject("parameters") ?: JSONObject()
            codec.common(parameters)
            val body = envelope.optJSONObject("body") ?: JSONObject()
            val request = incoming.newBuilder()
                .removeHeader("Cookie")
                .header("p3", NeteaseUrsSdkProfile.ACCESS_ID)
                .apply { codec.crypto.encryptHeaders(parameters).forEach { (name, value) -> header(name, value) } }
                .method(incoming.method, body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            chain.proceed(request).let { response ->
                val payload = response.body ?: return@let response
                val raw = JSONObject(payload.use { it.string() })
                val normalized = JSONObject().put("code", raw.optInt("result", -1))
                    .put("data", codec.crypto.decryptResponse(raw))
                response.newBuilder().body(normalized.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
    }

    private class Codec(context: Context, namespace: String) {
        private val identifier = MessageDigest.getInstance("SHA-256").digest(namespace.toByteArray())
            .joinToString("") { "%02x".format(it) }
        private val directory = File(context.filesDir, "source-sdk/$identifier/urs").apply { mkdirs() }
        private val privateContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("source-$identifier-$name", mode)
        }
        private val uniqueId = File(directory, "installation-id").let { file ->
            if (file.isFile) file.readText() else UUID.randomUUID().toString().replace("-", "").also(file::writeText)
        }
        private val certificate = context.assets.open("netease_official_signing_certificate.der").use { it.readBytes() }
        private val sdkContext = NeteaseUrsApplicationContext(privateContext, certificate)
        val crypto = NeteaseUrsCrypto(sdkContext, NeteaseUrsSdkProfile.APP_SIGN)
        private val applicationVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

        fun common(parameters: JSONObject) {
            parameters.put("packageSign", crypto.packageSignature)
                .put("appVersion", applicationVersion)
                .put("systemVersion", Build.VERSION.SDK_INT).put("model", Build.MODEL)
                .put("uniqueId", uniqueId).put("aId", "private").put("carrier", "private")
                .put("resolution", "private").put("emulator", 0).put("ydUniqueId", "")
                .put("time", System.currentTimeMillis()).put("reqId", UUID.randomUUID().toString().replace("-", ""))
            if (!parameters.has("ua")) parameters.put("ua", System.getProperty("http.agent").orEmpty())
        }
    }
}
