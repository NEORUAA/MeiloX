package com.ljyh.mei.parasite

import android.app.Application
import android.app.AppComponentFactory
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.MainActivity
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.playback.MusicService
import io.github.libxposed.api.XposedModule
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ProbeMedia(val url: String, val owner: SessionStamp)

internal data class ProbePlaybackState(val media: ProbeMedia? = null, val playing: Boolean = false, val positionMs: Long = 0) {
    val ready: Boolean get() = media != null
}

/** Diagnostic URLs remain owned by the session that resolved them. */
internal class ProbeMediaOwnership(private val sessions: SessionStore, scope: CoroutineScope) : Closeable {
    private val playback = MutableStateFlow(ProbePlaybackState())
    val playbackState = playback.asStateFlow()
    private val invalidation = sessions.onInvalidated { revision ->
        playback.update { state ->
            if (state.media?.owner?.generation?.let { it < revision } == true) ProbePlaybackState() else state
        }
    }
    private val recovery = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        sessions.recoveryRequired.collect { required ->
            if (required) playback.value.media?.let(::retire)
        }
    }

    fun offer(url: String, owner: SessionStamp): Boolean = runCatching {
        sessions.withCurrent(owner) {
            requireUsable(owner)
            playback.value = ProbePlaybackState(media = ProbeMedia(url, owner))
            true
        }
    }.getOrElse { playback.value.media?.let(::isCurrent); false }

    fun withMedia(action: (ProbeMedia) -> Unit): Boolean {
        val media = playback.value.media ?: return false
        return runCatching {
            sessions.withCurrent(media.owner) {
                requireUsable(media.owner)
                if (playback.value.media != media) false else {
                    action(media)
                    true
                }
            }
        }.getOrElse { retire(media); false }
    }

    fun isCurrent(media: ProbeMedia): Boolean = runCatching {
        sessions.requireCurrent(media.owner)
        requireUsable(media.owner)
        playback.value.media == media
    }.getOrDefault(false).also { if (!it) retire(media) }

    fun update(media: ProbeMedia?, playing: Boolean, positionMs: Long) {
        if (media == null) return
        runCatching {
            sessions.withCurrent(media.owner) {
                requireUsable(media.owner)
                playback.update { state ->
                    if (state.media == media) state.copy(playing = playing, positionMs = positionMs)
                    else state
                }
            }
        }.onFailure { retire(media) }
    }

    private fun requireUsable(owner: SessionStamp) {
        if (!owner.identity.authenticated || owner.identity.anonymous || sessions.recoveryRequired.value) {
            throw SessionChangedException()
        }
    }

    private fun retire(media: ProbeMedia) {
        playback.update { if (it.media == media) ProbePlaybackState() else it }
    }

    override fun close() {
        invalidation.close()
        recovery.cancel()
        playback.value = ProbePlaybackState()
    }
}

/** Installs the app shell, or the explicitly requested isolated runtime probe. */
internal object HostRuntimeProbe {
    const val ACTIVITY = HostComponentMapping.ACTIVITY
    const val SERVICE = HostComponentMapping.SERVICE
    private lateinit var mediaOwnership: ProbeMediaOwnership
    val playbackState get() = mediaOwnership.playbackState
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
        if (BuildConfig.PARASITE_RUNTIME_PROBE) {
            mediaOwnership = ProbeMediaOwnership(com.ljyh.mei.di.AppGraph.component.sessions(),
                CoroutineScope(SupervisorJob() + Dispatchers.Default))
        }
        if (BuildConfig.PARASITE_APP_ENABLED) HostAppComponentHooks.install(module, applicationContext, report)
        if (BuildConfig.PARASITE_APP_ENABLED) {
            HostWorkManager.install(module, applicationContext, report)
            HostWorkForeground.install(module, applicationContext, application.classLoader, report)
        }
        if (BuildConfig.PARASITE_RUNTIME_PROBE) {
            Thread({ ModuleStorageProbe.run(applicationContext, application, report) }, "MeiloX-storage-probe").start()
        }
        module.hook(AppComponentFactory::class.java.getMethod("instantiateService", ClassLoader::class.java, String::class.java, Intent::class.java))
            .intercept { chain ->
                val loader = chain.getArg(0) as ClassLoader
                if (BuildConfig.PARASITE_APP_ENABLED) HostPlaybackHooks.install(module, loader, report)
                if (BuildConfig.PARASITE_APP_ENABLED && chain.getArg(1) in setOf(
                        HostWorkPolicy.CARRIER, HostWorkForegroundPolicy.CARRIER)) onHostActivity(loader)
                // ActivityThread supplies no start Intent until after service creation.
                if (chain.getArg(1) == SERVICE) {
                    onHostActivity(loader)
                    report("runtime_service_instantiated")
                    if (BuildConfig.PARASITE_APP_ENABLED) MusicService() else HostRuntimeProbeService()
                } else chain.proceed()
            }
        if (BuildConfig.PARASITE_APP_ENABLED) {
            module.hook(AppComponentFactory::class.java.getMethod("instantiateReceiver", ClassLoader::class.java, String::class.java, Intent::class.java))
                .intercept { chain ->
                    HostPlaybackHooks.install(module, chain.getArg(0) as ClassLoader, report)
                    if (BuildConfig.PARASITE_WORK_PROBE && chain.getArg(1) == HostWorkProbeReceiver.CARRIER &&
                        (chain.getArg(2) as? Intent)?.action == HostWorkProbeReceiver.ACTION) {
                        onHostActivity(chain.getArg(0) as ClassLoader)
                        return@intercept HostWorkProbeReceiver()
                    }
                    chain.proceed()
                }
        }
        module.hook(Instrumentation::class.java.getMethod(
            "newActivity", ClassLoader::class.java, String::class.java, Intent::class.java,
        )).intercept { chain ->
            val className = chain.getArg(1) as String
            if (HostComponentMapping.replacesActivity(className, BuildConfig.PARASITE_APP_ENABLED)) {
                val loader = chain.getArg(0) as ClassLoader
                if (BuildConfig.PARASITE_APP_ENABLED) HostPlaybackHooks.install(module, loader, report)
                onHostActivity(loader)
                val config = loader.loadClass("me.jessyan.autosize.AutoSizeConfig")
                    .getMethod("getInstance").invoke(null)
                val manager = config.javaClass.getMethod("getExternalAdaptManager").invoke(config)
                val activityClass = if (BuildConfig.PARASITE_APP_ENABLED) MainActivity::class.java else HostRuntimeProbeActivity::class.java
                if (manager.javaClass.getMethod("isCancelAdapt", Class::class.java)
                        .invoke(manager, activityClass) != true) {
                    manager.javaClass.getMethod("addCancelAdaptOfActivity", Class::class.java)
                        .invoke(manager, activityClass)
                }
                report("runtime_activity_instantiated host_process=${Application.getProcessName() == HostIdentity.PACKAGE} entry=$className")
                if (BuildConfig.PARASITE_APP_ENABLED) MainActivity() else HostRuntimeProbeActivity()
            } else {
                chain.proceed()
            }
        }
    }

    fun wrap(base: Context): Context = applicationContext.wrap(base)

    fun offerMedia(url: String, owner: SessionStamp): Boolean = mediaOwnership.offer(url, owner)

    fun withCurrentMedia(action: (ProbeMedia) -> Unit): Boolean = mediaOwnership.withMedia(action)

    fun isMediaCurrent(media: ProbeMedia): Boolean = mediaOwnership.isCurrent(media)

    fun updatePlayback(media: ProbeMedia?, playing: Boolean, positionMs: Long) =
        mediaOwnership.update(media, playing, positionMs)

    fun playbackIntent(context: Context, action: String): Intent =
        Intent(action).setClassName(context.packageName, SERVICE)

}
