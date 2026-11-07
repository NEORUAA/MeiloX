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
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
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

internal data class PluginUpdateSnapshot(val manifest: PluginManifest, val fingerprint: String, val repository: String?, val enabled: Boolean)
@Keep
private data class PluginMarketOrigin(val repository: String, val fingerprint: String)

@Keep
private data class PluginInstallState(val enabled: Boolean = true, val revision: Long = 0)

/** Package replacement and its reviewed origin share one rollback boundary. */
@Singleton
class ScriptPluginManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: ProviderRegistry,
    private val runtime: ScriptRuntime,
    private val featureExecutor: com.ljyh.mei.source.core.SourceFeatureExecutor =
        com.ljyh.mei.source.core.SourceFeatureExecutor(com.ljyh.mei.source.core.SourceRouter(registry)),
    private val platformEffects: com.ljyh.mei.source.platform.SourcePlatformEffects =
        com.ljyh.mei.source.platform.SourcePlatformEffects(context, registry),
    private val artworkStore: SourceArtworkStore? = null,
    private val playbackUpdates: com.ljyh.mei.source.update.SourceUpdatePlaybackGuard = com.ljyh.mei.source.update.SourceUpdatePlaybackGuard(),
) {
    private val root = File(context.filesDir, "music-sources").apply { mkdirs() }
    private val secrets = PluginSecretStore(context)
    private val mutex get() = installationMutex
    private val revisions = java.util.concurrent.ConcurrentHashMap<String, PluginRevisionGate>()
    private val providers = java.util.concurrent.ConcurrentHashMap<String, ScriptMusicProvider>()
    private val configurations = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    private val stores = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    private val diagnostics = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val mutableInstalled = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val installed: StateFlow<List<InstalledPlugin>> = mutableInstalled
    private val mutableStartupError = MutableStateFlow<String?>(null)
    val startupError: StateFlow<String?> = mutableStartupError
    val runtimeSupported: Boolean get() = runtime.supported

    suspend fun loadInstalled() = withContext(Dispatchers.IO) { mutex.withLock {
        try {
        val incomplete = PluginInstallJournal.recover(root) { fingerprint(it, installed = true) }
        try {
            BundledSourceProvisioner.ensure(context, root, secrets)
            mutableStartupError.value = if (incomplete.isEmpty()) null else "部分音源安装未完成，可重新导入"
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutableStartupError.value = "默认音源恢复失败，可重试或从文件导入"
        }
        root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') && it.name !in incomplete }.forEach { directory ->
            if (providers.containsKey(directory.name)) return@forEach
            try {
                val manifest = PluginManifest.parse(File(directory, "manifest.json").readText())
                check(manifest.id == directory.name && File(directory, manifest.entry).isFile)
                val state = readState(directory)
                changeSource(manifest.id) { gate, revision ->
                    configurations[manifest.id] = secrets.read(manifest.id, "config")
                    stores[manifest.id] = secrets.read(manifest.id, "storage")
                    register(manifest, directory, state, gate, revision)
                }
            } catch (_: Exception) {
                // A damaged installation is retained for removal/re-import. No unrelated state is changed.
                runCatching {
                    val manifest = PluginManifest.parse(File(directory, "manifest.json").readText())
                    changeSource(manifest.id) { gate, revision ->
                        configurations[manifest.id] = emptyMap()
                        stores[manifest.id] = emptyMap()
                        diagnostics[manifest.id] = "Source configuration could not be restored"
                        register(manifest, directory, PluginInstallState(enabled = false), gate, revision)
                    }
                }
            }
        }
        publish()
        } finally { registry.completeInitialization() }
    } }

    suspend fun inspect(uri: Uri): PreparedPlugin {
        var completed: PreparedPlugin? = null
        try {
            return withContext(Dispatchers.IO) { inspectPackage(uri).also { completed = it } }
        } catch (error: Exception) {
            // A cancelled return dispatcher can discard an already prepared result.
            completed?.directory?.deleteRecursively()
            throw error
        }
    }

    private fun inspectPackage(uri: Uri): PreparedPlugin {
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
                        val limit = packageFileLimit(name)
                        if (!names.add(name) || entry.isDirectory) invalid("Source package contains an unsupported path")
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
                                if (fileBytes > limit || total > 8 * 1024 * 1024) invalid("Source package exceeds size limits")
                                output.write(buffer, 0, read)
                            }
                        }
                        if (name == "LICENSE" || name == "NOTICE") validateLegalText(target.readBytes())
                    }
                }
            }
            val manifestFile = File(directory, "manifest.json")
            if (!manifestFile.isFile || !File(directory, "index.js").isFile) invalid("Source package needs manifest.json and index.js")
            val manifest = PluginManifest.parse(manifestFile.readText())
            return PreparedPlugin(manifest, directory, fingerprint(directory))
        } catch (error: Exception) {
            directory.deleteRecursively()
            if (error is SourceException || error is kotlinx.coroutines.CancellationException) throw error
            invalid("Unable to read source package")
        }
    }

    suspend fun install(prepared: PreparedPlugin, approved: Boolean): InstalledPlugin = withContext(Dispatchers.IO) { mutex.withLock {
        if (!approved) invalid("Source permissions were not approved")
        installLocked(prepared, repository = null)
    } }

    internal suspend fun updateSnapshot(id: String): PluginUpdateSnapshot? = withContext(Dispatchers.IO) { mutex.withLock { snapshotLocked(id) } }

    internal suspend fun adoptMarketOrigin(id: String, expected: PluginUpdateSnapshot, repository: String): Boolean =
        withContext(Dispatchers.IO) { mutex.withLock {
            val current = snapshotLocked(id) ?: return@withLock false
            if (!samePayload(current, expected) || current.repository != null) return@withLock false
            writeMarketOrigin(File(root, id), repository, current.fingerprint)
            true
        } }

    internal suspend fun installMarket(
        prepared: PreparedPlugin,
        entry: com.ljyh.mei.source.update.SourceMarketEntry,
        expected: PluginUpdateSnapshot?,
        automatic: Boolean,
        authorized: () -> Boolean = { true },
    ): InstalledPlugin = withContext(Dispatchers.IO) { mutex.withLock {
        com.ljyh.mei.source.update.SourceMarketIndex.validate(entry)
        if (!entry.matches(prepared.manifest)) invalid("Market package metadata changed")
        val current = snapshotLocked(entry.packageId)
        if (current == null && expected != null || current != null && expected == null ||
            current != null && expected != null && !samePayload(current, expected)) invalid("Source changed while preparing its update")
        if (current != null) {
            val oldVersion = com.ljyh.mei.source.update.SourceVersion.parse(current.manifest.version)
            val nextVersion = com.ljyh.mei.source.update.SourceVersion.parse(entry.version)
            if (oldVersion == null || nextVersion == null || nextVersion <= oldVersion) invalid("Source update is not newer")
        }
        if (automatic && (current == null || com.ljyh.mei.source.update.sourceUpdateReviewReasons(
                current.manifest, prepared.manifest, current.repository, entry.repository).isNotEmpty())) invalid("Source update needs permission review")
        if (automatic) playbackUpdates.installIfIdle(entry.providerId) {
            if (!authorized()) throw com.ljyh.mei.source.update.SourceUpdateDeferred()
            installLocked(prepared, entry.repository)
        }
        else installLocked(prepared, entry.repository)
    } }

    suspend fun discardPrepared(prepared: PreparedPlugin) = withContext(Dispatchers.IO) { mutex.withLock {
        val parent = File(context.cacheDir, "source-import").canonicalFile
        val directory = prepared.directory.canonicalFile
        if (directory.parentFile == parent && runCatching { UUID.fromString(directory.name) }.isSuccess) directory.deleteRecursively()
        Unit
    } }

    private fun samePayload(a: PluginUpdateSnapshot, b: PluginUpdateSnapshot): Boolean =
        a.fingerprint == b.fingerprint && a.manifest.id == b.manifest.id && a.manifest.providerId == b.manifest.providerId && a.repository == b.repository

    private fun snapshotLocked(id: String): PluginUpdateSnapshot? {
        val provider = providers[id] ?: return null
        val directory = File(root, id)
        val fingerprint = fingerprint(directory, installed = true)
        val origin = runCatching { Gson().fromJson(File(directory, ".market-origin.json").readText(), PluginMarketOrigin::class.java) }.getOrNull()
        return PluginUpdateSnapshot(provider.manifest, fingerprint, origin?.takeIf { it.fingerprint == fingerprint }?.repository, provider.enabled)
    }

    private fun writeMarketOrigin(directory: File, repository: String, fingerprint: String) {
        val temporary = File(directory, ".market-origin.tmp")
        temporary.writeText(Gson().toJson(PluginMarketOrigin(repository, fingerprint)))
        java.nio.file.Files.move(temporary.toPath(), File(directory, ".market-origin.json").toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private fun installLocked(prepared: PreparedPlugin, repository: String?): InstalledPlugin {
        if (!prepared.directory.isDirectory || prepared.fingerprint != fingerprint(prepared.directory)) invalid("Source package changed after review")
        val manifest = prepared.manifest
        if (providers.values.any { it.manifest.id != manifest.id && it.id == manifest.providerId }) invalid("Another source already uses this provider ID")
        val destination = File(root, manifest.id)
        val previous = providers[manifest.id]
        if (previous != null && previous.id != manifest.providerId) invalid("Updates cannot change provider identity")
        val previousState = if (destination.exists()) readState(destination) else PluginInstallState()
        val hadDestination = destination.exists()
        val backup = File(root, ".${manifest.id}-${UUID.randomUUID()}")
        val stage = File(root, ".source-stage-${manifest.id}-${UUID.randomUUID()}")
        val transaction = PluginInstallTransaction(manifest.id, stage.name, backup.name, prepared.fingerprint,
            if (hadDestination) fingerprint(destination, installed = true) else null)
        try {
            // Complete every potentially partial copy before changing the visible installation.
            if (!prepared.directory.renameTo(stage)) {
                check(prepared.directory.copyRecursively(stage, overwrite = false))
                prepared.directory.deleteRecursively()
            }
            val state = previousState.copy(revision = previousState.revision + 1)
            writeState(stage, state)
            if (repository != null) writeMarketOrigin(stage, repository, prepared.fingerprint)
            File(destination, "artwork").takeIf(File::isDirectory)?.let { check(it.copyRecursively(File(stage, "artwork"))) }
            PluginInstallJournal.write(root, transaction)
            return changeSource(manifest.id) { gate, revision ->
                var movedPrevious = false
                try {
                    if (hadDestination) {
                        if (!destination.renameTo(backup)) invalid("Unable to update installed source")
                        movedPrevious = true
                    }
                    if (!stage.renameTo(destination)) invalid("Unable to activate source package")
                    configurations[manifest.id] = configurations[manifest.id] ?: secrets.read(manifest.id, "config")
                    stores[manifest.id] = stores[manifest.id] ?: secrets.read(manifest.id, "storage")
                    diagnostics.remove(manifest.id)
                    register(manifest, destination, state, gate, revision)
                    backup.deleteRecursively()
                    PluginInstallJournal.finish(root, manifest.id)
                    publish()
                    mutableInstalled.value.first { it.manifest.id == manifest.id }
                } catch (error: Exception) {
                    if (!hadDestination || movedPrevious) destination.deleteRecursively()
                    if (movedPrevious) check(backup.renameTo(destination))
                    if (previous != null) register(previous.manifest, destination, previousState, gate, revision)
                    publish()
                    PluginInstallJournal.finish(root, manifest.id)
                    if (error is SourceException || error is kotlinx.coroutines.CancellationException) throw error
                    invalid("Unable to install source")
                }
            }
        } finally {
            stage.deleteRecursively()
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) { mutex.withLock {
        val provider = providers[id] ?: invalid("Source is not installed")
        val directory = File(root, id)
        val previousState = readState(directory)
        val state = previousState.copy(enabled = enabled, revision = previousState.revision + 1)
        changeSource(id) { gate, revision ->
            diagnostics.remove(id)
            try {
                writeState(directory, state)
                register(provider.manifest, directory, state, gate, revision)
            } catch (error: Exception) {
                register(provider.manifest, directory, previousState, gate, revision)
                throw error
            } finally { publish() }
        }
    } }

    suspend fun configure(id: String, values: Map<String, String>) = withContext(Dispatchers.IO) { mutex.withLock {
        val previous = providers[id] ?: invalid("Source is not installed")
        val keys = previous.manifest.config.map { it.key }.toSet()
        if (values.keys.any { it !in keys } || values.values.any { it.length > 8192 }) invalid("Invalid source configuration")
        val directory = File(root, id)
        val state = readState(directory).let { it.copy(revision = it.revision + 1) }
        changeSource(id) { gate, revision ->
            try {
                // Clear prior account state before changing credentials, even if a later write fails.
                secrets.write(id, "storage", emptyMap())
                stores[id] = emptyMap()
                secrets.write(id, "config", values)
                configurations[id] = values.toMap()
                writeState(directory, state)
                register(previous.manifest, directory, state, gate, revision)
                diagnostics.remove(id)
            } catch (error: Exception) {
                // Reactivate only the current snapshots under a new token; old requests stay revoked.
                register(previous.manifest, directory, state, gate, revision)
                throw error
            } finally { publish() }
        }
    } }

    fun configuration(id: String): Map<String, String> = configurations[id].orEmpty().toMap()

    suspend fun uninstall(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val provider = providers[id] ?: invalid("Source is not installed")
        if (id == BundledSourceProvisioner.NETEASE_ID) BundledSourceProvisioner.removed(root)
        changeSource(id) { _, _ ->
            try {
                providers.remove(id)
                registry.unregister(provider.instanceId)
                File(root, id).let { directory ->
                    directory.listFiles().orEmpty().filter { it.name != "artwork" }.forEach { it.deleteRecursively() }
                    if (directory.listFiles().isNullOrEmpty()) directory.delete()
                }
                configurations.remove(id)
                stores.remove(id)
                diagnostics.remove(id)
                secrets.delete(id)
                // Room, playlists, cached audio and downloaded files are intentionally independent.
            } finally { publish() }
        }
    } }

    private fun register(manifest: PluginManifest, directory: File, state: PluginInstallState, gate: PluginRevisionGate, revision: PluginRevisionGate.Revision) {
        val provider = ScriptMusicProvider(manifest, directory, runtime, state.enabled,
            configurationSnapshot = configurations[manifest.id].orEmpty().toMap(),
            isCurrent = { gate.isCurrent(revision) },
            config = { gate.read(revision) { configurations[manifest.id].orEmpty().toMap() } },
            storage = { gate.read(revision) { stores[manifest.id].orEmpty().toMap() } },
            saveStorage = { values ->
                gate.commit(revision) {
                    secrets.write(manifest.id, "storage", values)
                    stores[manifest.id] = values.toMap()
                }
            },
            onFailure = { code ->
                gate.commit(revision) {
                    diagnostics[manifest.id] = when (code) {
                        SourceErrorCode.INVALID_RESPONSE -> "Source returned invalid data"
                        else -> "Source execution failed; disable or re-enable it to recover"
                    }
                    registry.refresh()
                    publish()
                }
            },
            configurationVersion = state.revision,
            artworkDirectory = File(context.cacheDir, "source-artwork/${manifest.id}"),
            coreExecutor = featureExecutor,
            coreHandler = platformEffects,
            artworkStore = artworkStore,
        )
        providers[manifest.id] = provider
        registry.register(provider)
    }

    private fun <T> changeSource(id: String, action: (PluginRevisionGate, PluginRevisionGate.Revision) -> T): T {
        val gate = revisions.getOrPut(id) { PluginRevisionGate() }
        return gate.change { revision -> action(gate, revision) }
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

    private fun fingerprint(directory: File, installed: Boolean = false): String {
        val digest = MessageDigest.getInstance("SHA-256")
        directory.walkTopDown().filter(File::isFile).filter { file ->
            val name = file.relativeTo(directory).invariantSeparatorsPath
            !installed || name !in setOf("state.json", "state.tmp", ".market-origin.json", ".market-origin.tmp") && !name.startsWith("artwork/")
        }.sortedBy { it.relativeTo(directory).path }.forEach { file ->
            digest.update(file.relativeTo(directory).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            val fileDigest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; fileDigest.update(buffer, 0, count) }
            }
            digest.update(fileDigest.digest())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun invalid(message: String): Nothing = throw SourceException(SourceErrorCode.PLUGIN, message)

    companion object {
        private val installationMutex = Mutex()
        internal fun packageFileLimit(name: String): Int = when {
            name in setOf("manifest.json", "LICENSE", "NOTICE") -> 64 * 1024
            name == "index.js" -> 256 * 1024
            Regex("assets/[A-Za-z0-9._-]+\\.(wav|mp3|flac|m4a|ogg)").matches(name) -> 4 * 1024 * 1024
            else -> throw SourceException(SourceErrorCode.PLUGIN, "Source package contains an unsupported path")
        }

        internal fun validateLegalText(bytes: ByteArray) {
            if (bytes.size > 64 * 1024) throw SourceException(SourceErrorCode.PLUGIN, "Source legal text exceeds size limits")
            val text = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) {
                throw SourceException(SourceErrorCode.PLUGIN, "Source legal text must be UTF-8 text")
            }
            if (text.any { it.isISOControl() && it !in "\t\r\n\u000c" }) {
                throw SourceException(SourceErrorCode.PLUGIN, "Source legal text must be plain text")
            }
        }
    }
}
