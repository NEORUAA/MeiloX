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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response

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
    private val monitor = Any()
    private var transfer: Transfer? = null
    private var cancelled = false
    @Volatile private var opaqueUri: Uri? = null
    private val listeners = mutableListOf<TransferListener>()

    class Factory(@Suppress("UNUSED_PARAMETER") context: Context) : DataSource.Factory {
        override fun createDataSource(): ScriptAudioDataSource = ScriptAudioDataSource()
    }

    override fun addTransferListener(transferListener: TransferListener) {
        synchronized(monitor) {
            listeners += transferListener
            transfer?.source?.addTransferListener(transferListener)
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        val entry = ScriptAudioResources.entry(dataSpec.uri.toString())
        val current = synchronized(monitor) {
            check(transfer == null)
            if (cancelled) throw SourceException(SourceErrorCode.CANCELLED)
            val calls = if (entry.file == null) AudioCalls(entry.policy.audioClient) else null
            val source = if (calls == null) FileDataSource() else OkHttpDataSource.Factory(calls).createDataSource()
            listeners.forEach(source::addTransferListener)
            Transfer(source, calls).also { transfer = it; opaqueUri = dataSpec.uri }
        }
        // Caller-supplied headers must never override the validated plugin request headers.
        val realSpec = dataSpec.buildUpon()
            .setUri(entry.file?.let { Uri.fromFile(it) } ?: Uri.parse(entry.resource.uri))
            .setHttpRequestHeaders(entry.resource.headers).build()
        return try {
            val length = current.source.open(realSpec)
            val closed = synchronized(monitor) { current.opening = false; current.closed }
            if (closed) throw SourceException(SourceErrorCode.CANCELLED)
            length
        } catch (_: java.io.IOException) {
            current.calls?.cancel()
            runCatching { current.source.close() }
            val closed = synchronized(monitor) {
                current.opening = false
                if (transfer === current) { transfer = null; opaqueUri = null }
                current.closed
            }
            throw SourceException(if (closed) SourceErrorCode.CANCELLED else SourceErrorCode.NETWORK,
                "Source audio request failed")
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val current = synchronized(monitor) {
            (transfer ?: throw IllegalStateException("Source is not open")).also { it.reading = true }
        }
        return try { current.source.read(buffer, offset, length)
        } catch (_: java.io.IOException) { throw SourceException(SourceErrorCode.NETWORK, "Source audio read failed")
        } finally {
            val closed = synchronized(monitor) { current.reading = false; current.closed }
            if (closed) runCatching { current.source.close() }
        }
    }

    // Avoid publishing a signed URL via transfer diagnostics or metadata.
    override fun getUri(): Uri? = opaqueUri
    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
    override fun close() {
        val current = synchronized(monitor) {
            val value = transfer ?: return
            value.closed = true
            transfer = null
            opaqueUri = null
            value to (value.opening || value.reading)
        }
        current.first.calls?.cancel()
        // OkHttpDataSource mutates response fields in close(). Cancellation unblocks active
        // I/O first, and that thread then performs cleanup without racing those fields.
        if (!current.second) current.first.source.close()
    }

    /** Unlike reusable close(), cancellation also fences an open that has not started yet. */
    internal fun cancel() {
        synchronized(monitor) { cancelled = true }
        close()
    }

    private class Transfer(val source: DataSource, val calls: AudioCalls?) {
        var opening = true
        var reading = false
        var closed = false
    }

    private class AudioCalls(private val factory: Call.Factory) : Call.Factory {
        private val monitor = Any()
        private var cancelled = false
        private var call: AudioCall? = null

        override fun newCall(request: Request): Call = AudioCall(factory.newCall(request)).also { next ->
            synchronized(monitor) {
                call = next
                if (cancelled) next.cancel()
            }
        }

        fun cancel() {
            val current = synchronized(monitor) { cancelled = true; call }
            current?.cancel()
        }
    }

    private class AudioCall(private val delegate: Call) : Call by delegate {
        private val callback = AtomicReference<Callback?>()
        private val delivered = AtomicBoolean()

        override fun enqueue(responseCallback: Callback) {
            check(callback.compareAndSet(null, responseCallback)) { "Already enqueued" }
            if (delegate.isCanceled()) { cancelled(responseCallback); return }
            delegate.enqueue(object : Callback {
                override fun onFailure(call: Call, error: java.io.IOException) {
                    if (delivered.compareAndSet(false, true)) responseCallback.onFailure(this@AudioCall, error)
                }
                override fun onResponse(call: Call, response: Response) {
                    if (delivered.compareAndSet(false, true)) responseCallback.onResponse(this@AudioCall, response)
                    else response.close()
                }
            })
        }

        override fun cancel() {
            delegate.cancel()
            callback.get()?.let(::cancelled)
        }

        private fun cancelled(callback: Callback) {
            // OkHttp keeps a cancelled queued call until a dispatcher slot opens. Complete
            // Media3's waiting future now; a late response still gets closed exactly once.
            if (delivered.compareAndSet(false, true)) callback.onFailure(this, java.io.IOException("Source audio cancelled"))
        }
    }

    companion object { fun accepts(uri: Uri): Boolean = uri.scheme == "mei-source" }
}
