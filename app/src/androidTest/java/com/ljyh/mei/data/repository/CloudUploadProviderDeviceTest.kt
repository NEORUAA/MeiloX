package com.ljyh.mei.data.repository

import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.ljyh.mei.data.network.api.MeloXDirectService
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStore
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual ContentResolver IPC, but synthetic provider/session/business/transfer only. */
@RunWith(AndroidJUnit4::class)
class CloudUploadProviderDeviceTest {
    @Test fun snapshotUsesOneReadAndExactBytesBeforeClosedPublicationAndCleansUp() = runBlocking {
        val f = Fixture()
        try {
            f.upload()
            val file = f.transferred.single()
            assertEquals(f.size, file.size)
            assertEquals(f.digest, file.md5)
            assertEquals("Fixture song.WAV", file.filename)
            assertEquals("wav", file.extension)
            assertEquals("Fixturesong", file.normalizedStem)
            assertEquals("audio/wav", file.mimeType)
            assertEquals("Fixture song", file.songName)
            assertEquals("Unknown artist", file.artist)
            assertEquals("Unknown album", file.album)
            assertEquals(1, f.status().getInt("opens"))
            assertTrue(f.status().getBoolean("query_signal"))
            assertTrue(f.status().getBoolean("open_signal"))
            assertTrue(f.status().getBoolean("source_unchanged"))
            assertEquals(5, f.requests.size)
            assertEquals(f.size to f.size, f.progress.last())
            f.assertNoSnapshot()
        } finally { f.close() }
    }

    @Test fun assetOffsetAndDeclaredLengthDoNotLeakProviderPrefixIntoTheSnapshot() = runBlocking {
        val f = Fixture("slice")
        try { f.upload(); assertEquals(f.digest, f.transferred.single().md5); f.assertNoSnapshot() }
        finally { f.close() }
    }

    @Test fun denialMissingEmptyAndBrokenSourcesNeverReachBusinessAuthorization() = runBlocking {
        for (mode in listOf("deny_query", "deny_open", "null_open", "empty", "broken_pipe")) {
            val f = Fixture(mode)
            try {
                assertTrue("mode=$mode", runCatching { f.upload() }.isFailure)
                assertTrue(f.requests.isEmpty())
                assertTrue(f.transferred.isEmpty())
                f.assertNoSnapshot()
            } finally { f.close() }
        }
    }

    @Test fun queryCancellationReachesTheProviderAndNeverOpensOrAllocates() = runBlocking {
        val f = Fixture("block_query")
        try {
            val task = async(Dispatchers.IO) { f.upload() }
            f.await("queries")
            withTimeout(5_000) { task.cancelAndJoin() }
            assertTrue(task.isCancelled)
            assertTrue(f.status().getBoolean("canceled"))
            assertEquals(0, f.status().getInt("opens"))
            assertTrue(f.requests.isEmpty())
            f.assertNoSnapshot()
        } finally { f.close() }
    }

    @Test fun openCancellationReachesTheProviderAndRemovesTheAlreadyCreatedSnapshot() = runBlocking {
        val f = Fixture("block_open")
        try {
            val task = async(Dispatchers.IO) { f.upload() }
            f.await("opens")
            withTimeout(5_000) { task.cancelAndJoin() }
            assertTrue(f.status().getBoolean("canceled"))
            assertTrue(f.requests.isEmpty())
            f.assertNoSnapshot()
        } finally { f.close() }
    }

    @Test fun accountChangeWhileQueryingRejectsTheResultBeforeOpening() = runBlocking {
        val f = Fixture("block_query")
        try {
            val task = async(Dispatchers.IO) { runCatching { f.upload() }.exceptionOrNull() }
            f.await("queries")
            f.sessions.invalidate()
            f.command("release")
            assertTrue(withTimeout(5_000) { task.await() } is SessionChangedException)
            assertEquals(0, f.status().getInt("opens"))
            assertTrue(f.requests.isEmpty())
            f.assertNoSnapshot()
        } finally { f.close() }
    }

    @Test fun recoveryWhileOpeningRejectsBeforeCopyOrAuthorization() = runBlocking {
        val f = Fixture("block_open")
        try {
            val task = async(Dispatchers.IO) { runCatching { f.upload() }.exceptionOrNull() }
            f.await("opens")
            f.sessions.setRecoveryRequired(true)
            f.command("release")
            assertNotNull(withTimeout(5_000) { task.await() })
            assertTrue(f.requests.isEmpty())
            f.assertNoSnapshot()
        } finally { f.close() }
    }

    @Test fun aLateMimeResultCannotAllocateForAChangedAccount() = runBlocking {
        val f = Fixture("block_type")
        try {
            val task = async(Dispatchers.IO) { runCatching { f.upload() }.exceptionOrNull() }
            f.await("types")
            f.sessions.invalidate()
            f.command("release")
            assertTrue(withTimeout(5_000) { task.await() } is SessionChangedException)
            assertTrue(f.requests.isEmpty())
            f.assertNoSnapshot()
        } finally { f.close() }
    }

    @Test fun sourceChangesAfterPreparationDoNotChangeTheAuthorizedSnapshot() = runBlocking {
        val f = Fixture()
        try {
            f.onRequest = { if (it == "/api/cloud/upload/check") f.command("mutate") }
            f.upload()
            assertEquals(f.digest, f.transferred.single().md5)
            assertEquals(f.size, f.transferred.single().size)
            assertFalse(f.status().getBoolean("source_unchanged"))
            assertEquals(1, f.status().getInt("opens"))
            f.assertNoSnapshot()
        } finally { f.close() }
    }

    @Test fun failedBusinessAndTransferPhasesAlwaysRemoveOnlyThePrivateCopy() = runBlocking {
        for (binary in listOf(false, true)) {
            val f = Fixture()
            try {
                if (binary) f.onTransfer = { throw IOException("Synthetic transfer denial") }
                else f.onRequest = { throw IOException("Synthetic allocation denial") }
                assertTrue(runCatching { f.upload() }.isFailure)
                assertFalse(f.requests.contains("/api/cloud/pub/v2"))
                assertTrue(f.status().getBoolean("source_unchanged"))
                f.assertNoSnapshot()
            } finally { f.close() }
        }
    }

    @Test fun cancellationDuringClosedTransferDoesNotRegisterOrPublishAndCleansUp() = runBlocking {
        val f = Fixture()
        try {
            val entered = CompletableDeferred<Unit>()
            f.onTransfer = { entered.complete(Unit); CompletableDeferred<Unit>().await() }
            val task = async(Dispatchers.IO) { f.upload() }
            withTimeout(5_000) { entered.await(); task.cancelAndJoin() }
            assertEquals(3, f.requests.size)
            assertFalse(f.requests.contains("/api/cloud/pub/v2"))
            f.assertNoSnapshot()
        } finally { f.close() }
    }

    @Test fun missingProviderNameAndMimeKeepExistingFallbacks() = runBlocking {
        val f = Fixture("no_name")
        try {
            f.upload()
            assertEquals(f.id, f.transferred.single().filename)
            assertEquals("mp3", f.transferred.single().extension)
            assertEquals("audio/mpeg", f.transferred.single().mimeType)
            f.assertNoSnapshot()
        } finally { f.close() }
    }

    @Test fun deniedMimeLookupKeepsThePlatformNullFallbackAfterAnAuthorizedRead() = runBlocking {
        val f = Fixture("deny_type")
        try {
            f.upload()
            assertEquals("audio/mpeg", f.transferred.single().mimeType)
            assertEquals(1, f.status().getInt("opens"))
            assertTrue(f.status().getInt("types") > 0)
            f.assertNoSnapshot()
        } finally { f.close() }
    }

    @Test fun aProviderThatIgnoresCancellationCannotContinueAfterItEventuallyReturns() = runBlocking {
        val f = Fixture("ignore_cancel")
        try {
            val task = async(Dispatchers.IO) { f.upload() }
            f.await("opens")
            task.cancel()
            f.command("release")
            withTimeout(5_000) { task.join() }
            assertTrue(task.isCancelled)
            assertFalse(f.status().getBoolean("canceled"))
            assertTrue(f.requests.isEmpty())
            f.assertNoSnapshot()
        } finally { f.close() }
    }

    @Test fun freshInitializationCleansOnlyInterruptedOwnedSnapshots() = runBlocking {
        val f = Fixture()
        try {
            val dir = File(f.cache, "cloud-upload-snapshots-v1").apply { mkdirs() }
            val interrupted = File(dir, "upload-interrupted.bin").apply { writeText("fixture") }
            val unrelated = File(dir, "untouched.txt").apply { writeText("preserved") }
            f.upload()
            assertFalse(interrupted.exists())
            assertEquals("preserved", unrelated.readText())
            assertEquals(listOf(unrelated), dir.listFiles()!!.toList())
        } finally { f.close() }
    }

    private class Fixture(mode: String = "") : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val resolver = instrumentation.targetContext.contentResolver
        private val authority = instrumentation.context.packageManager
            .getPackageInfo(instrumentation.context.packageName, PackageManager.GET_PROVIDERS).providers.orEmpty()
            .single { it.name == CloudUploadFixtureProvider::class.java.name }.authority
        val id = UUID.randomUUID().toString()
        private val base = Uri.parse("content://$authority")
        private val uri = base.buildUpon().appendPath(id).build()
        private val created = resolver.call(base, "create", id, Bundle().apply { putString("mode", mode) })!!
        val size = created.getLong("size")
        val digest = created.getString("md5")!!
        val cache = File(instrumentation.targetContext.cacheDir, "cloud-upload-device-$id").apply { check(mkdirs()) }
        private val context = object : ContextWrapper(instrumentation.targetContext) { override fun getCacheDir() = cache }
        val sessions = SessionStore().apply { bind { SessionIdentity(7, true, false) } }
        private val owner = sessions.snapshot()
        val requests = mutableListOf<String>()
        val transferred = mutableListOf<CloudUploadFile>()
        val progress = mutableListOf<Pair<Long, Long>>()
        var onRequest: (String) -> Unit = {}
        var onTransfer: suspend () -> Unit = {}
        private val api = Proxy.newProxyInstance(MeloXDirectService::class.java.classLoader, arrayOf(MeloXDirectService::class.java)) { _, method, args ->
            check(method.name == "post")
            val path = args[0] as String
            assertEquals(owner, args[3])
            requests += path
            onRequest(path)
            JsonParser.parseString(when (path) {
                "/api/cloud/upload/check" -> """{"code":200,"needUpload":true,"songId":0}"""
                "/api/nos/token/alloc" -> """{"code":200,"result":{"resourceId":"fixture","objectKey":"fixture/file","token":"fixture-token"}}"""
                "/api/upload/cloud/info/v2" -> """{"code":200,"songId":101}"""
                "/api/cloud/pub/v2" -> """{"code":200}"""
                else -> error("Unexpected closed request")
            }).asJsonObject
        } as MeloXDirectService
        private val binary = object : CloudBinaryUploader {
            override suspend fun upload(file: CloudUploadFile, authorization: CloudUploadAuthorization, owner: com.ljyh.mei.data.session.SessionStamp, onProgress: (Long, Long) -> Unit) {
                assertEquals(this@Fixture.owner, owner)
                assertEquals(size, file.file.length())
                assertEquals(digest, MessageDigest.getInstance("MD5").digest(file.file.readBytes()).joinToString("") { "%02x".format(it) })
                assertEquals(file.md5, digest)
                transferred += file
                onTransfer()
                onProgress(file.size, file.size)
            }
        }
        private val repository = MeloXRepository(api, api, context, sessions,
            CloudUploadCoordinator(api, api, sessions, binary), CloudLibraryBackend(api, sessions))
        suspend fun upload() = repository.uploadCloudSong(owner, uri.toString()) { sent, total -> progress += sent to total }
        fun command(command: String) = resolver.call(base, command, id, null)!!
        fun status() = command("status")
        suspend fun await(field: String) = withTimeout(5_000) { while (status().getInt(field) == 0) delay(10) }
        fun assertNoSnapshot() {
            assertTrue(File(cache, "cloud-upload-snapshots-v1").listFiles().orEmpty().none { it.name.startsWith("upload-") && it.name.endsWith(".bin") })
            assertTrue(transferred.none { it.file.exists() })
        }
        override fun close() { command("release"); command("delete"); check(cache.deleteRecursively()) }
    }
}
