package com.ljyh.mei.di

import com.google.gson.JsonParser
import com.ljyh.mei.standalone.StandaloneCredentials
import com.ljyh.mei.utils.encrypt.decryptEApi
import java.io.IOException
import java.util.HexFormat
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class StandaloneSigningTest {
    @Test fun eapiBodyAndCookieUseOnlyTheCapturedCredential() {
        val wire = Wire()
        wire.client.newCall(request("eapi", "candidate-cookie")).execute().close()
        val sent = requireNotNull(wire.sent)
        assertEquals("/eapi/test", sent.url.encodedPath)
        assertTrue(sent.header("Cookie").orEmpty().contains("MUSIC_U=candidate-cookie"))
        assertNull(sent.header("X-Netease-Crypto"))
        val fields = signedFields(sent)
        assertEquals("value", fields.get("key").asString)
        assertEquals("candidate-cookie", fields.getAsJsonObject("header").get("MUSIC_U").asString)
        assertEquals("fixture-device", fields.getAsJsonObject("header").get("deviceId").asString)
    }

    @Test fun unsignedRequestWithoutCapturedCredentialsNeverReachesWire() {
        val wire = Wire()
        val request = request("eapi", "discarded").newBuilder()
            .tag(StandaloneCredentials::class.java, null).build()
        assertThrows(IOException::class.java) { wire.client.newCall(request).execute() }
        assertNull(wire.sent)
    }

    @Test fun anonymousRequestsHaveNoMusicUInHeadersOrSignedBody() {
        val wire = Wire()
        wire.client.newCall(request("eapi", "")).execute().close()
        val sent = requireNotNull(wire.sent)
        assertFalse(sent.header("Cookie").orEmpty().contains("MUSIC_U"))
        assertFalse(signedFields(sent).getAsJsonObject("header").has("MUSIC_U"))
    }

    @Test fun weblogCompatibilityProfileIsRetainedWithoutPrivateControlHeaders() {
        val wire = Wire()
        val request = request("eapi", "fixture-cookie").newBuilder()
            .header(NETEASE_EAPI_PROFILE_HEADER, PLAYBACK_HISTORY_PROFILE).build()
        wire.client.newCall(request).execute().close()
        val sent = requireNotNull(wire.sent)
        assertTrue(sent.headers.names().none { it.startsWith("X-Netease-", true) })
        assertNull(sent.header("Referer"))
        assertNull(sent.header("X-Real-IP"))
        val header = signedFields(sent).getAsJsonObject("header")
        assertEquals("osx", header.get("os").asString)
        assertEquals("3.1.10.5100", header.get("appver").asString)
        assertEquals("fixture-cookie", header.get("MUSIC_U").asString)
    }

    @Test fun weapiStillUsesItsDoubleEncryptedFormAndCapturedCookie() {
        val wire = Wire()
        wire.client.newCall(request("weapi", "web-cookie")).execute().close()
        val sent = requireNotNull(wire.sent)
        val form = sent.body as FormBody
        assertEquals(listOf("params", "encSecKey"), (0 until form.size).map(form::name))
        assertTrue(form.value(0).isNotBlank())
        assertTrue(form.value(1).isNotBlank())
        assertTrue(sent.header("Cookie").orEmpty().contains("MUSIC_U=web-cookie"))
        assertEquals("https://music.163.com", sent.header("Referer"))
    }

    private fun request(mode: String, cookie: String): Request = Request.Builder()
        .url("https://interface.music.163.com/api/test")
        .header("X-Netease-Crypto", mode)
        .tag(StandaloneCredentials::class.java, StandaloneCredentials(cookie))
        .post("{\"key\":\"value\"}".toRequestBody("application/json".toMediaType()))
        .build()

    private fun signedFields(request: Request) = JsonParser.parseString(
        decryptEApi(HexFormat.of().parseHex((request.body as FormBody).value(0)))
            .split("-36cd479b6b5-")[1],
    ).asJsonObject

    private class Wire {
        var sent: Request? = null
        val client = OkHttpClient.Builder().addInterceptor(NeteaseInterceptor { "fixture-device" })
            .addInterceptor { chain ->
                sent = chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("synthetic").body("{\"code\":200}".toResponseBody()).build()
            }.build()
    }
}
