package com.ljyh.mei.standalone

import android.content.ComponentName
import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import com.ljyh.mei.AppContext
import com.ljyh.mei.R
import com.ljyh.mei.di.AppGraph
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
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

    @Test fun fixtureApplicationProvidesPlatformContextWithoutTheProductionGraph() {
        assertTrue(context.applicationContext is StandaloneFixtureApplication)
        assertSame(context.applicationContext, AppContext.instance)
        assertFalse(context.applicationContext is androidx.work.Configuration.Provider)
        assertThrows(IllegalStateException::class.java) { AppGraph.component }
        assertThrows(IllegalStateException::class.java) { androidx.work.WorkManager.getInstance(context) }
    }

    @Test fun fixtureImageLoaderRejectsRemoteImagesWithoutOpeningAConnection() = runBlocking {
        val application = context.applicationContext as StandaloneFixtureApplication
        val loader = application.newImageLoader(context)
        try {
            val result = loader.execute(ImageRequest.Builder(context)
                .data("https://fixture.example.test/offline.png").build())
            assertTrue(result is ErrorResult)
            assertEquals("Network images are disabled in standalone fixtures", (result as ErrorResult).throwable.message)
        } finally { loader.shutdown() }
    }

    @Test fun productionManifestDeclaresTheApplicationAndGatedDownloadFactory() {
        assertEquals(AppContext::class.java.name, context.applicationInfo.className)
        // This checks the configuration contract, not production onCreate or cold startup.
        val application = AppContext() as androidx.work.Configuration.Provider
        assertTrue(application.workManagerConfiguration.workerFactory is StandaloneDownloadWorkerFactory)
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
