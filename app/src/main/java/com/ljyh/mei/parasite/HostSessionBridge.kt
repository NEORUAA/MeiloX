package com.ljyh.mei.parasite

import android.graphics.Bitmap
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class HostSessionIdentity(val userId: Long, val authenticated: Boolean, val anonymous: Boolean)
data class HostSessionStamp(val generation: Long, val identity: HostSessionIdentity)

class HostSessionChangedException : IOException("Official session changed")

/** Tracks public session identity and generations, never cookies or signing credentials. */
@Singleton
class HostSessionBridge @Inject constructor() {
    @Volatile private var reader: (() -> HostSessionIdentity)? = null
    @Volatile private var loginBackend: HostLoginBackend<Bitmap>? = null
    private val generation = AtomicLong()
    private val transitions = AtomicInteger()
    private val invalidationListeners = CopyOnWriteArrayList<(Long) -> Unit>()
    private val revisions = MutableStateFlow(-1L)
    val changes = revisions.asStateFlow()

    @Synchronized
    internal fun bind(reader: () -> HostSessionIdentity) {
        check(this.reader == null) { "Official session is already bound" }
        this.reader = reader
        revisions.update { maxOf(it, generation.get()) }
    }

    @Synchronized
    internal fun bindLogin(backend: HostLoginBackend<Bitmap>) {
        check(loginBackend == null) { "Official login is already bound" }
        loginBackend = backend
    }

    fun newLogin(): HostLoginController<Bitmap> = HostLoginController(
        open = { emit -> (loginBackend ?: throw IOException("Official login is not ready")).open(emit) },
        authenticated = { runCatching { snapshot().identity.authenticated }.getOrDefault(false) },
    )

    fun logout() {
        val backend = loginBackend ?: throw IOException("Official login is not ready")
        beginTransition().use { backend.logout() }
    }

    @Synchronized
    fun snapshot(): HostSessionStamp {
        val read = reader ?: throw IOException("Official session is not ready")
        repeat(3) {
            if (transitions.get() != 0) throw HostSessionChangedException()
            val before = generation.get()
            val identity = read()
            if (before == generation.get() && transitions.get() == 0) return HostSessionStamp(before, identity)
        }
        throw HostSessionChangedException()
    }

    fun requireCurrent(stamp: HostSessionStamp) {
        if (snapshot() != stamp) throw HostSessionChangedException()
    }

    /** Keep a short, non-suspending state publication atomic with session invalidation. */
    @Synchronized
    fun <T> withCurrent(stamp: HostSessionStamp, publish: () -> T): T {
        requireCurrent(stamp)
        return publish()
    }

    /** Also invalidates same-account reauthorization, which an ID comparison cannot detect. */
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
