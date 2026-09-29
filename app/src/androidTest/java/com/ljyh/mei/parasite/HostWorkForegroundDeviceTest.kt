package com.ljyh.mei.parasite

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ForegroundInfo
import androidx.work.impl.foreground.SystemForegroundDispatcher
import androidx.work.impl.model.WorkGenerationalId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostWorkForegroundDeviceTest {
    private val context: Context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getPackageName() = HostIdentity.PACKAGE
    }
    private val work = WorkGenerationalId("4e8037a4-2df9-4d96-b830-46bca17d08cc", 2)
    private val info get() = ForegroundInfo(HostWorkForegroundPolicy.MIN_NOTIFICATION_ID,
        Notification.Builder(context, "test").setSmallIcon(android.R.drawable.stat_sys_download).build())

    @Test fun installedAndroidXCommandsRouteAndSurviveParcelableTransport() {
        listOf(
            SystemForegroundDispatcher.createStartForegroundIntent(context, work, info),
            SystemForegroundDispatcher.createNotifyIntent(context, work, info),
            SystemForegroundDispatcher.createCancelWorkIntent(context, work.workSpecId),
            SystemForegroundDispatcher.createStopForegroundIntent(context),
        ).forEach { original ->
            val routed = HostWorkForeground.route(original)
            assertNotSame(original, routed)
            assertEquals(HostWorkForegroundPolicy.MODULE_SERVICE, original.component?.className)
            assertFalse(original.hasExtra(HostWorkForegroundPolicy.MARKER))
            assertTrue(HostWorkForeground.owns(routed))
            val parcel = Parcel.obtain()
            try {
                routed.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                val restored = Intent.CREATOR.createFromParcel(parcel)
                assertTrue(HostWorkForeground.owns(restored))
                assertEquals(original.action, restored.action)
                assertEquals(original.data, restored.data)
            } finally { parcel.recycle() }
        }
    }

    @Test fun originalBinderIntentsAndOtherPackagesAreNeverClaimed() {
        val original = Intent().setClassName(HostIdentity.PACKAGE, HostWorkForegroundPolicy.CARRIER)
        assertSame(original, HostWorkForeground.route(original))
        assertFalse(HostWorkForeground.owns(original))
        assertFalse(HostWorkForeground.owns(null))
        val other = SystemForegroundDispatcher.createStopForegroundIntent(context)
            .setComponent(ComponentName("other.package", HostWorkForegroundPolicy.MODULE_SERVICE))
        assertSame(other, HostWorkForeground.route(other))
    }

    @Test fun missingMarkerOrAlteredPayloadIsRejected() {
        val routed = HostWorkForeground.route(SystemForegroundDispatcher.createStartForegroundIntent(context, work, info))
        assertFalse(HostWorkForeground.owns(Intent(routed).apply { removeExtra(HostWorkForegroundPolicy.MARKER) }))
        assertFalse(HostWorkForeground.owns(Intent(routed).putExtra(HostWorkForegroundPolicy.NOTIFICATION_ID, 888)))
        assertFalse(HostWorkForeground.owns(Intent(routed).putExtra(HostWorkForegroundPolicy.NOTIFICATION_TYPE, 1)))
        assertFalse(HostWorkForeground.owns(Intent(routed).setAction("unknown")))
        val invalid = SystemForegroundDispatcher.createStartForegroundIntent(context, work, info)
            .putExtra(HostWorkForegroundPolicy.GENERATION, -1)
        assertTrue(runCatching { HostWorkForeground.route(invalid) }.exceptionOrNull() is IllegalArgumentException)
    }
}
