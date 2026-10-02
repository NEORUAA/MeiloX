package com.ljyh.mei.parasite

import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProbeMediaOwnershipTest {
    private var identity = SessionIdentity(41, true, false)
    private val sessions = SessionStore().apply { bind { identity } }

    @Test fun currentOfferPreservesTheResolvingOwnerAndUpdatesItsPlayback() = runTest {
        ProbeMediaOwnership(sessions, backgroundScope).use { media ->
            val owner = sessions.snapshot()
            assertTrue(media.offer(URL, owner))
            val captured = checkNotNull(media.playbackState.value.media)
            assertEquals(owner, captured.owner)
            assertEquals(URL, captured.url)
            assertTrue(media.withMedia { assertEquals(captured, it) })
            media.update(captured, true, 1234)
            assertTrue(media.playbackState.value.ready)
            assertTrue(media.playbackState.value.playing)
            assertEquals(1234L, media.playbackState.value.positionMs)
        }
    }

    @Test fun aDelayedUrlCannotAdoptSameAccountReauthorization() = runTest {
        ProbeMediaOwnership(sessions, backgroundScope).use { media ->
            val resolvedBy = sessions.snapshot()
            sessions.invalidate()
            assertFalse(media.offer(URL, resolvedBy))
            assertFalse(media.withMedia { fail("A stale URL must not start playback") })
            assertEquals(ProbePlaybackState(), media.playbackState.value)
        }
    }

    @Test fun queuedPlaybackPublicationReadsIdentityAfterAtomicAcceptanceReleasesItsMonitor() = runTest {
        lateinit var guardedSessions: SessionStore
        guardedSessions = SessionStore().apply { bind {
            check(!Thread.holdsLock(guardedSessions)) { "Credential reader entered under the session monitor" }
            identity
        } }
        ProbeMediaOwnership(guardedSessions, backgroundScope).use { media ->
            assertTrue(media.offer(URL, guardedSessions.snapshot()))
            val queued = mutableListOf<() -> Unit>()
            assertTrue(media.withMedia { accepted ->
                assertTrue(Thread.holdsLock(guardedSessions))
                queued += { media.update(accepted, true, 1000) }
            })
            queued.forEach { it() }
            assertTrue(media.playbackState.value.playing)
            assertEquals(1000L, media.playbackState.value.positionMs)
        }
    }

    @Test fun accountReplacementRetiresUrlReadyAndActivePlayback() = runTest {
        ProbeMediaOwnership(sessions, backgroundScope).use { media ->
            assertTrue(media.offer(URL, sessions.snapshot()))
            val loaded = checkNotNull(media.playbackState.value.media)
            media.update(loaded, true, 1000)
            identity = SessionIdentity(42, true, false)
            sessions.invalidate()
            assertEquals(ProbePlaybackState(), media.playbackState.value)
            assertFalse(media.isCurrent(loaded))
            assertFalse(media.withMedia { fail("A replaced owner must not resume") })
            media.update(loaded, true, 2000)
            assertEquals(ProbePlaybackState(), media.playbackState.value)
        }
    }

    @Test fun identityChangesWithoutInvalidationStillRejectResumeAndClearReady() = runTest {
        ProbeMediaOwnership(sessions, backgroundScope).use { media ->
            assertTrue(media.offer(URL, sessions.snapshot()))
            identity = SessionIdentity(42, true, false)
            assertFalse(media.withMedia { fail("A changed identity must not resume") })
            assertEquals(ProbePlaybackState(), media.playbackState.value)
        }
    }

    @Test fun staleOffersAndPlaybackUpdatesCannotEraseANewOwnersMedia() = runTest {
        ProbeMediaOwnership(sessions, backgroundScope).use { media ->
            val oldOwner = sessions.snapshot()
            assertTrue(media.offer(URL, oldOwner))
            val oldMedia = checkNotNull(media.playbackState.value.media)
            sessions.invalidate()
            val currentOwner = sessions.snapshot()
            assertTrue(media.offer(NEW_URL, currentOwner))
            val currentMedia = checkNotNull(media.playbackState.value.media)
            media.update(currentMedia, true, 1000)
            assertFalse(media.offer(URL, oldOwner))
            media.update(oldMedia, false, 0)
            media.update(null, false, 0)
            assertEquals(ProbePlaybackState(currentMedia, true, 1000), media.playbackState.value)
            assertTrue(media.withMedia { assertEquals(currentOwner, it.owner) })
        }
    }

    @Test fun recoveryRetiresMediaAndCannotRestoreItWhenTheFenceClears() = runTest {
        ProbeMediaOwnership(sessions, backgroundScope).use { media ->
            val owner = sessions.snapshot()
            assertTrue(media.offer(URL, owner))
            val loaded = checkNotNull(media.playbackState.value.media)
            sessions.setRecoveryRequired(true)
            assertFalse(media.withMedia { fail("Recovery must reject playback") })
            assertFalse(media.offer(URL, owner))
            runCurrent()
            assertEquals(ProbePlaybackState(), media.playbackState.value)
            sessions.setRecoveryRequired(false)
            runCurrent()
            assertFalse(media.isCurrent(loaded))
            assertFalse(media.withMedia { fail("Recovery must not resurrect the retired URL") })
            assertTrue(media.offer(NEW_URL, sessions.snapshot()))
        }
    }

    @Test fun recoveryClearsReadyEvenWhenNoPlaybackCommandArrives() = runTest {
        ProbeMediaOwnership(sessions, backgroundScope).use { media ->
            assertTrue(media.offer(URL, sessions.snapshot()))
            sessions.setRecoveryRequired(true)
            runCurrent()
            assertEquals(ProbePlaybackState(), media.playbackState.value)
        }
    }

    @Test fun transitionAndGuestSessionsCannotPublishPlayableMedia() = runTest {
        ProbeMediaOwnership(sessions, backgroundScope).use { media ->
            val owner = sessions.snapshot()
            assertTrue(media.offer(URL, owner))
            sessions.beginTransition().use {
                assertFalse(media.offer(URL, owner))
                assertFalse(media.withMedia { fail("A transition must reject playback") })
            }
            assertEquals(ProbePlaybackState(), media.playbackState.value)
            identity = SessionIdentity(0, false, true)
            assertFalse(media.offer(URL, sessions.snapshot()))
        }
    }

    @Test fun delayedInvalidationCannotEraseANewerGenerationsOffer() = runTest {
        lateinit var media: ProbeMediaOwnership
        sessions.onInvalidated { revision ->
            if (revision == 1L) {
                sessions.invalidate()
                assertTrue(media.offer(NEW_URL, sessions.snapshot()))
            }
        }.use {
            ProbeMediaOwnership(sessions, backgroundScope).use { ownership ->
                media = ownership
                assertTrue(media.offer(URL, sessions.snapshot()))
                sessions.invalidate()
                assertEquals(NEW_URL, media.playbackState.value.media?.url)
                val expectedOwner = sessions.snapshot()
                assertTrue(media.withMedia { assertEquals(expectedOwner, it.owner) })
            }
        }
    }

    companion object {
        private const val URL = "https://example.invalid/probe.mp3"
        private const val NEW_URL = "https://example.invalid/current-probe.mp3"
    }
}
