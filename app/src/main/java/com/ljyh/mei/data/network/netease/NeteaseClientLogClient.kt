package com.ljyh.mei.data.network.netease

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.DeviceIdKey
import com.ljyh.mei.data.network.netease.NcblCodec.encode
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.getDeviceId
import com.ljyh.mei.utils.log.logPlaybackHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

internal const val NCBL_UPLOAD_ENDPOINT =
    "https://clientlogsf.music.163.com/api/clientlog/encrypt/upload?multiupload=true"
internal const val NCBL_MAX_RESPONSE_BYTES = 16_384

internal data class NcblUploadResult(
    val fileAccepted: Boolean,
    val httpStatus: Int? = null,
    val businessCode: Int? = null,
)

internal fun interface NcblSessionContextProvider {
    suspend fun beginSession(
        song: NcblSongInfo,
        source: String,
        sourceId: Long,
        startedAtMs: Long,
    ): NcblSessionContext?
}

@Singleton
internal class DataStoreNcblSessionContextProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : NcblSessionContextProvider {
    override suspend fun beginSession(
        song: NcblSongInfo,
        source: String,
        sourceId: Long,
        startedAtMs: Long,
    ): NcblSessionContext? {
        if (song.id <= 0L || source.isBlank() || sourceId <= 0L) return null
        val preferences = context.dataStore.data.first()
        val musicU = preferences[CookieKey]?.trim().orEmpty()
        if (musicU.isBlank()) return null

        val deviceId = preferences[DeviceIdKey]?.trim().orEmpty().ifBlank {
            val generated = getDeviceId()
            context.dataStore.edit { stored ->
                if (stored[DeviceIdKey].isNullOrBlank()) stored[DeviceIdKey] = generated
            }
            context.dataStore.data.first()[DeviceIdKey]?.trim().orEmpty().ifBlank { generated }
        }
        val profile = NcblClientProfile.Android
        val buildVersion = startedAtMs.coerceAtLeast(0L).div(1_000L).toString()
        return NcblSessionContext(
            credentials = NcblCredentials(musicU = musicU, deviceId = deviceId),
            device = NcblDeviceInfo(
                deviceId = deviceId,
                osVersion = Build.VERSION.RELEASE.orEmpty(),
                model = Build.MODEL.orEmpty(),
                brand = Build.BRAND.orEmpty(),
                processName = context.applicationInfo.processName ?: context.packageName,
                buildType = BuildConfig.BUILD_TYPE,
                pid = android.os.Process.myPid(),
                buildId = Build.ID.orEmpty(),
            ),
            profile = profile,
            song = song,
            source = source,
            sourceId = sourceId.toString(),
            startedAtMs = startedAtMs,
            buildVersion = buildVersion,
            sessionId = UUID.randomUUID().toString(),
        )
    }
}

@Singleton
class NeteaseClientLogClient @Inject internal constructor(
    @Named("NeteaseClientLog") private val httpClient: OkHttpClient,
    private val contextProvider: NcblSessionContextProvider,
) {
    internal suspend fun beginSession(
        song: NcblSongInfo,
        source: String,
        sourceId: Long,
        startedAtMs: Long,
    ): NcblSessionContext? = contextProvider.beginSession(song, source, sourceId, startedAtMs)

    internal suspend fun submitStart(
        session: NcblSessionContext,
        eventTimeMs: Long,
    ): NcblUploadResult = upload(session, NcblPayload.start(session, eventTimeMs))

    internal suspend fun submitEnd(
        session: NcblSessionContext,
        playedDurationMs: Long,
        eventTimeMs: Long,
        endReason: String,
    ): NcblUploadResult = upload(
        session,
        NcblPayload.end(session, playedDurationMs, eventTimeMs, endReason),
    )

    private suspend fun upload(
        session: NcblSessionContext,
        event: NcblLogEvent,
    ): NcblUploadResult {
        val fileName = nextFileName()
        val result = try {
            val meta = buildMeta(session).toString().toByteArray(Charsets.UTF_8)
            val encoded = encode(meta, event.record)
            val request = buildRequest(session, fileName, encoded)
            val response = execute(request)
            response.use { received ->
                val responseBytes = try {
                    readBoundedResponseBody(received.body)
                } catch (_: IOException) {
                    null
                }
                parseNcblUploadResponse(
                    httpStatus = received.code,
                    body = responseBytes,
                    expectedFileName = fileName,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            NcblUploadResult(fileAccepted = false)
        }
        logPlaybackHistory(
            priority = if (result.fileAccepted) android.util.Log.INFO else android.util.Log.WARN,
            format = "NCBL action=%s id=%s time=%s startlogtime=%s logtime=%s " +
                "fileAccepted=%s httpStatus=%s businessCode=%s",
            event.action,
            event.songId,
            event.timeSeconds,
            event.startLogTimeSeconds,
            event.logTimeMs,
            result.fileAccepted,
            result.httpStatus,
            result.businessCode,
        )
        return result
    }

    private fun buildMeta(session: NcblSessionContext): JsonObject = JsonObject().apply {
        addProperty("MUSIC_U", session.credentials.musicU)
        addProperty("URS_APPID", session.profile.ursAppId)
        addProperty("appver", session.profile.appVersion)
        addProperty("buildver", session.buildVersion)
    }

    private fun buildRequest(
        session: NcblSessionContext,
        fileName: String,
        encoded: ByteArray,
    ): Request {
        val profile = session.profile
        val device = session.device
        val version = profile.appVersion
        val userAgent = "NeteaseMusic/$version(${profile.versionCode}); Dalvik/2.1.0 " +
            "(Linux; U; Android ${device.osVersion}; ${device.model} Build/${device.buildId})"
        val cookie = buildString {
            append("MUSIC_U=").append(session.credentials.musicU)
            append("; URS_APPID=").append(profile.ursAppId)
            append("; deviceId=").append(device.deviceId)
            append("; sDeviceId=").append(device.deviceId)
            append("; os=").append(profile.os)
            append("; osver=").append(device.osVersion)
            append("; appver=").append(profile.appVersion)
            append("; versioncode=").append(profile.versionCode)
            append("; buildver=").append(session.buildVersion)
            append("; channel=").append(profile.channel)
            append("; mobilename=").append(device.model.replace(' ', '+'))
            append("; brand=").append(device.brand.replace(' ', '+'))
            append("; packageType=").append(device.buildType)
        }
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                fileName,
                encoded.toRequestBody("multipart/form-data".toMediaType()),
            )
            .build()
        return Request.Builder()
            .url(NCBL_UPLOAD_ENDPOINT)
            .header("X-Music-U", session.credentials.musicU)
            .header("X-DeviceId", device.deviceId)
            .header("X-Os", profile.os)
            .header("X-Osver", device.osVersion)
            .header("X-SDeviceId", device.deviceId)
            .header("X-Buildver", session.buildVersion)
            .header("User-Agent", userAgent)
            .header("Cookie", cookie)
            .header("Accept", "application/json")
            .post(multipart)
            .build()
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                } else {
                    response.close()
                }
            }
        })
    }

    private fun nextFileName(): String {
        val id = fileSequence.getAndIncrement()
        val random = Random.nextLong(1L, 4_294_967_296L)
        return "flush_ua_${Random.nextInt(10_000, 100_000)}_${id}_$random"
    }

    private companion object {
        val fileSequence = AtomicLong()
    }
}

internal fun readBoundedResponseBody(body: ResponseBody?): ByteArray? {
    body ?: return null
    return body.use { responseBody ->
        if (responseBody.contentLength() > NCBL_MAX_RESPONSE_BYTES) return@use null
        val source: BufferedSource = responseBody.source()
        if (source.request(NCBL_MAX_RESPONSE_BYTES + 1L) &&
            source.buffer.size > NCBL_MAX_RESPONSE_BYTES
        ) {
            null
        } else {
            source.buffer.readByteArray()
        }
    }
}

internal fun parseNcblUploadResponse(
    httpStatus: Int,
    body: ByteArray?,
    expectedFileName: String,
): NcblUploadResult {
    val json = try {
        body?.toString(Charsets.UTF_8)?.let(JsonParser::parseString)?.asJsonObject
    } catch (_: Exception) {
        null
    }
    val code = json?.get("code")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.let { runCatching { it.asBigDecimal.intValueExact() }.getOrNull() }
    val data = json?.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
    val files = data?.get("successfiles")?.takeIf { it.isJsonArray }?.asJsonArray
    val acceptedFile = files
        ?.any { it.isJsonPrimitive && it.asString == expectedFileName }
        ?: false
    return NcblUploadResult(
        fileAccepted = httpStatus in 200..299 && code == 200 && acceptedFile,
        httpStatus = httpStatus,
        businessCode = code,
    )
}
