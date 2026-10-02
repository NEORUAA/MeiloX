package com.ljyh.mei.parasite.helper

internal object MicrophoneProtocol {
    const val DESCRIPTOR = "com.neoruaa.meilox.parasite.microphone.v1"
    const val MODULE = "com.neoruaa.meilox.parasite"
    const val ACTIVITY = "com.ljyh.mei.parasite.helper.MicrophonePermissionActivity"
    const val SERVICE = "com.ljyh.mei.parasite.helper.MicrophoneCaptureService"
    const val TOKEN = "captureToken"
    const val RECORD = android.os.IBinder.FIRST_CALL_TRANSACTION
    const val CLOSE = RECORD + 1
    const val MAX_SECONDS = 15
    const val SAMPLE_RATE = 8_000
}
