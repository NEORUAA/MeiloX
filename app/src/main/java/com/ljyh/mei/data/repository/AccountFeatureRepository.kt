package com.ljyh.mei.data.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.data.model.melox.*
import com.ljyh.mei.data.network.NETEASE_EXPECTED_SESSION_HEADER
import com.ljyh.mei.data.network.NeteaseLoginSecurity
import com.ljyh.mei.data.network.NeteaseSecurityCookies
import com.ljyh.mei.data.network.neteaseSessionFingerprint
import com.ljyh.mei.data.network.api.MeloXDirectService
import com.ljyh.mei.di.NETEASE_EAPI_PROFILE_HEADER
import com.ljyh.mei.di.OFFICIAL_ANDROID_PROFILE
import com.ljyh.mei.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

class AccountFeatureException(val code: Int, message: String) : Exception(message)

@Singleton
class AccountFeatureRepository @Inject constructor(
    @Named("MeloXEapi") private val service: MeloXDirectService,
    @Named("MeloXWeapi") private val webService: MeloXDirectService,
    @ApplicationContext private val context: Context,
    private val security: NeteaseLoginSecurity,
) {
    val sessionChanges = context.dataStore.data.map {
        it[CookieKey].orEmpty().takeIf(String::isNotBlank)?.let(::neteaseSessionFingerprint).orEmpty()
    }.distinctUntilChanged()

    private suspend fun call(path: String, body: Map<String, Any> = emptyMap(), checkToken: Boolean = false): JsonObject {
        val preferences = context.dataStore.data.first()
        val credential = preferences[CookieKey].orEmpty()
        check(credential.isNotBlank()) { "请先登录网易云音乐" }
        val fields = body.toMutableMap()
        val token = if (checkToken) security.freshCheckToken() else null
        if (token != null) fields["checkToken"] = token
        val cookies = if (checkToken) security.securityCookies() else null
        return requestAccountFeature(service, path, fields, neteaseSessionFingerprint(credential), token, cookies)
    }

    suspend fun summary(): AccountSecuritySummary = coroutineScope {
        val phone = async { attempt { phoneBinding() } }
        val protection = async { attempt { call("/api/usersafe/loginprotect/status/get").get("data")?.asBoolean } }
        val devices = async { attempt { devices() } }
        val bindings = async { attempt { bindings() } }
        val p = phone.await(); val l = protection.await(); val d = devices.await(); val b = bindings.await()
        AccountSecuritySummary(
            phone = p.getOrNull()?.second,
            phoneBound = p.getOrNull()?.first == true,
            protectionEnabled = l.getOrNull(),
            deviceCount = d.getOrNull()?.size,
            bindings = b.getOrNull(),
            errors = listOf(p, l, d, b).mapNotNull { it.exceptionOrNull()?.message }.distinct(),
        )
    }

    private suspend fun phoneBinding(): Pair<Boolean, String?> {
        val preferences = context.dataStore.data.first()
        val expected = neteaseSessionFingerprint(preferences[CookieKey].orEmpty())
        val response = validateAccountFeatureResponse(webService.post(
            "/weapi/user/binding/cellphone",
            headers = mapOf(NETEASE_EXPECTED_SESSION_HEADER to expected),
        ))
        val data = response.accountObject("data") ?: return false to null
        return data.accountBoolean("bind") to data.accountText("number").takeIf(String::isNotBlank)
    }

    suspend fun devices(): List<AccountDevice> = parseAccountDevices(call(
        "/api/middle/user/device/list", mapOf("status" to "", "excStatus" to "9"),
    ))

    suspend fun authorizations(history: Boolean, cursor: String = ""): AccountAuthorizationPage =
        parseAccountAuthorizations(call("/api/oauth/app/list/get", mapOf(
            "type" to if (history) "history" else "access",
            "page" to Gson().toJson(mapOf("cursor" to cursor, "size" to 30)),
        )))

    suspend fun removeDevice(key: String, captcha: String = "") {
        require(key.isNotBlank())
        call("/api/middle/user/security/device/kickoff", mapOf(
            "deviceKey" to key, "deviceKeys" to "", "captcha" to captcha,
        ))
    }

    suspend fun revokeAuthorization(clientId: String) {
        require(clientId.isNotBlank())
        call("/api/oauth/app/cancel", mapOf("clientId" to clientId))
    }

    suspend fun sendDeviceVerificationCode(countryCode: String = "86") {
        call("/api/sms/captcha/safe/sent", mapOf("ctcode" to countryCode))
    }

    suspend fun bindings(): List<AccountBinding> {
        var id = context.dataStore.data.first()[UserIdKey]?.toLongOrNull()
        if (id == null || id <= 0) {
            val account = call("/api/nuser/account/get")
            id = account.accountObject("profile")?.accountLong("userId")
        }
        check(id != null && id > 0) { "无法读取当前账号" }
        return parseAccountBindings(call("/api/user/bindings/$id"))
    }

    suspend fun musician(): MusicianAccount {
        val authentication = call("/api/nmusician/workbench/common/user/authentication", checkToken = true)
        val info = call("/api/nmusician/entrance/user/musician/info/get", checkToken = true)
        return parseMusicianAccount(authentication, info)
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { Result.failure(failure) }
}

internal suspend fun requestAccountFeature(
    service: MeloXDirectService,
    path: String,
    fields: Map<String, Any>,
    expectedSession: String,
    antiCheatToken: String? = null,
    securityCookies: NeteaseSecurityCookies? = null,
): JsonObject {
    require(path.startsWith("/api/"))
    return validateAccountFeatureResponse(service.post(
        "https://interface3.music.163.com" + path.replaceFirst("/api/", "/eapi/"), fields,
        cryptoMode = "eapi", antiCheatToken = antiCheatToken,
        nmcid = securityCookies?.nmcid, nmdi = securityCookies?.nmdi, nmtid = securityCookies?.nmtid,
        headers = mapOf(NETEASE_EAPI_PROFILE_HEADER to OFFICIAL_ANDROID_PROFILE,
            NETEASE_EXPECTED_SESSION_HEADER to expectedSession),
    ))
}

internal fun validateAccountFeatureResponse(value: JsonObject): JsonObject {
    val code = value.accountInt("code") ?: 200
    if (code !in 200..299) throw AccountFeatureException(code,
        value.accountText("message").ifBlank { value.accountText("msg") }.ifBlank { "请求失败 ($code)" })
    return value
}

internal fun parseAccountDevices(response: JsonObject): List<AccountDevice> {
    validateAccountFeatureResponse(response)
    val data = response.accountObject("data") ?: return emptyList()
    return data.getAsJsonArray("userDeviceList")?.toList().orEmpty().mapNotNull { value ->
        val item = value.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
        val key = item.accountText("deviceKey")
        if (key.isBlank()) return@mapNotNull null
        AccountDevice(key, item.accountText("deviceName"), item.accountText("loginPlace").ifBlank { item.accountText("region") },
            item.accountLong("lastActiveTime") ?: 0L, item.accountBoolean("ifLoginDevice"), item.accountText("os"))
    }.sortedByDescending(AccountDevice::current)
}

internal fun parseAccountAuthorizations(response: JsonObject): AccountAuthorizationPage {
    validateAccountFeatureResponse(response)
    val data = response.accountObject("data")
    val records = data?.getAsJsonArray("records")?.toList().orEmpty().mapNotNull { value ->
        val item = value.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
        val id = item.accountText("clientId")
        if (id.isBlank()) null else AccountAuthorization(id,
            item.accountText("clientName").ifBlank { item.accountText("appName") }.ifBlank { item.accountText("name") },
            item.accountText("description"))
    }
    val page = data?.accountObject("page")
    return AccountAuthorizationPage(records, page?.accountText("cursor")?.takeIf(String::isNotBlank), page?.accountBoolean("more") == true)
}

internal fun parseAccountBindings(response: JsonObject): List<AccountBinding> {
    validateAccountFeatureResponse(response)
    val bindings = response.get("bindings") ?: response.accountObject("data")?.get("bindings")
    check(bindings?.isJsonArray == true) { "无法读取第三方账号状态" }
    return bindings.asJsonArray.toList().mapNotNull { value ->
        val item = value.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
        val type = item.accountInt("type") ?: return@mapNotNull null
        val publicInfo = runCatching { JsonParser.parseString(item.accountText("tokenJsonStr")).asJsonObject }.getOrNull()
        AccountBinding(type, true,
            publicInfo?.accountText("nickname").orEmpty()
                .ifBlank { publicInfo?.accountText("screen_name").orEmpty() }
                .ifBlank { publicInfo?.accountText("name").orEmpty() })
    }
}

internal fun parseMusicianAccount(authentication: JsonObject, info: JsonObject): MusicianAccount {
    val auth = validateAccountFeatureResponse(authentication).accountObject("data") ?: JsonObject()
    val entry = validateAccountFeatureResponse(info).accountObject("data") ?: JsonObject()
    return MusicianAccount(auth.accountBoolean("musician"), auth.accountBoolean("dj"), auth.accountBoolean("daren"),
        auth.accountBoolean("live"), entry.accountInt("userStatus") ?: 0,
        entry.accountInt("certStatus") ?: 0, entry.accountInt("leftProductionTimes"))
}

internal fun JsonObject.accountObject(key: String): JsonObject? = get(key)?.takeIf(JsonElement::isJsonObject)?.asJsonObject
internal fun JsonObject.accountText(key: String): String = get(key)?.takeIf(JsonElement::isJsonPrimitive)?.asString.orEmpty()
internal fun JsonObject.accountBoolean(key: String): Boolean = runCatching { get(key)?.asBoolean == true }.getOrDefault(false)
internal fun JsonObject.accountInt(key: String): Int? = runCatching { get(key)?.asInt }.getOrNull()
internal fun JsonObject.accountLong(key: String): Long? = runCatching { get(key)?.asLong }.getOrNull()
