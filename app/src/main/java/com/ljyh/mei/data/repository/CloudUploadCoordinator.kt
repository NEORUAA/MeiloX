package com.ljyh.mei.data.repository

import com.google.gson.JsonObject
import com.ljyh.mei.data.network.api.MeloXDirectService
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import java.io.File
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Called once per singleton Repository; unfinished uploads do not survive a process. */
internal fun prepareCloudUploadDirectory(cache: File): File = File(cache, "cloud-upload-snapshots-v1").apply {
    check(isDirectory || mkdirs()) { "Cloud upload cache is unavailable" }
    listFiles()?.filter { it.isFile && it.name.startsWith("upload-") && it.name.endsWith(".bin") }
        ?.forEach { check(it.delete()) { "Unable to remove an interrupted upload snapshot" } }
}

data class CloudUploadFile(
    val file: File,
    val filename: String,
    val extension: String,
    val normalizedStem: String,
    val size: Long,
    val md5: String,
    val songName: String,
    val artist: String,
    val album: String,
    val mimeType: String,
)

class CloudUploadAuthorization(val bucket: String, val objectKey: String, val token: String) {
    init { require(bucket.isNotBlank() && objectKey.isNotBlank() && token.isNotBlank()) }
    override fun toString() = "CloudUploadAuthorization(redacted)"
}

interface CloudBinaryUploader {
    suspend fun upload(file: CloudUploadFile, authorization: CloudUploadAuthorization, owner: SessionStamp, onProgress: (Long, Long) -> Unit)
}

/** Keep allocation, file transfer and publication under one captured account. */
class CloudUploadCoordinator @Inject constructor(
    @Named("MeloXEapi") private val eapi: MeloXDirectService,
    @Named("MeloXWeapi") private val weapi: MeloXDirectService,
    private val sessions: SessionStore,
    private val binary: CloudBinaryUploader,
) {
    suspend fun upload(file: CloudUploadFile, owner: SessionStamp, onProgress: (Long, Long) -> Unit) {
        require(file.size > 0 && file.file.length() == file.size && file.md5.matches(Regex("[a-f0-9]{32}")))
        requireOwner(owner)
        val context = currentCoroutineContext()
        suspend fun request(service: MeloXDirectService, path: String, body: Map<String, Any>): JsonObject {
            context.ensureActive()
            requireOwner(owner)
            val response = service.post(path, body, expectedSession = owner)
            context.ensureActive()
            requireOwner(owner)
            check(response.get("code")?.asInt == 200) { "Cloud request was not accepted" }
            return response
        }
        val bitrate = "999000"
        val check = request(eapi, "/api/cloud/upload/check", mapOf(
            "bitrate" to bitrate, "ext" to "", "length" to file.size,
            "md5" to file.md5, "songId" to "0", "version" to 1,
        ))
        val needUpload = check.get("needUpload")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
            ?.asBoolean ?: error("Missing cloud upload decision")
        // A new upload may not have a catalog identity before metadata registration.
        val songId = check.get("songId")?.asString?.toLongOrNull()?.takeIf { it >= 0 }
            ?: error("Missing cloud song identity")
        fun tokenBody(bucket: String) = mapOf<String, Any>(
            "bucket" to bucket, "ext" to file.extension, "filename" to file.normalizedStem,
            "local" to false, "nos_product" to 3, "type" to "audio", "md5" to file.md5,
        )
        val metadata = request(eapi, "/api/nos/token/alloc", tokenBody("")).getAsJsonObject("result")
            ?: error("Missing cloud metadata authorization")
        val resourceId = metadata.get("resourceId")?.asString?.takeIf { it.isNotBlank() }
            ?: error("Missing cloud resource identity")
        if (needUpload) {
            val bucket = "jd-musicrep-privatecloud-audio-public"
            val token = request(weapi, "/api/nos/token/alloc", tokenBody(bucket)).getAsJsonObject("result")
                ?: error("Missing cloud file authorization")
            check(!token.has("bucket") || token.get("bucket").asString == bucket) { "Unexpected cloud upload bucket" }
            val authorization = CloudUploadAuthorization(bucket,
                token.get("objectKey")?.asString ?: error("Missing cloud object identity"),
                token.get("token")?.asString ?: error("Missing cloud file token"))
            binary.upload(file, authorization, owner) { sent, total ->
                context.ensureActive()
                check(total == file.size && sent in 0..total) { "Invalid cloud upload progress" }
                sessions.withCurrent(owner) { onProgress(sent.coerceAtMost(total - 1), total) }
            }
        }
        val info = request(eapi, "/api/upload/cloud/info/v2", mapOf(
            "md5" to file.md5, "songid" to songId, "filename" to file.filename,
            "song" to file.songName, "album" to file.album, "artist" to file.artist,
            "bitrate" to bitrate, "resourceId" to resourceId,
        ))
        val publishId = info.get("songId")?.asString?.toLongOrNull()?.takeIf { it > 0 }
            ?: error("Missing cloud publication identity")
        request(eapi, "/api/cloud/pub/v2", mapOf("songid" to publishId))
        context.ensureActive()
        sessions.withCurrent(owner) { onProgress(file.size, file.size) }
    }

    private fun requireOwner(owner: SessionStamp) {
        check(owner.identity.authenticated && !owner.identity.anonymous && owner.identity.userId > 0) { "Sign-in required" }
        sessions.requireCurrent(owner)
    }
}
