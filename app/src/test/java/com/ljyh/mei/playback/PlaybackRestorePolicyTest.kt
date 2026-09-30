package com.ljyh.mei.playback

import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStore
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRestorePolicyTest {
    private var identity = SessionIdentity(1, true, false)
    private val sessions = SessionStore().apply { bind { identity } }

    @Test fun unchangedSessionCanPrepareTheRestoredQueue() {
        var prepared = false
        assertTrue(PlaybackRestorePolicy(sessions).withCurrentAuthorization { prepared = true })
        assertTrue(prepared)
    }

    @Test fun initialRecoveryCannotAutoplayEvenAfterTheCookieIsRestored() {
        sessions.setRecoveryRequired(true)
        val policy = PlaybackRestorePolicy(sessions)
        sessions.setRecoveryRequired(false)
        reject(policy)
    }

    @Test fun recoveryDuringTheLocalReadSuppressesPreparation() {
        val policy = PlaybackRestorePolicy(sessions)
        sessions.setRecoveryRequired(true)
        reject(policy)
    }

    @Test fun accountReplacementCannotPrepareTheSavedQueue() {
        val policy = PlaybackRestorePolicy(sessions)
        identity = SessionIdentity(2, true, false)
        reject(policy)
    }

    @Test fun sameAccountRenewalCannotPrepareTheSavedQueue() {
        val policy = PlaybackRestorePolicy(sessions)
        sessions.invalidate()
        reject(policy)
    }

    @Test fun queuedInvalidationPreventsPreparationAfterRecoveryEnds() {
        val policy = PlaybackRestorePolicy(sessions)
        policy.invalidate()
        sessions.setRecoveryRequired(false)
        reject(policy)
    }

    @Test fun anUnavailableInitialSessionDoesNotBreakLocalRestore() {
        reject(PlaybackRestorePolicy(SessionStore()))
    }

    @Test fun anUnavailableSessionReaderDoesNotBreakLocalRestore() {
        var available = true
        val flaky = SessionStore().apply { bind { if (available) identity else throw IOException() } }
        val policy = PlaybackRestorePolicy(flaky)
        available = false
        reject(policy)
    }

    @Test fun stableGuestSessionKeepsExistingPublicOrLocalPlaybackBehavior() {
        identity = SessionIdentity(0, false, true)
        assertTrue(PlaybackRestorePolicy(sessions).withCurrentAuthorization {})
    }

    private fun reject(policy: PlaybackRestorePolicy) {
        assertFalse(policy.withCurrentAuthorization { throw AssertionError("Unexpected source preparation") })
    }
}
