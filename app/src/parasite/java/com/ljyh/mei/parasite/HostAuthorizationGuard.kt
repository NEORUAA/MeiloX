package com.ljyh.mei.parasite

import java.io.Closeable
import java.io.IOException

/** Fences module-owned login work without retaining any host credential or response. */
internal class HostAuthorizationGuard(
    private val sessions: HostSessionBridge,
    pending: Boolean,
    private val persistPending: (Boolean) -> Unit,
    private val dispatch: (() -> Unit) -> Unit,
    private val report: (String) -> Unit = {},
) {
    internal class Attempt internal constructor() {
        internal var active = true
        internal var touched = false
        internal var verified = false
    }

    private val lock = Any()
    private val owner = ThreadLocal<Attempt>()
    private val writing = ThreadLocal<Attempt>()
    private var current: Attempt? = null
    private var lease: Closeable? = if (pending) sessions.beginTransition() else null
    private var unresolved = pending
    private var writes = 0
    private var cookieRevision = 0L
    private var mutationRevision = 0L
    private var recovery: (() -> Boolean)? = null
    private var recoveryQueued = false

    init { sessions.setRecoveryRequired(pending) }

    fun setRecovery(recover: () -> Boolean) {
        synchronized(lock) { recovery = recover }
        scheduleRecovery()
    }

    fun begin(): Attempt = synchronized(lock) {
        check(writes == 0) { "Official session write is still completing" }
        current?.active = false
        if (lease == null) lease = sessions.beginTransition()
        // Persist before allowing any operation that can change the official session.
        try { persistPending(true) } catch (error: Exception) {
            sessions.setRecoveryRequired(true)
            throw error
        }
        report("login_session_fenced")
        Attempt().also { current = it; sessions.setRecoveryRequired(false) }
    }

    fun isActive(attempt: Attempt): Boolean = synchronized(lock) { current === attempt && attempt.active }

    fun <T> run(attempt: Attempt, block: () -> T): T {
        if (!isActive(attempt)) throw IOException("Official login attempt retired")
        val previous = owner.get()
        owner.set(attempt)
        try { return block() } finally {
            if (previous == null) owner.remove() else owner.set(previous)
        }
    }

    /** An entered write may finish; retirement waits for it before recovery or another login. */
    fun <T> mutate(cookie: Boolean = false, block: () -> T): T {
        val attempt = owner.get()
        val nested = attempt != null && writing.get() === attempt
        synchronized(lock) {
            if (attempt != null && !nested && (current !== attempt || !attempt.active)) {
                report("login_retired_write_rejected")
                throw IOException("Retired login cannot change the official session")
            }
            if (cookie) cookieRevision++
            mutationRevision++
            if (lease != null) {
                unresolved = true
                current?.let { it.touched = true; it.verified = false }
            }
            writes++
        }
        val previous = writing.get()
        if (attempt != null) writing.set(attempt)
        try { return block() } finally {
            if (previous == null) writing.remove() else writing.set(previous)
            synchronized(lock) { writes--; mutationRevision++ }
            scheduleRecovery()
        }
    }

    /** The official account helper must complete against the same credential revision. */
    fun <T> verifyProfile(block: () -> T, accepted: (T) -> Boolean): T {
        val attempt = owner.get() ?: return block()
        val before = synchronized(lock) {
            attempt.verified = false
            cookieRevision
        }
        val result = block()
        val after = synchronized(lock) { mutationRevision }
        val valid = runCatching { accepted(result) }.getOrDefault(false)
        synchronized(lock) {
            if (current === attempt && attempt.active && before == cookieRevision &&
                after == mutationRevision && writes == 0 && valid) {
                attempt.verified = true
            }
            report("login_profile_verified=${attempt.verified}")
        }
        return result
    }

    fun complete(attempt: Attempt): Boolean = synchronized(lock) {
        if (current !== attempt || !attempt.active || !attempt.verified || writes != 0) return false
        try { release(); true } catch (_: Exception) {
            sessions.setRecoveryRequired(true)
            false
        }
    }

    fun retire(attempt: Attempt) {
        synchronized(lock) {
            if (current !== attempt || !attempt.active) return
            attempt.active = false
            if (!unresolved && !attempt.touched && writes == 0) {
                try { release() } catch (_: Exception) { sessions.setRecoveryRequired(true) }
            }
            else sessions.setRecoveryRequired(true)
        }
        scheduleRecovery()
    }

    private fun release() {
        // A failed commit leaves the in-memory fence and on-disk recovery marker intact.
        persistPending(false)
        current?.active = false
        current = null
        unresolved = false
        sessions.setRecoveryRequired(false)
        val previous = lease
        lease = null
        previous?.close()
        report("login_session_released")
    }

    private fun scheduleRecovery() {
        val queued = synchronized(lock) {
            if (lease == null || current?.active == true || writes != 0 || recoveryQueued || recovery == null) false
            else { recoveryQueued = true; true }
        }
        if (!queued) return
        val task: () -> Unit = task@{
            val task = synchronized(lock) {
                recoveryQueued = false
                if (lease == null || current?.active == true || writes != 0) null
                else Attempt().also { current = it } to requireNotNull(recovery)
            } ?: return@task
            val (attempt, recover) = task
            val success = runCatching { run(attempt, recover) && complete(attempt) }.getOrDefault(false)
            report("login_session_recovery_complete success=$success")
            if (!success) synchronized(lock) {
                if (current === attempt) {
                    attempt.active = false
                    sessions.setRecoveryRequired(true)
                }
            }
            // No automatic retry loop. A new user attempt or process restart can retry.
        }
        try { dispatch(task) } catch (_: Exception) {
            synchronized(lock) { recoveryQueued = false; sessions.setRecoveryRequired(true) }
        }
    }
}
