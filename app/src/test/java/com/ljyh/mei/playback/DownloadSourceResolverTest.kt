package com.ljyh.mei.playback

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.DownloadUrlResponse
import com.ljyh.mei.data.model.api.GetDownloadUrl
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.parasite.HostSessionBridge
import com.ljyh.mei.parasite.HostSessionChangedException
import com.ljyh.mei.parasite.HostSessionIdentity
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DownloadSourceResolverTest {
    private var identity = HostSessionIdentity(17, true, false)
    private val sessions = HostSessionBridge().apply { bind { identity } }
    private val owner = sessions.snapshot()
    private val requests = mutableListOf<GetDownloadUrl>()
    private var now = 1_000_000L
    private var respond: (GetDownloadUrl) -> DownloadUrlResponse = { fixture(it.id.substringBefore('_').toLong()) }
    private val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, args ->
        check(method.name == "getDownloadUrl") { "Download resolution must not use playback APIs" }
        assertEquals(owner, args[1])
        (args[0] as GetDownloadUrl).let { requests += it; respond(it) }
    } as ApiService

    private fun fixture(id: Long = 1, edit: JsonObject.() -> Unit = {}): DownloadUrlResponse {
        val json = JsonParser.parseString("""{"code":200,"data":{"id":$id,"code":200,"url":"https://media.example.test/audio.flac","type":"flac","level":"lossless","size":12345678901,"md5":"0123456789abcdef0123456789abcdef","expi":600}}""").asJsonObject
        json.edit()
        return Gson().fromJson(json, DownloadUrlResponse::class.java)
    }
    private suspend fun resolve(ids: List<String> = listOf("1"), quality: MusicQuality = MusicQuality.LOSSLESS) =
        resolveOfficialDownloadSources(api, sessions, ids, quality, owner) { now }

    @Test fun usesSingleDownloadTuplesAndKeepsTheOriginalOrderWithoutDuplicates() = runTest {
        val result = resolve(listOf(" 2 ", "1", "2"))
        assertEquals(listOf("2_0", "1_0"), requests.map { it.id })
        assertEquals(listOf(2L, 1L), result.sources.map { it.id })
        assertTrue(requests.all { it.level == "lossless" && it.immerseType == "ste" })
        assertEquals(12345678901L, result.sources.first().size)
        assertEquals(now + 600_000, result.sources.first().expiresAtMs)
        assertTrue(result.rejectedCodes.isEmpty())
    }

    @Test fun serverSelectedQualityIsNotReplacedWithTheRequestedLabel() = runTest {
        val source = resolve(quality = MusicQuality.SKY).sources.single()
        assertEquals("sky", source.requestedLevel)
        assertEquals("lossless", source.level)
        assertEquals("c51", requests.single().immerseType)
    }

    @Test fun missingOptionalQualityOrExpiryIsNotInvented() = runTest {
        respond = { fixture { getAsJsonObject("data").apply { remove("level"); remove("expi") } } }
        val source = resolve().sources.single()
        assertEquals("", source.level)
        assertNull(source.expiresAtMs)
    }

    @Test fun explicitDenialsNeverFallBackToPlaybackOrAnotherQuality() = runTest {
        listOf(-103, -105, -120, -125, -130, -140, 404).forEach { code ->
            requests.clear()
            respond = { fixture { getAsJsonObject("data").apply { addProperty("code", code); remove("url") } } }
            val result = resolve()
            assertTrue(result.sources.isEmpty())
            assertEquals(mapOf("1" to code), result.rejectedCodes)
            assertEquals(1, requests.size)
        }
    }

    @Test fun aBatchCanContainExplicitlyDeniedSongsButNeverAnUnownedSource() = runTest {
        respond = { request -> fixture(request.id.substringBefore('_').toLong()) {
            if (request.id == "2_0") getAsJsonObject("data").addProperty("code", -105)
        } }
        val result = resolve(listOf("1", "2", "3"))
        assertEquals(listOf(1L, 3L), result.sources.map { it.id })
        assertEquals(mapOf("2" to -105), result.rejectedCodes)
        respond = { fixture(999) }
        assertTrue(runCatching { resolve() }.exceptionOrNull() is IOException)
    }

    @Test fun malformedSourcesAndTrialsNeverBecomeDownloadGrants() = runTest {
        val changes: List<JsonObject.() -> Unit> = listOf(
            { remove("id") }, { remove("code") }, { remove("url") },
            { addProperty("url", "file:///secret") }, { addProperty("url", "https://user:pass@example.test/file") },
            { addProperty("type", "../flac") }, { addProperty("size", 0) }, { remove("size") },
            { addProperty("md5", "broken") }, { remove("md5") },
            { add("freeTrialInfo", JsonObject()) }, { addProperty("expi", 0) },
            { addProperty("expi", -1) }, { addProperty("expi", Long.MAX_VALUE) },
        )
        for (change in changes) {
            respond = { fixture { getAsJsonObject("data").change() } }
            assertTrue(runCatching { resolve() }.isFailure)
        }
    }

    @Test fun missingDataAndBusinessErrorsAbortInsteadOfBecomingEmptySuccess() = runTest {
        for (json in listOf("{}", """{"code":301}""", """{"code":200}""")) {
            respond = { Gson().fromJson(json, DownloadUrlResponse::class.java) }
            assertTrue(runCatching { resolve() }.exceptionOrNull() is IOException)
        }
        for (code in listOf(301, 401, 500, 429)) {
            respond = { fixture { getAsJsonObject("data").addProperty("code", code) } }
            assertTrue(runCatching { resolve() }.exceptionOrNull() is IOException)
        }
    }

    @Test fun transportFailureAbortsWithoutPublishingPartialBatchResults() = runTest {
        respond = { if (it.id == "2_0") throw IOException("Network unavailable") else fixture() }
        assertTrue(runCatching { resolve(listOf("1", "2", "3")) }.isFailure)
        assertEquals(listOf("1_0", "2_0"), requests.map { it.id })
    }

    @Test fun noUrlIsCachedOrReusedFromAnEarlierGrant() = runTest {
        resolve()
        respond = { fixture { getAsJsonObject("data").addProperty("code", -105) } }
        assertTrue(resolve().sources.isEmpty())
        assertEquals(2, requests.size)
    }

    @Test fun invalidInputFailsBeforeAnyRequestAndEmptyInputRemainsEmpty() = runTest {
        for (invalid in listOf("", "0", "-1", "1_17", "1,2", "9223372036854775808")) {
            assertTrue(runCatching { resolve(listOf("1", invalid)) }.isFailure)
        }
        assertTrue(requests.isEmpty())
        assertTrue(resolve(emptyList()).sources.isEmpty())
    }

    @Test fun recoveryGuestAndStaleOwnersNeverDispatch() = runTest {
        sessions.setRecoveryRequired(true)
        assertTrue(runCatching { resolve() }.exceptionOrNull() is HostSessionChangedException)
        sessions.setRecoveryRequired(false)
        identity = HostSessionIdentity(0, false, true)
        assertTrue(runCatching { resolveOfficialDownloadSources(api, sessions, listOf("1"), MusicQuality.STANDARD, sessions.snapshot()) }.exceptionOrNull() is HostSessionChangedException)
        identity = owner.identity
        sessions.invalidate()
        assertTrue(runCatching { resolve() }.exceptionOrNull() is HostSessionChangedException)
        assertTrue(requests.isEmpty())
    }

    @Test fun aLateResponseCannotEscapeAnAccountOrAuthorizationChange() = runTest {
        respond = { sessions.invalidate(); fixture() }
        assertTrue(runCatching { resolve(listOf("1", "2")) }.exceptionOrNull() is HostSessionChangedException)
        assertEquals(1, requests.size)
    }

    @Test fun identityChangesWithoutCallbacksAlsoRejectTheResponse() = runTest {
        respond = { identity = HostSessionIdentity(88, true, false); fixture() }
        assertTrue(runCatching { resolve() }.exceptionOrNull() is HostSessionChangedException)
    }

    @Test fun expiryIsMeasuredFromDispatchNotFromTheDelayedResponse() = runTest {
        respond = { now += 600_000; fixture() }
        assertTrue(runCatching { resolve() }.exceptionOrNull() is IOException)
    }

    @Test fun anEarlierBatchGrantCannotExpireWhileLaterSongsAreResolved() = runTest {
        respond = {
            if (it.id == "2_0") now += 10_000
            fixture(it.id.substringBefore('_').toLong()) {
                getAsJsonObject("data").addProperty("expi", if (it.id == "1_0") 5 else 600)
            }
        }
        assertTrue(runCatching { resolve(listOf("1", "2")) }.exceptionOrNull() is IOException)
    }

    @Test fun recoveryBeginningDuringARequestRejectsItsResponse() = runTest {
        respond = { sessions.setRecoveryRequired(true); fixture() }
        assertTrue(runCatching { resolve() }.exceptionOrNull() is HostSessionChangedException)
    }

    @Test fun cancellationIsNotConvertedToAQualityFallbackOrGrant() = runTest {
        val result = async {
            val job = currentCoroutineContext().job
            respond = { job.cancel(); fixture() }
            resolve()
        }
        assertTrue(runCatching { result.await() }.exceptionOrNull() is CancellationException)
        assertEquals(1, requests.size)
    }
}
