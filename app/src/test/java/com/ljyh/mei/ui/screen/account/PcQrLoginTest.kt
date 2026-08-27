package com.ljyh.mei.ui.screen.account

import com.ljyh.mei.data.repository.PcQrLoginAction
import com.ljyh.mei.data.repository.buildPcQrLoginBody
import com.ljyh.mei.di.RetrofitModule
import com.ljyh.mei.di.usesOfficialAndroidIdentity
import com.ljyh.mei.utils.log.toSafeLogUrl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcQrLoginTest {
    @Test
    fun `security preparation gates camera analysis`() {
        assertFalse(PcQrLoginUiState(PcQrLoginPhase.Preparing).isAnalyzing)
        assertTrue(PcQrLoginUiState().isAnalyzing)
    }

    @Test
    fun `parses official https login url`() {
        val payload = parseNeteasePcLoginQr(
            "https://music.163.com/login?codekey=a1962083-40b4-4f85-9cb0-c9eae9151a1a",
        )

        assertEquals("a1962083-40b4-4f85-9cb0-c9eae9151a1a", payload?.key)
        assertNull(payload?.clientTraceId)
    }

    @Test
    fun `accepts official http url and trailing slash`() {
        val payload = parseNeteasePcLoginQr(
            "http://music.163.com/login/?codekey=abc123",
        )

        assertEquals("abc123", payload?.key)
        assertNull(payload?.clientTraceId)
    }

    @Test
    fun `rejects lookalike hosts and user info`() {
        assertNull(
            parseNeteasePcLoginQr(
                "https://music.163.com.evil.example/login?codekey=abc123",
            ),
        )
        assertNull(
            parseNeteasePcLoginQr(
                "https://evil.example@music.163.com/login?codekey=abc123",
            ),
        )
    }

    @Test
    fun `rejects missing key wrong path and non url values`() {
        assertNull(parseNeteasePcLoginQr("https://music.163.com/login"))
        assertNull(parseNeteasePcLoginQr("https://music.163.com/song?id=1&codekey=abc123"))
        assertNull(parseNeteasePcLoginQr("not a qr login url"))
    }

    @Test
    fun `builds source faithful action bodies`() {
        val scan = buildPcQrLoginBody("key", "trace", 123L, PcQrLoginAction.Scan, "fresh-token")
        val authorize = buildPcQrLoginBody("key", null, 123L, PcQrLoginAction.Authorize, "fresh-token")
        val cancel = buildPcQrLoginBody("key", "", 123L, PcQrLoginAction.Cancel, "fresh-token")

        assertEquals("1", scan["type"])
        assertEquals("2", authorize["type"])
        assertEquals("3", cancel["type"])
        assertEquals("123", scan["userid"])
        assertEquals("fresh-token", scan["checkToken"])
        assertEquals("trace", scan["clientTraceId"])
        assertFalse(authorize.containsKey("clientTraceId"))
        assertFalse(cancel.containsKey("clientTraceId"))
        assertTrue((scan["checkToken"] as String).isNotBlank())
    }

    @Test
    fun `all xeapi requests use the official Android identity`() {
        assertTrue(usesOfficialAndroidIdentity("xeapi", "/api/banner/get/v3"))
        assertTrue(usesOfficialAndroidIdentity("xeapi", "/api/login/qrcode/server/login"))
        assertTrue(usesOfficialAndroidIdentity("eapi", "/api/middle/device-info/get"))
        assertFalse(usesOfficialAndroidIdentity("eapi", "/api/banner/get/v3"))
        assertTrue(
            usesOfficialAndroidIdentity(
                "eapi",
                "/api/banner/get/v3",
                hasMobileSession = true,
            ),
        )
    }

    @Test
    fun `redacts device token from production network logs`() {
        val loggingInterceptor = RetrofitModule.provideOkHttpClient()
            .interceptors
            .filterIsInstance<HttpLoggingInterceptor>()
            .single()
        val originalToken = "sensitive-device-token"
        val url = (
            "https://interface.music.163.com/api/middle/device-info/get" +
                "?ydDeviceType=Android&ydDeviceToken=$originalToken"
            ).toHttpUrl()
        val redactUrl = HttpLoggingInterceptor::class.java.getDeclaredMethod(
            "redactUrl\$logging_interceptor",
            HttpUrl::class.java,
        )

        val loggedUrl = redactUrl.invoke(loggingInterceptor, url) as String

        assertFalse(loggedUrl.contains(originalToken))
        assertTrue(loggedUrl.contains("ydDeviceType=Android"))
        assertTrue(loggedUrl.contains("ydDeviceToken="))
    }

    @Test
    fun `removes query parameters from network error log urls`() {
        val safeUrl = (
            "https://interface.music.163.com/api/middle/device-info/get" +
                "?ydDeviceType=Android&ydDeviceToken=sensitive-device-token"
            ).toHttpUrl().toSafeLogUrl()

        assertEquals(
            "https://interface.music.163.com/api/middle/device-info/get",
            safeUrl,
        )
    }
}
