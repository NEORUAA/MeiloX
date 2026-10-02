package com.ljyh.mei.parasite.helper

/** One-use authorization issued only by the verified permission activity. */
internal class MicrophoneGrants(private val now: () -> Long) {
    private data class Grant(val uid: Int, val expires: Long)
    private val grants = mutableMapOf<String, Grant>()

    @Synchronized fun authorize(token: String, uid: Int) {
        require(java.util.UUID.fromString(token).toString() == token)
        grants.entries.removeAll { it.value.expires <= now() }
        grants[token] = Grant(uid, now() + 20_000)
    }

    @Synchronized fun consume(token: String): Int? {
        val grant = grants.remove(token) ?: return null
        return grant.uid.takeIf { grant.expires > now() }
    }

    companion object {
        val instance = MicrophoneGrants(android.os.SystemClock::elapsedRealtime)
    }
}
