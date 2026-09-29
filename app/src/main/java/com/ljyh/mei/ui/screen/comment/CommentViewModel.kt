package com.ljyh.mei.ui.screen.comment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.ljyh.mei.data.model.api.CommentResourceType
import com.ljyh.mei.data.model.api.CommentSortType
import com.ljyh.mei.data.model.weapi.CommentX
import com.ljyh.mei.data.model.weapi.FComment
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.CommentRepository
import com.ljyh.mei.data.repository.CommentSource
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

internal data class CommentQuery(val song: String = "", val sort: CommentSortType = CommentSortType.RECOMMEND,
    val session: SessionStamp? = null, val revision: Long = 0)

@OptIn(ExperimentalCoroutinesApi::class)
class CommentViewModel internal constructor(private val repository: CommentSource, private val sessions: SessionStore) : ViewModel() {
    @Inject constructor(repository: CommentRepository, sessions: SessionStore) : this(repository as CommentSource, sessions)
    private val lock = Any()
    private val query = MutableStateFlow(CommentQuery())
    private val mutableSort = MutableStateFlow(CommentSortType.RECOMMEND)
    val sortType = mutableSort.asStateFlow()
    private val mutableTotal = MutableStateFlow(0)
    val total = mutableTotal.asStateFlow()
    private val mutableFloor = MutableStateFlow<Resource<List<FComment>>>(Resource.Loading)
    val floorComments = mutableFloor.asStateFlow()
    private val mutableExpanded = MutableStateFlow<Long?>(null)
    val expandedCommentId = mutableExpanded.asStateFlow()
    private val mutableCount = MutableStateFlow(0)
    val expandedFloorCount = mutableCount.asStateFlow()
    private var floorJob: Job? = null
    private var floorVersion = 0L
    private var activeSource: CommentPagingSource? = null
    private val invalidation = sessions.onInvalidated { revision ->
        synchronized(lock) {
            if ((query.value.session?.generation ?: -1) < revision) reset(query.value.copy(session = null))
        }
    }

    val pagingData = query.flatMapLatest { captured -> flow {
        emit(PagingData.empty<CommentX>())
        if (captured.song.isNotBlank()) emitAll(Pager(
            PagingConfig(pageSize = PAGE_SIZE, initialLoadSize = PAGE_SIZE, enablePlaceholders = false),
            pagingSourceFactory = { source(captured) },
        ).flow)
    } }.cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            combine(sessions.changes, sessions.recoveryRequired) { _, recovery -> recovery }.collect { recovery ->
                val stamp = if (recovery) null else runCatching { sessions.snapshot() }.getOrNull()
                if (stamp == null) synchronized(lock) { reset(query.value.copy(session = null)) }
                else runCatching { sessions.withCurrent(stamp) {
                    synchronized(lock) { if (query.value.session != stamp) reset(query.value.copy(session = stamp)) }
                } }
            }
        }
    }

    private fun reset(next: CommentQuery) {
        query.value = next.copy(revision = query.value.revision + 1)
        activeSource?.invalidate()
        activeSource = null
        mutableSort.value = next.sort
        mutableTotal.value = 0
        collapse()
    }

    private fun collapse() {
        floorVersion++
        floorJob?.cancel()
        mutableExpanded.value = null
        mutableCount.value = 0
        mutableFloor.value = Resource.Loading
    }

    fun setSongId(id: String) = synchronized(lock) {
        if (query.value.song != id) reset(query.value.copy(song = id))
    }

    fun setSortType(type: CommentSortType) = synchronized(lock) {
        if (query.value.sort != type) reset(query.value.copy(sort = type))
    }

    private fun source(captured: CommentQuery): CommentPagingSource = synchronized(lock) {
        CommentPagingSource(repository, sessions, captured.session, captured.song, captured.sort,
            isCurrent = { query.value == captured },
            onTotalReceived = { count -> synchronized(lock) { if (query.value == captured) mutableTotal.value = count } },
        ).also { if (query.value == captured) activeSource = it }
    }

    internal fun newPagingSource(): CommentPagingSource = source(query.value)

    fun toggleFloorComments(commentId: Long, floorCount: Int) {
        val job = synchronized(lock) {
            if (mutableExpanded.value == commentId) { collapse(); null }
            else {
                collapse()
                mutableExpanded.value = commentId
                mutableCount.value = floorCount
                prepareFloor()
            }
        }
        job?.start()
    }

    private fun prepareFloor(): Job? {
        floorJob?.cancel()
        val captured = query.value
        val parent = mutableExpanded.value ?: return null
        val stamp = captured.session ?: run { mutableFloor.value = Resource.Error("Account session is not ready"); return null }
        val version = ++floorVersion
        mutableFloor.value = Resource.Loading
        return viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val rows = linkedMapOf<Long, FComment>()
                val cursors = mutableSetOf<Long>()
                var time = -1L
                do {
                    sessions.requireCurrent(stamp)
                    check(!sessions.recoveryRequired.value) { "Account session recovery is required" }
                    val response = repository.getFloorComment(stamp, parent, captured.song, CommentResourceType.SONG, PAGE_SIZE, time)
                    currentCoroutineContext().ensureActive()
                    val result = when (response) {
                        is Resource.Success -> response.data.also { check(it.code == 200) { "Comment replies failed (${it.code})" } }
                        is Resource.Error -> error(response.message)
                        Resource.Loading -> error("Comment replies did not complete")
                    }
                    val data = checkNotNull(result.data)
                    val page = checkNotNull(data.comments)
                    check(page.all { it.commentId > 0 }) { "Invalid reply identity" }
                    val previous = rows.size
                    page.forEach { rows.putIfAbsent(it.commentId, it) }
                    val more = checkNotNull(data.hasMore)
                    if (more) {
                        check(rows.size > previous) { "Reply pagination did not advance" }
                        cursors += time
                        val next = data.time ?: page.lastOrNull()?.time ?: error("Reply cursor is missing")
                        check(next > 0 && next !in cursors) { "Reply cursor did not advance" }
                        time = next
                    }
                } while (more)
                publishFloor(captured, parent, version, Resource.Success(rows.values.toList()))
            } catch (error: CancellationException) { throw error }
            catch (_: SessionChangedException) { }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                runCatching { publishFloor(captured, parent, version, Resource.Error(error.message ?: "Comment replies failed")) }
            }
        }.also { floorJob = it }
    }

    private fun publishFloor(captured: CommentQuery, parent: Long, version: Long, result: Resource<List<FComment>>) {
        sessions.withCurrent(checkNotNull(captured.session)) {
            synchronized(lock) {
                if (query.value == captured && floorVersion == version && mutableExpanded.value == parent && !sessions.recoveryRequired.value) {
                    mutableFloor.value = result
                }
            }
        }
    }

    override fun onCleared() {
        invalidation.close()
        synchronized(lock) { activeSource?.invalidate(); collapse() }
        super.onCleared()
    }

    private companion object { const val PAGE_SIZE = 20 }
}
