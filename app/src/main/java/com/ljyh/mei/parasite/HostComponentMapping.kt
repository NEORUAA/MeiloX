package com.ljyh.mei.parasite

/** Only module-owned explicit components may be routed through the qualified host. */
internal object HostComponentMapping {
    const val ACTIVITY = "com.netease.cloudmusic.tv.test.TextMainActivity"
    const val SERVICE = "com.netease.cloudmusic.service.LocalMusicMatchService"
    const val MODULE_ACTIVITY = "com.ljyh.mei.MainActivity"
    const val MODULE_SERVICE = "com.ljyh.mei.playback.MusicService"

    fun target(packageName: String?, className: String?): String? {
        if (packageName != HostIdentity.PACKAGE) return null
        return when (className) {
            MODULE_ACTIVITY -> ACTIVITY
            MODULE_SERVICE -> SERVICE
            else -> null
        }
    }

    fun usesModuleResource(packageName: String, resourceId: Int): Boolean =
        packageName == HostIdentity.PACKAGE && resourceId ushr 24 == 0x7f
}
