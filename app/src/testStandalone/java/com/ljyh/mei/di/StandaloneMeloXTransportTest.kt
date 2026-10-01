package com.ljyh.mei.di

import com.google.gson.JsonParser
import com.ljyh.mei.data.model.api.GetUserPhotoAlbum
import com.ljyh.mei.data.session.SessionCallFactory
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.standalone.StandaloneCredentials
import com.ljyh.mei.utils.encrypt.decryptEApi
import java.util.HexFormat
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Production providers, Retrofit and signing; the terminal interceptor never opens a socket. */
class StandaloneMeloXTransportTest {
    @Test fun eapiQualifierSelectsTheOriginalPathAndSignedPayload() = runBlocking {
        val f = Fixture()
        val result = f.eapi.post("/api/w/nuser/account/get", mapOf("key" to "value"), expectedSession = f.owner)
        assertEquals(200, result.get("code").asInt)
        val sent = f.sent.single()
        assertEquals("interface.music.163.com", sent.url.host)
        assertEquals("/eapi/w/nuser/account/get", sent.url.encodedPath)
        val plaintext = decryptEApi(HexFormat.of().parseHex((sent.body as FormBody).value(0)))
        assertEquals("/api/w/nuser/account/get", plaintext.split("-36cd479b6b5-")[0])
        val fields = JsonParser.parseString(plaintext.split("-36cd479b6b5-")[1]).asJsonObject
        assertEquals("value", fields.get("key").asString)
        assertEquals("fixture-cookie", fields.getAsJsonObject("header").get("MUSIC_U").asString)
        assertFalse(fields.get("e_r").asBoolean)
        assertEquals(f.owner, sent.tag(SessionStamp::class.java))
        assertNull(sent.header("X-Netease-Crypto"))
    }

    @Test fun weapiQualifierSelectsTheOriginalDoubleEncryptedWebRequest() = runBlocking {
        val f = Fixture()
        f.weapi.post("/api/djradio/category/get", expectedSession = f.owner)
        val sent = f.sent.single()
        assertEquals("music.163.com", sent.url.host)
        assertEquals("/weapi/djradio/category/get", sent.url.encodedPath)
        val form = sent.body as FormBody
        assertEquals(listOf("params", "encSecKey"), (0 until form.size).map(form::name))
        assertTrue(form.value(0).isNotEmpty())
        assertTrue(form.value(1).isNotEmpty())
        assertEquals("https://music.163.com", sent.header("Referer"))
        assertEquals(f.owner, sent.tag(SessionStamp::class.java))
    }

    @Test fun typedPhotoApiRestoresOnlyItsOriginalTransportDefaults() = runBlocking {
        val f = Fixture()
        RetrofitModule.provideApiService(f.apiRetrofit).getUserPhotoAlbum(GetUserPhotoAlbum("17"), f.owner)
        val sent = f.sent.single()
        assertEquals("/api/user/photo/album/get", sent.url.encodedPath)
        val fields = formFields(sent)
        assertEquals(setOf("userId", "page", "header", "e_r"), fields.keys)
        assertEquals("{}", fields["header"])
        assertEquals("true", fields["e_r"])
        assertEquals("17", fields["userId"])
        val page = JsonParser.parseString(fields["page"]).asJsonObject
        assertEquals(10, page.get("size").asInt)
        assertFalse(page.has("cursor"))
    }

    @Test fun ordinaryTypedApiRequestsDoNotAcquirePhotoMetadataOrCrypto() = runBlocking {
        val f = Fixture()
        f.apiRetrofit.create(com.ljyh.mei.data.network.api.MeloXDirectService::class.java)
            .post("/api/test", mapOf("key" to "value"), expectedSession = f.owner)
        assertEquals(mapOf("key" to "value"), formFields(f.sent.single()))
        assertEquals("/api/test", f.sent.single().url.encodedPath)
    }

    @Test fun explicitPathsAreNotRewrittenTwiceAndRawPlaybackKeepsItsProfile() = runBlocking {
        val f = Fixture()
        f.weapi.post("/weapi/v1/user/detail/17", expectedSession = f.owner)
        assertEquals("/weapi/v1/user/detail/17", f.sent.last().url.encodedPath)
        f.eapi.postPlaybackRaw("/api/feedback/weblog", mapOf("logs" to "[]"), mapOf(
            "X-Netease-Crypto" to "eapi", NETEASE_EAPI_PROFILE_HEADER to PLAYBACK_HISTORY_PROFILE,
        )).body()?.close()
        val report = f.sent.last()
        assertEquals("/eapi/feedback/weblog", report.url.encodedPath)
        assertTrue(report.headers.names().none { it.startsWith("X-Netease-", true) })
        val signed = decryptEApi(HexFormat.of().parseHex((report.body as FormBody).value(0)))
        val header = JsonParser.parseString(signed.split("-36cd479b6b5-")[1]).asJsonObject.getAsJsonObject("header")
        assertEquals("osx", header.get("os").asString)
        assertEquals("3.1.10.5100", header.get("appver").asString)
    }

    private fun formFields(request: Request): Map<String, String> = (request.body as FormBody).let { form ->
        (0 until form.size).associate { form.name(it) to form.value(it) }
    }

    private class Fixture {
        val sessions = SessionStore().apply { bind { SessionIdentity(17, true, false) } }
        val owner = sessions.snapshot()
        val sent = mutableListOf<Request>()
        private val client = OkHttpClient.Builder().addInterceptor(NeteaseInterceptor { "fixture-device" })
            .addInterceptor { chain ->
                sent += chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("synthetic").body("{\"code\":200}".toResponseBody()).build()
            }.build()
        private val calls = SessionCallFactory(sessions, client) { request, _ ->
            request.newBuilder().tag(StandaloneCredentials::class.java, StandaloneCredentials("fixture-cookie")).build()
        }
        val apiRetrofit = RetrofitModule.provideRetrofit(calls)
        val eapi = RetrofitModule.provideMeloXEapiService(apiRetrofit)
        val weapi = RetrofitModule.provideMeloXWeapiService(RetrofitModule.provideWeApiRetrofit(calls))
    }
}
