package com.ljyh.mei.parasite.helper

import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsPipActivationTimeoutTest {
    @Test fun observedOemConfirmationDelayDoesNotExpireTheEndpoint() {
        val observedLaunchDelayMillis = 24_471L
        assertTrue("The host endpoint expired before HyperOS allowed the helper to start",
            observedLaunchDelayMillis < HostLyricsPip.ACTIVATION_TIMEOUT_MILLIS)
    }

    @Test fun anUnstartedHelperStillHasAFiniteActivationDeadline() {
        assertTrue(HostLyricsPip.ACTIVATION_TIMEOUT_MILLIS in 1L..120_000L)
    }
}
