package com.ljyh.mei.parasite

import android.content.Context
import android.content.Intent
import io.github.libxposed.api.XposedModule

/** Retires only the verified host's isolated legacy media control path. */
internal object HostPlaybackHooks {
    const val RECEIVER = "com.netease.cloudmusic.receiver.MediaButtonEventReceiver"
    private val installed = mutableSetOf<Class<*>>()

    @Synchronized
    fun install(module: XposedModule, loader: ClassLoader, report: (String) -> Unit) {
        val compat = loader.loadClass("android.support.v4.media.session.MediaSessionCompat")
        check(compat.classLoader !== HostPlaybackHooks::class.java.classLoader) {
            "Official media session is not isolated"
        }
        if (compat in installed) return
        val setActive = compat.getMethod("setActive", Boolean::class.javaPrimitiveType)
        val receive = loader.loadClass(RECEIVER).getMethod("onReceive", Context::class.java, Intent::class.java)
        module.hook(setActive).intercept { chain ->
            // Keep the host wrapper and platform state consistent; do not skip initialization.
            chain.proceed(arrayOf(false))
        }
        module.hook(receive).intercept { chain ->
            HostMediaButtons.receive(chain.getArg(0) as Context, chain.getArg(1) as? Intent, report)
            null
        }
        installed += compat
        report("official_media_controls_retired isolated=true")
    }
}
