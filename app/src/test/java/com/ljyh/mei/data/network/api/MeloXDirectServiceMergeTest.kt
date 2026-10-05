package com.ljyh.mei.data.network.api

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class MeloXDirectServiceMergeTest {
    private val requests = mutableListOf<Request>()
    private val service = Retrofit.Builder()
        .baseUrl("https://example.test/")
        .client(
            OkHttpClient.Builder().addInterceptor { chain ->
                requests += chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("synthetic")
                    .header("x-refresh-token", "synthetic-refresh")
                    .body("{\"code\":200}".toResponseBody("application/json".toMediaType()))
                    .build()
            }.build(),
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(MeloXDirectService::class.java)

    @Test
    fun ordinaryTransportRetainsSecurityParametersAndMainHeaderOverrides() = runBlocking {
        val response = service.post(
            path = "/api/login/qrcode/server/login",
            body = mapOf("key" to "synthetic-key"),
            cryptoMode = "eapi",
            antiCheatToken = "synthetic-check",
            ydDeviceToken = "synthetic-device",
            loginChainId = "synthetic-chain",
            nmcid = "synthetic-nmcid",
            nmdi = "synthetic-nmdi",
            nmtid = "synthetic-nmtid",
            ursAppId = "synthetic-app-id",
            headers = mapOf("X-Netease-Cookie-OS" to "android"),
        )

        assertEquals(200, response.get("code").asInt)
        val request = requests.single()
        assertEquals("eapi", request.header("X-Netease-Crypto"))
        assertEquals("synthetic-check", request.header("X-Netease-Anti-Cheat-Token"))
        assertEquals("synthetic-device", request.header("X-Netease-Yd-Device-Token"))
        assertEquals("synthetic-chain", request.header("X-Netease-Login-Chain-Id"))
        assertEquals("synthetic-nmcid", request.header("X-Netease-NMCID"))
        assertEquals("synthetic-nmdi", request.header("X-Netease-NMDI"))
        assertEquals("synthetic-nmtid", request.header("X-Netease-NMTID"))
        assertEquals("synthetic-app-id", request.header("X-Netease-URS-App-Id"))
        assertEquals("android", request.header("X-Netease-Cookie-OS"))
    }

    @Test
    fun mobileLoginStillReceivesResponseHeadersAndUsesItsUrsAppId() = runBlocking {
        val response = service.postResponse(
            path = "/api/login/cellphone",
            body = mapOf("ursToken" to "synthetic-urs"),
            cryptoMode = "eapi",
            ursAppId = "synthetic-app-id",
            withoutAccount = true,
        )

        assertEquals("synthetic-refresh", response.headers()["x-refresh-token"])
        assertEquals("synthetic-app-id", requests.single().header("X-Netease-URS-App-Id"))
        assertEquals("true", requests.single().header("X-Netease-Without-Account"))
    }

    @Test
    fun playbackHistoryKeepsItsSeparateStreamingTransport() = runBlocking {
        val response = service.postPlaybackRaw(
            path = "/api/feedback/weblog",
            body = mapOf("logs" to "[]"),
            headers = mapOf(
                "X-Netease-Crypto" to "eapi",
                "X-Netease-Eapi-Profile" to "playback-history",
            ),
        )

        assertEquals("playback-history", requests.single().header("X-Netease-Eapi-Profile"))
        assertEquals("eapi", requests.single().header("X-Netease-Crypto"))
        assertEquals("{\"code\":200}", response.body()?.use { it.string() })
    }
}
