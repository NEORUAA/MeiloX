package com.ljyh.mei.ui.screen.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.model.melox.PrivateConversation
import com.ljyh.mei.data.model.melox.PrivateMessage
import com.ljyh.mei.data.model.melox.MessageContact
import com.ljyh.mei.data.repository.MeloXRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConversationsUiState(
    val isLoading: Boolean = true,
    val conversations: List<PrivateConversation> = emptyList(),
    val error: String? = null,
)

data class ConversationUiState(
    val isLoading: Boolean = true,
    val userId: Long? = null,
    val messages: List<PrivateMessage> = emptyList(),
    val isSending: Boolean = false,
    val error: String? = null,
)

data class MessageContactsUiState(
    val isLoading: Boolean = true,
    val contacts: List<MessageContact> = emptyList(),
    val error: String? = null,
)

class ConversationsViewModel @Inject constructor(
    private val repository: MeloXRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ConversationsUiState())
    val state = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            runCatching { repository.privateConversations() }
                .onSuccess { _state.value = ConversationsUiState(isLoading = false, conversations = it) }
                .onFailure { _state.value = _state.value.copy(isLoading = false, error = it.message) }
        }
    }
}

class ConversationViewModel @Inject constructor(
    private val repository: MeloXRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ConversationUiState())
    val state = _state.asStateFlow()

    fun load(userId: Long) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, userId = userId, error = null)
            runCatching { repository.privateMessages(userId) }
                .onSuccess { _state.value = _state.value.copy(isLoading = false, messages = it) }
                .onFailure { _state.value = _state.value.copy(isLoading = false, error = it.message) }
        }
    }

    fun send(text: String, onSent: () -> Unit) {
        val userId = _state.value.userId ?: return
        if (text.isBlank() || _state.value.isSending) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isSending = true, error = null)
            runCatching { repository.sendPrivateText(text.trim(), listOf(userId)) }
                .onSuccess {
                    onSent()
                    load(userId)
                }
                .onFailure { _state.value = _state.value.copy(isSending = false, error = it.message) }
        }
    }
}

class MessageContactsViewModel @Inject constructor(
    private val repository: MeloXRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(MessageContactsUiState())
    val state = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            runCatching {
                val profile = repository.accountProfile()
                repository.messageContacts(profile.id)
            }.onSuccess {
                _state.value = MessageContactsUiState(isLoading = false, contacts = it)
            }.onFailure {
                _state.value = _state.value.copy(isLoading = false, error = it.message)
            }
        }
    }
}
