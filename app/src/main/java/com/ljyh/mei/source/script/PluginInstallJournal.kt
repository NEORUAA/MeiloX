package com.ljyh.mei.source.script

import androidx.annotation.Keep
import com.google.gson.Gson
import java.io.File
import java.util.UUID

@Keep
internal data class PluginInstallTransaction(
    val id: String,
    val stage: String,
    val backup: String,
    val nextFingerprint: String,
    val previousFingerprint: String?,
)

/** No credentials or package URLs are needed to recover a stopped directory replacement. */
internal object PluginInstallJournal {
    private val id = Regex("[a-z][a-z0-9._-]{2,79}")
    private val hash = Regex("[a-f0-9]{64}")
    private const val PREFIX = ".source-transaction-"

    fun write(root: File, transaction: PluginInstallTransaction) {
        validate(transaction)
        val temporary = File(root, "$PREFIX${transaction.id}.tmp")
        temporary.outputStream().use { output ->
            output.write(Gson().toJson(transaction).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        java.nio.file.Files.move(temporary.toPath(), file(root, transaction.id).toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    fun finish(root: File, id: String) { file(root, id).delete() }

    /** Keep a complete next payload, otherwise restore the complete previous payload. */
    fun recover(root: File, fingerprint: (File) -> String): Set<String> {
        val blocked = mutableSetOf<String>()
        root.listFiles().orEmpty().filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(".json") }.forEach { journal ->
            val sourceId = journal.name.removePrefix(PREFIX).removeSuffix(".json")
            if (!id.matches(sourceId)) return@forEach
            try {
                require(journal.length() <= 4096)
                val transaction = Gson().fromJson(journal.readText(), PluginInstallTransaction::class.java)
                validate(transaction)
                require(transaction.id == sourceId)
                val destination = File(root, transaction.id)
                val backup = File(root, transaction.backup)
                val stage = File(root, transaction.stage)
                fun matches(directory: File, expected: String?) = expected != null && directory.isDirectory &&
                    runCatching { fingerprint(directory) == expected }.getOrDefault(false)
                when {
                    matches(destination, transaction.nextFingerprint) -> { backup.deleteRecursively(); stage.deleteRecursively() }
                    matches(backup, transaction.previousFingerprint) -> {
                        destination.deleteRecursively()
                        check(backup.renameTo(destination))
                        stage.deleteRecursively()
                    }
                    matches(destination, transaction.previousFingerprint) -> { backup.deleteRecursively(); stage.deleteRecursively() }
                    transaction.previousFingerprint == null && !backup.exists() -> { destination.deleteRecursively(); stage.deleteRecursively() }
                    else -> error("Incomplete source transaction")
                }
                check(journal.delete())
            } catch (_: Exception) { blocked += sourceId }
        }
        return blocked
    }

    private fun file(root: File, sourceId: String) = File(root, "$PREFIX$sourceId.json")

    private fun validate(transaction: PluginInstallTransaction) {
        require(id.matches(transaction.id) && hash.matches(transaction.nextFingerprint) &&
            (transaction.previousFingerprint == null || hash.matches(transaction.previousFingerprint)))
        fun owned(name: String, prefix: String): Boolean = name.startsWith(prefix) &&
            runCatching { UUID.fromString(name.removePrefix(prefix)) }.isSuccess
        require(owned(transaction.stage, ".source-stage-${transaction.id}-"))
        require(owned(transaction.backup, ".${transaction.id}-"))
    }
}
