package com.ljyh.mei.parasite

import java.util.UUID

/** Reserved job identity; neither the host's jobs nor another WorkManager share it. */
internal object HostWorkPolicy {
    const val CARRIER = "com.tencent.tinker.lib.service.DefaultTinkerResultService"
    const val NAMESPACE = "com.neoruaa.meilox.parasite.work"
    const val MARKER_KEY = "meilox_work_owner"
    const val MARKER_VALUE = "v1"
    const val WORK_ID = "EXTRA_WORK_SPEC_ID"
    const val WORK_GENERATION = "EXTRA_WORK_SPEC_GENERATION"
    const val MIN_JOB_ID = 0x4D580000
    const val MAX_JOB_ID = MIN_JOB_ID + 1000

    fun owns(
        packageName: String?, serviceName: String?, id: Int, marker: String?,
        workId: String?, generation: Int, namespace: String?, sdk: Int,
    ): Boolean = packageName == HostIdentity.PACKAGE && serviceName == CARRIER &&
        id in MIN_JOB_ID..MAX_JOB_ID && marker == MARKER_VALUE && generation >= 0 &&
        workId != null && runCatching { UUID.fromString(workId).toString() == workId }.getOrDefault(false) &&
        (sdk < 34 || namespace == NAMESPACE)
}
