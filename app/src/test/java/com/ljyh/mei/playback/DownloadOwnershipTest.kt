package com.ljyh.mei.playback

import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.data.model.room.DownloadTask
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionIdentity
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class DownloadOwnershipTest {
    private val id = UUID.randomUUID()
    private val task = DownloadTask("1", requestId = id.toString(), ownerId = 17, quality = "lossless")

    @Test fun onlyTheCurrentAccountAndRequestCanExecute() {
        task.requireExecutable(id, 17)
        task.copy(status = DownloadStatus.DOWNLOADING).requireExecutable(id, 17)
        assertTrue(runCatching { task.requireExecutable(UUID.randomUUID(), 17) }.isFailure)
        assertTrue(runCatching { task.requireExecutable(id, 18) }.isFailure)
        assertTrue(runCatching { task.copy(requestId = "").requireExecutable(id, 17) }.isFailure)
    }

    @Test fun terminalPausedMalformedAndUnownedRowsCannotBeAdopted() {
        for (status in DownloadStatus.entries.filterNot { it == DownloadStatus.PENDING || it == DownloadStatus.DOWNLOADING }) {
            assertTrue(runCatching { task.copy(status = status).requireExecutable(id, 17) }.isFailure)
        }
        listOf(task.copy(ownerId = 0), task.copy(quality = ""), task.copy(songId = "01"), task.copy(songId = "-1"))
            .forEach { assertTrue(runCatching { it.requireExecutable(id, 17) }.isFailure) }
    }

    @Test fun processRecreationNeedsAFreshStampAndSameAccountReauthorizationInvalidatesActiveWork() {
        val sessions = SessionStore().apply { bind { SessionIdentity(17, true, false) } }
        val first = sessions.snapshot()
        sessions.requireDownloadOwner(first)
        sessions.invalidate()
        assertTrue(runCatching { sessions.requireDownloadOwner(first) }.isFailure)
        val fresh = sessions.snapshot()
        task.requireExecutable(id, fresh.identity.userId)
        sessions.requireDownloadOwner(fresh)
        sessions.setRecoveryRequired(true)
        assertTrue(runCatching { sessions.requireDownloadOwner(fresh) }.isFailure)
    }

    @Test fun anonymousAndUnauthenticatedSessionsNeverDownload() {
        listOf(SessionIdentity(0, true, false), SessionIdentity(17, false, false), SessionIdentity(17, true, true))
            .forEach { identity ->
                val sessions = SessionStore().apply { bind { identity } }
                assertTrue(runCatching { sessions.requireDownloadOwner(sessions.snapshot()) }.isFailure)
            }
    }

    @Test fun invalidationCancelsTheEntireOwnedOperation() = runBlocking {
        val sessions = SessionStore().apply { bind { SessionIdentity(17, true, false) } }
        var finished = false
        assertTrue(runCatching {
            sessions.withDownloadOwner(sessions.snapshot()) {
                sessions.invalidate()
                currentCoroutineContext().ensureActive()
                finished = true
            }
        }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertFalse(finished)
        sessions.withDownloadOwner(sessions.snapshot()) { finished = true }
        assertTrue(finished)
    }
}
