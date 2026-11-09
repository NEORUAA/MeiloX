package com.ljyh.mei.source.update

import com.google.gson.JsonParser
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.script.ScriptNetworkPolicy
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Request

data class SourceIndexResponse(val json: String?, val etag: String?)

interface SourceUpdateTransport {
    suspend fun index(etag: String?): SourceIndexResponse
    suspend fun resolve(listing: SourceMarketListing): SourceMarketEntry
    suspend fun download(entry: SourceMarketEntry, destination: File)
}

@Singleton
class SourceMarketTransport internal constructor(private val policy: ScriptNetworkPolicy) : SourceUpdateTransport {
    @Inject constructor() : this(ScriptNetworkPolicy(listOf("raw.githubusercontent.com", "api.github.com")))

    // Resolve the repository's current package commit when the GitHub API allows it;
    // the raw HEAD fallback keeps latest-package checks working under public API limits.
    private val client = policy.client.newBuilder().dns(okhttp3.Dns.SYSTEM).build()

    override suspend fun index(etag: String?): SourceIndexResponse {
        val builder = Request.Builder().url(SourceMarketIndex.URL)
        etag?.takeIf { it.length <= 512 && it.none(Char::isISOControl) }?.let { builder.header("If-None-Match", it) }
        return policy.exchange(client.newCall(builder.build()), {}) { response, guard ->
            if (response.code == 304) return@exchange SourceIndexResponse(null, etag)
            if (!response.isSuccessful) unavailable()
            val body = response.body
            if (body.contentLength() > SourceMarketIndex.MAX_BYTES) unavailable()
            val source = body.source()
            if (source.request(SourceMarketIndex.MAX_BYTES + 1L)) unavailable()
            val bytes = source.readByteArray()
            guard()
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            SourceIndexResponse(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString(),
                response.header("ETag")?.takeIf { it.length <= 512 && it.none(Char::isISOControl) })
        }
    }

    override suspend fun resolve(listing: SourceMarketListing): SourceMarketEntry {
        SourceMarketIndex.validate(listing)
        val (owner, repository) = SourceMarketIndex.repositoryParts(listing.repository)
        val query = java.net.URLEncoder.encode(listing.packagePath, Charsets.UTF_8.name())
        val apiUrl = "https://api.github.com/repos/$owner/$repository/commits?path=$query&per_page=1"
        val commit = policy.exchange(client.newCall(Request.Builder().url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "MeiloX-Source-Market")
            .build()), {}) { response, guard ->
            if (response.code in 400..499) return@exchange null
            if (!response.isSuccessful) unavailable()
            val body = response.body
            if (body.contentLength() > MAX_METADATA_BYTES) invalidResponse()
            val source = body.source()
            if (source.request(MAX_METADATA_BYTES + 1L)) invalidResponse()
            val json = JsonParser.parseString(source.readString(Charsets.UTF_8))
            guard()
            val first = json.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()?.takeIf { it.isJsonObject }
                ?: invalidResponse()
            first.asJsonObject.get("sha")?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { SHA1.matches(it) } ?: invalidResponse()
        }
        val ref = commit ?: "HEAD"
        val url = "https://raw.githubusercontent.com/$owner/$repository/$ref/${listing.packagePath}"
        val archive = File.createTempFile("source-market-resolve-", ".meisource")
        try {
            val digest = downloadBytes(url, archive)
            val manifest = ZipFile(archive).use { zip ->
                val item = zip.getEntry("manifest.json") ?: invalidResponse()
                if (item.size > MAX_MANIFEST_BYTES) invalidResponse()
                zip.getInputStream(item).use { input ->
                    JsonParser.parseReader(input.reader(Charsets.UTF_8)).takeIf { it.isJsonObject }?.asJsonObject
                        ?: invalidResponse()
                }
            }
            val id = manifest.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: invalidResponse()
            val providerId = manifest.get("providerId")?.takeIf { it.isJsonPrimitive }?.asString ?: invalidResponse()
            val version = manifest.get("version")?.takeIf { it.isJsonPrimitive }?.asString ?: invalidResponse()
            val apiVersion = manifest.get("apiVersion")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
                ?: invalidResponse()
            if (id != listing.packageId || providerId != listing.providerId || apiVersion != listing.apiVersion || SourceVersion.parse(version) == null) invalidResponse()
            return SourceMarketEntry(id, providerId, listing.name, version, listing.repository, url, digest, apiVersion, listing.packagePath)
        } finally { archive.delete() }
    }

    override suspend fun download(entry: SourceMarketEntry, destination: File) {
        SourceMarketIndex.validate(entry)
        try {
            downloadBytes(entry.packageUrl, destination)
            if (sha256(destination) != entry.sha256) throw SourceException(SourceErrorCode.INVALID_RESPONSE, "音源安装包校验失败")
        } catch (error: Exception) { destination.delete(); throw error }
    }

    private suspend fun downloadBytes(url: String, destination: File): String = policy.exchange(
        client.newCall(Request.Builder().url(url).build()), {}) { response, guard ->
        if (!response.isSuccessful) unavailable()
        val body = response.body
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
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun unavailable(): Nothing = throw SourceException(SourceErrorCode.NETWORK, "暂时无法连接音源市场")
    private fun invalidResponse(): Nothing = throw SourceException(SourceErrorCode.INVALID_RESPONSE, "音源市场响应无效")

    companion object {
        internal const val MAX_PACKAGE_BYTES = 8L * 1024 * 1024
        private const val MAX_METADATA_BYTES = 256 * 1024L
        private const val MAX_MANIFEST_BYTES = 64 * 1024L
        private val SHA1 = Regex("[a-f0-9]{40}")
    }
}
