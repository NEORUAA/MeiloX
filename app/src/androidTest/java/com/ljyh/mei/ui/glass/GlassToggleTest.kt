package com.ljyh.mei.ui.glass

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Assert.*
import org.junit.Test

class GlassToggleTest {
    // Direct View dispatch avoids Espresso's removed InputManager API on API 37.
    private class Fixture : AutoCloseable {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        val checked = mutableStateOf(false)
        val revision = mutableStateOf(0)
        val enabled = mutableStateOf(true)
        val calls = mutableListOf<Pair<Int, Boolean>>()
        var parentClicks = 0
        var bounds = Rect.Zero
        var rendered: Triple<Boolean, Int, Boolean>? = null

        init {
            scenario.onActivity { activity ->
                activity.setContent {
                    val callbackRevision = revision.value
                    val state = Triple(checked.value, callbackRevision, enabled.value)
                    SideEffect { rendered = state }
                    GlassBackdropProvider(rememberLayerBackdrop()) {
                        Row(Modifier.clickable { parentClicks++; checked.value = !checked.value }.padding(32.dp)) {
                            GlassToggle(checked.value, {
                                calls += callbackRevision to it
                                checked.value = it
                            }, Modifier.onGloballyPositioned { bounds = it.boundsInWindow() }, enabled = enabled.value)
                        }
                    }
                }
            }
            await { bounds.width > 0 && rendered != null }
        }

        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 5_000
            do {
                var ready = false
                scenario.onActivity { ready = condition() }
                if (ready) return
                SystemClock.sleep(16)
            } while (SystemClock.uptimeMillis() < deadline)
            fail("Timed out waiting for Compose state")
        }

        fun gesture(canceled: Boolean = false, dragFraction: Float = 0f) {
            val down = SystemClock.uptimeMillis()
            fun event(action: Int, dx: Float = 0f) = scenario.onActivity { activity ->
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.center.x + dx, bounds.center.y, 0)
                try { activity.window.decorView.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            event(MotionEvent.ACTION_DOWN)
            val dx = if (canceled) 15f else bounds.width * dragFraction
            if (dx != 0f) event(MotionEvent.ACTION_MOVE, dx)
            event(if (canceled) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, dx)
        }

        override fun close() = scenario.close()
    }

    @Test fun repeatedTapsUseLatestStateAndCallbackWithoutClickingParent() = Fixture().use { fixture ->
        with(fixture) {
            gesture()
            await { rendered?.first == true }
            scenario.onActivity { revision.value = 1 }
            await { rendered?.second == 1 }
            gesture()
            await { rendered?.first == false }
            gesture()
            await { rendered?.first == true }
            scenario.onActivity {
                assertEquals(0, parentClicks)
                assertEquals(listOf(0 to true, 1 to false, 1 to true), calls)
            }
            Unit
        }
    }

    @Test fun canceledAndDisabledGesturesDoNotCommit() = Fixture().use { fixture ->
        with(fixture) {
            gesture(canceled = true)
            scenario.onActivity { assertFalse(checked.value); assertTrue(calls.isEmpty()); enabled.value = false }
            await { rendered?.third == false }
            gesture()
            scenario.onActivity { assertFalse(checked.value); assertTrue(calls.isEmpty()); assertEquals(0, parentClicks) }
            Unit
        }
    }

    @Test fun dragUsesFinalPointerPositionAndExternalUpdatesRemainAuthoritative() = Fixture().use { fixture ->
        with(fixture) {
            gesture(dragFraction = 0.45f)
            await { rendered?.first == true }
            gesture(dragFraction = -0.45f)
            await { rendered?.first == false }
            scenario.onActivity { checked.value = true }
            await { rendered?.first == true }
            gesture()
            await { rendered?.first == false }
            scenario.onActivity { assertEquals(0, parentClicks); assertEquals(listOf(true, false, false), calls.map { it.second }) }
            Unit
        }
    }
}
