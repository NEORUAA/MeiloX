package com.ljyh.mei.source.netease

import com.google.gson.JsonParser
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.source.api.PageRequest
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.api.TrackRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class NeteaseProviderTest {
    private val requests = mutableListOf<Request>()
    private var respond: (Request) -> String = { "{}" }
    private val api = Retrofit.Builder()
        .baseUrl("https://example.test/")
        .client(OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture")
                .body(respond(chain.request()).toResponseBody("application/json".toMediaType())).build()
        }.build())
        .addConverterFactory(GsonConverterFactory.create()).build().create(ApiService::class.java)
    private val provider = NeteaseProvider(NeteaseCatalogTransport(api), NeteasePlaybackTransport(api), api)

    @Test
    fun searchKeepsNativeIdentityAndPaging() = runBlocking {
        respond = { """{"code":200,"result":{"songs":[{"id":42,"name":"Song","duration":180000,
            "artists":[{"id":5,"name":"Artist"}],"album":{"id":7,"name":"Album","picId":8}}]}}""" }
        val page = provider.search("query", PageRequest(offset = 30, limit = 1))

        assertEquals(TrackRef("netease", "42"), page.items.single().ref)
        assertEquals("Artist", page.items.single().artists.single().name)
        assertTrue(page.hasMore)
        val body = requestBody(requests.single())
        assertEquals(30, body.get("offset").asInt)
        assertEquals(1, body.get("limit").asInt)
    }

    @Test
    fun batchDetailsPreserveRequestedOrderAndIgnoreUnrequestedTracks() = runBlocking {
        respond = { """{"code":200,"songs":[
            {"id":2,"name":"Second","dt":2,"ar":[],"al":{"id":4,"name":"B","picUrl":""}},
            {"id":1,"name":"First","dt":1,"ar":[],"al":{"id":3,"name":"A","picUrl":""}},
            {"id":9,"name":"Unrequested","dt":9,"ar":[],"al":{"id":9,"name":"X","picUrl":""}}]}""" }
        val result = provider.getTracks(listOf(TrackRef("netease", "1"), TrackRef("netease", "2"), TrackRef("netease", "1")))

        assertEquals(listOf("First", "Second", "First"), result.map { it.title })
        assertEquals(1, requests.size)
    }

    @Test
    fun playbackRejectsTrialAndUsesReturnedQualityAndExpiry() = runBlocking {
        respond = { request ->
            if (requestBody(request).get("level").asString == "hires") {
                """{"code":200,"data":[{"id":42,"code":200,"url":"https://audio.test/trial",
                    "level":"hires","freeTrialInfo":{"start":0,"end":30000}}]}"""
            } else {
                """{"code":200,"data":[{"id":42,"code":200,"url":"https://audio.test/full",
                    "level":"exhigh","size":512,"md5":"content-id","type":"mp3","expi":120}]}"""
            }
        }
        val before = System.currentTimeMillis()
        val result = provider.resolve(TrackRef("netease", "42"), "hires")

        assertEquals("https://audio.test/full", result.uri)
        assertEquals("exhigh", result.quality)
        assertEquals(512L, result.contentLength)
        assertEquals("content-id", result.contentId)
        assertTrue(result.expiresAtMs!! >= before + 90_000L)
        assertEquals(listOf("hires", "lossless"), requests.map { requestBody(it).get("level").asString })
        assertFalse(result.toString().contains("https://"))
    }

    @Test
    fun authenticationAndUnsupportedIdentityFailWithTypedErrors() = runBlocking {
        respond = { """{"code":301,"data":[]}""" }
        try {
            provider.resolve(TrackRef("netease", "42"), "exhigh")
            fail("Expected authentication error")
        } catch (error: SourceException) {
            assertEquals(SourceErrorCode.AUTH_REQUIRED, error.code)
        }
        try {
            provider.getTrack(TrackRef("other", "42"))
            fail("Expected foreign identity rejection")
        } catch (error: SourceException) {
            assertEquals(SourceErrorCode.NOT_FOUND, error.code)
        }
        assertEquals(1, requests.size)
        assertNull(requests.single().header("Cookie"))
    }

    @Test
    fun cancellationIsNeverConvertedToSourceFailure() = runBlocking {
        val cancelled = CancellationException("cancelled")
        try {
            nativeCall<Unit> { throw cancelled }
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertTrue(error === cancelled)
        }
    }

    private fun requestBody(request: Request) = JsonParser.parseString(
        Buffer().also { request.body!!.writeTo(it) }.readUtf8(),
    ).asJsonObject
}
