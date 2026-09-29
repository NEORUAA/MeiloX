package com.ljyh.mei.parasite

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.media.session.MediaSession
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class HostComponentRuntimeDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private class IdentityContext(base: Context, private val identity: String) : ContextWrapper(base) {
        override fun getPackageName() = identity
        override fun startForegroundService(service: Intent): ComponentName? = service.component
    }

    private class TestActivity(private val identity: String) : Activity() {
        var orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        override fun getPackageName() = identity
        override fun setRequestedOrientation(value: Int) { orientation = value }
    }

    @Test fun wrappingRequiresBothTheEnabledRuntimeAndHostIdentity() {
        val base = instrumentation.targetContext
        val host = IdentityContext(base, HostIdentity.PACKAGE)
        val foreign = IdentityContext(base, "unrelated.package")
        val wrapped = ContextWrapper(base)
        var wraps = 0
        val enabled = HostComponentRuntime(true, { wraps++; assertSame(host, it); wrapped }, {})
        val disabled = HostComponentRuntime(false, { error("Disabled runtime wrapped a context") }, {})
        assertSame(wrapped, enabled.wrapComponent(host))
        assertSame(foreign, enabled.wrapComponent(foreign))
        assertSame(host, disabled.wrapComponent(host))
        assertEquals(1, wraps)
        assertTrue(enabled.deferMediaButtonsUntilRestored)
        assertFalse(disabled.deferMediaButtonsUntilRestored)
    }

    @Test fun activityAndServiceCallbacksRetainTheExistingHostOnlyBehavior() {
        val reports = mutableListOf<String>()
        val enabled = HostComponentRuntime(true, { it }, reports::add)
        val disabled = HostComponentRuntime(false, { it }, reports::add)
        instrumentation.runOnMainSync {
            val host = TestActivity(HostIdentity.PACKAGE)
            val foreign = TestActivity("unrelated.package")
            disabled.activityCreated(host, false)
            enabled.activityCreated(foreign, false)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, host.orientation)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, foreign.orientation)
            assertTrue(reports.isEmpty())
            enabled.activityCreated(host, true)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, host.orientation)
        }
        disabled.playbackServiceCreated(2)
        enabled.playbackServiceCreated(1)
        assertEquals(listOf("app_activity_created restored=true", "app_music_service_created sessions=1"), reports)
    }

    @Test fun disabledRuntimeNeitherConsumesResumeOwnershipNorBindsAController() {
        val base = instrumentation.targetContext
        val context = IdentityContext(base, HostIdentity.PACKAGE)
        val disabled = HostComponentRuntime(false, { it }, {})
        val enabled = HostComponentRuntime(true, { it }, {})
        val session = MediaSession(base, "MeiloXRuntimeBindingTest")
        try {
            HostMediaButtons.consumeResumeRequest()
            HostMediaButtons.receive(context, Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(
                Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)), {})
            assertFalse(disabled.consumePlaybackResumeRequest())
            assertNull(disabled.bindMediaButtons(base, session.sessionToken))
            assertTrue(enabled.consumePlaybackResumeRequest())
            assertFalse(enabled.consumePlaybackResumeRequest())
        } finally {
            HostMediaButtons.consumeResumeRequest()
            session.release()
        }
    }
}
