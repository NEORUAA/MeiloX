package com.ljyh.mei.utils

import android.os.Build

/** ROM markers used by the reference client's Flyme detector, independent of the phone brand. */
internal object FlymeSystem {
    val isFlyme: Boolean by lazy {
        isFlymeRom(
            Build.DISPLAY,
            systemProperty("ro.flyme.published"),
            systemProperty("ro.meizu.setupwizard.flyme"),
        )
    }

    private fun systemProperty(name: String): String = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, name) as? String ?: ""
    } catch (_: Exception) {
        ""
    }
}

internal fun isFlymeRom(display: String, published: String, setupWizard: String): Boolean =
    display.trim().startsWith("flyme", ignoreCase = true) ||
        published.isNotBlank() || setupWizard.isNotBlank()
