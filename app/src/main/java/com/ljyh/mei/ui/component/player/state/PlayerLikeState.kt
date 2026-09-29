package com.ljyh.mei.ui.component.player.state

import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.PlayerLikeSource
import com.ljyh.mei.parasite.HostSessionBridge
import com.ljyh.mei.parasite.HostSessionStamp
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class PlayerLikeSnapshot(
    val songId: Long? = null,
    val owner: HostSessionStamp? = null,
    val revision: Long = 0,
    val liked: Boolean? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Owns the original player's favorite control, independently of lyrics and playback. */
internal class PlayerLikeState(
    private val scope: CoroutineScope,
    private val sessions: HostSessionBridge,
    private val source: PlayerLikeSource,
    private val onChanged: (HostSessionStamp) -> Unit,
) : Closeable {
    private val lock = Any()
    private var selectedId: Long? = null
    private var revision = 0L
    private var request: Job? = null
    private val mutableState = MutableStateFlow(PlayerLikeSnapshot())
    val state = mutableState.asStateFlow()
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = mutableMessages.asSharedFlow()
    private val invalidation = sessions.onInvalidated { generation ->
        synchronized(lock) {
            if ((state.value.owner?.generation ?: -1) < generation) clear()
        }
    }
    private val observer = scope.launch {
        combine(sessions.changes, sessions.recoveryRequired) { _, _ -> Unit }.collect {
            if (sessions.recoveryRequired.value) {
                synchronized(lock) { clear() }
                return@collect
            }
            val owner = runCatching { sessions.snapshot() }.getOrNull()
            if (owner != state.value.owner) load()
        }
    }

    fun select(songId: Long?) {
        val id = songId?.takeIf { it > 0 }
        synchronized(lock) {
            if (selectedId == id) return
            selectedId = id
            clear()
        }
        load()
    }

    private fun clear() {
        request?.cancel()
        mutableState.value = PlayerLikeSnapshot(songId = selectedId, revision = ++revision)
    }

    private fun load(notifyFailure: Boolean = false) {
        if (sessions.recoveryRequired.value) {
            synchronized(lock) { clear() }
            if (notifyFailure) mutableMessages.tryEmit("请重新登录网易云")
            return
        }
        val owner = runCatching { sessions.snapshot() }.getOrNull()
        if (owner == null) {
            synchronized(lock) { clear() }
            return
        }
        val selection = runCatching {
            sessions.withCurrent(owner) {
                synchronized(lock) {
                    clear()
                    state.value.copy(
                        owner = owner,
                        busy = selectedId != null && owner.identity.authenticated,
                        error = if (selectedId != null && !owner.identity.authenticated) "请先登录网易云" else null,
                    ).also { mutableState.value = it }
                }
            }
        }.getOrElse { return }
        val id = selection.songId ?: return
        if (!owner.identity.authenticated) {
            if (notifyFailure) mutableMessages.tryEmit("请先登录网易云")
            return
        }
        request = scope.launch {
            val result = fetch { source.checkSongLike(id, owner) }
            publish(selection) {
                resultState(it, result).also { next ->
                    if (notifyFailure && next.error != null) mutableMessages.tryEmit("读取收藏状态失败，请重试")
                }
            }
        }
    }

    fun toggle(expected: PlayerLikeSnapshot) {
        if (sessions.recoveryRequired.value) return
        val owner = expected.owner ?: return
        val id = expected.songId ?: return
        val accepted = runCatching {
            sessions.withCurrent(owner) {
                synchronized(lock) {
                    if (state.value != expected || expected.busy) false
                    else {
                        mutableState.value = expected.copy(busy = true, error = null)
                        true
                    }
                }
            }
        }.getOrDefault(false)
        if (!accepted) return
        val previous = expected.liked
        if (previous == null) {
            // Unknown state is a read retry, never an implicit "like" write.
            load(notifyFailure = true)
            return
        }
        request = scope.launch {
            val result = fetch { source.like(id, !previous, owner) }
            if (result is Resource.Success) runCatching { onChanged(owner) }
            publish(expected) {
                resultState(it, result).also { next ->
                    if (next.error != null) mutableMessages.tryEmit("收藏操作未确认，请重试查询")
                }
            }
        }
    }

    private suspend fun fetch(action: suspend () -> Resource<Boolean>): Resource<Boolean> {
        val result = try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Resource.Error("Official favorite request failed")
        }
        currentCoroutineContext().ensureActive()
        return result
    }

    private fun resultState(current: PlayerLikeSnapshot, result: Resource<Boolean>): PlayerLikeSnapshot =
        when (result) {
            is Resource.Success -> current.copy(liked = result.data, busy = false, error = null)
            is Resource.Error -> current.copy(liked = null, busy = false, error = result.message)
            Resource.Loading -> current.copy(liked = null, busy = false, error = "Incomplete favorite response")
        }

    private fun publish(expected: PlayerLikeSnapshot, update: (PlayerLikeSnapshot) -> PlayerLikeSnapshot) {
        val owner = expected.owner ?: return
        runCatching {
            sessions.withCurrent(owner) {
                synchronized(lock) {
                    val current = state.value
                    if (current.revision == expected.revision && current.owner == owner && current.songId == expected.songId) {
                        mutableState.value = update(current)
                    }
                }
            }
        }
    }

    override fun close() {
        invalidation.close()
        observer.cancel()
        synchronized(lock) { clear() }
    }
}
