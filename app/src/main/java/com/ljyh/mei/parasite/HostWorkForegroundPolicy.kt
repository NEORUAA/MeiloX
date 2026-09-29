package com.ljyh.mei.parasite

import com.ljyh.mei.playback.DownloadNotifications
import java.util.UUID

/** Commands from the isolated AndroidX dispatcher, not the host's IPC protocol. */
internal object HostWorkForegroundPolicy {
    const val CARRIER = "org.chromium.wow.extension.usage.WowIPCService"
    const val MODULE_SERVICE = "androidx.work.impl.foreground.SystemForegroundService"
    const val MARKER = "meilox_foreground_owner"
    const val VERSION = "v1"
    const val WORK_ID = "KEY_WORKSPEC_ID"
    const val GENERATION = "KEY_GENERATION"
    const val NOTIFICATION_ID = "KEY_NOTIFICATION_ID"
    const val NOTIFICATION = "KEY_NOTIFICATION"
    const val NOTIFICATION_TYPE = "KEY_FOREGROUND_SERVICE_TYPE"
    const val START = "ACTION_START_FOREGROUND"
    const val NOTIFY = "ACTION_NOTIFY"
    const val CANCEL = "ACTION_CANCEL_WORK"
    const val STOP = "ACTION_STOP_FOREGROUND"
    const val MIN_NOTIFICATION_ID = 0x4D590000
    const val MAX_NOTIFICATION_ID = DownloadNotifications.PROGRESS_ID

    fun valid(action: String?, workId: String?, generation: Int, notificationId: Int, hasNotification: Boolean, notificationType: Int): Boolean {
        if (action == STOP) return true
        if (workId == null || !runCatching { UUID.fromString(workId).toString() == workId }.getOrDefault(false)) return false
        return when (action) {
            CANCEL -> true
            START, NOTIFY -> generation >= 0 && hasNotification && notificationType == 0 &&
                notificationId in MIN_NOTIFICATION_ID..MAX_NOTIFICATION_ID
            else -> false
        }
    }
}
