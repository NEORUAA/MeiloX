package com.ljyh.mei.source.script

import coil3.intercept.Interceptor
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import java.security.MessageDigest

/** Advanced pages keep image loading lazy while every socket stays inside the source's policy. */
object SourceArtworkRegistry {
    private const val PREFIX = "meisource-artwork:"
    @Volatile private var durable: SourceArtworkStore? = null
    internal fun attach(store: SourceArtworkStore) { durable = store }
    internal fun isLegacy(value: String) = value.startsWith(PREFIX)
    private val entries = object : LinkedHashMap<String, suspend () -> String>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, suspend () -> String>?) = size > 4096
    }

    internal fun register(namespace: String, url: String, load: suspend () -> String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((namespace + "\n" + url).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val key = PREFIX + digest
        synchronized(entries) { entries[key] = load }
        return key
    }

    internal fun owns(value: String) = isLegacy(value) || durable?.owns(value) == true
    internal suspend fun needsMetadataRefresh(value: String): Boolean {
        if (isLegacy(value)) return synchronized(entries) { value !in entries }
        return durable?.needsMetadataRefresh(value) == true
    }
    internal suspend fun resolve(value: String): String {
        if (!isLegacy(value)) return durable?.resolve(value) ?: throw SourceException(SourceErrorCode.MISSING_PROVIDER)
        val loader = synchronized(entries) { entries[value] } ?: throw SourceException(SourceErrorCode.MISSING_PROVIDER)
        return loader()
    }

    val interceptor = Interceptor { chain ->
        val data = chain.request.data.toString()
        if (owns(data)) {
            val file = resolve(data)
            chain.withRequest(chain.request.newBuilder().data(file).build()).proceed()
        } else chain.proceed()
    }
}
