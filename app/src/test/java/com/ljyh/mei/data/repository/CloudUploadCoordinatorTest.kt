package com.ljyh.mei.data.repository

import com.google.gson.JsonParser
import com.ljyh.mei.data.network.api.MeloXDirectService
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import java.io.IOException
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CloudUploadCoordinatorTest {
    @get:Rule val temp = TemporaryFolder()
    private val sessions = SessionStore().apply { bind { SessionIdentity(7, true, false) } }
    private val owner = sessions.snapshot()
    private val calls = mutableListOf<Triple<String, Map<String, Any>, SessionStamp>>()
    private val progress = mutableListOf<Pair<Long, Long>>()
    private var transferCount = 0
    private var after: (String) -> Unit = {}
    private var transfer: () -> Unit = {}
    private var replies = mapOf(
        "/api/cloud/upload/check" to """{"code":200,"needUpload":true,"songId":0}""",
        "/api/nos/token/alloc" to """{"code":200,"result":{"resourceId":"100","objectKey":"fixture/file","token":"fixture-token"}}""",
        "/api/upload/cloud/info/v2" to """{"code":200,"songId":101}""",
        "/api/cloud/pub/v2" to """{"code":200}""",
    )
    private val api = Proxy.newProxyInstance(MeloXDirectService::class.java.classLoader,
        arrayOf(MeloXDirectService::class.java)) { _, method, args ->
        check(method.name == "post")
        @Suppress("UNCHECKED_CAST")
        calls += Triple(args[0] as String, args[1] as Map<String, Any>, args[3] as SessionStamp)
        after(args[0] as String)
        JsonParser.parseString(replies.getValue(args[0] as String)).asJsonObject
    } as MeloXDirectService
    private val source = CloudUploadCoordinator(api, api, sessions, object : CloudBinaryUploader {
        override suspend fun upload(file: CloudUploadFile, authorization: CloudUploadAuthorization, owner: SessionStamp, onProgress: (Long, Long) -> Unit) {
            transferCount++
            assertEquals(sessions.snapshot(), owner)
            assertEquals("fixture/file", authorization.objectKey)
            assertEquals("fixture-token", authorization.token)
            assertFalse(authorization.toString().contains(authorization.token))
            transfer()
            onProgress(file.size, file.size)
        }
    })
    private fun file(): CloudUploadFile {
        val file = temp.newFile().apply { writeText("abc") }
        return CloudUploadFile(file, "Test.mp3", "mp3", "Test", 3, "900150983cd24fb0d6963f7d28e17f72", "Test", "Artist", "Album", "audio/mpeg")
    }
    private suspend fun upload(stamp: SessionStamp = owner) = source.upload(file(), stamp) { sent, total -> progress += sent to total }

    @Test fun allPhasesUseOneOwnerAndOnlyPublishMarksComplete() = runBlocking {
        upload()
        assertEquals(listOf("/api/cloud/upload/check", "/api/nos/token/alloc", "/api/nos/token/alloc",
            "/api/upload/cloud/info/v2", "/api/cloud/pub/v2"), calls.map { it.first })
        assertTrue(calls.all { it.third == owner })
        assertEquals(1, transferCount)
        assertEquals(listOf(2L to 3L, 3L to 3L), progress)
        assertEquals("", calls[1].second["bucket"])
        assertEquals("jd-musicrep-privatecloud-audio-public", calls[2].second["bucket"])
        assertEquals(0L, calls[3].second["songid"])
        assertEquals("100", calls[3].second["resourceId"])
        assertEquals(101L, calls[4].second["songid"])
    }

    @Test fun existingObjectSkipsOnlyTheBinaryTransfer() = runBlocking {
        replies = replies + ("/api/cloud/upload/check" to """{"code":200,"needUpload":false,"songId":10}""")
        upload()
        assertEquals(0, transferCount)
        assertEquals(4, calls.size)
        assertEquals(listOf(3L to 3L), progress)
    }

    @Test fun malformedCheckNeverAllocatesOrPublishes() = runBlocking {
        for (reply in listOf("{}", """{"code":200,"songId":0}""", """{"code":200,"needUpload":true}""",
            """{"code":200,"needUpload":true,"songId":-1}""", """{"code":301,"needUpload":false,"songId":1}""")) {
            replies = replies + ("/api/cloud/upload/check" to reply)
            assertTrue(runCatching { upload() }.isFailure)
        }
        assertTrue(calls.all { it.first == "/api/cloud/upload/check" })
        assertEquals(0, transferCount)
    }

    @Test fun invalidTokensAndRegistrationNeverBecomePublication() = runBlocking {
        for ((path, reply) in listOf(
            "/api/nos/token/alloc" to """{"code":200,"result":{}}""",
            "/api/nos/token/alloc" to """{"code":200,"result":{"resourceId":"100","objectKey":"x","token":"","bucket":"wrong"}}""",
            "/api/upload/cloud/info/v2" to """{"code":200,"songId":0}""",
        )) {
            val original = replies
            replies = replies + (path to reply)
            assertTrue(runCatching { upload() }.isFailure)
            replies = original
        }
        assertFalse(calls.any { it.first == "/api/cloud/pub/v2" })
        assertFalse(progress.any { it.first == it.second })
    }

    @Test fun invalidationAfterEveryResponseStopsTheRemainingPipeline() = runBlocking {
        for (path in replies.keys) {
            val stamp = sessions.snapshot()
            val start = calls.size
            after = { if (it == path) sessions.invalidate() }
            assertTrue(runCatching { upload(stamp) }.isFailure)
            assertEquals(path, calls.drop(start).last().first)
        }
        assertFalse(progress.any { it.first == it.second })
    }

    @Test fun invalidationDuringTransferCannotRegisterOrPublish() = runBlocking {
        transfer = sessions::invalidate
        assertTrue(runCatching { upload() }.isFailure)
        assertEquals(3, calls.size)
        assertTrue(progress.isEmpty())
    }

    @Test fun canceledRequestsAndTransfersPropagateWithoutPublication() = runBlocking {
        after = { throw CancellationException() }
        assertTrue(runCatching { upload() }.exceptionOrNull() is CancellationException)
        after = {}
        transfer = { throw CancellationException() }
        assertTrue(runCatching { upload() }.exceptionOrNull() is CancellationException)
        assertFalse(calls.any { it.first == "/api/cloud/pub/v2" })
    }

    @Test fun failedTransferAndPublishAreNotRetriedOrReportedComplete() = runBlocking {
        transfer = { throw IOException("synthetic") }
        assertTrue(runCatching { upload() }.isFailure)
        assertEquals(3, calls.size)
        transfer = {}
        replies = replies + ("/api/cloud/pub/v2" to """{"code":500}""")
        assertTrue(runCatching { upload() }.isFailure)
        assertEquals(1, calls.count { it.first == "/api/cloud/pub/v2" })
        assertFalse(progress.any { it.first == it.second })
    }

    @Test fun guestsAndObsoleteOwnersNeverAllocate() = runBlocking {
        assertTrue(runCatching { upload(owner.copy(identity = SessionIdentity(0, false, true))) }.isFailure)
        sessions.invalidate()
        assertTrue(runCatching { upload() }.isFailure)
        assertTrue(calls.isEmpty())
    }

    @Test fun pendingRecoveryCannotAllocateEvenWithoutAGenerationChange() = runBlocking {
        sessions.setRecoveryRequired(true)
        assertTrue(runCatching { upload() }.isFailure)
        assertEquals(owner, sessions.snapshot())
        assertTrue(calls.isEmpty())
    }

    @Test fun recoveryAfterEveryResponseStopsTheRemainingPipeline() = runBlocking {
        for (path in replies.keys) {
            sessions.setRecoveryRequired(false)
            val start = calls.size
            after = { if (it == path) sessions.setRecoveryRequired(true) }
            assertTrue(runCatching { upload() }.isFailure)
            assertEquals(path, calls.drop(start).last().first)
            assertEquals(owner, sessions.snapshot())
        }
        assertFalse(progress.any { it.first == it.second })
    }

    @Test fun recoveryDuringTransferRejectsProgressRegistrationAndPublication() = runBlocking {
        transfer = { sessions.setRecoveryRequired(true) }
        assertTrue(runCatching { upload() }.isFailure)
        assertEquals(3, calls.size)
        assertTrue(progress.isEmpty())
        assertEquals(owner, sessions.snapshot())
    }

    @Test fun snapshotCleanupOnlyRemovesOwnedInterruptedFiles() {
        val cache = temp.newFolder()
        val directory = prepareCloudUploadDirectory(cache)
        val interrupted = File(directory, "upload-fixture.bin").apply { writeText("private fixture") }
        val unrelated = File(directory, "keep.txt").apply { writeText("keep") }
        val nested = File(directory, "upload-dir.bin").apply { mkdir() }
        val outside = File(cache, "upload-outside.bin").apply { writeText("keep") }
        assertEquals(directory, prepareCloudUploadDirectory(cache))
        assertFalse(interrupted.exists())
        assertTrue(unrelated.exists())
        assertTrue(nested.isDirectory)
        assertTrue(outside.exists())
    }
}
