package com.ljyh.mei.data.repository

import com.ljyh.mei.source.netease.repository.*

import com.google.gson.JsonParser
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
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
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class SearchRepositoryTest {
    private val requests = mutableListOf<Request>()
    private var responseBody = """{"code":200,"result":{},"trp":{"rules":[]}}"""
    private val repository = SearchRepository(
        Retrofit.Builder()
            .baseUrl("https://example.test/")
            .client(OkHttpClient.Builder().addInterceptor { chain ->
                requests += chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("synthetic")
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }.build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java),
    )

    @Test
    fun onlyPodcastSearchSelectsTheDesktopSearchContract() = runBlocking {
        listOf(1, 10, 100, 1000, 1009).forEach { type ->
            assertTrue(repository.search("HOYO", type, 30) is Resource.Success)
            val request = requests.last()
            val body = JsonParser.parseString(
                Buffer().also { request.body!!.writeTo(it) }.readUtf8(),
            ).asJsonObject

            assertEquals("/api/search/get/", request.url.encodedPath)
            assertEquals("HOYO", body.get("s").asString)
            assertEquals(type, body.get("type").asInt)
            assertEquals(30, body.get("limit").asInt)
            assertEquals(0, body.get("offset").asInt)
            assertEquals(if (type == 1009) "pc" else null, request.header("X-Netease-Cookie-OS"))
            assertFalse(body.has("cookieOs"))
            assertNull(request.header("X-Netease-Crypto"))
        }
    }

    @Test
    fun podcastSearchKeepsTheQueryAndServerResultOrder() = runBlocking {
        responseBody = """{
            "code":200,
            "result":{"djRadios":[
                {"id":1,"name":"A server-selected radio"},
                {"id":2,"name":"HOYO-MiX collection"}
            ]},
            "trp":{"rules":[]}
        }"""

        val result = repository.search("  HOYO 原神  ", 1009, 12) as Resource.Success
        val body = JsonParser.parseString(
            Buffer().also { requests.single().body!!.writeTo(it) }.readUtf8(),
        ).asJsonObject

        assertEquals("  HOYO 原神  ", body.get("s").asString)
        assertEquals(12, body.get("limit").asInt)
        assertEquals(listOf(1L, 2L), result.data.result.podcasts!!.map { it.id })
    }

    @Test
    fun suggestionsKeepTheirExistingClientProfile() = runBlocking {
        responseBody = """{"code":200,"result":{}}"""
        assertTrue(repository.searchSuggest("HOYO") is Resource.Success)

        assertEquals("/api/search/suggest/web/", requests.single().url.encodedPath)
        assertNull(requests.single().header("X-Netease-Cookie-OS"))
    }
}
