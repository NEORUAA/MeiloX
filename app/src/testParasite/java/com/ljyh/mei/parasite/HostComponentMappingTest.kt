package com.ljyh.mei.parasite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostComponentMappingTest {
    private class ModuleActivity
    private class OtherActivity

    @Test fun lifecycleIsolationRequiresTheExactModuleClassInTheHost() {
        val expected = ModuleActivity::class.java
        assertTrue(HostComponentMapping.ownsModuleActivity(HostIdentity.PACKAGE, expected, expected))
        listOf(null, "another.package", "${HostIdentity.PACKAGE}.other").forEach { packageName ->
            assertFalse(HostComponentMapping.ownsModuleActivity(packageName, expected, expected))
        }
        assertFalse(HostComponentMapping.ownsModuleActivity(HostIdentity.PACKAGE, OtherActivity::class.java, expected))
    }

    @Test fun aSameNamedClassFromAnotherLoaderCannotAcquireModuleLifecycle() {
        val expected = ModuleActivity::class.java
        val bytes = requireNotNull(expected.getResourceAsStream("/${expected.name.replace('.', '/')}.class"))
            .use { it.readBytes() }
        val other = object : ClassLoader(expected.classLoader) {
            fun defineCopy(): Class<*> = defineClass(expected.name, bytes, 0, bytes.size)
        }.defineCopy()
        assertEquals(expected.name, other.name)
        assertFalse(HostComponentMapping.ownsModuleActivity(HostIdentity.PACKAGE, other, expected))
        assertFalse(HostComponentMapping.restoresLegacyReportFragment(
            HostIdentity.PACKAGE, other, expected, HostComponentMapping.HOST_REPORT_FRAGMENT,
        ))
    }

    @Test fun legacyRestoreDoesNotBorrowTheHostLoaderForAnyOtherFragment() {
        val expected = ModuleActivity::class.java
        assertTrue(HostComponentMapping.restoresLegacyReportFragment(
            HostIdentity.PACKAGE, expected, expected, HostComponentMapping.HOST_REPORT_FRAGMENT,
        ))
        listOf(null, "", "androidx.lifecycle.v", "com.netease.cloudmusic.HostFragment",
            "${HostComponentMapping.HOST_REPORT_FRAGMENT}Other").forEach { name ->
            assertFalse(HostComponentMapping.restoresLegacyReportFragment(HostIdentity.PACKAGE, expected, expected, name))
        }
        assertFalse(HostComponentMapping.restoresLegacyReportFragment("another.package", expected, expected,
            HostComponentMapping.HOST_REPORT_FRAGMENT))
        assertFalse(HostComponentMapping.restoresLegacyReportFragment(HostIdentity.PACKAGE, OtherActivity::class.java,
            expected, HostComponentMapping.HOST_REPORT_FRAGMENT))
    }

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
