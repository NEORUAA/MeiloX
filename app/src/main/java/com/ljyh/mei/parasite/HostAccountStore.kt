package com.ljyh.mei.parasite

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

data class HostAccountState(
    val session: HostSessionStamp? = null,
    val profile: AccountProfile? = null,
    val loading: Boolean = true,
    val profileUnavailable: Boolean = false,
) {
    val authenticated: Boolean get() = session?.identity?.authenticated == true
    val userId: String get() = if (authenticated) session!!.identity.userId.toString() else ""
}

/** Public account presentation only. The official client remains the sole session owner. */
@Singleton
class HostAccountStore internal constructor(
    val sessions: HostSessionBridge,
    private val loadProfile: suspend () -> AccountProfile,
    scope: CoroutineScope,
) : Closeable {
    @Inject constructor(sessions: HostSessionBridge, repository: MeloXRepository) : this(
        sessions, repository::accountProfile, CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val mutableState = MutableStateFlow(HostAccountState())
    val state = mutableState.asStateFlow()
    private val refreshes = MutableStateFlow(0L)
    private val invalidation = sessions.onInvalidated { revision ->
        mutableState.update { current ->
            if ((current.session?.generation ?: -1) < revision) HostAccountState() else current
        }
    }
    private val worker = scope.launch {
        combine(sessions.changes, refreshes) { _, _ -> Unit }.collectLatest {
            val stamp = runCatching { sessions.snapshot() }.getOrNull() ?: return@collectLatest
            try {
                publish(HostAccountState(session = stamp, loading = stamp.identity.authenticated))
                if (!stamp.identity.authenticated) return@collectLatest
                val profile = loadProfile()
                currentCoroutineContext().ensureActive()
                if (profile.id != stamp.identity.userId) throw IOException("Official profile does not match session")
                publish(HostAccountState(stamp, profile, loading = false))
            } catch (error: CancellationException) {
                throw error
            } catch (_: HostSessionChangedException) {
                // The new generation owns both the next load and the visible account state.
            } catch (_: Exception) {
                runCatching { publish(HostAccountState(stamp, loading = false, profileUnavailable = true)) }
            }
        }
    }

    fun refresh() { refreshes.update { it + 1 } }

    fun requireAuthenticated(): HostSessionStamp = sessions.snapshot().also {
        if (!it.identity.authenticated) throw IOException("Official sign-in required")
    }

    private fun publish(next: HostAccountState) {
        sessions.withCurrent(requireNotNull(next.session)) { mutableState.value = next }
    }

    override fun close() {
        worker.cancel()
        invalidation.close()
        mutableState.value = HostAccountState()
    }
}
