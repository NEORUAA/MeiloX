package com.ljyh.mei.parasite

import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import java.io.IOException
import com.ljyh.mei.playback.PlaybackReportSink
import com.ljyh.mei.playback.PlaybackReportDetails
import com.ljyh.mei.data.session.SessionStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

internal interface HostPlaybackReportBackend {
    fun emit(action: String, fields: Map<String, Any>, owner: SessionStamp)
}

/** Business events only. The official SDK owns log metadata, encryption and upload. */
@Singleton
class HostPlaybackReportBridge @Inject constructor(override val sessions: SessionStore) : PlaybackReportSink {
    @Volatile private var backend: HostPlaybackReportBackend? = null

    @Synchronized
    internal fun bind(backend: HostPlaybackReportBackend) {
        check(this.backend == null) { "Official playback reporting is already bound" }
        this.backend = backend
    }

    override suspend fun submit(action: String, fields: Map<String, Any>, owner: SessionStamp, details: PlaybackReportDetails) {
        require(action == "startplay" || action == "play")
        requireOwner(owner)
        (backend ?: throw IOException("Official playback reporting is not ready")).emit(action, fields.toMap(), owner)
    }

    override fun requireOwner(owner: SessionStamp) {
        sessions.requireCurrent(owner)
        if (!owner.identity.authenticated || owner.identity.anonymous || sessions.recoveryRequired.value) {
            throw SessionChangedException()
        }
    }
}

/** The marker is removed at the host worker boundary, before either SDK serializes it. */
internal class PlaybackReportOwnership(
    private val bridge: HostPlaybackReportBridge,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Pending(val action: String, val owner: SessionStamp, val createdAt: Long)
    private val pending = ConcurrentHashMap<String, Pending>()
    private val invalidation = bridge.sessions.onInvalidated { revision ->
        pending.entries.removeAll { it.value.owner.generation < revision }
    }

    fun mark(action: String, fields: Map<String, Any>, owner: SessionStamp): Map<String, Any> {
        require(action in ACTIONS && MARKER !in fields)
        bridge.requireOwner(owner)
        val timestamp = now()
        pending.entries.removeAll { timestamp - it.value.createdAt >= TTL_MS }
        if (pending.size >= MAX_PENDING) {
            pending.entries.minByOrNull { it.value.createdAt }?.let { pending.remove(it.key, it.value) }
        }
        val token = UUID.randomUUID().toString()
        bridge.sessions.withCurrent(owner) {
            if (bridge.sessions.recoveryRequired.value) throw SessionChangedException()
            pending[token] = Pending(action, owner, timestamp)
        }
        return fields + (MARKER to token)
    }

    fun consume(action: String, fields: MutableMap<String, Any?>): SessionStamp? {
        val token = fields.remove(MARKER) as? String ?: return null
        val entry = pending.remove(token) ?: return null
        if (entry.action != action || now() - entry.createdAt >= TTL_MS) return null
        return entry.owner.takeIf { runCatching { bridge.requireOwner(it) }.isSuccess }
    }

    companion object {
        const val MARKER = "s_meilox_playback_owner"
        val ACTIONS = setOf("startplay", "play", "_plv", "_pld")
        private const val TTL_MS = 300_000L
        private const val MAX_PENDING = 256
    }
}
