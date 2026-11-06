package com.ljyh.mei.source.script

import kotlinx.coroutines.CancellationException

/** Serializes one installed source's account mutations and asynchronous state commits. */
internal class PluginRevisionGate {
    internal class Revision internal constructor()
    private val lock = Any()
    @Volatile private var current: Revision? = null

    fun <T> change(action: (Revision) -> T): T = synchronized(lock) {
        val revision = Revision()
        current = revision
        action(revision)
    }

    fun isCurrent(revision: Revision): Boolean = current === revision

    fun <T> read(revision: Revision, action: () -> T): T = synchronized(lock) {
        if (!isCurrent(revision)) throw CancellationException("Source revision changed")
        action()
    }

    fun commit(revision: Revision, action: () -> Unit): Boolean = synchronized(lock) {
        if (!isCurrent(revision)) return@synchronized false
        action()
        true
    }
}
