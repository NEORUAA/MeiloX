package com.ljyh.mei.parasite

internal object HostIdentity {
    const val PACKAGE = "com.netease.cloudmusic.tv"
    const val VERSION_CODE = 1001080L
    const val VERSION_NAME = "1.1.80"
    const val SIGNER_SHA256 = "54254d2be09daef48dedc2b4a4f497d153e14ed9d70814fc9c360ee9240827f7"

    fun accepts(
        packageName: String,
        versionCode: Long,
        versionName: String?,
        currentSigners: List<String>,
    ): Boolean = packageName == PACKAGE && versionCode == VERSION_CODE &&
        versionName == VERSION_NAME && currentSigners == listOf(SIGNER_SHA256)
}
