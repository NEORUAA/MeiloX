package com.ljyh.mei.data.network.netease

/** Existing Android API compatibility profile used by the NetEase transport in this app. */
internal data class NcblClientProfile(
    val os: String,
    val appVersion: String,
    val versionCode: String,
    val channel: String,
    val ursAppId: String,
) {
    companion object {
        val Android = NcblClientProfile(
            os = "android",
            appVersion = "8.20.20.231215173437",
            versionCode = "6006066",
            channel = "xiaomi",
            ursAppId = "F2219AE9D7828A7D73E2006D000C61031D196A37DB497E3885B8298504867886B6F0E44087D61EFC06BE92279CD6EEC6",
        )
    }
}

internal data class NcblSongInfo(
    val id: Long,
    val name: String,
    val artist: String,
    val durationMs: Long?,
)

internal data class NcblDeviceInfo(
    val deviceId: String,
    val osVersion: String,
    val model: String,
    val brand: String,
    val processName: String,
    val buildType: String,
    val pid: Int,
    val buildId: String,
)

internal class NcblCredentials(
    internal val musicU: String,
    internal val deviceId: String,
) {
    override fun toString(): String = "NcblCredentials"
}

/** One immutable session snapshot. Its credentials are deliberately absent from toString(). */
internal class NcblSessionContext(
    internal val credentials: NcblCredentials,
    internal val device: NcblDeviceInfo,
    internal val profile: NcblClientProfile,
    internal val song: NcblSongInfo,
    internal val source: String,
    internal val sourceId: String,
    internal val startedAtMs: Long,
    internal val buildVersion: String,
    internal val sessionId: String,
) {
    internal val startLogTimeSeconds: Long = startedAtMs / 1_000L

    override fun toString(): String = "NcblSessionContext"
}
