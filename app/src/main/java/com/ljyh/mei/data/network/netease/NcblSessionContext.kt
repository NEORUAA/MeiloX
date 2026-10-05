package com.ljyh.mei.data.network.netease

internal data class NcblSongInfo(
    val id: Long,
    val name: String,
    val artist: String,
    val durationMs: Long?,
)

internal data class NcblDeviceInfo(
    val deviceId: String,
    val sDeviceId: String,
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
    internal val ursAppId: String,
    internal val musicA: String = "",
    internal val csrf: String = "",
) {
    override fun toString(): String = "NcblCredentials"
}

/** One immutable session snapshot. Its credentials are deliberately absent from toString(). */
internal class NcblSessionContext(
    internal val credentials: NcblCredentials,
    internal val device: NcblDeviceInfo,
    internal val song: NcblSongInfo,
    internal val source: String,
    internal val sourceId: String,
    internal val startedAtMs: Long,
    internal val sessionId: String,
) {
    internal val startLogTimeSeconds: Long = startedAtMs / 1_000L

    override fun toString(): String = "NcblSessionContext"
}
