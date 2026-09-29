package com.ljyh.mei.data.session

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SessionIdentity(val userId: Long, val authenticated: Boolean, val anonymous: Boolean)
data class SessionStamp(val generation: Long, val identity: SessionIdentity)

class SessionChangedException : IOException("Account session changed")

/** Public identity and request generations only; each backend owns its credentials. */
open class SessionStore {
    @Volatile private var reader: (() -> SessionIdentity)? = null
    private val generation = AtomicLong()
    private val transitions = AtomicInteger()
    private val invalidationListeners = CopyOnWriteArrayList<(Long) -> Unit>()
    private val revisions = MutableStateFlow(-1L)
    val changes = revisions.asStateFlow()
    private val recovery = MutableStateFlow(false)
    val recoveryRequired = recovery.asStateFlow()

    internal fun setRecoveryRequired(required: Boolean) { recovery.value = required }

    @Synchronized
    internal fun bind(reader: () -> SessionIdentity) {
        check(this.reader == null) { "Account session is already bound" }
        this.reader = reader
        revisions.update { maxOf(it, generation.get()) }
    }

    fun snapshot(): SessionStamp {
        val read = reader ?: throw IOException("Account session is not ready")
        repeat(3) {
            if (transitions.get() != 0) throw SessionChangedException()
            val before = generation.get()
            val identity = read()
            if (before == generation.get() && transitions.get() == 0) return SessionStamp(before, identity)
        }
        throw SessionChangedException()
    }

    fun requireCurrent(stamp: SessionStamp) {
        if (snapshot() != stamp) throw SessionChangedException()
    }

    /** Keep a short, non-suspending state publication atomic with session invalidation. */
    fun <T> withCurrent(stamp: SessionStamp, publish: () -> T): T {
        // Credential readers may take backend locks. Do not invoke them under our monitor.
        requireCurrent(stamp)
        return synchronized(this) {
            if (generation.get() != stamp.generation || transitions.get() != 0) throw SessionChangedException()
            publish()
        }
    }

    /** Same-account reauthorization must also invalidate outstanding requests. */
    fun invalidate() {
        val revision = synchronized(this) { generation.incrementAndGet() }
        publish(revision)
    }

    fun beginTransition(): Closeable {
        val started = synchronized(this) {
            transitions.incrementAndGet()
            generation.incrementAndGet()
        }
        publish(started)
        val closed = AtomicBoolean()
        return Closeable {
            if (closed.compareAndSet(false, true)) {
                val revision = synchronized(this) {
                    transitions.decrementAndGet()
                    generation.incrementAndGet()
                }
                publish(revision)
            }
        }
    }

    internal fun onInvalidated(listener: (Long) -> Unit): Closeable {
        invalidationListeners += listener
        return Closeable { invalidationListeners -= listener }
    }

    private fun publish(revision: Long) {
        invalidationListeners.forEach { listener -> runCatching { listener(revision) } }
        revisions.update { maxOf(it, revision) }
    }
}
