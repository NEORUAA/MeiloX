package com.ljyh.mei.data.network

import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.DeviceIdKey
import com.ljyh.mei.constants.NeteaseCsrfKey
import com.ljyh.mei.constants.NeteaseMusicAKey
import com.ljyh.mei.constants.NeteaseRefreshTokenKey
import com.ljyh.mei.constants.NeteaseUrsAppIdKey
import com.ljyh.mei.constants.SDeviceIdKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.constants.UserNicknameKey
import com.ljyh.mei.constants.UserAvatarUrlKey
import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.di.NeteaseInterceptor
import com.ljyh.mei.di.RetrofitModule
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.encrypt.decryptEApi
import com.ljyh.mei.ui.screen.account.readPcQrImage
import com.ljyh.mei.ui.screen.account.parseNeteasePcLoginQr
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeteaseLoginFlowInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun initializesUrsWithoutRequestingAnSms() = runBlocking {
        NeteaseLoginTestRuntime.urs.prepare()
        assertTrue(NeteaseLoginTestRuntime.urs.appId().isNotBlank())
    }

    @Test
    fun prewarmsDeviceSecurityWithoutAnAccount() = runBlocking {
        val security = NeteaseLoginTestRuntime.security
        val token = security.prepareForPcQrLogin()
        val cookies = security.securityCookies()
        assertTrue(token.isNotBlank())
        assertTrue(security.pcQrCheckToken().isNotBlank())
        assertTrue(cookies.nmcid.isNotBlank())
        assertTrue(cookies.nmdi.isNotBlank())
    }

    @Test
    fun registersLoginDeviceWithoutAccountCredentials() = runBlocking {
        assertTrue(NeteaseLoginTestRuntime.repository.prepareNeteaseLoginDevice().isNotBlank())
        val preferences = context.dataStore.data.first()
        assertTrue(!preferences[DeviceIdKey].isNullOrBlank())
        assertTrue(!preferences[SDeviceIdKey].isNullOrBlank())
    }

    @Test
    fun readsWebQrCodeFromAnImage() = runBlocking {
        assertEquals(
            "https://music.163.com/login?codekey=test-web-key&login_traceId=test-web-trace",
            readQrTestImage("pc-login-test.png"),
        )
    }

    @Test
    fun readsCurrentWebsiteQrImageAndParsesItsKey() = runBlocking {
        val rawValue = readQrTestImage("web-scanlogin-test.png")
        assertEquals(
            "https://music.163.com/st/platform/scanlogin?codekey=00000000-0000-0000-0000-000000000000" +
                "&chainId=" + "t".repeat(62) + "&hdw_device=web&hdw_appid=web&hitExp=1",
            rawValue,
        )
        val payload = parseNeteasePcLoginQr(rawValue!!)
        assertEquals("00000000-0000-0000-0000-000000000000", payload?.key)
        assertNull(payload?.clientTraceId)
    }

    @Test
    fun returnsUnsupportedQrContentsForAnAccurateErrorMessage() = runBlocking {
        val rawValue = readQrTestImage("unsupported-qr-test.png")
        assertEquals("https://example.com", rawValue)
        assertNull(parseNeteasePcLoginQr(rawValue!!))
    }

    @Test
    fun distinguishesImageWithoutQrFromAnUnsupportedQr() = runBlocking {
        assertNull(readQrTestImage("no-qr-test.png"))
    }

    private suspend fun readQrTestImage(asset: String): String? {
        val file = File(context.cacheDir, "netease/$asset")
        file.parentFile!!.mkdirs()
        return try {
            InstrumentationRegistry.getInstrumentation().context.assets
                .open(asset).use { input -> file.outputStream().use(input::copyTo) }
            readPcQrImage(context, Uri.fromFile(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun acceptsParentDomainLoginCookiesWithoutARefreshToken() {
        val response = Response.Builder()
            .request(Request.Builder().url("https://interface.music.163.com/eapi/login/cellphone").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .addHeader("Set-Cookie", "MUSIC_U=test-session; Domain=.music.163.com; Path=/")
            .addHeader("Set-Cookie", "__csrf=test-csrf; Domain=.music.163.com; Path=/").build()
        assertEquals("test-session", readNeteaseLoginCookies(response).musicU)
    }

    @Test
    fun sendsAuthenticatedQrRequestWithoutARefreshToken() = runBlocking(Dispatchers.IO) {
        val store = context.dataStore
        val saved = store.data.first()
        val keys = listOf(CookieKey, DeviceIdKey, SDeviceIdKey, NeteaseCsrfKey, NeteaseRefreshTokenKey, NeteaseUrsAppIdKey)
        try {
            store.edit {
                it[CookieKey] = "test-account-session"
                it[DeviceIdKey] = "test-local-device"
                it[SDeviceIdKey] = "test-server-device"
                it[NeteaseCsrfKey] = "test-csrf"
                it[NeteaseUrsAppIdKey] = "test-account-urs-id"
                it.remove(NeteaseRefreshTokenKey)
            }
            // The terminal interceptor captures the encrypted request; no QR action
            // or fake account session is sent to NetEase.
            val client = OkHttpClient.Builder().addInterceptor(NeteaseInterceptor())
                .addInterceptor { chain ->
                    val request = chain.request()
                    assertEquals("/eapi/login/qrcode/server/login", request.url.encodedPath)
                    assertEquals("test-account-session", request.header("x-music-u"))
                    assertEquals("test-server-device", request.header("x-sDeviceId"))
                    assertTrue(request.header("Cookie").orEmpty().contains("MUSIC_U=test-account-session"))
                    assertTrue(request.header("Cookie").orEmpty().contains("URS_APPID=test-account-urs-id"))
                    assertEquals("test-header-token", request.header("X-antiCheatToken"))
                    val form = request.body as FormBody
                    val encrypted = form.value((0 until form.size).single { form.name(it) == "params" })
                    val payload = decryptEApi(encrypted.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                    assertTrue(payload.startsWith("/api/login/qrcode/server/login-36cd479b6b5-"))
                    assertTrue(payload.contains("\"checkToken\":\"test-body-token\""))
                    assertTrue(payload.contains("\"type\":\"1\""))
                    assertTrue(payload.contains("\"header\":\"{}\""))
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .body("{\"code\":200}".toResponseBody("application/json".toMediaType())).build()
                }.build()
            client.newCall(Request.Builder()
                .url("https://interface.music.163.com/api/login/qrcode/server/login")
                .header("X-Netease-Crypto", "eapi")
                .header("X-Netease-Anti-Cheat-Token", "test-header-token")
                .post("{\"checkToken\":\"test-body-token\",\"type\":\"1\"}"
                    .toRequestBody("application/json".toMediaType())).build())
                .execute().use { assertTrue(it.isSuccessful) }
        } finally {
            store.edit { preferences ->
                keys.forEach { key ->
                    val value = saved[key]
                    if (value == null) preferences.remove(key) else preferences[key] = value
                }
            }
        }
    }

    @Test
    fun sendsSmsTokenWithItsIssuingAppIdWhileReplacingAnAccount() = runBlocking(Dispatchers.IO) {
        val store = context.dataStore
        val saved = store.data.first()
        try {
            store.edit {
                it[CookieKey] = "test-old-account"
                it[NeteaseUrsAppIdKey] = "test-old-urs-id"
            }
            val client = OkHttpClient.Builder().addInterceptor(NeteaseInterceptor())
                .addInterceptor { chain ->
                    val request = chain.request()
                    val cookies = request.header("Cookie").orEmpty()
                    assertTrue(cookies.contains("URS_APPID=test-token-issuing-id"))
                    assertFalse(cookies.contains("test-old-urs-id"))
                    assertFalse(cookies.contains("MUSIC_U="))
                    assertNull(request.header("X-Netease-URS-App-Id"))
                    val form = request.body as FormBody
                    val encrypted = form.value((0 until form.size).single { form.name(it) == "params" })
                    val payload = decryptEApi(encrypted.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                    assertTrue(payload.contains("\"ursToken\":\"test-urs-token\""))
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .body("{\"code\":200}".toResponseBody("application/json".toMediaType())).build()
                }.build()
            val eapi = RetrofitModule.provideMeloXEapiService(RetrofitModule.provideRetrofit(client))
            eapi.postResponse(
                path = "/api/login/cellphone",
                body = mapOf("ursToken" to "test-urs-token"),
                cryptoMode = "eapi",
                withoutAccount = true,
                ursAppId = "test-token-issuing-id",
            ).let { assertTrue(it.isSuccessful) }
        } finally {
            store.edit { preferences ->
                listOf(CookieKey, NeteaseUrsAppIdKey).forEach { key ->
                    saved[key]?.let { preferences[key] = it } ?: preferences.remove(key)
                }
            }
        }
    }

    @Test
    fun passwordLoginUsesAndSavesTheCurrentSdkAppId() = runBlocking(Dispatchers.IO) {
        // Only SDK/device initialization uses the network. The account request
        // terminates in this interceptor, so no password or test login reaches NetEase.
        NeteaseLoginTestRuntime.repository.prepareNeteaseLoginDevice()
        val expectedAppId = NeteaseLoginTestRuntime.urs.appId()
        val store = context.dataStore
        val saved = store.data.first()
        val keys = listOf(CookieKey, NeteaseCsrfKey, NeteaseMusicAKey, NeteaseRefreshTokenKey,
            NeteaseUrsAppIdKey, UserIdKey, UserNicknameKey, UserAvatarUrlKey)
        try {
            store.edit {
                it[CookieKey] = "test-old-account"
                it[NeteaseUrsAppIdKey] = "test-old-urs-id"
            }
            val client = OkHttpClient.Builder().addInterceptor(NeteaseInterceptor())
                .addInterceptor { chain ->
                    val request = chain.request()
                    assertEquals("/eapi/login/cellphone", request.url.encodedPath)
                    val cookies = request.header("Cookie").orEmpty()
                    assertTrue(cookies.contains("URS_APPID=$expectedAppId"))
                    assertFalse(cookies.contains("MUSIC_U="))
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .addHeader("Set-Cookie", "MUSIC_U=test-new-account; Path=/")
                        .addHeader("Set-Cookie", "__csrf=test-new-csrf; Path=/")
                        .body("{\"code\":200,\"profile\":{\"userId\":123,\"nickname\":\"test\"}}"
                            .toResponseBody("application/json".toMediaType())).build()
                }.build()
            val eapi = RetrofitModule.provideMeloXEapiService(RetrofitModule.provideRetrofit(client))
            val repository = MeloXRepository(eapi, eapi, context, RetrofitModule.provideCloudUploadClient(),
                NeteaseLoginTestRuntime.security, NeteaseLoginTestRuntime.urs)
            assertEquals(123L, repository.loginWithMobilePassword("13000000000", "86", "test-password").id)
            val current = store.data.first()
            assertEquals("test-new-account", current[CookieKey])
            assertEquals(expectedAppId, current[NeteaseUrsAppIdKey])
        } finally {
            store.edit { preferences ->
                keys.forEach { key ->
                    saved[key]?.let { preferences[key] = it } ?: preferences.remove(key)
                }
            }
        }
    }
}
