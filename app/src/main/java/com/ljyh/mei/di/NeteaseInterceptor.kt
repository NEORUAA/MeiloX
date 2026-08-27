package com.ljyh.mei.di

import android.os.Build
import android.util.Log
import com.google.common.reflect.TypeToken
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.ljyh.mei.AppContext
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.DeviceIdKey
import com.ljyh.mei.constants.NeteaseCsrfKey
import com.ljyh.mei.constants.NeteaseMusicAKey
import com.ljyh.mei.constants.NeteaseRefreshTokenKey
import com.ljyh.mei.constants.SDeviceIdKey
import com.ljyh.mei.constants.checkToken
import com.ljyh.mei.data.network.NeteaseLoginSecurity
import com.ljyh.mei.data.network.NeteaseAegisSecurity
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.encrypt.createRandomKey
import com.ljyh.mei.utils.encrypt.decryptEApiBytes
import com.ljyh.mei.utils.encrypt.encryptEApi
import com.ljyh.mei.utils.encrypt.encryptWeAPI
import com.ljyh.mei.utils.get
import com.ljyh.mei.utils.getDeviceId
import com.ljyh.mei.utils.netease.ChineseIpUtils
import com.ljyh.mei.utils.netease.NeteaseUtils.getWNMCID
import okhttp3.FormBody
import okhttp3.Cookie
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import timber.log.Timber
import java.io.IOException
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.apply

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
    private val cachedAndroidCsrf: String by lazy {
        java.util.UUID.randomUUID().toString().replace("-", "")
    }

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

    // =========================================================================
    //  配置 B：普通 Android 模式 (用于 weapi/api - 保持手机端正常行为)
    // =========================================================================
    private val ANDROID_CONFIG by lazy {
        val metrics = AppContext.instance.resources.displayMetrics
        mapOf(
            "os" to "android",
            "osver" to Build.VERSION.RELEASE,
            "appver" to "9.5.70",
            "channel" to "xiaomi",
            "versioncode" to "9005070",
            "mobilename" to Build.MODEL.replace(" ", "+"),
            "buildver" to "260818213343",
            "resolution" to "${metrics.widthPixels}x${metrics.heightPixels}",
            "ua" to "NeteaseMusic/9.5.70.260818213343(9005070);Dalvik/2.1.0 " +
                "(Linux; U; Android ${Build.VERSION.RELEASE}; ${Build.MODEL} Build/${Build.ID})",
        )
    }

    // 公用常量
    private val CONST_NMDI = "Q1NKTQkBDAAMIEF4coQMHcb6TLA7AAAAciOiJ%2F%2FOO4VQ7m%2FLvLJ1pD9CIsJP5mfzI4SusB%2BaNScGLpThEYBcPxGzj0pL5hLdZ7LqB2UVULdYgc0%3D"
    private val CONST_URS_APPID = "F2219AE9D7828A7D73E2006D000C61031D196A37DB497E3885B8298504867886B6F0E44087D61EFC06BE92279CD6EEC6"
    private val CONST_CSRF = "40ab38f0a305fc4c7ff68e636bcf34aa"

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url.toString()
        val cryptoMode = originalRequest.header(CRYPTO_MODE_HEADER) ?: determineCryptoMethod(url)
        val suppliedAntiCheatToken =
            originalRequest.header(ANTI_CHEAT_TOKEN_HEADER)?.takeIf(String::isNotBlank)
        val ydDeviceToken = originalRequest.header(YD_DEVICE_TOKEN_HEADER)?.takeIf(String::isNotBlank)
        val loginChainId = originalRequest.header(LOGIN_CHAIN_ID_HEADER)?.takeIf(String::isNotBlank)
        val nmcid = originalRequest.header(NMCID_HEADER)?.takeIf(String::isNotBlank)
        val nmdi = originalRequest.header(NMDI_HEADER)?.takeIf(String::isNotBlank)
        val nmtid = originalRequest.header(NMTID_HEADER)?.takeIf(String::isNotBlank)
        val withoutAccount = originalRequest.header(WITHOUT_ACCOUNT_HEADER) == "true"
        val builder = originalRequest.newBuilder()
            .removeHeader(CRYPTO_MODE_HEADER)
            .removeHeader(CHECK_TOKEN_HEADER)
            .removeHeader(ANTI_CHEAT_TOKEN_HEADER)
            .removeHeader(YD_DEVICE_TOKEN_HEADER)
            .removeHeader(LOGIN_CHAIN_ID_HEADER)
            .removeHeader(NMCID_HEADER)
            .removeHeader(NMDI_HEADER)
            .removeHeader(NMTID_HEADER)
            .removeHeader(WITHOUT_ACCOUNT_HEADER)

        if (cryptoMode in setOf("eapi", "xeapi") && "/api/" in originalRequest.url.encodedPath) {
            builder.url(
                originalRequest.url.newBuilder()
                    .encodedPath(
                        originalRequest.url.encodedPath.replaceFirst(
                            "/api/",
                            if (cryptoMode == "xeapi") "/xeapi/" else "/eapi/",
                        ),
                    )
                    .build()
            )
        }

        val storedMusicU = AppContext.instance.dataStore[CookieKey].orEmpty()
        val hasMobileSession = storedMusicU.isNotBlank() &&
            !AppContext.instance.dataStore[NeteaseRefreshTokenKey].isNullOrBlank()
        val usesAndroidEapiIdentity = usesOfficialAndroidIdentity(
            cryptoMode = cryptoMode,
            encodedPath = originalRequest.url.encodedPath,
            hasMobileSession = hasMobileSession,
        )
        val usesXeapiIdentity = cryptoMode == "xeapi"
        val config = if (cryptoMode == "eapi" && !usesAndroidEapiIdentity) {
            EAPI_CONFIG
        } else {
            ANDROID_CONFIG
        }

        val deviceId = AppContext.instance.dataStore[DeviceIdKey] ?: getDeviceId()
        val storedSDeviceId = AppContext.instance.dataStore[SDeviceIdKey].orEmpty()
        val sDeviceId = if (usesXeapiIdentity) deviceId else storedSDeviceId
        val musicU = if (withoutAccount || !hasMobileSession) {
            ""
        } else {
            storedMusicU
        }
        val musicA = if (withoutAccount || !hasMobileSession) {
            ""
        } else {
            AppContext.instance.dataStore[NeteaseMusicAKey].orEmpty()
        }
        val rawBody = getBodyString(originalRequest.body)
        val requiresCheckToken = originalRequest.header(CHECK_TOKEN_HEADER) == "true" ||
            rawBody.contains("\"checkToken\"")
        val antiCheatToken = suppliedAntiCheatToken ?: extractCheckToken(rawBody) ?: checkToken
        val csrfToken = when {
            !usesAndroidEapiIdentity -> CONST_CSRF
            withoutAccount || !hasMobileSession -> cachedAndroidCsrf
            else -> AppContext.instance.dataStore[NeteaseCsrfKey]
                ?.takeIf(String::isNotBlank)
                ?: cachedAndroidCsrf
        }
        val requestId = "${System.currentTimeMillis()}_${(Math.random() * 1000).toInt().toString().padStart(4, '0')}"

        val cookieMap = buildMap {
            // 基础字段 (动态从 config 取)
            put("os", config["os"]!!)
            put("appver", config["appver"]!!)
            put("osver", config["osver"]!!)
            put("channel", config["channel"]!!)
            put("versioncode", config["versioncode"]!!)
            put("mobilename", config["mobilename"]!!)
            put("buildver", config["buildver"]!!)
            put("resolution", config["resolution"]!!)

            // 固定字段
            put("deviceId", deviceId)
            sDeviceId.takeIf(String::isNotBlank)?.let { put("sDeviceId", it) }
            put("brand", Build.BRAND)
            put("packageType", "release")
            put("ntes_kaola_ad", "1")
            if (!usesXeapiIdentity) {
                put("_ntes_nuid", cachedNuid)
                put("WNMCID", cachedWnmcid)
                put("WEVNSM", "1.0.0")
            }
            put("__csrf", csrfToken)

            if (usesAndroidEapiIdentity) {
                nmcid?.let { put("NMCID", it) }
                put("EVNSM", "1.0.0")
                nmdi?.let { put("NMDI", it) }
                nmtid?.let { put("NMTID", it) }
                if (usesXeapiIdentity) {
                    put("URS_APPID", CONST_URS_APPID)
                    put("minors_mode_age_range", "0")
                    put("screenType", "normal")
                }
            }

            if (!usesAndroidEapiIdentity) {
                put("URS_APPID", CONST_URS_APPID)
                put("NMDI", CONST_NMDI)
                put("NMTID", cachedNmtid)
            }

            if (musicU.isNotEmpty()) {
                put("MUSIC_U", musicU)
            }
            if (musicA.isNotEmpty()) {
                put("MUSIC_A", musicA)
            }
        }

        val neteaseHeader = NeteaseHeader(
            osver = config["osver"]!!,
            deviceId = deviceId,
            os = config["os"]!!,
            appver = config["appver"]!!,
            versioncode = config["versioncode"]!!,
            mobilename = config["mobilename"]!!,
            buildver = config["buildver"]!!,
            resolution = config["resolution"]!!,
            __csrf = csrfToken,
            channel = config["channel"]!!,
            requestId = requestId
        ).apply {
            if (musicU.isNotEmpty()) this.MUSIC_U = musicU
            if (musicA.isNotEmpty()) this.MUSIC_A = musicA
        }
        val useEApiHeaderCookie = cryptoMode == "eapi" &&
            originalRequest.url.encodedPath in setOf(
                "/api/playlist/subscribe",
                "/api/playlist/unsubscribe",
            )
        builder.addHeader(
            "Cookie",
            if (useEApiHeaderCookie) {
                buildEApiCookieString(neteaseHeader)
            } else {
                buildCookieString(cookieMap)
            },
        )

        // UA 处理：
        // weapi: 永远使用 PC Web UA (Chrome/Edge)，这是 weapi 协议的特性
        // eapi: 使用 Config 中指定的 UA (PC Desktop)
        // api: 使用 Config 中指定的 UA (Android)
        val userAgent = when (cryptoMode) {
            "weapi" -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0"
            else -> config["ua"]!!
        }
        builder.addHeader("User-Agent", userAgent)

        if (cryptoMode == "weapi") {
            builder.addHeader("Referer", "https://music.163.com")
        }

        if (usesAndroidEapiIdentity) {
            loginChainId?.let { builder.header("x-login-chain-id", it) }
            if (requiresCheckToken) builder.header("X-antiCheatToken", antiCheatToken)
            builder.header("x-appver", config["appver"]!!)
            builder.header("x-buildver", config["buildver"]!!)
            builder.header("x-deviceId", deviceId)
            sDeviceId.takeIf(String::isNotBlank)?.let { builder.header("x-sDeviceId", it) }
            builder.header("x-os", config["os"]!!)
            builder.header("x-osver", config["osver"]!!)
            musicU.takeIf(String::isNotBlank)?.let { builder.header("x-music-u", it) }
            builder.header("x-mam-custommark", if (cryptoMode == "xeapi") "cronet" else "okhttp")
            builder.header("X-Client-Enc-State", "ENCRYPTED")
            if (cryptoMode == "xeapi") {
                builder.header("X-AEAPI", "true")
                builder.header("mconfig-info", XEAPI_MCONFIG_INFO)
            } else {
                builder.header("x-channel", config["channel"]!!)
                builder.header("x-mobilename", config["mobilename"]!!.replace("+", " "))
            }
        } else {
            builder.addHeader("X-Real-IP", fakeIP)
            builder.addHeader("X-Forwarded-For", fakeIP)
        }

        handleRequestEncryption(
            builder = builder,
            originalRequest = originalRequest,
            cryptoMode = cryptoMode,
            url = url,
            headerObj = neteaseHeader,
            usesAndroidEapiIdentity = usesAndroidEapiIdentity,
            requiresCheckToken = requiresCheckToken,
            antiCheatToken = antiCheatToken,
            ydDeviceToken = ydDeviceToken,
            deviceId = deviceId,
            sDeviceId = sDeviceId,
        )

        val response = chain.proceed(builder.build())
        NeteaseAegisSecurity.acceptSession(
            sessionId = response.header(AEGIS_SESSION_ID_HEADER),
            sessionKey = response.header(AEGIS_SESSION_KEY_HEADER),
        )
        Cookie.parseAll(response.request.url, response.headers)
            .firstOrNull { it.name.equals("NMTID", ignoreCase = true) }
            ?.value
            ?.takeIf(String::isNotBlank)
            ?.let { NeteaseLoginSecurity.acceptServerTrackId(it) }
        return handleResponseDecryption(response, cryptoMode)
    }

    private fun buildCookieString(map: Map<String, String>): String {
        return map.entries.joinToString("; ") {
            "${it.key}=${it.value}" // 抓包日志中并未对值进行过度 UrlEncode，保持原样即可，除非遇到特殊字符
        }
    }

    private fun buildEApiCookieString(
        headerObj: NeteaseHeader,
    ): String {
        val headerJson = gson.toJsonTree(headerObj).asJsonObject
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
        usesAndroidEapiIdentity: Boolean,
        requiresCheckToken: Boolean,
        antiCheatToken: String,
        ydDeviceToken: String?,
        deviceId: String,
        sDeviceId: String,
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

                bodyMap["header"] = if (usesAndroidEapiIdentity) {
                    "{}"
                } else {
                    gson.toJsonTree(headerObj).asJsonObject
                }
                bodyMap["e_r"] = false

                val newBodyJson = gson.toJson(bodyMap)
                val apiPath = originalRequest.url.encodedPath
                    .replaceFirst("/eapi/", "/api/")

                val encryptedData = encryptEApi(apiPath, newBodyJson)
                builder.post(
                    FormBody.Builder()
                        .add("params", encryptedData.params)
                        .apply {
                            ydDeviceToken?.takeIf(String::isNotBlank)?.let {
                                add("ydDeviceToken", it)
                            }
                        }
                        .build(),
                )
            }
            "xeapi" -> {
                val formBody = FormBody.Builder().apply {
                    if (rawBody.isNotEmpty()) {
                        val body = gson.fromJson(rawBody, JsonObject::class.java)
                        body.entrySet().forEach { (key, value) ->
                            add(
                                key,
                                if (value.isJsonPrimitive) value.asString else gson.toJson(value),
                            )
                        }
                    }
                    ydDeviceToken?.takeIf(String::isNotBlank)?.let {
                        add("ydDeviceToken", it)
                    }
                }.build()
                val envelope = linkedMapOf<String, Any>(
                    "content" to getBodyString(formBody),
                    "queryString" to buildXeapiQueryString(originalRequest),
                )
                val encrypted = NeteaseAegisSecurity.encryptActive(gson.toJson(envelope))
                if (usesAndroidEapiIdentity) {
                    Log.i(
                        NeteaseLoginSecurity.TAG,
                        "XEAPI request ready innerBytes=${getBodyString(formBody).toByteArray().size} " +
                            "ydDeviceToken=${ydDeviceToken?.length ?: 0} " +
                            "deviceId=${deviceId.length} sDeviceId=${sDeviceId.length}",
                    )
                }
                builder.post(encrypted.toRequestBody(XEAPI_FORM_MEDIA_TYPE))
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



    private fun handleResponseDecryption(response: Response, cryptoMode: String): Response {
        if (cryptoMode in setOf("eapi", "xeapi") && response.isSuccessful) {
            val body = response.body
            val contentType = body.contentType()
            val encryptedBytes = body.bytes()
            if (encryptedBytes.isEmpty()) {
                return response.newBuilder().body(encryptedBytes.toResponseBody(contentType)).build()
            }
            val firstByte = encryptedBytes.firstOrNull { it.toInt() > 32 }
            if (firstByte == '{'.code.toByte() || firstByte == '['.code.toByte()) {
                return response.newBuilder().body(encryptedBytes.toResponseBody(contentType)).build()
            }
            return runCatching {
                Timber.tag("Decrypted Response").d(cryptoMode)
                val decrypted = decryptEApiBytes(encryptedBytes)
                val decoded = if (
                    decrypted.size >= 2 &&
                    decrypted[0] == GZIP_MAGIC_FIRST &&
                    decrypted[1] == GZIP_MAGIC_SECOND
                ) {
                    GZIPInputStream(ByteArrayInputStream(decrypted)).use { it.readBytes() }
                } else {
                    decrypted
                }
                decoded.toResponseBody(contentType)
            }.fold(
                onSuccess = { response.newBuilder().body(it).build() },
                onFailure = { error ->
                    Timber.e(error, "Decrypt EAPI response failed; forwarding the raw response")
                    response.newBuilder().body(encryptedBytes.toResponseBody(contentType)).build()
                },
            )
        }
        return response
    }

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

    private fun buildXeapiQueryString(request: Request): String {
        val encodedQuery = request.url.encodedQuery.orEmpty()
        if ("e_r" in request.url.queryParameterNames) return encodedQuery
        return if (encodedQuery.isBlank()) "e_r=true" else "$encodedQuery&e_r=true"
    }

    private fun extractCheckToken(rawBody: String): String? = runCatching {
        gson.fromJson(rawBody, JsonObject::class.java)
            .get("checkToken")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.takeIf(String::isNotBlank)
    }.getOrNull()

    private fun determineCryptoMethod(url: String): String {
        return when {
            url.contains("/weapi/") -> "weapi"
            url.contains("/xeapi/") -> "xeapi"
            url.contains("/eapi/") -> "eapi"
            else -> "api"
        }
    }

    private companion object {
        const val CRYPTO_MODE_HEADER = "X-Netease-Crypto"
        const val CHECK_TOKEN_HEADER = "X-Netease-Check-Token"
        const val ANTI_CHEAT_TOKEN_HEADER = "X-Netease-Anti-Cheat-Token"
        const val YD_DEVICE_TOKEN_HEADER = "X-Netease-Yd-Device-Token"
        const val LOGIN_CHAIN_ID_HEADER = "X-Netease-Login-Chain-Id"
        const val NMCID_HEADER = "X-Netease-NMCID"
        const val NMDI_HEADER = "X-Netease-NMDI"
        const val NMTID_HEADER = "X-Netease-NMTID"
        const val WITHOUT_ACCOUNT_HEADER = "X-Netease-Without-Account"
        const val AEGIS_SESSION_ID_HEADER = "x-encr-ssid"
        const val AEGIS_SESSION_KEY_HEADER = "x-encr-sskey"
        val XEAPI_FORM_MEDIA_TYPE =
            "application/x-www-form-urlencoded;charset=utf-8".toMediaType()
        val GZIP_MAGIC_FIRST = 0x1F.toByte()
        val GZIP_MAGIC_SECOND = 0x8B.toByte()
        const val XEAPI_MCONFIG_INFO =
            "{\"IuRPVVmc3WWul9fT\":{\"version\":\"118190080\",\"appver\":\"9.5.70\"}," +
                "\"tPJJnts2H31BZXmp\":{\"version\":\"5388288\",\"appver\":\"4.78.0\"}," +
                "\"c0Ve6C0uNl2Am0Rl\":{\"version\":\"276480\",\"appver\":\"1.4.30\"}," +
                "\"zr4bw6pKFDIZScpo\":{\"version\":\"3772416\",\"appver\":\"2.40.0\"}}"
    }
}

internal fun usesOfficialAndroidIdentity(
    cryptoMode: String,
    encodedPath: String,
    hasMobileSession: Boolean = false,
): Boolean =
    cryptoMode == "xeapi" || (
        cryptoMode == "eapi" && (hasMobileSession ||
            encodedPath.endsWith("/login/qrcode/server/login") ||
                encodedPath.endsWith("/middle/device-info/get") ||
                encodedPath.endsWith("/bsr/sk/get")
            )
        )
