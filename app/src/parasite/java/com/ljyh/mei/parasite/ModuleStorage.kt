package com.ljyh.mei.parasite

import java.io.File

internal object ModuleStorage {
    const val NAMESPACE = "meilox_parasite"

    fun name(value: String): String {
        require(value.isNotBlank() && value != "." && value != ".." &&
            value.none { it == '/' || it == '\\' || it == '\u0000' }) { "Invalid storage name" }
        return value
    }

    fun database(name: String, dataDir: File, noBackupDir: File): File {
        val file = File(name)
        if (!file.isAbsolute) return File(File(dataDir, "databases"), name(name))
        val resolved = file.canonicalFile
        require(listOf(dataDir, noBackupDir).any { root ->
            resolved.toPath().startsWith(root.canonicalFile.toPath()) && resolved != root.canonicalFile
        }) { "Database path is outside module storage" }
        return resolved
    }
}
