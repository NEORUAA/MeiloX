package com.ljyh.mei.playback

import com.ljyh.mei.data.session.SessionStore
import java.io.IOException

/** Local queue metadata survives recovery; automatic source acquisition does not. */
internal class PlaybackRestorePolicy(private val sessions: SessionStore) {
    private val owner = try {
        sessions.snapshot().takeUnless { sessions.recoveryRequired.value }
    } catch (_: IOException) {
        null
    }
    @Volatile private var invalidated = false

    fun invalidate() { invalidated = true }

    fun withCurrentAuthorization(prepare: () -> Unit): Boolean {
        val captured = owner ?: return false
        return try {
            sessions.withCurrent(captured) {
                if (invalidated || sessions.recoveryRequired.value) false
                else {
                    prepare()
                    true
                }
            }
        } catch (_: IOException) {
            false
        }
    }
}
