package com.ljyh.mei.parasite

import android.app.job.JobInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Parcel
import android.os.PersistableBundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostWorkCarrierDeviceTest {
    @Test fun reservedRangeIsAcceptedByTheInstalledWorkManagerVersion() {
        Configuration.Builder().setJobSchedulerJobIdRange(
            HostWorkPolicy.MIN_JOB_ID, HostWorkPolicy.MAX_JOB_ID,
        )
    }

    @Test fun markerAddedAfterConversionSurvivesTheJobSchedulerParcel() {
        val carrier = ComponentName(HostIdentity.PACKAGE, HostWorkPolicy.CARRIER)
        val job = JobInfo.Builder(HostWorkPolicy.MIN_JOB_ID, carrier)
            .setMinimumLatency(30_000)
            .setExtras(PersistableBundle().apply {
                putString(HostWorkPolicy.WORK_ID, "4e8037a4-2df9-4d96-b830-46bca17d08cc")
                putInt(HostWorkPolicy.WORK_GENERATION, 0)
            }).build()
        job.extras.putString(HostWorkPolicy.MARKER_KEY, HostWorkPolicy.MARKER_VALUE)
        val parcel = Parcel.obtain()
        val restored = try {
            job.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            JobInfo.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }

        assertEquals(carrier, restored.service)
        assertEquals(30_000L, restored.minLatencyMillis)
        assertTrue(HostWorkManager.owns(restored, HostWorkPolicy.NAMESPACE))
        if (Build.VERSION.SDK_INT >= 34) {
            assertFalse(HostWorkManager.owns(restored, "androidx.work.systemjobscheduler"))
        }
        restored.extras.remove(HostWorkPolicy.MARKER_KEY)
        assertFalse(HostWorkManager.owns(restored, HostWorkPolicy.NAMESPACE))
    }

    @Test fun originalPatchIntentsAreNeverRoutedToTheMusicService() {
        val intent = Intent().setClassName(HostIdentity.PACKAGE, HostWorkPolicy.CARRIER)
            .putExtra("result_extra", "substitute")
        assertSame(intent, HostAppComponentHooks.route(intent))
    }
}
