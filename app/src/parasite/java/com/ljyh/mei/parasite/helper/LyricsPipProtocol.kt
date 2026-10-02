package com.ljyh.mei.parasite.helper

internal object LyricsPipProtocol {
    const val DESCRIPTOR = "com.neoruaa.meilox.parasite.lyrics_pip.v1"
    const val ACTIVITY = "com.ljyh.mei.parasite.helper.LyricsPipActivity"
    const val ENDPOINT = "lyricsEndpoint"
    const val FRAME = android.os.IBinder.FIRST_CALL_TRANSACTION
    const val COMMAND = FRAME + 1
    const val CLOSE = FRAME + 2
}
