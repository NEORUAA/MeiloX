package com.ljyh.mei.ui.component.player.component.applemusic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A small top-edge pull shared by the queue and its separately drawn cover. */
internal class PlayerQueueTopOverscroll(
    private val scope: CoroutineScope,
    private val limit: Float,
    private val isAtTop: () -> Boolean,
    private val onUserInput: () -> Unit = {},
    private val onListScroll: (Float) -> Unit = {},
    private val enabled: () -> Boolean,
) : NestedScrollConnection {
    var offset by mutableFloatStateOf(0f)
        private set
    private var pull = 0f
    private var releaseJob: Job? = null

    private fun interruptRelease() {
        if (releaseJob != null) {
            releaseJob?.cancel()
            releaseJob = null
            pull = 3f * limit * offset / (limit - offset).coerceAtLeast(0.001f)
        }
    }

    private fun updateOffset() {
        offset = limit * pull / (pull + 3f * limit)
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
        onUserInput()
        // Claim the first downward delta at the boundary, before lazy scrolling
        // or a pending entry alignment can choose a different motion owner.
        if (available.y > 0f) {
            if (!enabled() || !isAtTop()) return Offset.Zero
            interruptRelease()
            pull += available.y
            updateOffset()
            return Offset(0f, available.y)
        }
        if (offset <= 0f) return Offset.Zero
        interruptRelease()
        val consumed = maxOf(available.y, -pull)
        pull += consumed
        updateOffset()
        return Offset(0f, consumed)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        onListScroll(consumed.y)
        if (!enabled() || !isAtTop() || source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
        interruptRelease()
        pull += available.y
        updateOffset()
        return Offset(0f, available.y)
    }

    suspend fun release() {
        if (offset <= 0f) return
        releaseJob?.cancel()
        val job = scope.launch {
            Animatable(offset).animateTo(
                0f, spring(dampingRatio = 1f, stiffness = 400f, visibilityThreshold = 0.1f),
            ) { offset = value.coerceAtLeast(0f) }
            offset = 0f
            pull = 0f
        }
        releaseJob = job
        job.join()
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (offset <= 0f) return Velocity.Zero
        release()
        return Velocity(0f, available.y)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (offset <= 0f) return Velocity.Zero
        release()
        return Velocity(0f, available.y)
    }
}
