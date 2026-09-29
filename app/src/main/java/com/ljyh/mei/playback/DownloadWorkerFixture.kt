package com.ljyh.mei.playback

import android.content.Context
import androidx.room.Room
import com.google.gson.JsonObject
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.data.model.room.DownloadTask
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.parasite.HostCallFactory
import com.ljyh.mei.parasite.HostPendingRequest
import com.ljyh.mei.parasite.HostRequestBackend
import com.ljyh.mei.parasite.HostRequestBridge
import com.ljyh.mei.parasite.HostSessionBridge
import com.ljyh.mei.parasite.HostSessionIdentity
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

internal enum class DownloadWorkerScenario { SUCCESS, DENIED, TRUNCATED, CORRUPT, HOLD, INVALIDATE }

/** A closed, synthetic graph: neither the request backend nor resource calls can use the network. */
internal class DownloadWorkerFixture(
    private val context: Context,
    val scenario: DownloadWorkerScenario,
    val database: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build(),
) : Closeable {
    init { check(BuildConfig.DEBUG) }
    val sessions = HostSessionBridge()
    var account = 17L
    val grants = AtomicInteger()
    val transfers = AtomicInteger()
    val canceled = AtomicBoolean()
    val closed = AtomicBoolean()
    val readBlocked = CountDownLatch(1)
    private val release = CountDownLatch(1)
    val notifications = java.util.Collections.synchronizedList(mutableListOf<Triple<String, Int, Boolean>>())
    private val original = syntheticAudio()
    private val requests = HostRequestBridge(sessions).apply {
        bind(object : HostRequestBackend {
            override fun sessionIdentity() = HostSessionIdentity(account, true, false)
            override fun open(path: String, parameters: Map<String, String>): HostPendingRequest {
                check(path == "song/enhance/download/url/v1")
                check(parameters == mapOf("id" to "1_0", "level" to "standard", "immerseType" to "ste"))
                grants.incrementAndGet()
                return object : HostPendingRequest {
                    override fun execute(): String {
                        val data = JsonObject().apply {
                            addProperty("id", 1)
                            addProperty("code", if (scenario == DownloadWorkerScenario.DENIED) -105 else 200)
                            addProperty("url", ADDRESS)
                            addProperty("type", "wav")
                            addProperty("level", "standard")
                            addProperty("size", original.size)
                            addProperty("md5", MessageDigest.getInstance("MD5").digest(original)
                                .joinToString("") { "%02x".format(it) })
                            addProperty("expi", 600)
                        }
                        return JsonObject().apply { addProperty("code", 200); add("data", data) }.toString()
                    }
                    override fun cancel() = Unit
                    override fun close() = Unit
                }
            }
        })
    }
    private val media = AndroidDownloadMediaStore(context)
    val publication = DownloadPublication(database, media, context.packageName)
    val environment = DownloadWorkerEnvironment(
        database, sessions,
        Retrofit.Builder().baseUrl("https://music.163.com/").callFactory(HostCallFactory(requests))
            .addConverterFactory(GsonConverterFactory.create()).build().create(ApiService::class.java),
        client = { request -> syntheticCall(request) },
        lyric = { _, _ -> "[00:00.00]Synthetic qualification" }, cover = { null },
        publication = publication,
        notification = { title, progress, ongoing -> notifications += Triple(title, progress, ongoing) },
    )

    fun task(id: UUID) = DownloadTask(
        songId = "1", requestId = id.toString(), ownerId = 17, quality = "standard",
        songTitle = "MeiloX synthetic qualification", songArtist = "Test", songAlbum = "Test",
        playlistName = "MeiloX Test/$id", downloadPath = "Music",
    )

    private fun syntheticCall(request: Request): Call {
        check(request.url.toString() == ADDRESS)
        transfers.incrementAndGet()
        val bytes = when (scenario) {
            DownloadWorkerScenario.TRUNCATED -> original.copyOf(original.size - 1)
            DownloadWorkerScenario.CORRUPT -> original.copyOf().apply { this[lastIndex] = 9 }
            else -> original
        }
        val source = object : Source {
            var offset = 0
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (offset > 0 && scenario in setOf(DownloadWorkerScenario.HOLD, DownloadWorkerScenario.INVALIDATE)) {
                    readBlocked.countDown()
                    if (scenario == DownloadWorkerScenario.INVALIDATE) sessions.invalidate()
                    check(release.await(120, TimeUnit.SECONDS)) { "Synthetic cancellation timed out" }
                    throw IOException("Synthetic call canceled")
                }
                if (offset == bytes.size) return -1
                val count = minOf(byteCount.toInt(), bytes.size - offset, 4096)
                sink.write(bytes, offset, count)
                offset += count
                return count.toLong()
            }
            override fun timeout() = Timeout.NONE
            override fun close() { closed.set(true) }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source() = source
        }
        return object : Call by OkHttpClient().newCall(request) {
            override fun execute() = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("Synthetic").body(body).build()
            override fun enqueue(responseCallback: Callback) = error("Synthetic call is synchronous")
            override fun clone(): Call = error("Synthetic call cannot be cloned")
            override fun cancel() { canceled.set(true); release.countDown() }
            override fun isCanceled() = canceled.get()
            override fun isExecuted() = true
        }
    }

    fun hasTemporaryFile(id: UUID) = File(context.cacheDir, "download/$id.wav").exists()

    suspend fun cleanMedia() {
        database.songDao().updatePath("1", null)
        publication.recover()
        check(database.downloadArtifactDao().all().isEmpty()) { "Synthetic media cleanup is incomplete" }
    }

    override fun close() { release.countDown(); database.close() }

    companion object {
        private const val ADDRESS = "https://media.example.test/qualification.wav"
        private fun syntheticAudio(): ByteArray = ByteBuffer.allocate(44 + 32000).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(32036).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(32000).array()
    }
}
