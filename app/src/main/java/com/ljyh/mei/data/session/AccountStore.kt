package com.ljyh.mei.data.session

import com.ljyh.mei.data.model.melox.AccountProfile
import com.ljyh.mei.data.repository.MeloXRepository
import java.io.Closeable
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountState(
    val session: SessionStamp? = null,
    val profile: AccountProfile? = null,
    val loading: Boolean = true,
    val profileUnavailable: Boolean = false,
    val recoveryRequired: Boolean = false,
) {
    val authenticated: Boolean get() = !recoveryRequired && session?.identity?.authenticated == true
    val userId: String get() = if (authenticated) session!!.identity.userId.toString() else ""
}

/** Public account presentation only. Credentials stay with the selected backend. */
@Singleton
class AccountStore internal constructor(
    val sessions: SessionStore,
    private val loadProfile: suspend (SessionStamp) -> AccountProfile,
    scope: CoroutineScope,
) : Closeable {
    @Inject constructor(sessions: SessionStore, repository: MeloXRepository) : this(
        sessions, repository::accountProfile, CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val mutableState = MutableStateFlow(AccountState())
    val state = mutableState.asStateFlow()
    private val refreshes = MutableStateFlow(0L)
    private val invalidation = sessions.onInvalidated { revision ->
        mutableState.update { current ->
            if ((current.session?.generation ?: -1) < revision) pendingState() else current
        }
    }
    private val worker = scope.launch {
        combine(sessions.changes, refreshes, sessions.recoveryRequired) { _, _, _ -> Unit }.collectLatest {
            val stamp = runCatching { sessions.snapshot() }.getOrNull()
            if (stamp == null) {
                mutableState.value = pendingState()
                return@collectLatest
            }
            if (sessions.recoveryRequired.value) {
                runCatching { publish(pendingState(stamp)) }
                return@collectLatest
            }
            try {
                publish(AccountState(session = stamp, loading = stamp.identity.authenticated))
                if (!stamp.identity.authenticated || sessions.recoveryRequired.value) return@collectLatest
                val profile = loadProfile(stamp)
                currentCoroutineContext().ensureActive()
                if (profile.id != stamp.identity.userId) throw IOException("Account profile does not match session")
                publish(AccountState(stamp, profile, loading = false))
            } catch (error: CancellationException) {
                throw error
            } catch (_: SessionChangedException) {
                // The new generation owns both the next load and the visible account state.
            } catch (_: Exception) {
                runCatching { publish(AccountState(stamp, loading = false, profileUnavailable = true)) }
            }
        }
    }

    fun refresh() { refreshes.update { it + 1 } }

    private fun pendingState(stamp: SessionStamp? = null): AccountState {
        val recoveryRequired = sessions.recoveryRequired.value
        return AccountState(
            session = stamp,
            loading = !recoveryRequired, profileUnavailable = recoveryRequired, recoveryRequired = recoveryRequired,
        )
    }

    fun requireAuthenticated(): SessionStamp = sessions.snapshot().also {
        if (sessions.recoveryRequired.value) throw IOException("Session recovery is required")
        if (!it.identity.authenticated || it.identity.anonymous) throw IOException("Sign-in required")
    }

    private fun publish(next: AccountState) {
        sessions.withCurrent(requireNotNull(next.session)) {
            mutableState.value = if (sessions.recoveryRequired.value) pendingState(next.session) else next
        }
    }

    override fun close() {
        worker.cancel()
        invalidation.close()
        mutableState.value = AccountState()
    }
}
