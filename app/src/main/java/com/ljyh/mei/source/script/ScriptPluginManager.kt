package com.ljyh.mei.source.script

import android.content.Context
import android.net.Uri
import androidx.annotation.Keep
import com.google.gson.Gson
import com.ljyh.mei.source.api.ProviderInstanceId
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.api.SourceStatus
import com.ljyh.mei.source.core.ProviderRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class InstalledPlugin(val manifest: PluginManifest, val enabled: Boolean, val status: SourceStatus, val diagnostic: String? = null)
class PreparedPlugin internal constructor(val manifest: PluginManifest, internal val directory: File, internal val fingerprint: String)

@Keep
private data class PluginInstallState(val enabled: Boolean = true, val revision: Long = 0)

/** Local package installation is transactional; every update requires a new permission review. */
@Singleton
class ScriptPluginManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: ProviderRegistry,
    private val runtime: ScriptRuntime,
) {
    private val root = File(context.filesDir, "music-sources").apply { mkdirs() }
    private val secrets = PluginSecretStore(context)
    private val mutex = Mutex()
    private val providers = java.util.concurrent.ConcurrentHashMap<String, ScriptMusicProvider>()
    private val configurations = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    private val stores = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    private val diagnostics = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val mutableInstalled = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val installed: StateFlow<List<InstalledPlugin>> = mutableInstalled
    val runtimeSupported: Boolean get() = runtime.supported

    suspend fun loadInstalled() = withContext(Dispatchers.IO) { mutex.withLock {
        root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') }.forEach { directory ->
            if (providers.containsKey(directory.name)) return@forEach
            try {
                val manifest = PluginManifest.parse(File(directory, "manifest.json").readText())
                check(manifest.id == directory.name && File(directory, manifest.entry).isFile)
                val state = readState(directory)
                configurations[manifest.id] = secrets.read(manifest.id, "config")
                stores[manifest.id] = secrets.read(manifest.id, "storage")
                register(manifest, directory, state)
            } catch (_: Exception) {
                // A damaged installation is retained for removal/re-import. No unrelated state is changed.
                runCatching {
                    val manifest = PluginManifest.parse(File(directory, "manifest.json").readText())
                    configurations[manifest.id] = emptyMap()
                    stores[manifest.id] = emptyMap()
                    diagnostics[manifest.id] = "Source configuration could not be restored"
                    register(manifest, directory, PluginInstallState(enabled = false))
                }
            }
        }
        publish()
    } }

    suspend fun inspect(uri: Uri): PreparedPlugin = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "source-import/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val input = context.contentResolver.openInputStream(uri) ?: invalid("Unable to open source package")
            input.use { raw ->
                ZipInputStream(raw).use { zip ->
                    var total = 0L
                    var entries = 0
                    val names = mutableSetOf<String>()
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (++entries > 64) invalid("Source package has too many files")
                        val name = entry.name
                        val allowed = name in setOf("manifest.json", "index.js") || Regex("assets/[A-Za-z0-9._-]+\\.(wav|mp3|flac|m4a|ogg)").matches(name)
                        if (!allowed || !names.add(name) || entry.isDirectory) invalid("Source package contains an unsupported path")
                        val target = File(directory, name).canonicalFile
                        if (!target.path.startsWith(directory.canonicalPath + File.separator)) invalid("Invalid source package path")
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var fileBytes = 0
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                fileBytes += read
                                total += read
                                val limit = when (name) { "manifest.json" -> 64 * 1024; "index.js" -> 256 * 1024; else -> 4 * 1024 * 1024 }
                                if (fileBytes > limit || total > 8 * 1024 * 1024) invalid("Source package exceeds size limits")
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
            }
            val manifestFile = File(directory, "manifest.json")
            if (!manifestFile.isFile || !File(directory, "index.js").isFile) invalid("Source package needs manifest.json and index.js")
            val manifest = PluginManifest.parse(manifestFile.readText())
            PreparedPlugin(manifest, directory, fingerprint(directory))
        } catch (error: Exception) {
            directory.deleteRecursively()
            if (error is SourceException || error is kotlinx.coroutines.CancellationException) throw error
            invalid("Unable to read source package")
        }
    }

    suspend fun install(prepared: PreparedPlugin, approved: Boolean): InstalledPlugin = withContext(Dispatchers.IO) { mutex.withLock {
        if (!approved) invalid("Source permissions were not approved")
        if (!prepared.directory.isDirectory || prepared.fingerprint != fingerprint(prepared.directory)) invalid("Source package changed after review")
        val manifest = prepared.manifest
        if (providers.values.any { it.manifest.id != manifest.id && it.id == manifest.providerId }) invalid("Another source already uses this provider ID")
        val destination = File(root, manifest.id)
        val previous = providers[manifest.id]
        if (previous != null && previous.id != manifest.providerId) invalid("Updates cannot change provider identity")
        val previousState = if (destination.exists()) readState(destination) else PluginInstallState()
        val backup = File(root, ".${manifest.id}-${UUID.randomUUID()}")
        if (destination.exists() && !destination.renameTo(backup)) invalid("Unable to update installed source")
        try {
            if (!prepared.directory.renameTo(destination)) {
                prepared.directory.copyRecursively(destination, overwrite = false)
                prepared.directory.deleteRecursively()
            }
            val state = previousState.copy(revision = previousState.revision + 1)
            writeState(destination, state)
            configurations[manifest.id] = configurations[manifest.id] ?: secrets.read(manifest.id, "config")
            stores[manifest.id] = stores[manifest.id] ?: secrets.read(manifest.id, "storage")
            diagnostics.remove(manifest.id)
            register(manifest, destination, state)
            backup.deleteRecursively()
            publish()
            mutableInstalled.value.first { it.manifest.id == manifest.id }
        } catch (error: Exception) {
            destination.deleteRecursively()
            if (backup.exists()) backup.renameTo(destination)
            if (previous != null) {
                providers[manifest.id] = previous
                registry.register(previous)
            }
            if (error is SourceException || error is kotlinx.coroutines.CancellationException) throw error
            invalid("Unable to install source")
        }
    } }

    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) { mutex.withLock {
        val provider = providers[id] ?: invalid("Source is not installed")
        diagnostics.remove(id)
        val directory = File(root, id)
        val state = readState(directory).let { it.copy(enabled = enabled, revision = it.revision + 1) }
        writeState(directory, state)
        register(provider.manifest, directory, state)
        publish()
    } }

    suspend fun configure(id: String, values: Map<String, String>) = withContext(Dispatchers.IO) { mutex.withLock {
        val previous = providers[id] ?: invalid("Source is not installed")
        val keys = previous.manifest.config.map { it.key }.toSet()
        if (values.keys.any { it !in keys } || values.values.any { it.length > 8192 }) invalid("Invalid source configuration")
        secrets.write(id, "config", values)
        configurations[id] = values.toMap()
        // Configuration can select another account; do not carry old session state across it.
        secrets.write(id, "storage", emptyMap())
        stores[id] = emptyMap()
        val directory = File(root, id)
        val state = readState(directory).let { it.copy(revision = it.revision + 1) }
        writeState(directory, state)
        register(previous.manifest, directory, state)
        diagnostics.remove(id)
        publish()
    } }

    fun configuration(id: String): Map<String, String> = configurations[id].orEmpty().toMap()

    suspend fun uninstall(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val provider = providers.remove(id) ?: invalid("Source is not installed")
        registry.unregister(provider.instanceId)
        File(root, id).deleteRecursively()
        configurations.remove(id)
        stores.remove(id)
        diagnostics.remove(id)
        secrets.delete(id)
        // Room, playlists, cached audio and downloaded files are intentionally independent.
        publish()
    } }

    private fun register(manifest: PluginManifest, directory: File, state: PluginInstallState) {
        val provider = ScriptMusicProvider(manifest, directory, runtime, state.enabled,
            config = { configurations[manifest.id].orEmpty() },
            storage = { stores[manifest.id].orEmpty() },
            saveStorage = { values -> secrets.write(manifest.id, "storage", values); stores[manifest.id] = values },
            onFailure = { code ->
                diagnostics[manifest.id] = when (code) {
                    SourceErrorCode.INVALID_RESPONSE -> "Source returned invalid data"
                    else -> "Source execution failed; disable or re-enable it to recover"
                }
                registry.refresh()
                publish()
            },
            configurationVersion = state.revision,
        )
        registry.register(provider)
        providers[manifest.id] = provider
    }

    @Synchronized private fun publish() {
        mutableInstalled.value = providers.values.map { InstalledPlugin(it.manifest, it.enabled, it.status, diagnostics[it.manifest.id]) }.sortedBy { it.manifest.name }
    }

    private fun readState(directory: File): PluginInstallState = File(directory, "state.json").takeIf(File::isFile)
        ?.let { Gson().fromJson(it.readText(), PluginInstallState::class.java) } ?: PluginInstallState()

    private fun writeState(directory: File, state: PluginInstallState) {
        val temporary = File(directory, "state.tmp")
        temporary.writeText(Gson().toJson(state))
        check(temporary.renameTo(File(directory, "state.json")))
    }

    private fun fingerprint(directory: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        directory.walkTopDown().filter(File::isFile).sortedBy { it.relativeTo(directory).path }.forEach { file ->
            digest.update(file.relativeTo(directory).path.toByteArray())
            digest.update(file.readBytes())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun invalid(message: String): Nothing = throw SourceException(SourceErrorCode.PLUGIN, message)
}
