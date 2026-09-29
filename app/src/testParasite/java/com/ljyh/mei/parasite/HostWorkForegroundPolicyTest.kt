package com.ljyh.mei.parasite

import org.junit.Assert.*
import org.junit.Test

class HostWorkForegroundPolicyTest {
    private val id = "4e8037a4-2df9-4d96-b830-46bca17d08cc"
    private fun valid(action: String? = HostWorkForegroundPolicy.START, workId: String? = id,
        generation: Int = 0, notificationId: Int = HostWorkForegroundPolicy.MIN_NOTIFICATION_ID,
        hasNotification: Boolean = true, type: Int = 0) =
        HostWorkForegroundPolicy.valid(action, workId, generation, notificationId, hasNotification, type)

    @Test fun onlyKnownDispatcherActionsAreAccepted() {
        listOf(HostWorkForegroundPolicy.START, HostWorkForegroundPolicy.NOTIFY, HostWorkForegroundPolicy.CANCEL,
            HostWorkForegroundPolicy.STOP).forEach { assertTrue(valid(it)) }
        listOf(null, "", "org.chromium.wow.IPC", "ACTION_START_FOREGROUND_OTHER").forEach { assertFalse(valid(it)) }
    }

    @Test fun startAndNotifyRequireCanonicalWorkIdentityAndGeneration() {
        listOf(HostWorkForegroundPolicy.START, HostWorkForegroundPolicy.NOTIFY).forEach { action ->
            listOf(null, "", "1-1-1-1-1", id.uppercase(), "$id ").forEach { assertFalse(valid(action, it)) }
            assertFalse(valid(action, generation = -1))
            assertTrue(valid(action, generation = 5))
        }
    }

    @Test fun notificationIdsCannotCollideWithThePlayerOrOrdinaryHostIds() {
        listOf(0, 888, 1001, HostWorkForegroundPolicy.MIN_NOTIFICATION_ID - 1,
            HostWorkForegroundPolicy.MAX_NOTIFICATION_ID + 1).forEach { assertFalse(valid(notificationId = it)) }
        assertTrue(valid(notificationId = HostWorkForegroundPolicy.MAX_NOTIFICATION_ID))
        assertFalse(valid(hasNotification = false))
        assertFalse(valid(type = 1))
    }

    @Test fun cancelAndStopFollowTheirDifferentAndroidXPayloadContracts() {
        assertTrue(valid(HostWorkForegroundPolicy.CANCEL, generation = -1, notificationId = 0, hasNotification = false))
        assertFalse(valid(HostWorkForegroundPolicy.CANCEL, workId = null))
        assertTrue(valid(HostWorkForegroundPolicy.STOP, null, -1, 0, false))
    }
}
