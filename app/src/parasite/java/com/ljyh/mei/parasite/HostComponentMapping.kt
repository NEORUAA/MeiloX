package com.ljyh.mei.parasite

/** Explicit carriers and startup entries in the identity-pinned host only. */
internal object HostComponentMapping {
    const val ACTIVITY = "com.netease.cloudmusic.tv.test.TextMainActivity"
    const val LAUNCHER = "com.netease.cloudmusic.app.LoadingActivity"
    const val HOME = "com.netease.cloudmusic.tv.activity.MainActivity"
    const val CALENDAR = "com.netease.cloudmusic.tv.commentcalender.CommentCalenderActivity"
    const val SERVICE = "com.netease.cloudmusic.service.LocalMusicMatchService"
    const val MODULE_ACTIVITY = "com.ljyh.mei.MainActivity"
    const val MODULE_SERVICE = "com.ljyh.mei.playback.MusicService"
    const val HOST_REPORT_FRAGMENT = "androidx.lifecycle.ReportFragment"

    fun ownsModuleActivity(packageName: String?, actual: Class<*>, expected: Class<*>): Boolean =
        packageName == HostIdentity.PACKAGE && actual === expected

    fun restoresLegacyReportFragment(
        packageName: String?, actual: Class<*>, expected: Class<*>, fragmentName: String?,
    ): Boolean = ownsModuleActivity(packageName, actual, expected) && fragmentName == HOST_REPORT_FRAGMENT

    fun target(packageName: String?, className: String?): String? {
        if (packageName != HostIdentity.PACKAGE) return null
        return when (className) {
            MODULE_ACTIVITY -> LAUNCHER
            MODULE_SERVICE -> SERVICE
            else -> null
        }
    }

    fun replacesActivity(className: String?, appEnabled: Boolean): Boolean =
        className == ACTIVITY || (appEnabled && className in setOf(LAUNCHER, HOME, CALENDAR))

    fun usesModuleResource(packageName: String, resourceId: Int): Boolean =
        packageName == HostIdentity.PACKAGE && resourceId ushr 24 == 0x7f
}
