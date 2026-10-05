package com.ljyh.mei.data.network

/** Shared wire identity for mobile login, authenticated EAPI, and client logs. */
internal object NeteaseAndroidClientProfile {
    const val OS = "android"
    const val APP_VERSION = "9.5.70"
    const val VERSION_CODE = 9_005_070L
    const val BUILD_VERSION = "260818213343"
    const val CHANNEL = "xiaomi"
    const val PACKAGE_TYPE = "release"
    const val USER_AGENT_PREFIX =
        "NeteaseMusic/$APP_VERSION.$BUILD_VERSION($VERSION_CODE);Dalvik/2.1.0"

    fun userAgent(osVersion: String, model: String, buildId: String): String =
        "$USER_AGENT_PREFIX (Linux; U; Android $osVersion; $model Build/$buildId)"

    fun eapiConfig(
        osVersion: String,
        model: String,
        buildId: String,
        resolution: String,
    ): Map<String, String> = mapOf(
        "os" to OS,
        "osver" to osVersion,
        "appver" to APP_VERSION,
        "channel" to CHANNEL,
        "versioncode" to VERSION_CODE.toString(),
        "mobilename" to model.replace(" ", "+"),
        "buildver" to BUILD_VERSION,
        "resolution" to resolution,
        "ua" to userAgent(osVersion, model, buildId),
    )
}
