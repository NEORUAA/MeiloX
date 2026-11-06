package com.ljyh.mei.source.script

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.ljyh.mei.source.api.AudioResource
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Only a host-created opaque handle reaches Media3; scripts never receive file paths. */
object ScriptAudioResources {
    internal data class Entry(val pluginId: String, val resource: AudioResource, val policy: ScriptNetworkPolicy, val file: File?)
    private val entries = ConcurrentHashMap<String, Entry>()

    fun isRegistered(uri: String): Boolean = entries.containsKey(uri)

    internal fun register(pluginId: String, resource: AudioResource, policy: ScriptNetworkPolicy, directory: File): AudioResource {
        val file = if (resource.uri.startsWith("asset://")) {
            val name = resource.uri.removePrefix("asset://")
            if (!Regex("[A-Za-z0-9._-]+\\.(wav|mp3|flac|m4a|ogg)").matches(name)) invalid()
            val asset = File(directory, "assets/$name").canonicalFile
            if (!asset.isFile || !asset.path.startsWith(File(directory, "assets").canonicalPath + File.separator)) invalid()
            asset
        } else {
            policy.validateUrl(resource.uri)
            null
        }
        policy.validateHeaders(resource.headers)
        val handle = "mei-source://resource/${UUID.randomUUID()}"
        entries[handle] = Entry(pluginId, resource, policy, file)
        if (entries.size > 512) {
            val now = System.currentTimeMillis()
            entries.entries.removeIf { it.value.resource.expiresAtMs?.let { expires -> expires < now } == true }
        }
        if (entries.size > 1024) entries.keys.take(entries.size - 1024).forEach(entries::remove)
        return resource.copy(uri = handle, headers = emptyMap(), contentLength = file?.length() ?: resource.contentLength)
    }

    internal fun entry(uri: String): Entry = entries[uri] ?: invalid()
    internal fun remove(pluginId: String) { entries.entries.removeIf { it.value.pluginId == pluginId } }
    private fun invalid(): Nothing = throw SourceException(SourceErrorCode.PLUGIN, "Source audio resource is unavailable")
}

class ScriptAudioDataSource : DataSource {
    private var delegate: DataSource? = null
    private var opaqueUri: Uri? = null
    private val listeners = mutableListOf<TransferListener>()

    class Factory(@Suppress("UNUSED_PARAMETER") context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = ScriptAudioDataSource()
    }

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(delegate == null)
        val entry = ScriptAudioResources.entry(dataSpec.uri.toString())
        opaqueUri = dataSpec.uri
        val source: DataSource = if (entry.file != null) FileDataSource() else OkHttpDataSource.Factory(entry.policy.client).createDataSource()
        delegate = source
        listeners.forEach(source::addTransferListener)
        // Caller-supplied headers must never override the validated plugin request headers.
        val realSpec = dataSpec.buildUpon()
            .setUri(entry.file?.let { Uri.fromFile(it) } ?: Uri.parse(entry.resource.uri))
            .setHttpRequestHeaders(entry.resource.headers).build()
        return try { source.open(realSpec) } catch (_: java.io.IOException) {
            runCatching { source.close() }
            delegate = null
            throw SourceException(SourceErrorCode.NETWORK, "Source audio request failed")
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
        (delegate ?: throw IllegalStateException("Source is not open")).read(buffer, offset, length)
    } catch (_: java.io.IOException) { throw SourceException(SourceErrorCode.NETWORK, "Source audio read failed") }

    // Avoid publishing a signed URL via transfer diagnostics or metadata.
    override fun getUri(): Uri? = opaqueUri
    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
    override fun close() { try { delegate?.close() } finally { delegate = null; opaqueUri = null } }

    companion object { fun accepts(uri: Uri): Boolean = uri.scheme == "mei-source" }
}
