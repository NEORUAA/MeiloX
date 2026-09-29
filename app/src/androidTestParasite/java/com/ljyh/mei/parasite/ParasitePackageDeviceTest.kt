package com.ljyh.mei.parasite

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ParasitePackageDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun moduleHasNoStandaloneMusicComponentsOrLauncher() {
        assertEquals("com.neoruaa.meilox.parasite", context.packageName)
        val manager = context.packageManager
        assertNull(manager.getLaunchIntentForPackage(context.packageName))
        assertThrows(PackageManager.NameNotFoundException::class.java) {
            manager.getActivityInfo(ComponentName(context, HostComponentMapping.MODULE_ACTIVITY), 0)
        }
        assertThrows(PackageManager.NameNotFoundException::class.java) {
            manager.getServiceInfo(ComponentName(context, HostComponentMapping.MODULE_SERVICE), 0)
        }
    }

    @Test fun moduleRetainsOnlyTheDeclaredTvScope() {
        ZipFile(context.applicationInfo.sourceDir).use { apk ->
            fun read(name: String) = apk.getInputStream(requireNotNull(apk.getEntry(name)))
                .bufferedReader().use { it.readText().trim() }
            assertEquals("com.ljyh.mei.parasite.MeiloXModule", read("META-INF/xposed/java_init.list"))
            assertEquals(HostIdentity.PACKAGE, read("META-INF/xposed/scope.list"))
        }
    }

    @Test fun standaloneSigningClassesAreAbsent() {
        listOf(
            "com.ljyh.mei.di.NeteaseInterceptor",
            "com.ljyh.mei.di.NeteaseHeader",
            "com.ljyh.mei.utils.encrypt.RSAKt",
            "com.ljyh.mei.utils.encrypt.NeteaseCipherKt",
            "com.ljyh.mei.utils.netease.ChineseIpUtils",
        ).forEach { name ->
            assertThrows(ClassNotFoundException::class.java) {
                Class.forName(name, false, context.classLoader)
            }
        }
    }
}
