package com.ljyh.mei.parasite

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class HostLogShareFilesTest {
    private fun fixture(check: (File, File, File) -> Unit) {
        val root = Files.createTempDirectory("meilox-log-share-").toFile()
        try { check(root, File(root, "module/files"), File(root, "host/cache")) }
        finally { root.deleteRecursively() }
    }

    private fun log(files: File, path: String, text: String = "Synthetic log") = File(files, path).apply {
        parentFile.mkdirs()
        writeText(text)
    }

    @Test fun selectedLogsUseUniqueHostCacheCopiesWithoutChangingTheOriginal() = fixture { _, files, cache ->
        for (folder in listOf("app_logs", "crash_logs")) {
            val source = log(files, "$folder/example.log")
            val first = HostLogShareFiles.stage(source, files, cache)
            val second = HostLogShareFiles.stage(source, files, cache)
            assertEquals("Synthetic log", source.readText())
            assertEquals(source.readText(), first.readText())
            assertEquals(source.readText(), second.readText())
            assertEquals(source.name, first.name)
            assertTrue(first.toPath().startsWith(File(cache.canonicalFile, "apk/${ModuleStorage.NAMESPACE}_log_share").toPath()))
            assertNotEquals(first.parent, second.parent)
        }
    }

    @Test fun nonLogNestedMissingAndDirectorySourcesAreRejectedBeforeStaging() = fixture { root, files, cache ->
        val sources = listOf(
            log(files, "database/secret"), log(files, "app_logs/nested/example.log"),
            log(root, "foreign/example.log"), File(files, "app_logs/missing.log"), File(files, "crash_logs"),
        )
        for (source in sources) assertThrows(IllegalArgumentException::class.java) {
            HostLogShareFiles.stage(source, files, cache)
        }
        assertFalse(cache.exists())
    }

    @Test fun escapedSourceAndDestinationLinksCannotPublishForeignFiles() = fixture { root, files, cache ->
        val secret = log(root, "foreign/secret")
        val source = log(files, "app_logs/source.log")
        val link = File(source.parentFile, "escape.log")
        Files.createSymbolicLink(link.toPath(), secret.toPath())
        assertThrows(IllegalArgumentException::class.java) { HostLogShareFiles.stage(link, files, cache) }
        cache.mkdirs()
        Files.createSymbolicLink(File(cache, "apk").toPath(), secret.parentFile.toPath())
        assertThrows(IllegalArgumentException::class.java) { HostLogShareFiles.stage(source, files, cache) }
        assertEquals("Synthetic log", secret.readText())
    }

    @Test fun expiredOwnedCopiesArePrunedButFreshAndUnrelatedFilesRemain() = fixture { _, files, cache ->
        val source = log(files, "app_logs/source.log")
        val now = 10 * HostLogShareFiles.RETENTION_MS
        val expired = HostLogShareFiles.stage(source, files, cache, now - HostLogShareFiles.RETENTION_MS)
        val fresh = HostLogShareFiles.stage(source, files, cache, now - 1)
        val unrelated = log(expired.parentFile.parentFile, "other/export.txt")
        unrelated.parentFile.setLastModified(1)
        val new = HostLogShareFiles.stage(source, files, cache, now)
        assertFalse(expired.exists())
        assertFalse(expired.parentFile.exists())
        assertTrue(fresh.exists())
        assertTrue(unrelated.exists())
        assertTrue(new.exists())
    }

    @Test fun cleanupDoesNotFollowExpiredDirectoryLinks() = fixture { root, files, cache ->
        val source = log(files, "app_logs/source.log")
        val first = HostLogShareFiles.stage(source, files, cache)
        val secret = log(root, "foreign/secret")
        Files.createSymbolicLink(File(first.parentFile.parentFile, UUID.randomUUID().toString()).toPath(), secret.parentFile.toPath())
        HostLogShareFiles.stage(source, files, cache, System.currentTimeMillis() + HostLogShareFiles.RETENTION_MS)
        assertTrue(secret.exists())
        assertEquals("Synthetic log", secret.readText())
    }

    @Test fun explicitClearRemovesFreshCopiesButPreservesOriginalsAndUnrelatedCache() = fixture { _, files, cache ->
        val source = log(files, "crash_logs/source.log")
        val staged = HostLogShareFiles.stage(source, files, cache)
        val unrelated = log(cache, "unrelated/keep.txt")
        HostLogShareFiles.clear(cache)
        assertFalse(staged.exists())
        assertFalse(staged.parentFile.exists())
        assertEquals("Synthetic log", source.readText())
        assertTrue(unrelated.exists())
        HostLogShareFiles.clear(cache)
    }
}
