package com.ljyh.mei.parasite

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import com.ljyh.mei.BuildConfig
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class MeiloXModule : XposedModule() {
    private var eligibleProcess = false
    private val initialized = AtomicBoolean()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        eligibleProcess = !param.isSystemServer && param.processName == HostIdentity.PACKAGE &&
            apiVersion >= 102
        if (eligibleProcess) {
            report("loaded api=$apiVersion framework=$frameworkName version=$frameworkVersion")
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!eligibleProcess || !param.isFirstPackage || param.packageName != HostIdentity.PACKAGE) return
        // Bootstrap only; no host-specific entry points are used until identity verification passes.
        hook(Instrumentation::class.java.getMethod("callApplicationOnCreate", Application::class.java))
            .intercept { chain ->
                val result = chain.proceed()
                val application = chain.getArg(0) as Application
                if (application.packageName == HostIdentity.PACKAGE && initialized.compareAndSet(false, true)) {
                    try {
                        initialize(application, param.classLoader)
                    } catch (error: Throwable) {
                        report("initialization_failed type=${error.javaClass.name}")
                    }
                }
                result
            }
    }

    private fun initialize(application: Application, hostLoader: ClassLoader) {
        val info = application.packageManager.getPackageInfo(
            HostIdentity.PACKAGE,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
        val signers = info.signingInfo?.apkContentsSigners.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
        if (!HostIdentity.accepts(info.packageName, info.longVersionCode, info.versionName, signers)) {
            report("host_identity_rejected")
            return
        }
        val isolated = hostLoader !== javaClass.classLoader &&
            hostLoader.loadClass("kotlin.Unit") !== kotlin.Unit::class.java
        report("host_verified version=${info.versionName} isolated_kotlin=$isolated")
        if (!isolated) return
        HostRetrofitCompatibility.install(this, ::report)
        if (BuildConfig.PARASITE_RUNTIME_PROBE) {
            HostRuntimeProbe.install(this, application, moduleApplicationInfo, ::report)
        }
        if (BuildConfig.PARASITE_HOST_PROBE) {
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (activity.javaClass.name != "com.netease.cloudmusic.tv.activity.MainActivity") return
                    application.unregisterActivityLifecycleCallbacks(this)
                    // Tinker can replace the package loader during Application.attachBaseContext.
                    val runtimeLoader = activity.javaClass.classLoader ?: return
                    report("runtime_loader_changed=${runtimeLoader !== hostLoader}")
                    val requests = if (BuildConfig.PARASITE_RUNTIME_PROBE) {
                        com.ljyh.mei.di.AppGraph.component.hostRequests()
                    } else HostRequestBridge(HostSessionBridge())
                    try {
                        val backend = TvHostRequestBackend(runtimeLoader, ::report)
                        backend.installHooks(this@MeiloXModule, requests.sessions)
                        requests.bind(backend)
                        report("request_bridge_bound")
                    } catch (error: Throwable) {
                        report("request_bridge_failed type=${error.javaClass.name}")
                        return
                    }
                    Thread({
                        HostCapabilityProbe(requests, ::report) { url ->
                            if (BuildConfig.PARASITE_RUNTIME_PROBE) HostRuntimeProbe.offerMedia(url)
                        }.run()
                        if (BuildConfig.PARASITE_RUNTIME_PROBE) {
                            HostRetrofitProbe(com.ljyh.mei.di.AppGraph.component, ::report).run()
                        }
                    }, "MeiloX-host-probe").start()
                }
                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            })
        }
    }

    private fun report(message: String) = log(Log.INFO, "MeiloXParasite", message)
}
