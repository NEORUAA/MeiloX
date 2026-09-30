package com.ljyh.mei.ui.component.player.state

import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.PlayerLikeSource
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionStamp
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
    val owner: SessionStamp? = null,
    val revision: Long = 0,
    val liked: Boolean? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val sourceKey: String? = null,
)

/** Owns the original player's favorite control, independently of lyrics and playback. */
internal class PlayerLikeState(
    private val scope: CoroutineScope,
    private val sessions: SessionStore,
    private val source: PlayerLikeSource,
    private val onChanged: (SessionStamp) -> Unit,
) : Closeable {
    private val lock = Any()
    private var selectedSource: SongSourceIdentity? = null
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
        selectSource(songId?.takeIf { it > 0 }?.let { SongSourceIdentity(it) })
    }

    fun selectSource(source: SongSourceIdentity?) {
        val valid = source?.takeIf { runCatching { it.key }.isSuccess }
        synchronized(lock) {
            if (selectedSource == valid) return
            selectedSource = valid
            clear()
        }
        load()
    }

    private fun clear() {
        request?.cancel()
        mutableState.value = PlayerLikeSnapshot(songId = selectedSource?.entryId,
            sourceKey = selectedSource?.key, revision = ++revision)
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
                    val allowed = owner.identity.authenticated && !owner.identity.anonymous &&
                        owner.identity.userId > 0 && runCatching {
                            selectedSource?.requireAccount(owner.identity)
                        }.isSuccess
                    state.value.copy(
                        owner = owner,
                        busy = selectedSource != null && allowed,
                        error = if (selectedSource != null && !allowed) "Sign-in or current cloud source required" else null,
                    ).also { mutableState.value = it }
                }
            }
        }.getOrElse { return }
        val song = selection.sourceKey?.let { SongSourceIdentity.fromKey(it) } ?: return
        if (selection.error != null) {
            if (notifyFailure) mutableMessages.tryEmit("请先登录网易云")
            return
        }
        request = scope.launch {
            val result = fetch { source.checkSongLike(song, owner) }
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
        val song = expected.sourceKey?.let { SongSourceIdentity.fromKey(it) } ?: return
        val accepted = runCatching {
            sessions.withCurrent(owner) {
                synchronized(lock) {
                    if (state.value != expected || expected.busy ||
                        runCatching { song.requireAccount(owner.identity) }.isFailure) false
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
            val result = fetch { source.like(song, !previous, owner) }
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
                    if (current.revision == expected.revision && current.owner == owner &&
                        current.sourceKey == expected.sourceKey) {
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
