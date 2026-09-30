package com.ljyh.mei.parasite

import com.ljyh.mei.data.repository.CloudUploadAuthorization
import com.ljyh.mei.data.repository.CloudUploadFile
import com.ljyh.mei.data.session.SessionIdentity
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class HostCloudBinaryUploaderTest {
    private val sessions = HostSessionBridge().apply { bind { SessionIdentity(7, true, false) } }
    private val owner = sessions.snapshot()
    private val file = CloudUploadFile(File("unused-fixture"), "Test.mp3", "mp3", "Test", 3,
        "900150983cd24fb0d6963f7d28e17f72", "Test", "Artist", "Album", "audio/mpeg")
    private val authorization = CloudUploadAuthorization("bucket", "key", "fixture-token")

    @Test fun unboundAndStaleUploadersCannotDispatch() = runBlocking {
        val source = HostCloudBinaryUploader(sessions)
        assertTrue(runCatching { source.upload(file, authorization, owner) { _, _ -> } }.isFailure)
        source.bind { _, _, _, _ -> error("Must not dispatch") }
        sessions.invalidate()
        assertTrue(runCatching { source.upload(file, authorization, owner) { _, _ -> } }.isFailure)
    }

    @Test fun hostResultAndProgressArePassedWithoutNetworkFallback() = runBlocking {
        val source = HostCloudBinaryUploader(sessions)
        val seen = mutableListOf<Long>()
        source.bind { input, token, canceled, progress ->
            assertSame(file, input)
            assertSame(authorization, token)
            assertFalse(canceled())
            progress(2, 3)
            1
        }
        source.upload(file, authorization, owner) { sent, _ -> seen += sent }
        assertEquals(listOf(2L), seen)
        assertTrue(runCatching { source.bind { _, _, _, _ -> 1 } }.isFailure)
    }

    @Test fun failureAndLateInvalidationNeverSucceed() = runBlocking {
        for (status in listOf(-1, -2, -3, -5, -50, 0, 2)) {
            val source = HostCloudBinaryUploader(sessions)
            source.bind { _, _, _, _ -> status }
            assertTrue(runCatching { source.upload(file, authorization, owner) { _, _ -> } }.isFailure)
        }
        val source = HostCloudBinaryUploader(sessions)
        source.bind { _, _, canceled, progress ->
            sessions.invalidate()
            assertTrue(canceled())
            assertTrue(runCatching { progress(1, 3) }.isFailure)
            1
        }
        assertTrue(runCatching { source.upload(file, authorization, owner) { _, _ -> error("Stale progress") } }.isFailure)
    }

    @Test fun coroutineCancellationReachesTheSdkProbe() = runBlocking {
        val source = HostCloudBinaryUploader(sessions)
        val job = Job()
        source.bind { _, _, canceled, _ -> job.cancel(); assertTrue(canceled()); 1 }
        assertTrue(runCatching { withContext(job) { source.upload(file, authorization, owner) { _, _ -> } } }.exceptionOrNull() is CancellationException)
    }

    @Test fun recoveryWithoutInvalidationPreventsDispatchAndCancelsSdkCallbacks() = runBlocking {
        val source = HostCloudBinaryUploader(sessions)
        var dispatched = 0
        source.bind { _, _, canceled, progress ->
            dispatched++
            sessions.setRecoveryRequired(true)
            assertTrue(canceled())
            assertTrue(runCatching { progress(1, 3) }.isFailure)
            1
        }
        sessions.setRecoveryRequired(true)
        assertTrue(runCatching { source.upload(file, authorization, owner) { _, _ -> error("Stale progress") } }.isFailure)
        assertEquals(0, dispatched)
        sessions.setRecoveryRequired(false)
        assertTrue(runCatching { source.upload(file, authorization, owner) { _, _ -> error("Stale progress") } }.isFailure)
        assertEquals(1, dispatched)
        assertEquals(owner, sessions.snapshot())
    }
}
