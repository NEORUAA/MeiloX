package com.ljyh.mei.data.repository

import com.google.gson.JsonParser
import com.ljyh.mei.data.network.api.MeloXDirectService
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class AccountFeatureTransportTest {
    @Test fun accountRequestsUseTheCapturedMobileProtocolAndCurrentSessionGuard() = runBlocking {
        var calls = 0
        val transport = Interceptor { chain ->
            val request = chain.request()
            assertEquals("interface3.music.163.com", request.url.host)
            assertEquals("/eapi/middle/user/device/list", request.url.encodedPath)
            assertEquals("eapi", request.header("X-Netease-Crypto"))
            assertEquals("android", request.header("X-Netease-Eapi-Profile"))
            assertEquals("example-session-fingerprint", request.header("X-Netease-Expected-Session"))
            assertNull(request.header("Cookie"))
            val buffer = Buffer(); request.body!!.writeTo(buffer)
            val body = JsonParser.parseString(buffer.readUtf8()).asJsonObject
            assertEquals("", body["status"].asString)
            assertEquals("9", body["excStatus"].asString)
            assertFalse(body.has("MUSIC_U"))
            calls++
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":200,"data":{"userDeviceList":[]}}""".toResponseBody("application/json".toMediaType())).build()
        }
        val service = Retrofit.Builder().baseUrl("https://example.test/")
            .client(OkHttpClient.Builder().addInterceptor(transport).build())
            .addConverterFactory(GsonConverterFactory.create()).build().create(MeloXDirectService::class.java)
        val response = requestAccountFeature(service, "/api/middle/user/device/list",
            mapOf("status" to "", "excStatus" to "9"), "example-session-fingerprint")
        assertEquals(0, parseAccountDevices(response).size)
        assertEquals(1, calls)
    }
}
