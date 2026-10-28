package com.ljyh.mei.playback

import com.google.gson.JsonParser
import com.ljyh.mei.data.network.api.WeApiService
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

class PrivateFmModesTest {
    @Test fun everyModeUsesTheMobileRadioRequestIncludingDefault() {
        for (mode in PrivateFmMode.entries) {
            val body = mode.requestBody(listOf("101", "202", "101", "local", "-1"),
                PrivateFmScene("FOCUS", "Example scene"))
            assertEquals(mode.code, body["mode"])
            assertEquals("newframework_daily_recommend_mix", body["entranceType"])
            assertEquals("false", body["openAidj"])
            assertEquals("[101,202]", body["unplaySongIds"])
            if (mode == PrivateFmMode.SCENE) assertEquals("FOCUS", body["subMode"])
            else assertFalse(body.containsKey("subMode"))
        }
    }

    @Test fun sceneListComesFromTheServerAndDropsInvalidOrDuplicateItems() {
        val response = JsonParser.parseString("""{"code":200,"data":{"crossPlatformResource":{"dslData":{
          "currentSceneList":[{"code":"FOCUS","title":"Example focus","fmCascadeModeStr":"cascade"},
          {"code":"COMMUTE","title":"Example travel"},{"code":"FOCUS","title":"Duplicate"},{"title":"Invalid"}]}}}}""").asJsonObject
        val scenes = parsePrivateFmScenes(response)
        assertEquals(listOf("FOCUS", "COMMUTE"), scenes.map { it.code })
        assertEquals("cascade", scenes.first().cascadeMode)
        assertEquals(scenes.first(), PrivateFmScene.decode(scenes.first().encode()))
        assertNull(PrivateFmScene.decode("not json"))
    }

    @Test fun radioTransportUsesEapiAndAndroidProfileWithoutGoingToTheNetwork() = runBlocking {
        var inspected = false
        val transport = Interceptor { chain ->
            val request = chain.request()
            assertEquals("interface3.music.163.com", request.url.host)
            assertEquals("/eapi/v1/radio/get", request.url.encodedPath)
            assertEquals("eapi", request.header("X-Netease-Crypto"))
            assertEquals("android", request.header("X-Netease-Eapi-Profile"))
            val buffer = Buffer(); request.body!!.writeTo(buffer)
            val body = JsonParser.parseString(buffer.readUtf8()).asJsonObject
            assertEquals("SCENE_RCMD", body["mode"].asString)
            assertEquals("FOCUS", body["subMode"].asString)
            inspected = true
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":200,"data":[{"id":101}]}""".toResponseBody("application/json".toMediaType())).build()
        }
        val service = Retrofit.Builder().baseUrl("https://example.test/")
            .client(OkHttpClient.Builder().addInterceptor(transport).build())
            .addConverterFactory(GsonConverterFactory.create()).build().create(WeApiService::class.java)
        assertEquals(101L, service.getPersonalRadio(PrivateFmMode.SCENE.requestBody(scene = PrivateFmScene("FOCUS", "Example"))).data.first().id)
        assertTrue(inspected)
    }
}
