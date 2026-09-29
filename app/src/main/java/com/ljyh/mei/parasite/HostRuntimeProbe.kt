package com.ljyh.mei.parasite

import android.app.Application
import android.app.AppComponentFactory
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.MainActivity
import com.ljyh.mei.playback.MusicService
import io.github.libxposed.api.XposedModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class ProbePlaybackState(val ready: Boolean = false, val playing: Boolean = false, val positionMs: Long = 0)

/** Debug-only component/resource qualification. Never redirects the official launcher. */
internal object HostRuntimeProbe {
    const val ACTIVITY = HostComponentMapping.ACTIVITY
    const val SERVICE = HostComponentMapping.SERVICE
    @Volatile var mediaUrl: String? = null
        private set
    private val playback = MutableStateFlow(ProbePlaybackState())
    val playbackState = playback.asStateFlow()
    lateinit var applicationContext: ModuleContext
        private set
    var report: (String) -> Unit = {}
        private set

    fun install(
        module: XposedModule,
        application: Application,
        moduleInfo: ApplicationInfo,
        logger: (String) -> Unit,
        onHostActivity: (ClassLoader) -> Unit,
    ) {
        report = logger
        applicationContext = ModuleContext.create(application, moduleInfo.packageName)
        com.ljyh.mei.di.AppGraph.initialize(applicationContext)
        if (BuildConfig.PARASITE_APP_PROBE) HostAppComponentHooks.install(module, applicationContext, report)
        Thread({ ModuleStorageProbe.run(applicationContext, application, report) }, "MeiloX-storage-probe").start()
        module.hook(AppComponentFactory::class.java.getMethod("instantiateService", ClassLoader::class.java, String::class.java, Intent::class.java))
            .intercept { chain ->
                // ActivityThread supplies no start Intent until after service creation.
                if (chain.getArg(1) == SERVICE) {
                    report("runtime_service_instantiated")
                    if (BuildConfig.PARASITE_APP_PROBE) MusicService() else HostRuntimeProbeService()
                } else chain.proceed()
            }
        module.hook(Instrumentation::class.java.getMethod(
            "newActivity", ClassLoader::class.java, String::class.java, Intent::class.java,
        )).intercept { chain ->
            if (chain.getArg(1) == ACTIVITY) {
                val loader = chain.getArg(0) as ClassLoader
                onHostActivity(loader)
                val config = loader.loadClass("me.jessyan.autosize.AutoSizeConfig")
                    .getMethod("getInstance").invoke(null)
                val manager = config.javaClass.getMethod("getExternalAdaptManager").invoke(config)
                val activityClass = if (BuildConfig.PARASITE_APP_PROBE) MainActivity::class.java else HostRuntimeProbeActivity::class.java
                if (manager.javaClass.getMethod("isCancelAdapt", Class::class.java)
                        .invoke(manager, activityClass) != true) {
                    manager.javaClass.getMethod("addCancelAdaptOfActivity", Class::class.java)
                        .invoke(manager, activityClass)
                }
                report("runtime_activity_instantiated host_process=${Application.getProcessName() == HostIdentity.PACKAGE}")
                if (BuildConfig.PARASITE_APP_PROBE) MainActivity() else HostRuntimeProbeActivity()
            } else {
                chain.proceed()
            }
        }
    }

    fun wrap(base: Context): Context = applicationContext.wrap(base)

    fun wrapAppComponent(base: Context): Context =
        if (BuildConfig.PARASITE_APP_PROBE && base.packageName == HostIdentity.PACKAGE) wrap(base) else base

    fun offerMedia(url: String) {
        mediaUrl = url
        playback.update { it.copy(ready = true) }
    }

    fun updatePlayback(playing: Boolean, positionMs: Long) {
        playback.update { it.copy(playing = playing, positionMs = positionMs) }
    }

    fun playbackIntent(context: Context, action: String): Intent =
        Intent(action).setClassName(context.packageName, SERVICE)

}
