package com.ljyh.mei.source.update

import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.script.ScriptNetworkPolicy
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Request

data class SourceIndexResponse(val json: String?, val etag: String?)

interface SourceUpdateTransport {
    suspend fun index(etag: String?): SourceIndexResponse
    suspend fun download(entry: SourceMarketEntry, destination: File)
}

@Singleton
class SourceMarketTransport internal constructor(private val policy: ScriptNetworkPolicy) : SourceUpdateTransport {
    @Inject constructor() : this(ScriptNetworkPolicy(listOf("raw.githubusercontent.com")))

    // These host-owned requests can only target validated raw GitHub URLs. Preserve
    // system VPN/Fake-IP routing while TLS still authenticates that exact hostname.
    // Untrusted plugin networking retains its separate public-address DNS policy.
    private val client = policy.client.newBuilder().dns(okhttp3.Dns.SYSTEM).build()

    override suspend fun index(etag: String?): SourceIndexResponse {
        val builder = Request.Builder().url(SourceMarketIndex.URL)
        etag?.takeIf { it.length <= 512 && it.none(Char::isISOControl) }?.let { builder.header("If-None-Match", it) }
        return policy.exchange(client.newCall(builder.build()), {}) { response, guard ->
            if (response.code == 304) return@exchange SourceIndexResponse(null, etag)
            if (!response.isSuccessful) unavailable()
            val body = response.body ?: unavailable()
            if (body.contentLength() > SourceMarketIndex.MAX_BYTES) unavailable()
            val source = body.source()
            if (source.request(SourceMarketIndex.MAX_BYTES + 1L)) unavailable()
            val bytes = source.readByteArray()
            guard()
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            val json = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            SourceIndexResponse(json, response.header("ETag")?.takeIf { it.length <= 512 && it.none(Char::isISOControl) })
        }
    }

    override suspend fun download(entry: SourceMarketEntry, destination: File) {
        SourceMarketIndex.validate(entry)
        try {
            policy.exchange(client.newCall(Request.Builder().url(entry.packageUrl).build()), {}) { response, guard ->
                if (!response.isSuccessful) unavailable()
                val body = response.body ?: unavailable()
                if (body.contentLength() > MAX_PACKAGE_BYTES) unavailable()
                val digest = MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input -> destination.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        guard()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_PACKAGE_BYTES) unavailable()
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                } }
                guard()
                if (digest.digest().joinToString("") { "%02x".format(it) } != entry.sha256) {
                    throw SourceException(SourceErrorCode.INVALID_RESPONSE, "音源安装包校验失败")
                }
            }
        } catch (error: Exception) { destination.delete(); throw error }
    }

    private fun unavailable(): Nothing = throw SourceException(SourceErrorCode.NETWORK, "暂时无法连接音源市场")

    companion object { internal const val MAX_PACKAGE_BYTES = 8L * 1024 * 1024 }
}
