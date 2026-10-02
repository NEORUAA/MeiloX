package com.ljyh.mei.playback

import com.ljyh.mei.data.model.SongSourceIdentity
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class PlaybackSourceRecoveryTest {
    private val cloud = SongSourceIdentity(999, 88, 7, 17)

    @Test fun sameCloudSourceTransitionKeepsThePendingJobAndConsumedBudget() {
        val recovery = started(cloud.key)
        val job = recovery.job
        recovery.selectSource(cloud.key)
        assertSame(job, recovery.job)
        assertTrue(requireNotNull(job).isActive)
        assertEquals(1, recovery.attempts)
        assertFalse(recovery.tryBeginAttempt())
        recovery.clear()
    }

    @Test fun sameVisibleEntryWithAnotherFileAccountOrAudioRetiresTheOldRecovery() {
        for (replacement in listOf(cloud.copy(cloudOwnerId = 89), cloud.copy(accountId = 8),
            cloud.copy(songId = 1000))) {
            assertEquals(cloud.entryId, replacement.entryId)
            assertRetired(cloud.key, replacement.key)
        }
    }

    @Test fun anotherEntryForTheSameAudioHasItsOwnRecovery() {
        assertRetired(cloud.key, cloud.copy(entryId = 18).key)
    }

    @Test fun publicAndPrivateSourcesCannotShareARecoveryBudget() {
        val public = SongSourceIdentity(cloud.entryId).key
        assertRetired(public, cloud.key)
        assertRetired(cloud.key, public)
    }

    @Test fun ordinarySourceTransitionsRetainTheOriginalOneAttemptLimit() {
        val recovery = started("17")
        recovery.selectSource("17")
        assertFalse(recovery.tryBeginAttempt())
        recovery.selectSource("18")
        assertTrue(recovery.tryBeginAttempt())
        assertFalse(recovery.tryBeginAttempt())
        recovery.clear()
    }

    @Test fun removingTheCurrentSourceCancelsItsJobAndCannotStartAnotherAttempt() {
        val recovery = started(cloud.key)
        val job = requireNotNull(recovery.job)
        recovery.selectSource(null)
        assertTrue(job.isCancelled)
        assertNull(recovery.job)
        assertNull(recovery.sourceKey)
        assertEquals(0, recovery.attempts)
        assertFalse(recovery.tryBeginAttempt())
    }

    @Test fun sessionRetirementClearsEvenAnUnchangedPublicSource() {
        val recovery = started("17")
        val job = requireNotNull(recovery.job)
        recovery.clear()
        recovery.clear()
        assertTrue(job.isCancelled)
        assertNull(recovery.job)
        assertNull(recovery.sourceKey)
        assertFalse(recovery.tryBeginAttempt())
        recovery.selectSource("17")
        assertTrue(recovery.tryBeginAttempt())
    }

    @Test fun retryLimitMustBePositiveAndCannotBeExceeded() {
        assertThrows(IllegalArgumentException::class.java) { PlaybackSourceRecovery(0) }
        val recovery = PlaybackSourceRecovery(2)
        assertFalse(recovery.tryBeginAttempt())
        recovery.selectSource("17")
        assertTrue(recovery.tryBeginAttempt())
        assertTrue(recovery.tryBeginAttempt())
        assertFalse(recovery.tryBeginAttempt())
        assertEquals(2, recovery.attempts)
    }

    private fun started(key: String) = PlaybackSourceRecovery(1).apply {
        selectSource(key)
        assertTrue(tryBeginAttempt())
        job = Job()
    }

    private fun assertRetired(previous: String, next: String) {
        val recovery = started(previous)
        val oldJob = requireNotNull(recovery.job)
        recovery.selectSource(next)
        assertTrue(oldJob.isCancelled)
        assertNull(recovery.job)
        assertEquals(next, recovery.sourceKey)
        assertEquals(0, recovery.attempts)
        assertTrue(recovery.tryBeginAttempt())
        assertFalse(recovery.tryBeginAttempt())
    }
}
