package com.ljyh.mei.data.repository

import com.google.gson.JsonParser
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.data.network.api.MeloXDirectService
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import java.io.IOException
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response

class CloudUploadCoordinatorTest {
    @get:Rule val temp = TemporaryFolder()
    private var identity = SessionIdentity(7, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private val owner = sessions.snapshot()
    private val calls = mutableListOf<Triple<String, Map<String, Any>, SessionStamp>>()
    private val protocols = mutableListOf<Boolean>()
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
    private var replyFor: suspend (String, Map<String, Any>, Boolean) -> String = { path, _, _ -> replies.getValue(path) }
    private fun api(eapi: Boolean) = object : MeloXDirectService {
        override suspend fun post(path: String, body: Map<String, Any>, headers: Map<String, String>, expectedSession: SessionStamp?): com.google.gson.JsonObject {
            assertTrue(headers.isEmpty())
            calls += Triple(path, body, requireNotNull(expectedSession))
            protocols += eapi
            after(path)
            return JsonParser.parseString(replyFor(path, body, eapi)).asJsonObject
        }
        override suspend fun postPlaybackRaw(path: String, body: Map<String, Any>, headers: Map<String, String>): Response<ResponseBody> = error("Unexpected raw request")
    }
    private val source = CloudUploadCoordinator(api(true), api(false), sessions, object : CloudBinaryUploader {
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

    @Test fun failedBinaryAuthorizationRetriesOnlyStandaloneAndTransfersOnce() = runBlocking {
        replyFor = { path, body, eapi ->
            if (!eapi && path == "/api/nos/token/alloc" && body["bucket"] != "") """{"code":403}"""
            else replies.getValue(path)
        }
        val result = runCatching { upload() }
        if (BuildConfig.FLAVOR == "standalone") {
            result.getOrThrow()
            assertEquals(listOf(true, true, false, true, true, true), protocols)
            assertEquals(calls[2], calls[3])
            assertEquals(1, transferCount)
            assertEquals(listOf(2L to 3L, 3L to 3L), progress)
        } else {
            assertTrue(result.isFailure)
            assertEquals(listOf(true, true, false), protocols)
            assertEquals(0, transferCount)
            assertTrue(progress.isEmpty())
        }
        assertTrue(calls.all { it.third == owner })
    }

    @Test fun failedBinaryAuthorizationAlternativesDoNotLoopOrTransfer() = runBlocking {
        val primary = IOException("Synthetic primary failure")
        val alternative = IOException("Synthetic alternative failure")
        replyFor = { path, body, eapi ->
            if (path == "/api/nos/token/alloc" && body["bucket"] != "") throw if (eapi) alternative else primary
            replies.getValue(path)
        }
        assertSame(if (BuildConfig.FLAVOR == "standalone") alternative else primary,
            runCatching { upload() }.exceptionOrNull())
        assertEquals(if (BuildConfig.FLAVOR == "standalone") 4 else 3, calls.size)
        assertEquals(0, transferCount)
        assertTrue(progress.isEmpty())
    }

    @Test fun recoveryReauthorizationAndReplacementDuringBinaryFailurePreventTheAlternative() = runBlocking {
        for (change in listOf("reauthorization", "recovery", "replacement")) {
            sessions.setRecoveryRequired(false)
            val stamp = sessions.snapshot()
            val before = calls.size
            replyFor = { path, body, _ ->
                if (path == "/api/nos/token/alloc" && body["bucket"] != "") {
                    if (change == "recovery") sessions.setRecoveryRequired(true) else {
                        if (change == "replacement") identity = SessionIdentity(8, true, false)
                        sessions.invalidate()
                    }
                    throw IOException("Synthetic failure")
                }
                replies.getValue(path)
            }
            assertTrue(runCatching { upload(stamp) }.isFailure)
            assertEquals(3, calls.size - before)
            assertTrue(calls.drop(before).all { it.third == stamp })
        }
        assertEquals(0, transferCount)
        assertTrue(progress.isEmpty())
    }

    @Test fun canceledNonCooperativeBinaryFailureCannotAllocateAnAlternative() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        replyFor = { path, body, _ ->
            if (path == "/api/nos/token/alloc" && body["bucket"] != "") {
                started.complete(Unit)
                withContext(NonCancellable) { response.await() }
                """{"code":403}"""
            } else replies.getValue(path)
        }
        val pending = async { upload() }
        started.await()
        pending.cancel()
        response.complete(Unit)
        pending.join()
        assertTrue(pending.isCancelled)
        assertEquals(3, calls.size)
        assertEquals(0, transferCount)
        assertTrue(progress.isEmpty())
    }

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
