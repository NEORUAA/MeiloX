package com.ljyh.mei.source.update

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/** An already reserved installation finishes before a new service starts restoring its queue. */
@Singleton
class SourceUpdatePlaybackGuard @Inject constructor() {
    private val lock = Any()
    private var restoring = false
    private var protectedSources = emptySet<String>()
    private val resolving = mutableMapOf<String, Int>()
    private var installing: CompletableDeferred<Unit>? = null

    fun beginRestore(): Deferred<Unit> = synchronized(lock) {
        restoring = true
        installing ?: CompletableDeferred(Unit)
    }

    fun updateSources(sources: Set<String>) = synchronized(lock) { protectedSources = sources.toSet() }

    fun finishRestore(sources: Set<String>) = synchronized(lock) {
        protectedSources = sources.toSet()
        restoring = false
    }

    fun release() = synchronized(lock) { protectedSources = emptySet(); restoring = false }

    suspend fun <T> withResolution(sources: () -> Set<String>, resolve: suspend () -> T): T {
        var lease: Set<String>? = null
        while (lease == null) {
            val previousInstall = synchronized(lock) {
                installing.also { pending ->
                    if (pending == null) {
                        lease = sources().toSet().also { ids -> ids.forEach { resolving[it] = resolving.getOrDefault(it, 0) + 1 } }
                    }
                }
            }
            previousInstall?.await()
        }
        try { return resolve() }
        finally {
            synchronized(lock) {
                lease.orEmpty().forEach { id ->
                    val count = resolving.getValue(id) - 1
                    if (count == 0) resolving.remove(id) else resolving[id] = count
                }
            }
        }
    }

    internal fun <T> installIfIdle(providerId: String, install: () -> T): T {
        val completion = synchronized(lock) {
            if (restoring || providerId in protectedSources || providerId in resolving || installing != null) throw SourceUpdateDeferred()
            CompletableDeferred<Unit>().also { installing = it }
        }
        try { return install() }
        finally { synchronized(lock) { installing = null; completion.complete(Unit) } }
    }
}

internal class SourceUpdateDeferred : Exception("Source update deferred during playback")
