package com.ljyh.mei.standalone

import android.content.ComponentName
import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.R
import com.ljyh.mei.di.AppGraph
import com.ljyh.mei.runtime.StandaloneComponentRuntime
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

class StandalonePackageDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun sessionResourcesDescribeTheStandaloneWebLoginAndRuntime() {
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale.ENGLISH)
        }
        val localized = context.createConfigurationContext(configuration)
        assertEquals("Complete sign-in on the webpage", localized.getString(R.string.netease_login_waiting))
        assertEquals(
            "Download scheduler is not ready. Restart the app and try again.",
            localized.getString(R.string.download_queue_unavailable),
        )
    }

    @Test fun debugInstallHasItsOwnLauncherAndPlaybackService() {
        assertEquals("com.neoruaa.meilox.standalone.debug", context.packageName)
        val manager = context.packageManager
        assertEquals("com.ljyh.mei.MainActivity", manager.getLaunchIntentForPackage(context.packageName)?.component?.className)
        assertNotNull(manager.getServiceInfo(ComponentName(context, "com.ljyh.mei.playback.MusicService"), 0))
    }

    @Test fun applicationBootstrapsTheStandaloneGraphWithoutAnyHost() {
        val graph = AppGraph.component
        assertSame(context.applicationContext, graph.context())
        assertTrue(graph.runtime() is StandaloneComponentRuntime)
        assertSame(graph.sessions(), graph.standaloneSessions())
        assertSame(graph.sessions(), graph.playbackReports().sessions)
        assertNotNull(graph.sessions().snapshot())
    }

    @Test fun applicationSelectsTheGatedDownloadFactoryInsteadOfTheDefaultStartupInitializer() {
        val application = context.applicationContext as androidx.work.Configuration.Provider
        assertTrue(application.workManagerConfiguration.workerFactory is StandaloneDownloadWorkerFactory)
        val manager = androidx.work.WorkManager.getInstance(context) as androidx.work.impl.WorkManagerImpl
        assertTrue(manager.configuration.workerFactory is StandaloneDownloadWorkerFactory)
        val provider = context.packageManager.getProviderInfo(
            ComponentName(context, "androidx.startup.InitializationProvider"), android.content.pm.PackageManager.GET_META_DATA,
        )
        assertFalse(provider.metaData?.containsKey("androidx.work.WorkManagerInitializer") == true)
    }

    @Test fun apkDoesNotRegisterAModuleOrContainHostClasses() {
        ZipFile(context.applicationInfo.sourceDir).use { apk ->
            assertFalse(apk.entries().asSequence().any { it.name.startsWith("META-INF/xposed/") })
        }
        listOf(
            "com.ljyh.mei.parasite.MeiloXModule",
            "com.ljyh.mei.parasite.HostCallFactory",
            "com.ljyh.mei.parasite.ModuleContext",
        ).forEach { name ->
            assertThrows(ClassNotFoundException::class.java) { Class.forName(name, false, context.classLoader) }
        }
        assertNotNull(Class.forName("com.ljyh.mei.di.NeteaseInterceptor", false, context.classLoader))
    }
}
