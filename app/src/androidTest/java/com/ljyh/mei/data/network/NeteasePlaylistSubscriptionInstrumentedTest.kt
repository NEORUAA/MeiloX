package com.ljyh.mei.data.network

import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.DeviceIdKey
import com.ljyh.mei.constants.NeteaseCsrfKey
import com.ljyh.mei.constants.NeteaseMusicAKey
import com.ljyh.mei.constants.NeteaseUrsAppIdKey
import com.ljyh.mei.constants.SDeviceIdKey
import com.ljyh.mei.constants.checkToken
import com.ljyh.mei.data.repository.PlaylistRepository
import com.ljyh.mei.di.NeteaseInterceptor
import com.ljyh.mei.di.RetrofitModule
import com.ljyh.mei.utils.dataStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Manual issue #41 probe against the signed-in account. Disabled in ordinary test runs.
 *
 * Arguments: issue41Subscription=true, operation=search|inspect|subscribe|unsubscribe,
 * playlistId=<public playlist ID>, variant=baseline|fresh_tokens|fresh_security|repository.
 * Search accepts searchWord (default YOASOBI) and never changes a subscription.
 * Mutating probes require an originally unsubscribed playlist and restore that state.
 */
@RunWith(AndroidJUnit4::class)
class NeteasePlaylistSubscriptionInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val gson = Gson()
    private val security get() = NeteaseLoginTestRuntime.security
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .addInterceptor(NeteaseInterceptor())
            .addInterceptor { chain ->
                val request = chain.request()
                val cookies = request.header("Cookie").orEmpty().split(';')
                    .map { it.trim().substringBefore('=') }.toSet()
                report(
                    "wire path=${request.url.encodedPath} " +
                        "android=${request.header("x-os") == "android"} " +
                        "antiCheat=${!request.header("X-antiCheatToken").isNullOrBlank()} " +
                        "musicU=${"MUSIC_U" in cookies} musicA=${"MUSIC_A" in cookies} " +
                        "ursAppId=${"URS_APPID" in cookies} " +
                        "nmcid=${"NMCID" in cookies} nmdi=${"NMDI" in cookies} " +
                        "nmtid=${"NMTID" in cookies}",
                )
                chain.proceed(request).also { response ->
                    report("wire_response path=${request.url.encodedPath} http=${response.code}")
                }
            }
            .build()
    }
    private val repository by lazy {
        val retrofit = RetrofitModule.provideRetrofit(client)
        PlaylistRepository(
            apiService = RetrofitModule.provideApiService(retrofit),
            weApiService = RetrofitModule.provideWeApiService(
                RetrofitModule.provideWeApiRetrofit(client),
            ),
            eApiService = RetrofitModule.provideEApiService(retrofit),
            freshCheckToken = security::freshCheckToken,
            subscriptionAccount = {
                runBlocking { instrumentation.targetContext.dataStore.data.first()[CookieKey].orEmpty() }
            },
        )
    }

    @Test
    fun diagnosePlaylistSubscription() = runBlocking(Dispatchers.IO) {
        assumeTrue(arguments.getString("issue41Subscription") == "true")
        val operation = arguments.getString("operation") ?: "inspect"
        require(operation in setOf("search", "inspect", "subscribe", "unsubscribe"))
        val variant = arguments.getString("variant") ?: "baseline"
        require(variant in setOf("baseline", "fresh_tokens", "fresh_security", "repository"))
        val preferences = instrumentation.targetContext.dataStore.data.first()
        report(
            "session=${preferences.neteaseSessionType()} " +
                "musicU=${!preferences[CookieKey].isNullOrBlank()} " +
                "musicA=${!preferences[NeteaseMusicAKey].isNullOrBlank()} " +
                "deviceId=${!preferences[DeviceIdKey].isNullOrBlank()} " +
                "sDeviceId=${!preferences[SDeviceIdKey].isNullOrBlank()} " +
                "csrf=${!preferences[NeteaseCsrfKey].isNullOrBlank()} " +
                "ursAppId=${!preferences[NeteaseUrsAppIdKey].isNullOrBlank()} " +
                "operation=$operation variant=$variant",
        )
        assertTrue("The probe requires a signed-in account", !preferences[CookieKey].isNullOrBlank())
        if (operation == "search") {
            search(arguments.getString("searchWord") ?: "YOASOBI")
            return@runBlocking
        }
        val playlistId = requireNotNull(arguments.getString("playlistId")?.toLongOrNull()) {
            "A numeric playlistId instrumentation argument is required"
        }
        require(playlistId > 0)
        val before = subscribed(playlistId, "before")
        if (operation == "inspect") return@runBlocking
        assertFalse("Refusing to modify an originally subscribed playlist", before)

        var subscribeAttempted = false
        var primaryFailure: Throwable? = null
        try {
            // An unsubscribe probe first creates its own temporary subscription.
            subscribeAttempted = true
            val result = mutate(playlistId, "subscribe", variant)
            val afterSubscribe = subscribed(playlistId, "after_subscribe")
            assertEquals("Subscribe business code", 200, result.code)
            assertTrue("Subscription was not visible in playlist detail", afterSubscribe)
            if (operation == "unsubscribe") {
                val unsubscribe = mutate(playlistId, "unsubscribe", variant)
                val afterUnsubscribe = subscribed(playlistId, "after_unsubscribe")
                assertEquals("Unsubscribe business code", 200, unsubscribe.code)
                assertFalse("Subscription remains in playlist detail", afterUnsubscribe)
            }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            if (subscribeAttempted) {
                try {
                    // Re-read even after transport failure: the server may have committed it.
                    if (subscribed(playlistId, "cleanup_before")) {
                        val cleanup = mutate(playlistId, "unsubscribe", variant)
                        assertEquals("Cleanup business code", 200, cleanup.code)
                    }
                    assertFalse("Failed to restore the original subscription state", subscribed(playlistId, "restored"))
                } catch (cleanupFailure: Throwable) {
                    report("cleanup=failed error=${cleanupFailure.javaClass.simpleName}")
                    if (primaryFailure == null) throw cleanupFailure
                    primaryFailure.addSuppressed(cleanupFailure)
                }
            }
        }
    }

    private fun search(word: String) {
        val response = post(
            "/api/search/get/",
            mapOf("s" to word, "type" to 1000, "limit" to 10, "offset" to 0),
        )
        assertEquals("Search business code", 200, response.code)
        response.json.getAsJsonObject("result")?.getAsJsonArray("playlists")?.forEach { item ->
            val playlist = item.asJsonObject
            val creator = playlist.getAsJsonObject("creator")?.get("nickname")?.asString.orEmpty()
            report("search id=${playlist.get("id")?.asLong} name=${safeText(playlist.get("name")?.asString)} creator=${safeText(creator)}")
        }
    }

    private fun subscribed(playlistId: Long, stage: String): Boolean {
        val response = post(
            "/api/v6/playlist/detail",
            mapOf("id" to playlistId.toString(), "n" to "0", "s" to "0"),
        )
        assertEquals("Playlist detail business code", 200, response.code)
        val playlist = requireNotNull(response.json.getAsJsonObject("playlist")) {
            "Playlist detail is missing"
        }
        val subscribed = requireNotNull(playlist.get("subscribed")?.takeUnless { it.isJsonNull }) {
            "Playlist detail does not declare subscription state; refusing mutation"
        }.asBoolean
        report("state stage=$stage playlistId=$playlistId subscribed=$subscribed")
        return subscribed
    }

    private suspend fun mutate(playlistId: Long, operation: String, variant: String): ProbeResponse {
        report("mutation operation=$operation variant=$variant playlistId=$playlistId")
        if (variant == "repository") return mutateThroughRepository(playlistId, operation)
        val body = linkedMapOf<String, Any>("id" to playlistId)
        val headers = linkedMapOf(
            "X-Netease-Crypto" to "eapi",
            "X-Netease-Check-Token" to "true",
        )
        if (variant == "baseline") {
            // Match the legacy repository: subscribe has a body token, unsubscribe does not.
            if (operation == "subscribe") body["checkToken"] = checkToken
        } else {
            body["checkToken"] = security.freshCheckToken()
            headers["X-Netease-Anti-Cheat-Token"] = security.freshCheckToken()
            if (variant == "fresh_security") {
                headers["X-Netease-Yd-Device-Token"] = security.ydDeviceToken()
                val cookies = security.securityCookies()
                headers["X-Netease-NMCID"] = cookies.nmcid
                headers["X-Netease-NMDI"] = cookies.nmdi
                cookies.nmtid.takeIf(String::isNotBlank)?.let { headers["X-Netease-NMTID"] = it }
            }
        }
        return post("/api/playlist/$operation", body, headers)
    }

    private suspend fun mutateThroughRepository(playlistId: Long, operation: String): ProbeResponse {
        val result = if (operation == "subscribe") {
            repository.subscribePlaylist(playlistId.toString())
        } else {
            repository.unSubscribePlaylist(playlistId.toString())
        }
        return when (result) {
            is Resource.Success -> {
                report("repository operation=$operation code=${result.data.code}")
                ProbeResponse(gson.toJsonTree(result.data).asJsonObject, result.data.code)
            }
            is Resource.Error -> {
                val message = safeText(result.message)
                report("repository operation=$operation error=$message")
                throw AssertionError("Repository returned an error: $message")
            }
            Resource.Loading -> throw AssertionError("Repository returned a loading state")
        }
    }

    private fun post(
        path: String,
        body: Map<String, Any>,
        headers: Map<String, String> = emptyMap(),
    ): ProbeResponse {
        val request = Request.Builder()
            .url("https://interface.music.163.com$path")
            .post(gson.toJson(body).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        return client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            val code = runCatching { json?.get("code")?.asInt }.getOrNull()
            val message = runCatching {
                (json?.get("message") ?: json?.get("msg"))?.takeUnless { it.isJsonNull }?.asString
            }.getOrNull()
            report("response path=$path http=${response.code} code=$code message=${safeText(message)}")
            assertTrue("HTTP request failed: ${response.code}", response.isSuccessful)
            ProbeResponse(requireNotNull(json) { "Expected a JSON object response" }, code ?: -1)
        }
    }

    private fun safeText(text: String?): String = text.orEmpty()
        .replace(Regex("(?i)(music[_-]?[ua]|cookie|token|authorization|csrf|deviceid)\\s*[:=]\\s*[^\\s,;]+"), "$1=[redacted]")
        .replace(Regex("[A-Za-z0-9_+/=-]{32,}"), "[redacted]")
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .take(160)

    private fun report(message: String) {
        Log.i("Issue41PlaylistProbe", message)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Issue41PlaylistProbe $message\n") })
    }

    private data class ProbeResponse(val json: JsonObject, val code: Int)
}
