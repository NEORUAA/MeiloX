package com.ljyh.mei.ui.component.player.state

import com.ljyh.mei.data.model.Tracks
import com.ljyh.mei.data.model.api.Intelligence
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.PlayerIntelligenceSource
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class IntelligencePlaybackSnapshot(
    val revision: Long = 0,
    val owner: SessionStamp? = null,
    val seed: Resource<Tracks> = Resource.Loading,
    val recommendations: Resource<Intelligence> = Resource.Loading,
)

/** Pins the seed, recommendations and one-shot playback handoff to one authorization. */
internal class IntelligencePlaybackState(
    private val scope: CoroutineScope,
    private val sessions: SessionStore,
    private val source: PlayerIntelligenceSource,
) : Closeable {
    private val lock = Any()
    private var revision = 0L
    private var work: Job? = null
    private var closed = false
    private val mutableState = MutableStateFlow(IntelligencePlaybackSnapshot())
    val state = mutableState.asStateFlow()
    private val invalidation = sessions.onInvalidated { generation ->
        synchronized(lock) {
            if ((state.value.owner?.generation ?: -1) < generation) clear()
        }
    }
    private val recovery = scope.launch {
        sessions.recoveryRequired.collect { required ->
            if (required) synchronized(lock) { clear() }
        }
    }

    fun start(id: String, playlistId: String, seedId: String, expectedSession: SessionStamp? = null) {
        val owner = expectedSession ?: runCatching { sessions.snapshot() }.getOrNull() ?: return
        if (sessions.recoveryRequired.value || !owner.identity.authenticated ||
            owner.identity.anonymous || owner.identity.userId <= 0
        ) return
        val job = runCatching {
            sessions.withCurrent(owner) {
                synchronized(lock) {
                    if (closed || sessions.recoveryRequired.value) return@withCurrent null
                    clear()
                    val expected = state.value.copy(owner = owner)
                    mutableState.value = expected
                    scope.launch(start = CoroutineStart.LAZY) {
                        val seed = fetch { source.getSongDetail(seedId, owner) }
                        if (!publish(expected) { it.copy(seed = seed) }) return@launch
                        val list = fetch { source.getIntelligenceList(id, playlistId, seedId, owner) }
                        publish(expected) { it.copy(recommendations = list) }
                    }.also { work = it }
                }
            }
        }.getOrNull()
        job?.start()
    }

    fun consume(expected: IntelligencePlaybackSnapshot, play: (SessionStamp) -> Unit): Boolean {
        val owner = expected.owner ?: return false
        val claim = runCatching {
            sessions.withCurrent(owner) {
                synchronized(lock) {
                    if (!matches(expected) || state.value != expected || expected.seed is Resource.Loading ||
                        expected.recommendations !is Resource.Success
                    ) null else {
                        clear()
                        revision
                    }
                }
            }
        }.getOrNull() ?: return false
        return runCatching {
            sessions.requireCurrent(owner)
            if (sessions.recoveryRequired.value || synchronized(lock) { closed || revision != claim }) return false
            play(owner)
            true
        }.getOrDefault(false)
    }

    private fun matches(expected: IntelligencePlaybackSnapshot) = !closed &&
        !sessions.recoveryRequired.value && state.value.owner == expected.owner &&
        state.value.revision == expected.revision

    private fun publish(
        expected: IntelligencePlaybackSnapshot,
        update: (IntelligencePlaybackSnapshot) -> IntelligencePlaybackSnapshot,
    ): Boolean {
        val owner = expected.owner ?: return false
        return runCatching {
            sessions.withCurrent(owner) {
                synchronized(lock) {
                    if (!matches(expected)) false else {
                        mutableState.value = update(state.value)
                        true
                    }
                }
            }
        }.getOrDefault(false)
    }

    private suspend fun <T> fetch(action: suspend () -> Resource<T>): Resource<T> {
        val result = try { action() } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Resource.Error("Intelligence request failed")
        }
        currentCoroutineContext().ensureActive()
        return result
    }

    private fun clear() {
        work?.cancel()
        work = null
        mutableState.value = IntelligencePlaybackSnapshot(revision = ++revision)
    }

    override fun close() {
        invalidation.close()
        recovery.cancel()
        synchronized(lock) { closed = true; clear() }
    }
}
