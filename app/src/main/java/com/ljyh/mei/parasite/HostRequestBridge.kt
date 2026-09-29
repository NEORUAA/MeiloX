package com.ljyh.mei.parasite

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

interface HostRequestBackend {
    fun sessionIdentity(): HostSessionIdentity
    fun open(path: String, parameters: Map<String, String>): HostPendingRequest
}

interface HostPendingRequest : Closeable {
    fun execute(): String
    fun cancel()
}

data class HostResponse(val body: String, val session: HostSessionStamp)

/** Owns request lifetime only. Authentication, signing, and wire I/O remain in the host. */
@Singleton
class HostRequestBridge @Inject constructor(val sessions: HostSessionBridge) {
    @Volatile private var backend: HostRequestBackend? = null
    private val active = ConcurrentHashMap.newKeySet<Call>()
    val isBound: Boolean get() = backend != null

    init {
        sessions.onInvalidated { revision -> active.forEach { it.invalidateBefore(revision) } }
    }

    @Synchronized
    fun bind(backend: HostRequestBackend) {
        check(this.backend == null) { "Official request transport is already bound" }
        sessions.bind(backend::sessionIdentity)
        this.backend = backend
    }

    fun newCall(path: String, parameters: Map<String, String> = emptyMap()): Call {
        require(PATH.matches(path) && path.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "Expected a relative official business path"
        }
        require(parameters.keys.none { it.lowercase() in AUTH_PARAMETERS }) { "Authentication parameters belong to the host" }
        val transport = backend ?: throw IOException("Official request transport is not ready")
        return Call(transport, path, parameters.toMap(), sessions.snapshot())
    }

    inner class Call internal constructor(
        private val transport: HostRequestBackend,
        private val path: String,
        private val parameters: Map<String, String>,
        private val stamp: HostSessionStamp,
    ) {
        private val executed = AtomicBoolean()
        private val canceled = AtomicBoolean()
        private val pending = AtomicReference<HostPendingRequest?>()
        val isCanceled: Boolean get() = canceled.get()
        val isExecuted: Boolean get() = executed.get()
        internal val isRunning: Boolean get() = pending.get() != null

        fun execute(): HostResponse {
            check(executed.compareAndSet(false, true)) { "Request has already been executed" }
            active += this
            try {
                requireCurrent()
                transport.open(path, parameters).use { request ->
                    pending.set(request)
                    if (isCanceled) request.cancel()
                    requireCurrent()
                    val body = request.execute()
                    requireCurrent()
                    return HostResponse(body, stamp)
                }
            } finally {
                pending.set(null)
                active -= this
            }
        }

        fun cancel() {
            canceled.set(true)
            pending.get()?.let { runCatching { it.cancel() } }
        }

        fun requireCurrent() {
            if (isCanceled) throw IOException("Official request canceled")
            sessions.requireCurrent(stamp)
        }

        internal fun invalidateBefore(revision: Long) {
            if (stamp.generation < revision) cancel()
        }

        // A retry must not silently change the account that owns the original request.
        fun copy(): Call = Call(transport, path, parameters, stamp)
    }

    companion object {
        private val PATH = Regex("[A-Za-z0-9][A-Za-z0-9/_.-]*")
        private val AUTH_PARAMETERS = setOf("cookie", "music_u", "music_a", "__csrf", "csrf_token", "authorization", "header")
    }
}
