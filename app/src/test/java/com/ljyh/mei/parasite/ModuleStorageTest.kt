package com.ljyh.mei.parasite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModuleStorageTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "parasite-storage-test").canonicalFile
    private val noBackup = File(root, "no_backup")

    @Test fun permitsNamedAndScopedAbsoluteDatabases() {
        val named = File(root, "databases/app_database")
        assertEquals(named, ModuleStorage.database("app_database", root, noBackup))
        assertEquals(named, ModuleStorage.database(named.path, root, noBackup))
        val protected = File(noBackup, "room.db")
        assertEquals(protected, ModuleStorage.database(protected.path, root, noBackup))
    }

    @Test fun rejectsEscapesAndPrefixCollisions() {
        listOf("../host.db", "/host.db", "$root/../host.db", "${root}_host/db", root.path).forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { ModuleStorage.database(name, root, noBackup) }
        }
        listOf("", ".", "..", "a/b", "a\\b", "a\u0000b").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { ModuleStorage.name(name) }
        }
        assertEquals("settings.v1", ModuleStorage.name("settings.v1"))
    }
}
