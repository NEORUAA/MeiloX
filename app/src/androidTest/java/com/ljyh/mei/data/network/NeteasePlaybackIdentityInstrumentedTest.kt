package com.ljyh.mei.data.network

import android.os.Build
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.DeviceIdKey
import com.ljyh.mei.constants.NeteaseCsrfKey
import com.ljyh.mei.constants.NeteaseMusicAKey
import com.ljyh.mei.constants.NeteaseSessionTypeKey
import com.ljyh.mei.constants.NeteaseUrsAppIdKey
import com.ljyh.mei.constants.SDeviceIdKey
import com.ljyh.mei.di.NETEASE_EAPI_PROFILE_HEADER
import com.ljyh.mei.di.NeteaseInterceptor
import com.ljyh.mei.di.PLAYBACK_HISTORY_PROFILE
import com.ljyh.mei.utils.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic preferences and a terminal interceptor prevent account writes and network traffic. */
@RunWith(AndroidJUnit4::class)
class NeteasePlaybackIdentityInstrumentedTest {
    @Test fun playbackProfileUsesExactlyTheMobileLoginIdentity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = context.dataStore.data.first()
        val preferences = fixture(NeteaseSessionType.Mobile)
        val login = capture(preferences, "/api/login/cellphone", withoutAccount = true)
        val authenticated = capture(preferences, "/api/play-record/song/list")
        val playback = capture(preferences, "/api/feedback/weblog", playback = true)
        val loginCookie = cookie(login)
        val playbackCookie = cookie(playback)
        listOf("deviceId", "sDeviceId", "os", "osver", "appver", "versioncode", "buildver", "channel", "mobilename", "brand", "packageType", "URS_APPID")
            .forEach { key -> assertEquals("Cookie field $key", loginCookie[key], playbackCookie[key]) }
        listOf("MUSIC_U", "MUSIC_A", "__csrf").forEach { key ->
            assertEquals("Authenticated Cookie field $key", cookie(authenticated)[key], playbackCookie[key])
        }
        listOf("User-Agent", "x-deviceId", "x-sDeviceId", "x-os", "x-osver", "x-appver", "x-buildver", "x-channel", "x-mobilename")
            .forEach { key -> assertEquals("Header $key", login.header(key), playback.header(key)) }
        assertEquals(authenticated.header("x-music-u"), playback.header("x-music-u"))
        assertEquals("android", playbackCookie["os"])
        assertEquals("login-device", playbackCookie["deviceId"])
        assertEquals("registered-device", playbackCookie["sDeviceId"])
        assertEquals(NeteaseAndroidClientProfile.BUILD_VERSION, playbackCookie["buildver"])
        assertEquals(NeteaseAndroidClientProfile.userAgent(Build.VERSION.RELEASE, Build.MODEL, Build.ID), playback.header("User-Agent"))
        assertEquals(1, playback.headers.values("Cookie").size)
        assertEquals(1, playback.headers.values("User-Agent").size)
        assertNull(playback.header(NETEASE_EAPI_PROFILE_HEADER))
        assertNull(playback.header("X-Real-IP"))
        assertNull(playback.header("X-Forwarded-For"))
        assertEquals(before, context.dataStore.data.first())
    }

    @Test fun webPlaybackUsesTheExistingDesktopTransportInsteadOfPretendingToBeMobileOrMacOS() {
        val request = capture(fixture(NeteaseSessionType.Web), "/api/feedback/weblog", playback = true)
        val cookies = cookie(request)
        assertEquals("pc", cookies["os"])
        assertEquals("mobile-cookie", cookies["MUSIC_U"])
        assertFalse(cookies.containsKey("MUSIC_A"))
        assertTrue(request.header("User-Agent").orEmpty().contains("NeteaseMusicDesktop"))
        assertNull(request.header("x-music-u"))
        assertNull(request.header("x-os"))
    }

    private fun capture(
        preferences: Preferences,
        path: String,
        playback: Boolean = false,
        withoutAccount: Boolean = false,
    ): Request {
        var captured: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(NeteaseInterceptor { preferences })
            .addInterceptor { chain ->
                captured = chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("fixture")
                    .body("{\"code\":200}".toResponseBody("application/json".toMediaType())).build()
            }.build()
        val request = Request.Builder().url("https://interface.music.163.com$path")
            .header("X-Netease-Crypto", "eapi")
            .apply {
                if (withoutAccount) header("X-Netease-Without-Account", "true")
                if (playback) {
                    header(NETEASE_EAPI_PROFILE_HEADER, PLAYBACK_HISTORY_PROFILE)
                    header("User-Agent", "stale-user-agent")
                    header("Cookie", "stale-cookie")
                    header("X-Real-IP", "203.0.113.1")
                    header("X-Forwarded-For", "203.0.113.1")
                }
            }.post("{}".toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().close()
        return checkNotNull(captured)
    }

    private fun cookie(request: Request): Map<String, String> = request.header("Cookie").orEmpty()
        .split(';').map(String::trim).filter { '=' in it }
        .associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun fixture(type: NeteaseSessionType) = mutablePreferencesOf(
        CookieKey to "mobile-cookie", DeviceIdKey to "login-device", SDeviceIdKey to "registered-device",
        NeteaseSessionTypeKey to type.name, NeteaseUrsAppIdKey to "login-app-id",
        NeteaseMusicAKey to "login-music-a", NeteaseCsrfKey to "login-csrf",
    )
}
