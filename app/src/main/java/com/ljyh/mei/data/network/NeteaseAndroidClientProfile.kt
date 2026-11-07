package com.ljyh.mei.data.network

/** Pinned application identity required by the packaged native security SDK. */
internal object NeteaseAndroidClientProfile {
    const val OS = "android"
    const val APP_VERSION = "9.5.70"
    const val VERSION_CODE = 9_005_070L
    const val BUILD_VERSION = "260818213343"
    const val CHANNEL = "xiaomi"
    const val PACKAGE_TYPE = "release"
    const val USER_AGENT_PREFIX =
        "NeteaseMusic/$APP_VERSION.$BUILD_VERSION($VERSION_CODE);Dalvik/2.1.0"
}
