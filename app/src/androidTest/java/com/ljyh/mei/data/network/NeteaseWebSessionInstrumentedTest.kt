package com.ljyh.mei.data.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.di.NETEASE_WEB_SESSION_HEADER
import com.ljyh.mei.di.NeteaseInterceptor
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

/** A terminal interceptor prevents every request from reaching NetEase. */
@RunWith(AndroidJUnit4::class)
class NeteaseWebSessionInstrumentedTest {
    @Test fun verifiesWebCookieWithoutReplacingOrLeakingStoredMobileCredentials() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = context.dataStore.data.first()
        val client = OkHttpClient.Builder()
            .addInterceptor(NeteaseInterceptor())
            .addInterceptor { chain ->
                val request = chain.request()
                val cookie = request.header("Cookie").orEmpty()
                assertTrue(cookie.contains("MUSIC_U=fixture-web-session"))
                assertFalse(cookie.contains("MUSIC_A="))
                assertTrue(cookie.contains("os=pc"))
                assertNull(request.header(NETEASE_WEB_SESSION_HEADER))
                assertNull(request.header("x-music-u"))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("fixture")
                    .body("{\"code\":200}".toResponseBody("application/json".toMediaType())).build()
            }.build()
        val request = Request.Builder()
            .url("https://interface.music.163.com/eapi/w/nuser/account/get")
            .header(NETEASE_WEB_SESSION_HEADER, "fixture-web-session")
            .post("{}".toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { assertEquals(200, it.code) }
        assertEquals(before, context.dataStore.data.first())
    }
}
