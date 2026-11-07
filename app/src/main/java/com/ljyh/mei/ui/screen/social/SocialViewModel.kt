package com.ljyh.mei.ui.screen.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import com.ljyh.mei.source.shared.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

internal class SocialScreenSession(private val repository: SourceSocialRepository, private val scope: CoroutineScope,
    private val invalidated: () -> Unit) {
    private var binding: Deferred<SourceSocialSession>? = null
    fun open(providerId: String? = null) {
        if (binding != null) return
        binding = scope.async { repository.open(providerId).also { it.owner.guard(scope, invalidated) } }
    }
    suspend fun get(): SourceSocialSession { open(); return binding!!.await().also { it.owner.ensureCurrent() } }
}

data class ConversationsUiState(val isLoading: Boolean = true, val conversations: List<SourcePrivateConversation> = emptyList(),
    val error: String? = null, val hasMore: Boolean = false, val isLoadingMore: Boolean = false,
    val providerId: String? = null, val currentUserId: String = "")
data class ConversationUiState(val isLoading: Boolean = true, val userId: String? = null, val messages: List<SourcePrivateMessage> = emptyList(),
    val isSending: Boolean = false, val error: String? = null, val providerId: String? = null, val currentUserId: String = "")
data class MessageContactsUiState(val isLoading: Boolean = true, val contacts: List<SourceMessageContact> = emptyList(),
    val error: String? = null, val providerId: String? = null)

@HiltViewModel
class ConversationsViewModel @Inject constructor(repository: SourceSocialRepository) : ViewModel() {
    private val _state = MutableStateFlow(ConversationsUiState()); val state = _state.asStateFlow()
    private var officialMessages = false
    private var nextTime = -1L
    private var loadJob: Job? = null
    private val session = SocialScreenSession(repository, viewModelScope) {
        loadJob?.cancel(); _state.value = ConversationsUiState(isLoading = false, error = "音源或账号已变更")
    }
    fun load(officialMessages: Boolean, providerId: String? = null) {
        session.open(providerId); this.officialMessages = officialMessages; refresh()
    }
    fun refresh() { loadJob?.cancel(); fetch(true) }
    fun loadMore() { if (officialMessages && _state.value.hasMore && loadJob?.isActive != true) fetch(false) }
    private fun fetch(refresh: Boolean) {
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = refresh, isLoadingMore = !refresh, error = null)
            try {
                val bound = session.get()
                val page = bound.conversations(officialMessages, if (refresh) -1 else nextTime)
                val previous = nextTime; nextTime = page.nextTime
                _state.value = ConversationsUiState(false, if (refresh) page.conversations else (_state.value.conversations + page.conversations).distinctBy { it.id },
                    hasMore = page.hasMore && nextTime > 0 && (refresh || nextTime < previous), providerId = bound.providerId, currentUserId = bound.userId)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { _state.value = _state.value.copy(isLoading = false, isLoadingMore = false, error = error.message) }
        }
    }
}

@HiltViewModel
class ConversationViewModel @Inject constructor(repository: SourceSocialRepository) : ViewModel() {
    private val _state = MutableStateFlow(ConversationUiState()); val state = _state.asStateFlow()
    private var loadJob: Job? = null
    private var sendJob: Job? = null
    private val session = SocialScreenSession(repository, viewModelScope) {
        loadJob?.cancel(); sendJob?.cancel(); _state.value = ConversationUiState(isLoading = false, error = "音源或账号已变更")
    }
    fun load(userId: String, providerId: String? = null) {
        session.open(providerId); loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, userId = userId, error = null)
            try {
                val bound = session.get(); val messages = bound.messages(userId)
                _state.value = _state.value.copy(isLoading = false, messages = messages, providerId = bound.providerId, currentUserId = bound.userId)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { _state.value = _state.value.copy(isLoading = false, error = error.message) }
        }
    }
    fun send(text: String, onSent: () -> Unit) {
        val userId = _state.value.userId ?: return
        if (text.isBlank() || _state.value.isSending) return
        sendJob = viewModelScope.launch {
            _state.value = _state.value.copy(isSending = true, error = null)
            try {
                val bound = session.get(); bound.sendText(userId, text.trim()); onSent()
                val messages = bound.messages(userId)
                _state.value = _state.value.copy(isSending = false, messages = messages)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { _state.value = _state.value.copy(isSending = false, error = error.message) }
        }
    }
    fun playResource(resource: SourceShareResource, onReady: (MediaItem) -> Unit) {
        viewModelScope.launch {
            try { onReady(session.get().media(resource))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { _state.value = _state.value.copy(error = error.message) }
        }
    }
}

@HiltViewModel
class MessageContactsViewModel @Inject constructor(repository: SourceSocialRepository) : ViewModel() {
    private val _state = MutableStateFlow(MessageContactsUiState()); val state = _state.asStateFlow()
    private var loadJob: Job? = null
    private val session = SocialScreenSession(repository, viewModelScope) {
        loadJob?.cancel(); _state.value = MessageContactsUiState(isLoading = false, error = "音源或账号已变更")
    }
    fun open(providerId: String? = null) { session.open(providerId); refresh() }
    fun refresh() {
        loadJob?.cancel(); loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val bound = session.get(); val contacts = bound.contacts()
                _state.value = MessageContactsUiState(isLoading = false, contacts = contacts, providerId = bound.providerId)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { _state.value = _state.value.copy(isLoading = false, error = error.message) }
        }
    }
}
