package com.ljyh.mei.parasite

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.util.Util
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.playback.MusicService
import io.github.libxposed.api.XposedModule

/** App-shell components without changes to installed package metadata. */
internal object HostAppComponentHooks {
    fun route(intent: Intent): Intent {
        if (!BuildConfig.PARASITE_APP_ENABLED) return intent
        val foreground = HostWorkForeground.route(intent)
        if (foreground !== intent) return foreground
        val component = intent.component ?: return intent
        val target = HostComponentMapping.target(component.packageName, component.className) ?: return intent
        return Intent(intent).setClassName(component.packageName, target).apply {
            if (target == HostComponentMapping.LAUNCHER) {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
    }

    fun install(module: XposedModule, context: Context, report: (String) -> Unit) {
        // Media3 also constructs PendingIntents internally, outside our Context wrapper.
        PendingIntent::class.java.declaredMethods.filter {
            it.name in setOf("getActivity", "getService", "getForegroundService") &&
                it.parameterTypes.getOrNull(2) == Intent::class.java
        }.forEach { method ->
            module.hook(method).intercept { chain ->
                val original = chain.getArg(2) as Intent
                val routed = route(original)
                if (routed === original) chain.proceed()
                else chain.proceed(chain.args.toTypedArray().also { it[2] = routed })
            }
        }
        // Only the module's isolated AndroidX classes are hooked. SystemUI must resolve
        // their icons from the installed module APK, not the host's resource table.
        module.hook(IconCompat::class.java.getMethod("createWithResource", Context::class.java, Int::class.javaPrimitiveType))
            .intercept { chain ->
                val context = chain.getArg(0) as Context
                val resourceId = chain.getArg(1) as Int
                if (HostComponentMapping.usesModuleResource(context.packageName, resourceId)) {
                    IconCompat.createWithResource(context.resources, BuildConfig.APPLICATION_ID, resourceId)
                } else chain.proceed()
            }
        module.hook(NotificationCompat.Builder::class.java.getMethod("setSmallIcon", Int::class.javaPrimitiveType))
            .intercept { chain ->
                val builder = chain.thisObject as NotificationCompat.Builder
                val resourceId = chain.getArg(0) as Int
                if (HostComponentMapping.usesModuleResource(builder.mContext.packageName, resourceId)) {
                    builder.setSmallIcon(IconCompat.createWithResource(builder.mContext.resources, BuildConfig.APPLICATION_ID, resourceId))
                } else chain.proceed()
            }
        module.hook(NotificationCompat.Action::class.java.getMethod("getIconCompat"))
            .intercept { chain ->
                val icon = chain.proceed() as IconCompat?
                if (icon?.type == IconCompat.TYPE_RESOURCE && icon.resPackage == BuildConfig.APPLICATION_ID) {
                    // Legacy SystemUI media actions rebind resource IDs to the posting package.
                    // Rasterize only module action icons; preserve the action and PendingIntent.
                    icon.loadDrawable(context)?.let { IconCompat.createWithBitmap(it.toBitmap()) } ?: icon
                } else icon
            }
        module.hook(Util::class.java.getMethod("setForegroundServiceNotification",
            Service::class.java, Int::class.javaPrimitiveType, Notification::class.java,
            Int::class.javaPrimitiveType, String::class.java))
            .intercept { chain ->
                val service = chain.getArg(0) as Service
                if (service is MusicService && service.packageName == HostIdentity.PACKAGE &&
                    service.applicationInfo.targetSdkVersion == 29) {
                    // The pinned TV manifest supports legacy untyped foreground services.
                    // Keep Android's permission checks; do not fabricate a service type.
                    service.startForeground(chain.getArg(1) as Int, chain.getArg(2) as Notification)
                    report("app_foreground_service legacy_manifest=true")
                    null
                } else chain.proceed()
            }
    }
}
