package com.ljyh.mei.data.network

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.WebSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Source implementation of URS initialization and SMS authentication. */
@Singleton
class NeteaseUrsSmsLogin internal constructor(
    private val context: Context,
    private val client: OkHttpClient,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, createClient())

    private val initializationMutex = Mutex()
    private val preferences = context.getSharedPreferences("netease_urs_auth", Context.MODE_PRIVATE)
    private val sdkContext by lazy {
        val certificate = context.assets.open("netease_official_signing_certificate.der").use { it.readBytes() }
        NeteaseUrsApplicationContext(context.applicationContext, certificate)
    }
    private val crypto by lazy { NeteaseUrsCrypto(sdkContext, APP_SIGN) }
    private val installationId by lazy {
        preferences.getString("installationId", null) ?: UUID.randomUUID().toString().replace("-", "")
            .also { preferences.edit().putString("installationId", it).apply() }
    }
    @Volatile private var currentAppId = preferences.getString("appId", "").orEmpty()

    internal suspend fun prepare() { appId() }

    internal suspend fun appId(): String = withContext(Dispatchers.IO) {
        initializationMutex.withLock {
            // Check JNI/key initialization even when using an existing installation ID.
            crypto
            if (currentAppId.isBlank()) {
                val response = request(INIT_PATH, JSONObject(), JSONObject().put("sdv", JSONObject()))
                requireSuccess(response)
                val id = response.data.optString("appId")
                check(id.isNotBlank()) { "NetEase URS application ID is unavailable" }
                currentAppId = id
                preferences.edit().putString("appId", id).apply()
                Log.i(LOG_TAG, "URS initialized appIdLength=${id.length}")
            }
            currentAppId
        }
    }

    suspend fun requestCode(activity: Activity, phone: String, countryCode: String) =
        withOperationTimeout("SMS code request", 120_000) {
            check(!activity.isFinishing && !activity.isDestroyed) { "Sign-in screen closed" }
            appId()
            val parameters = JSONObject().put("username", ursPhoneAccount(phone, countryCode))
            var response = authenticatedRequest(SMS_SEND_PATH, parameters)
            if (response.code == 12010) {
                val validation = NeteaseUrsCaptcha.validate(activity, response.data.optString("captchaType"))
                response = authenticatedRequest(SMS_SEND_PATH, parameters, JSONObject().put("cp", validation))
            }
            // The SDK treats 12020 as a supported verification step, not a failed send.
            if (response.code == 12020 || (response.code == 200 && response.data.optBoolean("needUpMessage"))) {
                val destination = response.data.optString("upCode").trim()
                val content = response.data.optString("upMessage").trim()
                if (destination.isBlank() || content.isBlank()) throw NeteaseUrsException(
                    apiCode = response.code,
                    message = "网易账号要求短信验证，但未返回短信号码或内容，请重新获取。",
                )
                Log.i(LOG_TAG, "URS requires outgoing SMS destinationLength=${destination.length} contentLength=${content.length}")
                throw NeteaseUrsUpSmsRequiredException(NeteaseUrsUpSmsChallenge(
                    phone, countryCode, destination, content, currentAppId,
                ))
            }
            requireSuccess(response)
            Log.i(LOG_TAG, "URS SMS code request completed")
        }

    internal suspend fun verifyUpSms(challenge: NeteaseUrsUpSmsChallenge): NeteaseUrsLoginCredentials =
        withOperationTimeout("outgoing SMS verification", 60_000) {
            if (appId() != challenge.appId) throw NeteaseUrsException(
                message = "短信安全验证已过期，请重新获取验证信息。",
            )
            // upMsg belongs to the installation that issued it. Do not refresh/retry
            // under another appId when the server rejects that installation.
            val response = request(SMS_UP_CHECK_PATH, JSONObject()
                .put("username", ursPhoneAccount(challenge.phone, challenge.countryCode))
                .put("upMsg", challenge.content))
            requireSuccess(response)
            val token = response.data.optString("token")
            check(token.isNotBlank()) { "NetEase URS did not return a login token" }
            NeteaseUrsLoginCredentials(token, challenge.appId)
        }

    internal suspend fun verifyCode(phone: String, countryCode: String, code: String): NeteaseUrsLoginCredentials =
        withOperationTimeout("SMS verification", 60_000) {
            appId()
            val response = authenticatedRequest(SMS_LOGIN_PATH, JSONObject()
                .put("username", ursPhoneAccount(phone, countryCode)).put("smsCode", code))
            requireSuccess(response)
            val token = response.data.optString("token")
            check(token.isNotBlank()) { "NetEase URS did not return a login token" }
            // Keep the installation ID that issued this token; Music requires both values.
            NeteaseUrsLoginCredentials(token, currentAppId)
        }

    private suspend fun authenticatedRequest(path: String, parameters: JSONObject,
        body: JSONObject = JSONObject()): UrsResponse {
        var response = request(path, parameters, body)
        if (response.code == 12025) {
            initializationMutex.withLock {
                currentAppId = ""
                preferences.edit().remove("appId").apply()
            }
            appId()
            response = request(path, parameters, body)
        }
        return response
    }

    private suspend fun request(path: String, parameters: JSONObject,
        body: JSONObject = JSONObject()): UrsResponse = withContext(Dispatchers.IO) {
        var puzzle: NeteaseUrsPuzzle.Answer? = null
        repeat(2) { attempt ->
            val payload = commonParameters(parameters)
            puzzle?.let {
                payload.put("compId", it.id).put("compQues", it.question)
                    .put("compAns", JSONObject(it.values).toString())
            }
            val headers = crypto.encryptHeaders(payload)
            val requestBody = JSONObject(body.toString())
                .put("utid", currentAppId).put("rtid", requestId())
            val response = execute(path, headers, requestBody)
            val code = response.optInt("result", -1)
            val data = crypto.decryptResponse(response)
            if (code == 12056 && attempt == 0) {
                puzzle = NeteaseUrsPuzzle.solve(data)
            } else {
                Log.i(LOG_TAG, "URS response operation=${path.substringAfterLast('/')} code=$code")
                return@withContext UrsResponse(code, data, response.optString("msg"))
            }
        }
        error("URS computation verification failed")
    }

    private fun execute(path: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        var lastError: IOException? = null
        for (host in listOf("sdk.reg.163.com", "sdk2.reg.163.com")) {
            val request = Request.Builder().url("https://$host$path")
                .header("Content-Type", "application/json")
                .header("Connection", "close")
                .header("p3", ACCESS_ID)
                .apply { headers.forEach { (key, value) -> header(key, value) } }
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            try {
                return client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("URS HTTP ${response.code}")
                    JSONObject(response.body?.string() ?: throw IOException("Empty URS response"))
                }
            } catch (error: IOException) { lastError = error }
        }
        throw lastError ?: IOException("URS connection failed")
    }

    private fun commonParameters(parameters: JSONObject): JSONObject = JSONObject(parameters.toString())
        .put("appId", currentAppId).put("product", "music").put("platform", "android")
        .put("version", "1.6.12").put("appVersion", context.packageManager
            .getPackageInfo(context.packageName, 0).versionName)
        .put("systemVersion", Build.VERSION.SDK_INT).put("model", Build.MODEL)
        .put("resolution", "private").put("carrier", "private").put("aId", "private")
        .put("uniqueId", installationId).put("packageSign", crypto.packageSignature)
        .put("emulator", 0).put("ydUniqueId", "").put("ua", runCatching { WebSettings.getDefaultUserAgent(context) }
            .getOrElse { System.getProperty("http.agent").orEmpty() })
        .put("time", System.currentTimeMillis()).put("reqId", requestId())
        .put("wmf", if (Build.TAGS.orEmpty().contains("test-keys") || ROOT_PATHS.any { File(it).exists() }) 1 else 0)

    private fun requireSuccess(response: UrsResponse) {
        if (response.code != 200) throw NeteaseUrsException(
            apiCode = response.code,
            message = response.message.ifBlank { "网易账号认证失败" } + " [${response.code}]",
        )
    }

    private data class UrsResponse(val code: Int, val data: JSONObject, val message: String)
    private fun requestId(): String = UUID.randomUUID().toString().replace("-", "")
    private fun ursPhoneAccount(phone: String, countryCode: String): String =
        if (countryCode == "86") phone else "$countryCode-$phone"

    private companion object {
        const val LOG_TAG = "NeteaseUrsLogin"
        const val ACCESS_ID = "29c8a5f38b72a98ac74ac2c667d05dfa"
        const val INIT_PATH = "/uns/sdk/app/v1/ini"
        const val SMS_SEND_PATH = "/uns/sdk/login/mob/sms/v1/send"
        const val SMS_LOGIN_PATH = "/uns/sdk/login/mob/sms/v1/login"
        const val SMS_UP_CHECK_PATH = "/uns/sdk/login/mob/sms/v1/check"
        fun createClient() = OkHttpClient.Builder()
            .dns(NeteaseSecurityDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
        val ROOT_PATHS = listOf("/su", "/su/bin/su", "/sbin/su", "/data/local/xbin/su",
            "/data/local/bin/su", "/data/local/su", "/system/xbin/su", "/system/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su", "/system/app/Superuser.apk")
        const val APP_SIGN =
            "BHYOW1T4C3rr25657klSTYqpTPHBRtzMX4gD73mO/I0N3tnxEcjsshtIaXXv6+e79/" +
                "azwgyVwr/K4Ov0y3LuFdRjY04MXTWFun9r2xFc5Y1vnegkuvpOERfwFgbbSuYGT77tR/" +
                "+12VOGvOdufRSRWvk3xIHn3AV1CxjBu2admY3g6aEjbFqxcphy7T9aoGthod/JGmU4hJ7" +
                "HVFYH2rjOvCE30NKWxWpC4FTD1012JZ+kNNrIGOBBlUp6vvLWMhxoNaIePA3QV5alSX" +
                "Q4rlc8b9keNPBu8P+tpXI1LtrUx7fBx+VHrsAgPbfP8vXxFnzrXQVOKm1GgqB5btynA" +
                "4iT8g=="
    }
}

private suspend fun <T> withOperationTimeout(operation: String, timeout: Long,
    block: suspend () -> T): T = try {
    withTimeout(timeout) { block() }
} catch (error: TimeoutCancellationException) {
    throw NeteaseUrsException(message = "NetEase URS $operation timed out", cause = error)
}

class NeteaseUrsException(val apiCode: Int? = null, val subCode: Int? = null,
    val detailCode: Int? = null, message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

internal class NeteaseUrsLoginCredentials(val token: String, val appId: String)

/** Kept only in the current login screen; never persisted or logged. */
class NeteaseUrsUpSmsChallenge internal constructor(
    val phone: String,
    val countryCode: String,
    val destination: String,
    val content: String,
    internal val appId: String,
)

class NeteaseUrsUpSmsRequiredException(val challenge: NeteaseUrsUpSmsChallenge) :
    IllegalStateException("网易账号需要使用登录手机号发送短信完成安全验证。")
