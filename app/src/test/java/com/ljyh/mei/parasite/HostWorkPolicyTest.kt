package com.ljyh.mei.parasite

import org.junit.Assert.*
import org.junit.Test

class HostWorkPolicyTest {
    @Test fun reservesTheSpanRequiredByWorkManager() {
        assertTrue(HostWorkPolicy.MAX_JOB_ID - HostWorkPolicy.MIN_JOB_ID >= 1000)
    }

    private val workId = "4e8037a4-2df9-4d96-b830-46bca17d08cc"
    private fun owns(
        packageName: String? = HostIdentity.PACKAGE,
        service: String? = HostWorkPolicy.CARRIER,
        id: Int = HostWorkPolicy.MIN_JOB_ID,
        marker: String? = HostWorkPolicy.MARKER_VALUE,
        workId: String? = this.workId,
        generation: Int = 0,
        namespace: String? = HostWorkPolicy.NAMESPACE,
        sdk: Int = 37,
    ) = HostWorkPolicy.owns(packageName, service, id, marker, workId, generation, namespace, sdk)

    @Test fun acceptsOnlyTheReservedModuleJobIdentity() {
        assertTrue(owns())
        assertTrue(owns(id = HostWorkPolicy.MAX_JOB_ID, generation = 3))
        assertFalse(owns(id = HostWorkPolicy.MIN_JOB_ID - 1))
        assertFalse(owns(id = HostWorkPolicy.MAX_JOB_ID + 1))
    }

    @Test fun neitherOfficialNorForeignComponentsBecomeModuleWork() {
        assertFalse(owns(packageName = "other.app"))
        assertFalse(owns(service = "com.tencent.tinker.lib.service.TinkerPatchService"))
        assertFalse(owns(marker = null))
        assertFalse(owns(marker = "future-version"))
    }

    @Test fun requiresAnUnambiguousWorkSpecAndGeneration() {
        assertFalse(owns(workId = null))
        assertFalse(owns(workId = "1-1-1-1-1"))
        assertFalse(owns(workId = workId.uppercase()))
        assertFalse(owns(generation = -1))
    }

    @Test fun namespacesCannotCrossOnPlatformsThatSupportThem() {
        assertFalse(owns(namespace = null))
        assertFalse(owns(namespace = "androidx.work.systemjobscheduler"))
        assertTrue(owns(namespace = null, sdk = 33))
    }
}
