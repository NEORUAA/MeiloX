package com.ljyh.mei.source.script

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import com.ljyh.mei.constants.*
import com.ljyh.mei.data.network.neteaseSessionType
import com.ljyh.mei.utils.dataStore
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.first

/** Only these bundled, pinned bytes may receive the former built-in source's credentials. */
internal object BundledSourceProvisioner {
    const val NETEASE_ID = "org.meilox.source.netease"
    const val NETEASE_SHA256 = "e0f71f1e771333748d6bc1bba60e6c5c7990e1fa8ed24cbcd53342058a236ddc"
    private const val MARKER = ".netease-default-v1"
    private const val JOURNAL = ".netease-bundle-transaction"
    private val packageFiles = setOf("manifest.json", "index.js", "LICENSE", "NOTICE")
    private val legacyPins = mapOf(
        "caf879955220234f871db42d12d54d27f5262e0d5eb977b859ba59fb05c7dd6b" to
            "9f3b56dd3c20cffc6884db4d668a3fe678f7a80ec48b9127e4bfe2f06015129d",
    )

    suspend fun ensure(context: Context, root: File, secrets: PluginSecretStore) {
        recover(root)
        val marker = File(root, MARKER)
        val destination = File(root, NETEASE_ID)
        val backup = File(root, ".netease-bundle-backup")
        if (backup.exists() && !destination.exists() && marker.takeIf(File::isFile)?.readText() != "removed") {
            check(backup.renameTo(destination))
        }
        if (marker.exists()) {
            val provenance = marker.readText()
            if (!destination.isDirectory || provenance.substringBefore(':') == NETEASE_SHA256 ||
                !ownsBundledInstallation(destination, provenance)) return
            val bytes = context.assets.open("music-sources/netease.meisource").use { it.readBytes() }
            check(matches(bytes)) { "Bundled source integrity check failed" }
            val temporary = unpack(root, bytes)
            try {
                // Configuration/storage stay encrypted outside the installation. Preserve enabled state.
                File(destination, "state.json").takeIf(File::isFile)?.copyTo(File(temporary, "state.json"))
                File(destination, "artwork").takeIf(File::isDirectory)?.copyRecursively(File(temporary, "artwork"))
                val nextProvenance = "$NETEASE_SHA256:${contentFingerprint(temporary)}"
                journal(root, provenance, nextProvenance)
                backup.deleteRecursively()
                check(destination.renameTo(backup))
                try {
                    check(temporary.renameTo(destination))
                    mark(root, nextProvenance)
                } catch (error: Exception) {
                    destination.deleteRecursively()
                    check(backup.renameTo(destination))
                    File(root, JOURNAL).delete()
                    throw error
                }
                backup.deleteRecursively()
                File(root, JOURNAL).delete()
            } finally { temporary.deleteRecursively() }
            return
        }
        if (destination.exists()) {
            // An independently imported package never inherits the old built-in account.
            mark(root, "external")
            return
        }
        val bytes = context.assets.open("music-sources/netease.meisource").use { it.readBytes() }
        check(matches(bytes)) { "Bundled source integrity check failed" }
        val temporary = unpack(root, bytes)
        try {
            val previous = context.dataStore.data.first()
            val configuration = legacyConfiguration(previous)
            // Encrypted writes finish before the source becomes visible to the registry.
            secrets.write(NETEASE_ID, "config", configuration)
            secrets.write(NETEASE_ID, "storage", previous[NeteaseDeviceRegisteredModelKey]?.let { mapOf("deviceRegisteredModel" to it) }.orEmpty())
            com.ljyh.mei.source.core.SourcePreferences(context.dataStore).update {
                it.copy(fallbackOrder = legacyOrder(it.fallbackOrder))
            }
            val provenance = "$NETEASE_SHA256:${contentFingerprint(temporary)}"
            journal(root, "absent", provenance)
            check(temporary.renameTo(destination))
            mark(root, provenance)
            File(root, JOURNAL).delete()
        } finally { temporary.deleteRecursively() }
    }

    private fun journal(root: File, previous: String, next: String) {
        val temporary = File(root, "$JOURNAL.tmp")
        temporary.writeText("$previous\n$next")
        check(temporary.renameTo(File(root, JOURNAL)))
    }

    /** Complete a verified installation if the process ended between the directory and marker swaps. */
    internal fun recover(root: File) {
        val transaction = File(root, JOURNAL)
        if (!transaction.isFile) return
        val records = transaction.readLines()
        check(records.size == 2 && Regex("[a-f0-9]{64}:[a-f0-9]{64}").matches(records[1]))
        val marker = File(root, MARKER).takeIf(File::isFile)?.readText() ?: "absent"
        if (marker !in setOf(records[0], records[1])) { transaction.delete(); return }
        val destination = File(root, NETEASE_ID)
        val backup = File(root, ".netease-bundle-backup")
        if (ownsBundledInstallation(destination, records[1])) {
            mark(root, records[1])
            backup.deleteRecursively()
            transaction.delete()
        } else if (!destination.exists() && backup.isDirectory) {
            check(backup.renameTo(destination))
            transaction.delete()
        } else if (!destination.exists() || ownsBundledInstallation(destination, records[0])) {
            transaction.delete()
        }
    }

    private fun unpack(root: File, bytes: ByteArray): File {
        val temporary = File(root, ".bundle-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val names = mutableSetOf<String>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    check(!entry.isDirectory && entry.name in packageFiles && names.add(entry.name))
                    val content = zip.readBytes()
                    check(content.size <= 1_048_576)
                    File(temporary, entry.name).writeBytes(content)
                }
            }
            check(names == packageFiles)
            val manifest = PluginManifest.parse(File(temporary, "manifest.json").readText())
            check(manifest.id == NETEASE_ID && manifest.providerId == "netease")
            return temporary
        } catch (error: Exception) { temporary.deleteRecursively(); throw error }
    }

    internal fun ownsBundledInstallation(directory: File, provenance: String): Boolean {
        val recorded = if (':' in provenance && Regex("[a-f0-9]{64}:[a-f0-9]{64}").matches(provenance)) {
            provenance.substringAfter(':')
        } else legacyPins[provenance] ?: return false
        return runCatching { contentFingerprint(directory) == recorded }.getOrDefault(false)
    }

    internal fun contentFingerprint(directory: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        packageFiles.sorted().forEach { name ->
            val file = File(directory, name)
            check(file.isFile && file.length() <= 1_048_576)
            digest.update(name.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun legacyOrder(order: List<String>): List<String> = order.map {
        if (it == "netease") "script:$NETEASE_ID:default" else it
    }.distinct()

    fun removed(root: File) = mark(root, "removed")
    internal fun matches(bytes: ByteArray): Boolean =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == NETEASE_SHA256

    internal fun legacyConfiguration(preferences: Preferences): Map<String, String> = buildMap {
        val musicU = preferences[CookieKey].orEmpty()
        val musicA = preferences[NeteaseMusicAKey].orEmpty()
        val csrf = preferences[NeteaseCsrfKey].orEmpty()
        put("cookie", buildList {
            if (musicU.isNotBlank()) add("MUSIC_U=$musicU")
            if (musicA.isNotBlank()) add("MUSIC_A=$musicA")
            if (csrf.isNotBlank()) add("__csrf=$csrf")
        }.joinToString("; "))
        put("sessionType", preferences.neteaseSessionType().name.lowercase())
        for ((key, value) in listOf("deviceId" to preferences[DeviceIdKey], "sDeviceId" to preferences[SDeviceIdKey],
            "musicA" to preferences[NeteaseMusicAKey], "csrf" to preferences[NeteaseCsrfKey],
            "ursAppId" to preferences[NeteaseUrsAppIdKey], "refreshToken" to preferences[NeteaseRefreshTokenKey],
            "userId" to preferences[UserIdKey])) if (!value.isNullOrBlank()) put(key, value)
    }

    private fun mark(root: File, value: String) {
        val temporary = File(root, "$MARKER.tmp")
        temporary.writeText(value)
        check(temporary.renameTo(File(root, MARKER)))
    }
}
