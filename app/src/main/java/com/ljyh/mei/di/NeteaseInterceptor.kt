package com.ljyh.mei.di

import android.util.Log
import com.google.common.reflect.TypeToken
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.ljyh.mei.AppContext
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.DebugKey
import com.ljyh.mei.constants.DeviceIdKey
import com.ljyh.mei.constants.checkToken
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.encrypt.createRandomKey
import com.ljyh.mei.utils.encrypt.decryptEApi
import com.ljyh.mei.utils.encrypt.encryptEApi
import com.ljyh.mei.utils.encrypt.encryptWeAPI
import com.ljyh.mei.utils.get
import com.ljyh.mei.utils.getDeviceId
import com.ljyh.mei.utils.log.logPlaybackHistory
import com.ljyh.mei.utils.netease.ChineseIpUtils
import com.ljyh.mei.utils.netease.NeteaseUtils.getWNMCID
import kotlinx.coroutines.CancellationException
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import timber.log.Timber
import java.io.IOException
import kotlin.apply

internal const val NETEASE_EAPI_PROFILE_HEADER = "X-Netease-Eapi-Profile"
internal const val PLAYBACK_HISTORY_PROFILE = "playback-history"
internal const val MAX_PLAYBACK_HISTORY_RESPONSE_BYTES = 16_384

internal class PlaybackResponseBodyException(
    val httpStatus: Int,
    val failureKind: String,
    val failureReason: String,
    cause: IOException? = null,
) : IOException("Playback response $failureKind", cause)

internal fun readBoundedPlaybackResponseBody(
    body: ResponseBody,
    httpStatus: Int,
): ByteArray {
    return try {
        body.use {
            if (it.contentLength() > MAX_PLAYBACK_HISTORY_RESPONSE_BYTES) {
                throw playbackBodyTooLarge(httpStatus)
            }
            val source = it.source()
            if (source.request(MAX_PLAYBACK_HISTORY_RESPONSE_BYTES + 1L) ||
                source.buffer.size > MAX_PLAYBACK_HISTORY_RESPONSE_BYTES
            ) {
                throw playbackBodyTooLarge(httpStatus)
            }
            source.buffer.readByteArray(source.buffer.size)
        }
    } catch (error: PlaybackResponseBodyException) {
        throw error
    } catch (error: IOException) {
        throw PlaybackResponseBodyException(
            httpStatus = httpStatus,
            failureKind = "ResponseBodyReadFailure",
            failureReason = "response body read failed",
            cause = error,
        )
    }
}

private fun playbackBodyTooLarge(httpStatus: Int) = PlaybackResponseBodyException(
    httpStatus = httpStatus,
    failureKind = "ResponseBodyTooLarge",
    failureReason = "response body exceeds ${MAX_PLAYBACK_HISTORY_RESPONSE_BYTES} bytes",
)

class NeteaseInterceptor : Interceptor {

    private val gson by lazy {
        GsonBuilder()
            .registerTypeAdapter(Map::class.java, DynamicMapDeserializer())
            .disableHtmlEscaping()
            .create()
    }

    // 缓存随机值
    private val cachedNuid: String by lazy { createRandomKey(32) }
    private val cachedNmtid: String by lazy { createRandomKey(16) }
    private val fakeIP :String by lazy { ChineseIpUtils.generateRandomChineseIP() }
    private val cachedWnmcid: String by lazy { getWNMCID() } // 只计算一次保持会话一致性

    private val EAPI_CONFIG = mapOf(
        "os" to "pc",
        "osver" to "Microsoft-Windows-10-Professional-build-22631-64bit",
        "appver" to "3.0.18.203152",
        "channel" to "netease",
        "versioncode" to "6006066", // Android 高版本号
        "mobilename" to "Mi+A3",
        "buildver" to "1768990079",
        "resolution" to "2268x1080",
        "ua" to "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/3.0.18.203152"
    )

    private val PLAYBACK_HISTORY_USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    // =========================================================================
    //  配置 B：普通 Android 模式 (用于 weapi/api - 保持手机端正常行为)
    // =========================================================================
    private val ANDROID_CONFIG = mapOf(
        "os" to "android",
        "osver" to "14",
        "appver" to "8.20.20.231215173437",
        "channel" to "xiaomi",
        "versioncode" to "6006066",
        "mobilename" to "Mi+A3",
        "buildver" to System.currentTimeMillis().toString().take(10),
        "resolution" to "2268x1080",
        "ua" to "NeteaseMusic/9.4.32.251222163637" // 或者你之前的 Android UA
    )

    // 公用常量
    private val CONST_NMDI = "Q1NKTQkBDAAMIEF4coQMHcb6TLA7AAAAciOiJ%2F%2FOO4VQ7m%2FLvLJ1pD9CIsJP5mfzI4SusB%2BaNScGLpThEYBcPxGzj0pL5hLdZ7LqB2UVULdYgc0%3D"
    private val CONST_URS_APPID = "F2219AE9D7828A7D73E2006D000C61031D196A37DB497E3885B8298504867886B6F0E44087D61EFC06BE92279CD6EEC6"
    private val CONST_CSRF = "40ab38f0a305fc4c7ff68e636bcf34aa"

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url.toString()
        val cryptoMode = originalRequest.header(CRYPTO_MODE_HEADER) ?: determineCryptoMethod(url)
        val eapiProfile = originalRequest.header(NETEASE_EAPI_PROFILE_HEADER)
        val isPlaybackHistoryProfile =
            cryptoMode == "eapi" && eapiProfile == PLAYBACK_HISTORY_PROFILE
        val cookieOsOverride = originalRequest.header(COOKIE_OS_HEADER)
        val userAgentOverride = originalRequest.header(USER_AGENT_HEADER)
        val builder = originalRequest.newBuilder()
            .removeHeader(CRYPTO_MODE_HEADER)
            .removeHeader(CHECK_TOKEN_HEADER)
            .removeHeader(COOKIE_OS_HEADER)
            .removeHeader(USER_AGENT_HEADER)
            .removeHeader(NETEASE_EAPI_PROFILE_HEADER)

        if (isPlaybackHistoryProfile) {
            originalRequest.headers.names()
                .filter { it.startsWith("X-Netease-", ignoreCase = true) }
                .forEach(builder::removeHeader)
            builder
                .removeHeader("Referer")
                .removeHeader("X-Real-IP")
                .removeHeader("X-Forwarded-For")
        }

        if (cryptoMode == "eapi" && "/api/" in originalRequest.url.encodedPath) {
            builder.url(
                originalRequest.url.newBuilder()
                    .encodedPath(originalRequest.url.encodedPath.replaceFirst("/api/", "/eapi/"))
                    .build()
            )
        }

        val config = when {
            isPlaybackHistoryProfile -> mapOf(
                "os" to "osx",
                "osver" to "15.5",
                "appver" to "3.1.10.5100",
                "versioncode" to "140",
                "channel" to "netease",
                "resolution" to "1920x1080",
                "buildver" to (System.currentTimeMillis() / 1_000L).toString(),
            )
            cryptoMode == "eapi" -> EAPI_CONFIG
            else -> ANDROID_CONFIG
        }
        val requestOs = if (isPlaybackHistoryProfile) {
            config["os"]!!
        } else {
            cookieOsOverride ?: config["os"]!!
        }

        val deviceId = AppContext.instance.dataStore[DeviceIdKey] ?: getDeviceId()
        val musicU = AppContext.instance.dataStore[CookieKey] ?: ""
        val rawBody = getBodyString(originalRequest.body)
        val requiresCheckToken = !isPlaybackHistoryProfile && (
            originalRequest.header(CHECK_TOKEN_HEADER) == "true" ||
            rawBody.contains("\"checkToken\"")
        )
        val requestId = "${System.currentTimeMillis()}_${(Math.random() * 1000).toInt().toString().padStart(4, '0')}"
        val headerCsrf = if (isPlaybackHistoryProfile) "" else CONST_CSRF

        val cookieMap = buildMap {
            // 基础字段 (动态从 config 取)
            put("os", requestOs)
            put("appver", config["appver"]!!)
            put("osver", config["osver"]!!)
            put("channel", config["channel"]!!)
            put("versioncode", config["versioncode"]!!)
            config["mobilename"]?.let { put("mobilename", it) }
            put("buildver", config["buildver"]!!)
            put("resolution", config["resolution"]!!)

            // 固定字段
            put("deviceId", deviceId)
            put("sDeviceId", deviceId) // 部分接口需要这个
            put("ntes_kaola_ad", "1")
            put("_ntes_nuid", cachedNuid)
            put("WNMCID", cachedWnmcid)
            put("URS_APPID", CONST_URS_APPID)
            put("WEVNSM", "1.0.0")
            put("__csrf", headerCsrf)
            put("NMDI", CONST_NMDI)
            put("NMTID", cachedNmtid)

            if (musicU.isNotEmpty()) {
                put("MUSIC_U", musicU)
            }
            if (requiresCheckToken) {
                put("X-antiCheatToken", checkToken)
            }
        }

        val neteaseHeader = NeteaseHeader(
            osver = config["osver"]!!,
            deviceId = deviceId,
            os = requestOs,
            appver = config["appver"]!!,
            versioncode = config["versioncode"]!!,
            mobilename = config["mobilename"],
            buildver = config["buildver"]!!,
            resolution = config["resolution"]!!,
            __csrf = headerCsrf,
            channel = config["channel"]!!,
            requestId = requestId
        ).apply {
            if (musicU.isNotEmpty()) this.MUSIC_U = musicU
        }
        val useEApiHeaderCookie = isPlaybackHistoryProfile || (cryptoMode == "eapi" &&
            originalRequest.url.encodedPath in setOf(
                "/api/playlist/subscribe",
                "/api/playlist/unsubscribe",
                "/api/feedback/weblog",
            ))
        val cookie = if (useEApiHeaderCookie) {
            buildEApiCookieString(neteaseHeader, requiresCheckToken)
        } else {
            buildCookieString(cookieMap)
        }
        if (isPlaybackHistoryProfile) {
            builder.header("Cookie", cookie)
        } else {
            builder.addHeader("Cookie", cookie)
        }

        // UA 处理：
        // weapi: 永远使用 PC Web UA (Chrome/Edge)，这是 weapi 协议的特性
        // eapi: 使用 Config 中指定的 UA (PC Desktop)
        // api: 使用 Config 中指定的 UA (Android)
        val userAgent = if (isPlaybackHistoryProfile) {
            PLAYBACK_HISTORY_USER_AGENT
        } else {
            userAgentOverride ?: when (cryptoMode) {
                "weapi" -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0"
                else -> config["ua"]!!
            }
        }
        if (isPlaybackHistoryProfile) {
            builder
                .header("Accept", "*/*")
                .header("User-Agent", PLAYBACK_HISTORY_USER_AGENT)
        } else {
            builder.addHeader("User-Agent", userAgent)
        }

        if (cryptoMode == "weapi") {
            builder.addHeader("Referer", "https://music.163.com")
        }

        if (!isPlaybackHistoryProfile) {
            builder.addHeader("X-Real-IP", fakeIP)
            builder.addHeader("X-Forwarded-For", fakeIP)
        }

        if (isPlaybackHistoryProfile &&
            (BuildConfig.DEBUG || AppContext.instance.dataStore[DebugKey] == true)
        ) {
            logPlaybackHistory(
                Log.DEBUG,
                "PlaybackHistory profile os=%s appver=%s osver=%s channel=%s " +
                    "versioncode=%s resolution=%s requestUrl=%s signingUri=%s",
                config["os"],
                config["appver"],
                config["osver"],
                config["channel"],
                config["versioncode"],
                config["resolution"],
                builder.build().url,
                originalRequest.url.encodedPath.replaceFirst("/eapi/", "/api/"),
            )
        }

        handleRequestEncryption(
            builder = builder,
            originalRequest = originalRequest,
            cryptoMode = cryptoMode,
            url = url,
            headerObj = neteaseHeader,
            requiresCheckToken = requiresCheckToken,
        )

        val response = chain.proceed(builder.build())
        return handleResponseDecryption(response, cryptoMode, isPlaybackHistoryProfile)
    }

    private fun buildCookieString(map: Map<String, String>): String {
        return map.entries.joinToString("; ") {
            "${it.key}=${it.value}" // 抓包日志中并未对值进行过度 UrlEncode，保持原样即可，除非遇到特殊字符
        }
    }

    private fun buildEApiCookieString(
        headerObj: NeteaseHeader,
        requiresCheckToken: Boolean,
    ): String {
        val headerJson = gson.toJsonTree(headerObj).asJsonObject.apply {
            if (requiresCheckToken) {
                addProperty("X-antiCheatToken", checkToken)
            }
        }
        return headerJson.entrySet()
            .sortedBy { it.key }
            .joinToString("; ") { (key, value) ->
                "${encodeCookieComponent(key)}=${encodeCookieComponent(value.asString)}"
            }
    }

    private fun encodeCookieComponent(value: String): String {
        return java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
            .replace("+", "%20")
            .replace("%7E", "~")
    }

    private fun handleRequestEncryption(
        builder: Request.Builder,
        originalRequest: Request,
        cryptoMode: String,
        url: String,
        headerObj: NeteaseHeader,
        requiresCheckToken: Boolean,
    ) {
        val rawBody = getBodyString(originalRequest.body)

        when (cryptoMode) {
            "eapi" -> {
                val bodyMap: MutableMap<String, Any> =
                    if (rawBody.isNotEmpty()) {
                        try {
                            val jsonObject = gson.fromJson(rawBody, JsonObject::class.java)
                            jsonObject.entrySet().associateTo(mutableMapOf()) { (k, v) ->
                                k to when {
                                    v.isJsonPrimitive && v.asJsonPrimitive.isString -> v.asString
                                    v.isJsonPrimitive && v.asJsonPrimitive.isNumber -> v.asNumber
                                    v.isJsonPrimitive && v.asJsonPrimitive.isBoolean -> v.asBoolean
                                    else -> v
                                }
                            }
                        } catch (e: Exception) {
                            mutableMapOf()
                        }
                    } else {
                        mutableMapOf()
                    }

                val headerJson = gson.toJsonTree(headerObj).asJsonObject
                if (requiresCheckToken) {
                    headerJson.addProperty("X-antiCheatToken", checkToken)
                }
                bodyMap["header"] = headerJson
                bodyMap["e_r"] = false

                val newBodyJson = gson.toJson(bodyMap)
                val apiPath = originalRequest.url.encodedPath.replaceFirst("/eapi/", "/api/")

                val encryptedData = encryptEApi(apiPath, newBodyJson)
                builder.post(FormBody.Builder().add("params", encryptedData.params).build())
            }
            "weapi" -> {
                val encryptedData = encryptWeAPI(rawBody)
                builder.post(FormBody.Builder()
                    .add("params", encryptedData.params)
                    .add("encSecKey", encryptedData.encSecKey)
                    .build())
            }
            "api" -> {
                // API 模式通常用于 login，这里也可以简单处理
                val formBodyBuilder = FormBody.Builder()
                if (rawBody.isNotEmpty()) {
                    try {
                        val map = gson.fromJson(rawBody, Map::class.java)
                        for ((k, v) in map) formBodyBuilder.add(k.toString(), v.toString())
                    } catch (e: Exception) {}
                }
                builder.post(formBodyBuilder.build())
            }
        }
    }



    private fun handleResponseDecryption(
        response: Response,
        cryptoMode: String,
        isPlaybackHistoryProfile: Boolean,
    ): Response {
        if (isPlaybackHistoryProfile) {
            return handlePlaybackHistoryResponse(response)
        }
        if (cryptoMode == "eapi" && response.isSuccessful) {
            val body = response.body ?: return response
            val contentType = body.contentType()
            val encryptedBytes = body.use { it.bytes() }
            if (encryptedBytes.isEmpty()) {
                return response.withBody(encryptedBytes, contentType)
            }
            val firstByte = encryptedBytes.firstOrNull { it.toInt() > 32 }
            if (firstByte == '{'.code.toByte() || firstByte == '['.code.toByte()) {
                return response.withBody(encryptedBytes, contentType)
            }
            return try {
                Timber.tag("Decrypted Response").d("eapi")
                response.newBuilder()
                    .body(decryptEApi(encryptedBytes).toResponseBody(contentType))
                    .build()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.e(error, "Decrypt EAPI response failed; forwarding the raw response")
                response.withBody(encryptedBytes, contentType)
            }
        }
        return response
    }

    private fun handlePlaybackHistoryResponse(response: Response): Response {
        val body = response.body ?: return response
        val contentType = body.contentType()
        val responseBytes = readPlaybackHistoryResponseBody(response)
        if (!response.isSuccessful || responseBytes.isEmpty()) {
            return response.withBody(responseBytes, contentType)
        }

        val firstByte = responseBytes.firstOrNull { it.toInt() > 32 }
        if (firstByte == '{'.code.toByte() || firstByte == '['.code.toByte()) {
            return response.withBody(responseBytes, contentType)
        }

        return try {
            val decrypted = decryptEApi(responseBytes)
            if (decrypted.toByteArray(Charsets.UTF_8).size > MAX_PLAYBACK_HISTORY_RESPONSE_BYTES) {
                throw playbackBodyTooLarge(response.code)
            }
            response.newBuilder().body(decrypted.toResponseBody(contentType)).build()
        } catch (error: CancellationException) {
            throw error
        } catch (error: PlaybackResponseBodyException) {
            throw error
        } catch (error: Exception) {
            logPlaybackHistory(
                Log.WARN,
                "PlaybackHistory EAPI response decrypt failed type=%s status=%s",
                error::class.simpleName ?: "Exception",
                response.code,
            )
            response.withBody(responseBytes, contentType)
        }
    }

    internal fun readPlaybackHistoryResponseBody(response: Response): ByteArray {
        val body = response.body ?: return ByteArray(0)
        return readBoundedPlaybackResponseBody(body, response.code)
    }

    private fun Response.withBody(bytes: ByteArray, contentType: okhttp3.MediaType?): Response =
        newBuilder().body(bytes.toResponseBody(contentType)).build()

    private fun getBodyString(requestBody: RequestBody?): String {
        if (requestBody == null) return ""
        return try {
            val buffer = okio.Buffer()
            requestBody.writeTo(buffer)
            buffer.readUtf8()
        } catch (e: Exception) {
            ""
        }
    }

    private fun determineCryptoMethod(url: String): String {
        return when {
            url.contains("/weapi/") -> "weapi"
            url.contains("/eapi/") -> "eapi"
            else -> "api"
        }
    }

    private companion object {
        const val CRYPTO_MODE_HEADER = "X-Netease-Crypto"
        const val CHECK_TOKEN_HEADER = "X-Netease-Check-Token"
        const val COOKIE_OS_HEADER = "X-Netease-Cookie-OS"
        const val USER_AGENT_HEADER = "X-Netease-User-Agent"
    }
}
