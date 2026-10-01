package com.ljyh.mei.ui.screen.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.model.melox.PrivateConversation
import com.ljyh.mei.data.model.melox.PrivateMessage
import com.ljyh.mei.data.model.melox.MessageContact
import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.data.repository.SocialSource
import com.ljyh.mei.data.session.AccountStore
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConversationsUiState(
    val isLoading: Boolean = true,
    val conversations: List<PrivateConversation> = emptyList(),
    val error: String? = null,
    val session: SessionStamp? = null,
)

data class ConversationUiState(
    val isLoading: Boolean = true,
    val userId: Long? = null,
    val messages: List<PrivateMessage> = emptyList(),
    val isSending: Boolean = false,
    val error: String? = null,
    val session: SessionStamp? = null,
)

data class MessageContactsUiState(
    val isLoading: Boolean = true,
    val contacts: List<MessageContact> = emptyList(),
    val error: String? = null,
    val session: SessionStamp? = null,
)

/** All four social consumers clear private data before a replacement account can load. */
internal class SocialSessionState<T>(
    private val sessions: SessionStore,
    private val scope: CoroutineScope,
    private val empty: (SessionStamp?, String?) -> T,
) : Closeable {
    private val lock = Any()
    private val mutableState = MutableStateFlow(empty(null, null))
    val state = mutableState.asStateFlow()
    @Volatile private var session: SessionStamp? = null
    private var initialized = false
    private var error: String? = null
    private var observer: Job? = null
    private val invalidation = sessions.onInvalidated { revision ->
        synchronized(lock) {
            if ((session?.generation ?: -1) < revision) {
                session = null
                initialized = false
                mutableState.value = empty(null, null)
            }
        }
    }

    fun observe(onChange: (SessionStamp?) -> Unit) {
        observer = scope.launch {
            combine(sessions.changes, sessions.recoveryRequired) { _, recovery -> recovery }.collect { recovery ->
                val stamp = if (recovery) null else runCatching { sessions.snapshot() }.getOrNull()
                val failure = when {
                    recovery -> "Session recovery is required"
                    stamp == null -> null
                    !stamp.identity.authenticated || stamp.identity.anonymous || stamp.identity.userId <= 0 -> "Sign-in required"
                    else -> null
                }
                val reset = {
                    synchronized(lock) {
                        if (!initialized || session != stamp || error != failure) {
                            initialized = true
                            session = stamp
                            error = failure
                            mutableState.value = empty(stamp, failure)
                            true
                        } else false
                    }
                }
                val changed = runCatching {
                    if (stamp == null) reset() else sessions.withCurrent(stamp, reset)
                }.getOrDefault(false)
                if (changed) onChange(stamp?.takeIf { failure == null })
            }
        }
    }

    fun owner(): SessionStamp? = session?.takeIf {
        it.identity.authenticated && !it.identity.anonymous && it.identity.userId > 0 &&
            !sessions.recoveryRequired.value && runCatching { sessions.requireCurrent(it) }.isSuccess
    }

    fun publish(stamp: SessionStamp, update: (T) -> T, after: () -> Unit = {}) {
        sessions.withCurrent(stamp) {
            synchronized(lock) {
                if (session != stamp || sessions.recoveryRequired.value) throw SessionChangedException()
                mutableState.update(update)
                after()
            }
        }
    }

    override fun close() {
        invalidation.close()
        observer?.cancel()
    }
}

class ConversationsViewModel internal constructor(
    private val repository: SocialSource,
    accounts: AccountStore,
) : ViewModel() {
    @Inject constructor(repository: MeloXRepository, accounts: AccountStore) : this(repository as SocialSource, accounts)
    private val owner = SocialSessionState(accounts.sessions, viewModelScope) { stamp, error ->
        ConversationsUiState(session = stamp, isLoading = stamp == null && error == null, error = error)
    }
    val state = owner.state
    private var loadJob: Job? = null

    init { owner.observe { loadJob?.cancel(); if (it != null) refresh() } }

    fun refresh() {
        val stamp = owner.owner() ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                owner.publish(stamp, { it.copy(isLoading = true, error = null) })
                val conversations = repository.privateConversations(stamp)
                currentCoroutineContext().ensureActive()
                owner.publish(stamp, { it.copy(isLoading = false, conversations = conversations) })
            } catch (error: CancellationException) {
                throw error
            } catch (_: SessionChangedException) {
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                runCatching { owner.publish(stamp, { it.copy(isLoading = false, error = error.message) }) }
            }
        }
    }

    override fun onCleared() { owner.close(); super.onCleared() }
}

class ConversationViewModel internal constructor(
    private val repository: SocialSource,
    accounts: AccountStore,
) : ViewModel() {
    @Inject constructor(repository: MeloXRepository, accounts: AccountStore) : this(repository as SocialSource, accounts)
    private val owner = SocialSessionState(accounts.sessions, viewModelScope) { stamp, error ->
        ConversationUiState(session = stamp, isLoading = stamp == null && error == null, error = error)
    }
    val state = owner.state
    private var requestedUserId: Long? = null
    private var loadJob: Job? = null
    private var sendJob: Job? = null

    init {
        owner.observe {
            loadJob?.cancel()
            sendJob?.cancel()
            if (it != null) requestedUserId?.let(::load)
        }
    }

    fun load(userId: Long) {
        if (userId <= 0) return
        if (requestedUserId != userId) sendJob?.cancel()
        requestedUserId = userId
        val stamp = owner.owner() ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                owner.publish(stamp, { current ->
                    if (current.userId == userId) current.copy(isLoading = true, error = null)
                    else ConversationUiState(session = stamp, userId = userId)
                })
                val messages = repository.privateMessages(stamp, userId)
                currentCoroutineContext().ensureActive()
                owner.publish(stamp, { it.copy(isLoading = false, messages = messages) })
            } catch (error: CancellationException) {
                throw error
            } catch (_: SessionChangedException) {
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                runCatching { owner.publish(stamp, { it.copy(isLoading = false, error = error.message) }) }
            }
        }
    }

    fun send(expected: ConversationUiState, text: String, onSent: () -> Unit) {
        val stamp = owner.owner()?.takeIf { it == expected.session } ?: return
        val userId = expected.userId?.takeIf { it == state.value.userId && it == requestedUserId } ?: return
        if (text.isBlank() || state.value.isSending || sendJob?.isActive == true) return
        sendJob = viewModelScope.launch {
            try {
                owner.publish(stamp, { it.copy(isSending = true, error = null) })
                repository.sendPrivateText(stamp, text.trim(), listOf(userId))
                currentCoroutineContext().ensureActive()
                owner.publish(stamp, { it.copy(isSending = false) }) {
                    if (state.value.userId == userId && requestedUserId == userId) {
                        onSent()
                    }
                }
                if (owner.owner() == stamp && requestedUserId == userId) load(userId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: SessionChangedException) {
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                runCatching { owner.publish(stamp, { it.copy(isSending = false, error = error.message) }) }
            }
        }
    }

    override fun onCleared() { owner.close(); super.onCleared() }
}

class MessageContactsViewModel internal constructor(
    private val repository: SocialSource,
    accounts: AccountStore,
) : ViewModel() {
    @Inject constructor(repository: MeloXRepository, accounts: AccountStore) : this(repository as SocialSource, accounts)
    private val owner = SocialSessionState(accounts.sessions, viewModelScope) { stamp, error ->
        MessageContactsUiState(session = stamp, isLoading = stamp == null && error == null, error = error)
    }
    val state = owner.state
    private var loadJob: Job? = null

    init { owner.observe { loadJob?.cancel(); if (it != null) refresh() } }

    fun refresh() {
        val stamp = owner.owner() ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                owner.publish(stamp, { it.copy(isLoading = true, error = null) })
                val contacts = repository.messageContacts(stamp)
                currentCoroutineContext().ensureActive()
                owner.publish(stamp, { it.copy(isLoading = false, contacts = contacts) })
            } catch (error: CancellationException) {
                throw error
            } catch (_: SessionChangedException) {
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                runCatching { owner.publish(stamp, { it.copy(isLoading = false, error = error.message) }) }
            }
        }
    }

    override fun onCleared() { owner.close(); super.onCleared() }
}
