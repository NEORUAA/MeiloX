package com.ljyh.mei.ui.screen.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.model.melox.PrivateConversation
import com.ljyh.mei.data.model.melox.PrivateConversationPage
import com.ljyh.mei.data.model.melox.PrivateMessage
import com.ljyh.mei.data.model.melox.MessageContact
import com.ljyh.mei.data.repository.MeloXRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

data class ConversationsUiState(
    val isLoading: Boolean = true,
    val conversations: List<PrivateConversation> = emptyList(),
    val error: String? = null,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
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

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val repository: MeloXRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ConversationsUiState())
    val state = _state.asStateFlow()

    private var officialMessages: Boolean? = null
    private var nextTime: Long = -1
    private var loadJob: Job? = null

    fun load(officialMessages: Boolean) {
        if (this.officialMessages == officialMessages) return
        this.officialMessages = officialMessages
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        fetch(refresh = true)
    }

    fun loadMore() {
        if (officialMessages != true || !_state.value.hasMore || loadJob?.isActive == true) return
        fetch(refresh = false)
    }

    private fun fetch(refresh: Boolean) {
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = refresh, isLoadingMore = !refresh, error = null)
            try {
                val page = if (officialMessages == true) {
                    repository.officialConversations(before = if (refresh) -1 else nextTime)
                } else {
                    PrivateConversationPage(repository.privateConversations(), hasMore = false, nextTime = -1)
                }
                val previousTime = nextTime
                nextTime = page.nextTime
                _state.value = ConversationsUiState(
                    isLoading = false,
                    conversations = if (refresh) page.conversations else {
                        (_state.value.conversations + page.conversations).distinctBy(PrivateConversation::id)
                    },
                    hasMore = page.hasMore && page.nextTime > 0 && (refresh || page.nextTime < previousTime),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(isLoading = false, isLoadingMore = false, error = error.message)
            }
        }
    }
}

@HiltViewModel
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

@HiltViewModel
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
