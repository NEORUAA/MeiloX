package com.ljyh.mei.parasite

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostAppComponentRoutingTest {
    @Test fun notificationEntryUsesTheLauncherAndReusesTheExistingShell() {
        val original = Intent(Intent.ACTION_VIEW, Uri.parse("meilox://test"))
            .setClassName(HostIdentity.PACKAGE, HostComponentMapping.MODULE_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("test", "retained")
        val routed = HostAppComponentHooks.route(original)
        assertNotSame(original, routed)
        assertEquals(HostComponentMapping.LAUNCHER, routed.component?.className)
        assertEquals(HostIdentity.PACKAGE, routed.component?.packageName)
        assertEquals(original.action, routed.action)
        assertEquals(original.data, routed.data)
        assertEquals("retained", routed.getStringExtra("test"))
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP, routed.flags)
        assertEquals(HostComponentMapping.MODULE_ACTIVITY, original.component?.className)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, original.flags)
    }

    @Test fun serviceRoutingDoesNotAcquireActivityFlags() {
        val original = Intent("playback-test")
            .setClassName(HostIdentity.PACKAGE, HostComponentMapping.MODULE_SERVICE)
        val routed = HostAppComponentHooks.route(original)
        assertEquals(HostComponentMapping.SERVICE, routed.component?.className)
        assertEquals(0, routed.flags)
        assertEquals(original.action, routed.action)
    }

    @Test fun implicitExternalAndOfficialIntentsAreUnchanged() {
        listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org")),
            Intent().setClassName("another.package", HostComponentMapping.MODULE_ACTIVITY),
            Intent().setClassName(HostIdentity.PACKAGE, HostComponentMapping.LAUNCHER),
            Intent().setClassName(HostIdentity.PACKAGE, "com.netease.cloudmusic.service.PlayService"),
        ).forEach { assertSame(it, HostAppComponentHooks.route(it)) }
    }
}
