package com.ljyh.mei.data.network

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.DeviceIdKey
import com.ljyh.mei.constants.NeteaseCsrfKey
import com.ljyh.mei.constants.NeteaseMusicAKey
import com.ljyh.mei.constants.NeteaseSessionTypeKey
import com.ljyh.mei.constants.NeteaseUrsAppIdKey
import com.ljyh.mei.constants.SDeviceIdKey
import com.ljyh.mei.data.model.api.EApiSubscribePlaylist
import com.ljyh.mei.data.model.api.EApiUnsubscribePlaylist
import com.ljyh.mei.data.network.api.EApiService
import com.ljyh.mei.di.NeteaseInterceptor
import com.ljyh.mei.utils.encrypt.decryptEApi
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Synthetic preferences and a terminal interceptor prevent account writes and network traffic. */
@RunWith(AndroidJUnit4::class)
class NeteasePlaylistRequestInstrumentedTest {
    @Test
    fun mobileSubscriptionKeepsBodyAndHeaderTokensSeparate() = runBlocking {
        val request = capture(NeteaseSessionType.Mobile, subscribe = true)
        val body = decode(request, "subscribe")

        assertEquals(BODY_TOKEN, body.get("checkToken").asString)
        assertEquals(SUBSCRIBE_HEADER_TOKEN, request.header("X-antiCheatToken"))
        assertEquals("{}", body.get("header").asString)
    }

    @Test
    fun webSubscriptionKeepsHeaderTokenInEncryptedEnvelope() = runBlocking {
        val request = capture(NeteaseSessionType.Web, subscribe = true)
        val body = decode(request, "subscribe")

        assertEquals(BODY_TOKEN, body.get("checkToken").asString)
        assertEquals(
            SUBSCRIBE_HEADER_TOKEN,
            body.getAsJsonObject("header").get("X-antiCheatToken").asString,
        )
        assertNull(request.header("X-antiCheatToken"))
        assertTrue(request.header("Cookie")!!.contains("X-antiCheatToken=$SUBSCRIBE_HEADER_TOKEN"))
    }

    @Test
    fun mobileUnsubscriptionSendsFreshHeaderTokenWithoutBodyToken() = runBlocking {
        val request = capture(NeteaseSessionType.Mobile, subscribe = false)
        val body = decode(request, "unsubscribe")

        assertFalse(body.has("checkToken"))
        assertEquals(UNSUBSCRIBE_HEADER_TOKEN, request.header("X-antiCheatToken"))
        assertEquals("{}", body.get("header").asString)
    }

    @Test
    fun webUnsubscriptionSendsFreshEnvelopeTokenWithoutBodyToken() = runBlocking {
        val request = capture(NeteaseSessionType.Web, subscribe = false)
        val body = decode(request, "unsubscribe")

        assertFalse(body.has("checkToken"))
        assertEquals(
            UNSUBSCRIBE_HEADER_TOKEN,
            body.getAsJsonObject("header").get("X-antiCheatToken").asString,
        )
        assertNull(request.header("X-antiCheatToken"))
        assertTrue(request.header("Cookie")!!.contains("X-antiCheatToken=$UNSUBSCRIBE_HEADER_TOKEN"))
    }

    private suspend fun capture(type: NeteaseSessionType, subscribe: Boolean): Request {
        val preferences = mutablePreferencesOf(
            CookieKey to "fixture-account-session",
            DeviceIdKey to "fixture-local-device",
            SDeviceIdKey to "fixture-registered-device",
            NeteaseSessionTypeKey to type.name,
            NeteaseUrsAppIdKey to "fixture-urs-app-id",
            NeteaseMusicAKey to "fixture-music-a",
            NeteaseCsrfKey to "fixture-csrf",
        )
        val captured = AtomicReference<Request>()
        val client = OkHttpClient.Builder()
            .addInterceptor(NeteaseInterceptor { preferences })
            .addInterceptor { chain ->
                val request = chain.request()
                captured.set(request)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("fixture")
                    .body("{\"code\":200}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val service = Retrofit.Builder()
            .baseUrl("https://interface.music.163.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
            .create(EApiService::class.java)

        val response = if (subscribe) {
            service.subscribePlaylist(
                EApiSubscribePlaylist(id = PLAYLIST_ID, checkToken = BODY_TOKEN),
                antiCheatToken = SUBSCRIBE_HEADER_TOKEN,
            )
        } else {
            service.unSubscribePlaylist(
                EApiUnsubscribePlaylist(id = PLAYLIST_ID.toString()),
                id = PLAYLIST_ID.toString(),
                antiCheatToken = UNSUBSCRIBE_HEADER_TOKEN,
            )
        }
        assertEquals(200, response.code)
        return checkNotNull(captured.get())
    }

    private fun decode(request: Request, action: String): JsonObject {
        val path = "/playlist/$action" + if (action == "unsubscribe") "/" else ""
        assertEquals("POST", request.method)
        assertEquals("/eapi$path", request.url.encodedPath)
        assertTrue(
            "Internal transport headers must not reach the server",
            request.headers.names().none { it.startsWith("X-Netease-", ignoreCase = true) },
        )
        val form = request.body as FormBody
        assertEquals(1, form.size)
        assertEquals("params", form.name(0))
        val encrypted = form.value(0).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val envelope = decryptEApi(encrypted).split("-36cd479b6b5-")
        assertEquals(3, envelope.size)
        assertEquals("/api$path", envelope[0])
        val body = JsonParser.parseString(envelope[1]).asJsonObject
        if (action == "unsubscribe") {
            assertEquals(setOf("id"), request.url.queryParameterNames)
            assertEquals(listOf(PLAYLIST_ID.toString()), request.url.queryParameterValues("id"))
            assertTrue("Unsubscribe must preserve the official string ID", body.getAsJsonPrimitive("id").isString)
            assertEquals(PLAYLIST_ID.toString(), body.get("id").asString)
        } else {
            assertTrue(request.url.queryParameterNames.isEmpty())
            assertTrue("Subscribe must retain its numeric ID", body.getAsJsonPrimitive("id").isNumber)
            assertEquals(PLAYLIST_ID, body.get("id").asLong)
        }
        return body
    }

    private companion object {
        const val PLAYLIST_ID = 8668352495L
        const val BODY_TOKEN = "fixture-subscribe-body-token"
        const val SUBSCRIBE_HEADER_TOKEN = "fixture-subscribe-header-token"
        const val UNSUBSCRIBE_HEADER_TOKEN = "fixture-unsubscribe-header-token"
    }
}
