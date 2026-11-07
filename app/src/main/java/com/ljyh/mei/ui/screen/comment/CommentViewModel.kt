package com.ljyh.mei.ui.screen.comment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.ljyh.mei.data.model.api.CommentSortType
import com.ljyh.mei.source.shared.OnlineComment as CommentX
import com.ljyh.mei.source.shared.OnlineComment as FComment
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.source.shared.SharedOnlineRepository
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.api.SourceErrorCode
import kotlinx.coroutines.CancellationException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CommentReplyTarget(
    val commentId: String,
    val parentCommentId: String,
    val nickname: String,
    val content: String,
)

data class CommentLikeState(val liked: Boolean, val count: Int) {
    fun toggled() = CommentLikeState(!liked, (count + if (liked) -1 else 1).coerceAtLeast(0))
}

data class CommentComposerState(
    val draft: String = "",
    val replyTarget: CommentReplyTarget? = null,
    val isSending: Boolean = false,
    val error: String? = null,
) {
    val canSend: Boolean get() = draft.isNotBlank() && !isSending
}

sealed interface CommentEvent {
    data object Published : CommentEvent
    data class LikeFailed(val message: String) : CommentEvent
}

@HiltViewModel
class CommentViewModel @Inject constructor(
    onlineRepository: SharedOnlineRepository,
    savedStateHandle: androidx.lifecycle.SavedStateHandle,
) : ViewModel() {
    private val repository = onlineRepository.screen("comments", savedStateHandle["providerId"])
    private val _signedInUserId = MutableStateFlow<String?>(null)
    val signedInUserId: StateFlow<String?> = _signedInUserId
    private val _providerId = MutableStateFlow<String?>(savedStateHandle["providerId"])
    val providerId: StateFlow<String?> = _providerId
    private val _songId = MutableStateFlow("")
    private var accountId: String? = null
    private var sessionIdentity: String = ""
    private var contextVersion = 0
    private var publishJob: Job? = null
    private var floorJob: Job? = null
    private val likeJobs = mutableMapOf<String, Job>()
    private val refreshVersion = MutableStateFlow(0)
    private val _sortType = MutableStateFlow(CommentSortType.RECOMMEND)
    val sortType: StateFlow<CommentSortType> = _sortType
    private val _total = MutableStateFlow(0)
    val total: StateFlow<Int> = _total
    private val _composer = MutableStateFlow(CommentComposerState())
    val composer: StateFlow<CommentComposerState> = _composer
    private val _likes = MutableStateFlow<Map<String, CommentLikeState>>(emptyMap())
    val likes: StateFlow<Map<String, CommentLikeState>> = _likes
    private val _pendingLikes = MutableStateFlow<Set<String>>(emptySet())
    val pendingLikes: StateFlow<Set<String>> = _pendingLikes
    private val eventChannel = Channel<CommentEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    val pagingData: Flow<PagingData<CommentX>> = combine(
        _songId, _sortType, refreshVersion,
    ) { id, sort, version -> Triple(id, sort, version) }
        .filter { it.first.isNotBlank() }
        .flatMapLatest { (id, sort, _) ->
            val version = contextVersion
            Pager(
                config = PagingConfig(pageSize = 20, initialLoadSize = 20, enablePlaceholders = false),
                pagingSourceFactory = {
                    CommentPagingSource(repository, id, sort) {
                        if (version == contextVersion) _total.value = it
                    }
                },
            ).flow
        }.cachedIn(viewModelScope)

    suspend fun openContext(songId: String) {
        try {
            val source = repository.features()
            _providerId.value = source.providerId
            val account = try { repository.accountProfileRecord() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: SourceException) { null }
            setContext(songId, account?.id, source.scopeId)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SourceException) { setContext(songId, null, "") }
    }

    fun setContext(songId: String, signedInUserId: String?, sessionIdentity: String) {
        if (_songId.value == songId && accountId == signedInUserId && this.sessionIdentity == sessionIdentity) return
        val retainedComposer = _composer.value.takeIf {
            _songId.value == songId && accountId == signedInUserId && signedInUserId != null
        }?.copy(isSending = false, error = null) ?: CommentComposerState()
        contextVersion++
        publishJob?.cancel()
        floorJob?.cancel()
        likeJobs.values.forEach { it.cancel() }
        likeJobs.clear()
        _composer.value = retainedComposer
        _likes.value = emptyMap()
        _pendingLikes.value = emptySet()
        _expandedCommentId.value = null
        _expandedFloorCount.value = 0
        _floorComments.value = Resource.Loading
        _total.value = 0
        accountId = signedInUserId
        _signedInUserId.value = signedInUserId
        this.sessionIdentity = sessionIdentity
        _songId.value = songId
        refreshVersion.value++
    }

    fun setSortType(type: CommentSortType) { _sortType.value = type }

    fun updateDraft(draft: String) {
        if (!_composer.value.isSending) _composer.update { it.copy(draft = draft, error = null) }
    }

    fun replyTo(target: CommentReplyTarget) {
        if (accountId == null || _composer.value.isSending) return
        _composer.update { it.copy(replyTarget = target, error = null) }
    }

    fun cancelReply() {
        if (!_composer.value.isSending) _composer.update { it.copy(replyTarget = null, error = null) }
    }

    fun publish() {
        val state = _composer.value
        if (accountId == null || !state.canSend || _songId.value.isBlank()) return
        val version = contextVersion
        val songId = _songId.value
        _composer.value = state.copy(isSending = true, error = null)
        publishJob = viewModelScope.launch {
            val result = repository.publishComment(
                songId,
                state.draft,
                state.replyTarget?.commentId,
            )
            if (version != contextVersion) return@launch
            when (result) {
                is Resource.Success -> {
                    _composer.value = CommentComposerState()
                    if (state.replyTarget == null) _sortType.value = CommentSortType.TIME
                    refreshVersion.value++
                    state.replyTarget?.let { target ->
                        _expandedCommentId.value = target.parentCommentId
                        val count = (_expandedFloorCount.value + 1).coerceAtLeast(20)
                        _expandedFloorCount.value = count
                        loadFloorComments(target.parentCommentId, count)
                    }
                    eventChannel.send(CommentEvent.Published)
                }
                is Resource.Error -> _composer.update { it.copy(isSending = false, error = result.message) }
                Resource.Loading -> _composer.update { it.copy(isSending = false) }
            }
        }
    }

    fun toggleLike(commentId: String, liked: Boolean, count: Int) {
        if (accountId == null || commentId in _pendingLikes.value) return
        val version = contextVersion
        val songId = _songId.value
        val previous = _likes.value[commentId] ?: CommentLikeState(liked, count)
        val next = previous.toggled()
        _pendingLikes.update { it + commentId }
        likeJobs[commentId] = viewModelScope.launch {
            val result = repository.setCommentLiked(songId, commentId, next.liked)
            if (version != contextVersion) return@launch
            _pendingLikes.update { it - commentId }
            likeJobs.remove(commentId)
            when (result) {
                is Resource.Success -> _likes.update { it + (commentId to next) }
                is Resource.Error -> eventChannel.send(CommentEvent.LikeFailed(result.message))
                Resource.Loading -> Unit
            }
        }
    }

    private val _floorComments = MutableStateFlow<Resource<List<FComment>>>(Resource.Loading)
    val floorComments: StateFlow<Resource<List<FComment>>> = _floorComments
    private val _expandedCommentId = MutableStateFlow<String?>(null)
    val expandedCommentId: StateFlow<String?> = _expandedCommentId
    private val _expandedFloorCount = MutableStateFlow(0)
    val expandedFloorCount: StateFlow<Int> = _expandedFloorCount

    init { viewModelScope.launch { repository.sessionChanges.collect { owner ->
        if (owner.isBlank()) setContext(_songId.value, null, "invalidated")
    } } }

    fun toggleFloorComments(commentId: String, floorCount: Int) {
        floorJob?.cancel()
        if (_expandedCommentId.value == commentId) {
            _expandedCommentId.value = null
            _expandedFloorCount.value = 0
            return
        }
        _expandedCommentId.value = commentId
        _expandedFloorCount.value = floorCount
        loadFloorComments(commentId, floorCount)
    }

    fun retryFloorComments() {
        _expandedCommentId.value?.let { loadFloorComments(it, _expandedFloorCount.value) }
    }

    private fun loadFloorComments(parentCommentId: String, limit: Int) {
        floorJob?.cancel()
        val version = contextVersion
        floorJob = viewModelScope.launch {
            _floorComments.value = Resource.Loading
            val result = repository.getFloorComment(
                parentCommentId = parentCommentId,
                id = _songId.value,
                limit = limit.coerceAtLeast(20),
            )
            if (version != contextVersion || _expandedCommentId.value != parentCommentId) return@launch
            _floorComments.value = when (result) {
                is Resource.Success -> Resource.Success(result.data.comments)
                is Resource.Error -> Resource.Error(result.message)
                Resource.Loading -> Resource.Loading
            }
        }
    }
}
