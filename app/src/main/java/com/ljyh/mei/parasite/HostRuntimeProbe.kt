package com.ljyh.mei.parasite

import android.app.Application
import android.app.AppComponentFactory
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Resources
import android.view.LayoutInflater
import io.github.libxposed.api.XposedModule
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class ProbePlaybackState(val ready: Boolean = false, val playing: Boolean = false, val positionMs: Long = 0)

/** Debug-only component/resource qualification. Never redirects the official launcher. */
internal object HostRuntimeProbe {
    const val ACTIVITY = "com.netease.cloudmusic.tv.test.TextMainActivity"
    const val SERVICE = "com.netease.cloudmusic.service.LocalMusicMatchService"
    @Volatile var mediaUrl: String? = null
        private set
    private val playback = MutableStateFlow(ProbePlaybackState())
    val playbackState = playback.asStateFlow()
    lateinit var applicationContext: Context
        private set
    var report: (String) -> Unit = {}
        private set

    fun install(module: XposedModule, application: Application, moduleInfo: ApplicationInfo, logger: (String) -> Unit) {
        report = logger
        val resources = application.packageManager.getResourcesForApplication(moduleInfo)
        applicationContext = ProbeContext(application, resources)
        module.hook(AppComponentFactory::class.java.getMethod("instantiateService", ClassLoader::class.java, String::class.java, Intent::class.java))
            .intercept { chain ->
                // ActivityThread supplies no start Intent until after service creation.
                if (chain.getArg(1) == SERVICE) {
                    report("runtime_service_instantiated")
                    HostRuntimeProbeService()
                } else chain.proceed()
            }
        module.hook(Instrumentation::class.java.getMethod(
            "newActivity", ClassLoader::class.java, String::class.java, Intent::class.java,
        )).intercept { chain ->
            if (chain.getArg(1) == ACTIVITY) {
                val loader = chain.getArg(0) as ClassLoader
                val config = loader.loadClass("me.jessyan.autosize.AutoSizeConfig")
                    .getMethod("getInstance").invoke(null)
                val manager = config.javaClass.getMethod("getExternalAdaptManager").invoke(config)
                if (manager.javaClass.getMethod("isCancelAdapt", Class::class.java)
                        .invoke(manager, HostRuntimeProbeActivity::class.java) != true) {
                    manager.javaClass.getMethod("addCancelAdaptOfActivity", Class::class.java)
                        .invoke(manager, HostRuntimeProbeActivity::class.java)
                }
                report("runtime_activity_instantiated host_process=${Application.getProcessName() == HostIdentity.PACKAGE}")
                HostRuntimeProbeActivity()
            } else {
                chain.proceed()
            }
        }
    }

    fun wrap(base: Context): Context = ProbeContext(base, applicationContext.resources)

    fun offerMedia(url: String) {
        mediaUrl = url
        playback.update { it.copy(ready = true) }
    }

    fun updatePlayback(playing: Boolean, positionMs: Long) {
        playback.update { it.copy(playing = playing, positionMs = positionMs) }
    }

    fun playbackIntent(context: Context, action: String): Intent =
        Intent(action).setClassName(context.packageName, SERVICE)

    private class ProbeContext(base: Context, private val moduleResources: Resources) : ContextWrapper(base) {
        private val moduleTheme by lazy {
            moduleResources.newTheme().apply { applyStyle(android.R.style.Theme_Material_Light_NoActionBar, true) }
        }
        override fun getResources(): Resources = moduleResources
        override fun getAssets(): AssetManager = moduleResources.assets
        override fun getClassLoader(): ClassLoader = HostRuntimeProbe::class.java.classLoader!!
        override fun getApplicationContext(): Context = HostRuntimeProbe.applicationContext
        override fun getTheme(): Resources.Theme = moduleTheme
        override fun setTheme(resid: Int) = moduleTheme.applyStyle(resid, true)
        override fun getFilesDir(): File = File(super.getFilesDir(), "meilox_parasite").apply { mkdirs() }
        override fun getCacheDir(): File = File(super.getCacheDir(), "meilox_parasite").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("meilox_parasite_$name", mode)
        override fun getSystemService(name: String): Any? = if (name == LAYOUT_INFLATER_SERVICE) {
            LayoutInflater.from(baseContext).cloneInContext(this)
        } else {
            super.getSystemService(name)
        }
    }
}
