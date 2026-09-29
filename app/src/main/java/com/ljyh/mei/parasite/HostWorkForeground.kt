package com.ljyh.mei.parasite

import android.app.Notification
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.work.impl.foreground.SystemForegroundService
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.playback.DownloadNotifications
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import java.util.WeakHashMap

/** Keeps the original IPC service and Binder; only owned start commands reach AndroidX. */
internal object HostWorkForeground {
    private class Delegate(val service: HostWorkForegroundService, var created: Boolean = false)
    private val delegates = WeakHashMap<Service, Delegate>()

    fun route(intent: Intent): Intent {
        val component = intent.component ?: return intent
        if (component.packageName != HostIdentity.PACKAGE || component.className != HostWorkForegroundPolicy.MODULE_SERVICE) return intent
        require(valid(intent)) { "Invalid module foreground command" }
        return Intent(intent).setClassName(HostIdentity.PACKAGE, HostWorkForegroundPolicy.CARRIER)
            .putExtra(HostWorkForegroundPolicy.MARKER, HostWorkForegroundPolicy.VERSION)
    }

    internal fun owns(intent: Intent?): Boolean = intent != null &&
        intent.component == ComponentName(HostIdentity.PACKAGE, HostWorkForegroundPolicy.CARRIER) &&
        intent.getStringExtra(HostWorkForegroundPolicy.MARKER) == HostWorkForegroundPolicy.VERSION && valid(intent)

    private fun valid(intent: Intent): Boolean = HostWorkForegroundPolicy.valid(
        intent.action, intent.getStringExtra(HostWorkForegroundPolicy.WORK_ID),
        intent.getIntExtra(HostWorkForegroundPolicy.GENERATION, -1),
        intent.getIntExtra(HostWorkForegroundPolicy.NOTIFICATION_ID, 0),
        intent.getParcelableExtra(HostWorkForegroundPolicy.NOTIFICATION, Notification::class.java) != null,
        intent.getIntExtra(HostWorkForegroundPolicy.NOTIFICATION_TYPE, 0),
    )

    fun install(module: XposedModule, context: ModuleContext, hostLoader: ClassLoader, report: (String) -> Unit) {
        val carrier = ComponentName(HostIdentity.PACKAGE, HostWorkForegroundPolicy.CARRIER)
        val info = context.packageManager.getServiceInfo(carrier, PackageManager.ComponentInfoFlags.of(0))
        check(info.enabled && !info.exported && info.processName == HostIdentity.PACKAGE &&
            info.applicationInfo.targetSdkVersion == 29 && info.foregroundServiceType == 0)
        val original = hostLoader.loadClass(HostWorkForegroundPolicy.CARRIER)
        check(original.superclass == Service::class.java && original.declaredMethods.none {
            it.name in setOf("onCreate", "onStartCommand", "onDestroy", "onTimeout")
        }) { "Official foreground carrier lifecycle changed" }
        val hooks = mutableListOf<HookHandle>()
        try {
            val attach = Service::class.java.declaredMethods.single { it.name == "attach" && it.parameterCount == 6 }
                .apply { isAccessible = true }
            hooks += module.hook(attach).intercept { chain ->
                val result = chain.proceed()
                val service = chain.thisObject as Service
                if (service.javaClass == original) {
                    val delegate = HostWorkForegroundService(report)
                    attach.invoke(delegate, *chain.args.toTypedArray())
                    delegates[service] = Delegate(delegate)
                    report("work_foreground_attached original_preserved=true")
                }
                result
            }
            hooks += module.hook(Service::class.java.getMethod("onStartCommand", Intent::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)).intercept { chain ->
                val delegate = delegates[chain.thisObject as Service]
                val intent = chain.getArg(0) as? Intent
                if (delegate == null || !owns(intent)) return@intercept chain.proceed()
                if (!delegate.created) {
                    delegate.service.onCreate()
                    delegate.created = true
                }
                report("work_foreground_command action=${intent!!.action}")
                delegate.service.onStartCommand(intent, chain.getArg(1) as Int, chain.getArg(2) as Int)
            }
            hooks += module.hook(Service::class.java.getMethod("onDestroy")).intercept { chain ->
                try { chain.proceed() }
                finally {
                    delegates.remove(chain.thisObject as Service)?.let { delegate ->
                        if (delegate.created) delegate.service.onDestroy()
                        report("work_foreground_destroyed original_preserved=true")
                    }
                }
            }
            Service::class.java.declaredMethods.filter { it.name == "onTimeout" }.forEach { method ->
                hooks += module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    delegates[chain.thisObject as Service]?.takeIf { it.created }?.let { delegate ->
                        if (method.parameterCount == 2) delegate.service.onTimeout(chain.getArg(0) as Int, chain.getArg(1) as Int)
                        else delegate.service.onTimeout(chain.getArg(0) as Int)
                    }
                    result
                }
            }
        } catch (error: Throwable) {
            hooks.asReversed().forEach { runCatching { it.unhook() } }
            throw error
        }
        report("work_foreground_hooks_ready original_binder_untouched=true")
    }
}

internal class HostWorkForegroundService(private val report: (String) -> Unit) : SystemForegroundService() {
    private var foregroundId = 0
    override fun attachBaseContext(base: Context) = super.attachBaseContext(HostRuntimeProbe.wrap(base))

    private fun downloadNotifications(id: Int): DownloadNotifications? = when {
        id == DownloadNotifications.PROGRESS_ID -> DownloadNotifications.production
        BuildConfig.PARASITE_WORK_PROBE && id == DownloadNotifications.PROGRESS_ID - 1 -> DownloadNotifications.qualification
        else -> null
    }

    override fun startForeground(notificationId: Int, notificationType: Int, notification: Notification) {
        check(packageName == HostIdentity.PACKAGE && applicationInfo.targetSdkVersion == 29 && notificationType == 0)
        check(notificationId in HostWorkForegroundPolicy.MIN_NOTIFICATION_ID..HostWorkForegroundPolicy.MAX_NOTIFICATION_ID)
        // The pinned manifest has no typed foreground declaration; retain platform permission checks.
        startForeground(notificationId, downloadNotifications(notificationId)?.current(this, notification) ?: notification)
        if (foregroundId != notificationId) report("work_foreground_promoted legacy_manifest=true")
        foregroundId = notificationId
    }

    override fun notify(notificationId: Int, notification: Notification) {
        super.notify(notificationId, downloadNotifications(notificationId)?.current(this, notification) ?: notification)
    }

    override fun cancelNotification(notificationId: Int) {
        downloadNotifications(notificationId)?.let { notices ->
            // AndroidX cancels the promoted notification as well as the completed work's one.
            // Shared download progress is owned by the batch, never an individual callback.
            if (notices.refreshProgress(this)) return
            if (foregroundId != notificationId) notices.publishCompletion(this)
        }
        super.cancelNotification(notificationId)
    }

    override fun stop(startId: Int) {
        super.stop(startId)
        foregroundId = 0
        DownloadNotifications.production.publishCompletion(this)
        if (BuildConfig.PARASITE_WORK_PROBE) DownloadNotifications.qualification.publishCompletion(this)
    }
}
