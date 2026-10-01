package com.ljyh.mei.standalone

import com.ljyh.mei.constants.AndroidUserAgent
import com.ljyh.mei.di.NeteaseInterceptor
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class StandaloneTransportPolicyTest {
    @Test fun businessTimeoutsRetainTheOriginalThirtySecondBudget() {
        val client = createStandaloneBusinessClient()
        assertEquals(30_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.readTimeoutMillis)
        assertEquals(30_000, client.writeTimeoutMillis)
        assertEquals(0, client.callTimeoutMillis)
        assertTrue(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertSame(OkHttpClient().hostnameVerifier, client.hostnameVerifier)
        assertEquals(1, client.interceptors.count { it is NeteaseInterceptor })
    }

    @Test fun audioMatchRetainsItsOriginalBudgetAndUnsignedUserAgent() {
        val client = createStandaloneAudioMatchClient()
        assertEquals(30_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.readTimeoutMillis)
        assertEquals(10_000, client.writeTimeoutMillis)
        assertEquals(0, client.callTimeoutMillis)
        assertTrue(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertTrue(client.interceptors.none { it is NeteaseInterceptor })
        var sent: Request? = null
        client.newBuilder().addInterceptor { chain ->
            sent = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("synthetic").body("{}".toResponseBody()).build()
        }.build().newCall(Request.Builder()
            .url("https://interface.music.163.com/api/music/audio/match").build())
            .execute().use { response -> assertEquals(200, response.code) }
        assertEquals(AndroidUserAgent, requireNotNull(sent).header("User-Agent"))
        assertNull(requireNotNull(sent).header("Cookie"))
        assertNull(requireNotNull(sent).header("Authorization"))
    }

    @Test fun clientLogsKeepTheirBoundedNoReplayPolicy() {
        val client = createStandaloneClientLogClient()
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(15_000, client.readTimeoutMillis)
        assertEquals(15_000, client.writeTimeoutMillis)
        assertEquals(15_000, client.callTimeoutMillis)
        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertTrue(client.interceptors.isEmpty())
    }
}
