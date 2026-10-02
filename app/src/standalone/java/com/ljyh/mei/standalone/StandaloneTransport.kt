package com.ljyh.mei.standalone

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.constants.AndroidUserAgent
import com.ljyh.mei.data.network.netease.NCBL_UPLOAD_ENDPOINT
import com.ljyh.mei.data.session.SessionCallFactory
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.di.NeteaseInterceptor
import com.ljyh.mei.runtime.MeloXRequestPolicy
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun createStandaloneBusinessClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    // Never carry a captured Cookie to a redirected origin.
    .followRedirects(false)
    .followSslRedirects(false)
    .addInterceptor(NeteaseInterceptor())
    .build()

internal fun createStandaloneAudioMatchClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .followRedirects(false)
    .addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("User-Agent", AndroidUserAgent).build())
    }.build()

internal fun createStandaloneClientLogClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .writeTimeout(15, TimeUnit.SECONDS)
    .callTimeout(15, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

@Singleton
internal class StandaloneTransport internal constructor(
    private val sessions: StandaloneSessionStore,
    private val signedClient: OkHttpClient,
) {
    @Inject constructor(sessions: StandaloneSessionStore) : this(sessions, createStandaloneBusinessClient())

    val business: Call.Factory = SessionCallFactory(sessions, signedClient) { request, owner ->
        validateBusinessRequest(request)
        request.newBuilder().tag(StandaloneCredentials::class.java, sessions.credentials(owner)).build()
    }

    val audioMatch: Call.Factory = SessionCallFactory(sessions, createStandaloneAudioMatchClient()) { request, _ ->
        validateBusinessRequest(request)
        require(request.url.encodedPath == "/api/music/audio/match")
        request
    }

    val clientLogs: Call.Factory = SessionCallFactory(sessions, createStandaloneClientLogClient()) { request, owner ->
        sessions.requireAuthenticated(owner)
        require(request.method == "POST" && request.url.toString() == NCBL_UPLOAD_ENDPOINT)
        require(request.header("X-Music-U") == sessions.credentials(owner).musicU)
        request
    }

    suspend fun verify(musicU: String, owner: SessionStamp): StoredAccount {
        require(isValidMusicU(musicU))
        sessions.requireCurrent(owner)
        val recoveryAtStart = sessions.recoveryRequired.value
        fun requireOwner() {
            sessions.requireCurrent(owner)
            if (sessions.recoveryRequired.value != recoveryAtStart) throw SessionChangedException()
        }
        val candidate = SessionCallFactory(sessions, signedClient)
        suspend fun request(path: String): JsonObject {
            currentCoroutineContext().ensureActive()
            requireOwner()
            val request = Request.Builder()
                .url("https://interface.music.163.com$path")
                .header("X-Netease-Crypto", "eapi")
                .tag(SessionStamp::class.java, owner)
                .tag(StandaloneCredentials::class.java, StandaloneCredentials(musicU))
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            return candidate.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw IOException("Standalone account verification failed")
                val json = JsonParser.parseString(response.body.string()).asJsonObject
                currentCoroutineContext().ensureActive()
                requireOwner()
                val code = runCatching { json.get("code")?.asString?.toIntOrNull() }.getOrNull() ?: 200
                if (code !in 200..299) throw IOException("Standalone account verification rejected")
                json
            }
        }
        val json = MeloXRequestPolicy.request(
            { request("/api/w/nuser/account/get") },
            { request("/api/nuser/account/get") },
        )
        val profile = json.get("profile")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IOException("Account profile is unavailable")
        fun field(name: String): String? = runCatching {
            profile.get(name)?.takeUnless { it.isJsonNull }?.asString
        }.getOrNull()
        val userId = field("userId")?.toLongOrNull() ?: 0
        if (userId <= 0) throw IOException("Account identity is unavailable")
        currentCoroutineContext().ensureActive()
        requireOwner()
        return StoredAccount(musicU, userId, field("nickname") ?: "NetEase user", field("avatarUrl"))
    }

    private fun validateBusinessRequest(request: Request) {
        val url = request.url
        require(url.scheme == "https" && url.port == 443 && url.host in setOf(
            "music.163.com", "interface.music.163.com", "interface3.music.163.com",
        ) && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null)
        require(request.header("Cookie") == null && request.header("Authorization") == null)
        require(listOf("/api/", "/weapi/", "/eapi/").any(url.encodedPath::startsWith))
    }
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) { _, value, _ -> value.close() }
            else response.close()
        }
    })
}

@Singleton
class StandaloneAccountController internal constructor(
    private val sessions: StandaloneSessionStore,
    private val verify: suspend (String, SessionStamp) -> StoredAccount,
) {
    @Inject internal constructor(sessions: StandaloneSessionStore, transport: StandaloneTransport) :
        this(sessions, transport::verify)

    suspend fun login(musicU: String): Boolean = loginAttempt(musicU, null)

    suspend fun login(musicU: String, expected: SessionStamp): Boolean = loginAttempt(musicU, expected)

    private suspend fun loginAttempt(musicU: String, expected: SessionStamp?): Boolean {
        if (!isValidMusicU(musicU)) return false
        return try {
            val attempt = if (expected == null) sessions.beginLogin() else sessions.beginLogin(expected)
            val account = verify(musicU, attempt.owner)
            sessions.commitLogin(attempt, account)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }
}
