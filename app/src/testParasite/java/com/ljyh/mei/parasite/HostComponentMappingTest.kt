package com.ljyh.mei.parasite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostComponentMappingTest {
    @Test fun onlyExplicitModuleComponentsInTheHostAreMapped() {
        assertEquals(HostComponentMapping.LAUNCHER,
            HostComponentMapping.target(HostIdentity.PACKAGE, HostComponentMapping.MODULE_ACTIVITY))
        assertEquals(HostComponentMapping.SERVICE,
            HostComponentMapping.target(HostIdentity.PACKAGE, HostComponentMapping.MODULE_SERVICE))
        assertNull(HostComponentMapping.target(HostIdentity.PACKAGE, HostComponentMapping.ACTIVITY))
        assertNull(HostComponentMapping.target(HostIdentity.PACKAGE, "com.netease.cloudmusic.service.PlayService"))
        assertNull(HostComponentMapping.target("com.neoruaa.meilox.parasite", HostComponentMapping.MODULE_ACTIVITY))
        assertNull(HostComponentMapping.target("another.package", HostComponentMapping.MODULE_SERVICE))
        assertNull(HostComponentMapping.target(null, null))
    }

    @Test fun appShellReplacesLauncherRestoredHomeAndCalendarBeforeTheirCreation() {
        listOf(HostComponentMapping.ACTIVITY, HostComponentMapping.LAUNCHER,
            HostComponentMapping.HOME, HostComponentMapping.CALENDAR).forEach {
            assertTrue(HostComponentMapping.replacesActivity(it, appEnabled = true))
        }
    }

    @Test fun isolatedRuntimeProbeDoesNotTakeOverTheOfficialLauncher() {
        assertTrue(HostComponentMapping.replacesActivity(HostComponentMapping.ACTIVITY, appEnabled = false))
        listOf(HostComponentMapping.LAUNCHER, HostComponentMapping.HOME, HostComponentMapping.CALENDAR).forEach {
            assertFalse(HostComponentMapping.replacesActivity(it, appEnabled = false))
        }
    }

    @Test fun unrelatedActivitiesAndLookalikePackagesAreNotReplaced() {
        listOf(null, "another.package.MainActivity", HostComponentMapping.MODULE_ACTIVITY,
            "com.netease.cloudmusic.tv.webview.WebViewActivity", "com.netease.cloudmusic.tv.activity.TvLoginActivity",
            "com.netease.cloudmusic.app.LoadingActivityOther").forEach {
            assertFalse(HostComponentMapping.replacesActivity(it, appEnabled = true))
        }
    }

    @Test fun onlyAppResourceIdsInTheHostUseTheModuleResourcePackage() {
        assertTrue(HostComponentMapping.usesModuleResource(HostIdentity.PACKAGE, 0x7f080123))
        assertFalse(HostComponentMapping.usesModuleResource(HostIdentity.PACKAGE, 0x01080001))
        assertFalse(HostComponentMapping.usesModuleResource(HostIdentity.PACKAGE, 0))
        assertFalse(HostComponentMapping.usesModuleResource("another.package", 0x7f080123))
    }
}
