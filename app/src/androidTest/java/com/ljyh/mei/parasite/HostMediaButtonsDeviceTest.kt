package com.ljyh.mei.parasite

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.session.MediaSession
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostMediaButtonsDeviceTest {
    private class ReceiverContext(base: Context) : ContextWrapper(base) {
        val starts = mutableListOf<Intent>()
        var rejectStart = false
        var identity = HostIdentity.PACKAGE
        override fun getPackageName() = identity
        override fun startForegroundService(service: Intent): ComponentName? {
            if (rejectStart) throw IllegalStateException("Not allowed")
            starts += service
            return service.component
        }
    }

    private fun key(code: Int, action: Int = KeyEvent.ACTION_DOWN) =
        Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, code))

    @Test fun coldReceiverStartsOnlyTheRegisteredCarrierAndHandlesRejection() {
        val context = ReceiverContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val reports = mutableListOf<String>()
        HostMediaButtons.receive(context, key(KeyEvent.KEYCODE_MEDIA_PAUSE), reports::add)
        HostMediaButtons.receive(context, key(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.ACTION_UP), reports::add)
        HostMediaButtons.receive(context, Intent(Intent.ACTION_MEDIA_BUTTON), reports::add)
        HostMediaButtons.receive(context, Intent("unrelated"), reports::add)
        context.identity = "unrelated.package"
        HostMediaButtons.receive(context, key(KeyEvent.KEYCODE_MEDIA_PLAY), reports::add)
        assertTrue(context.starts.isEmpty())
        context.identity = HostIdentity.PACKAGE
        HostMediaButtons.receive(context, key(KeyEvent.KEYCODE_MEDIA_PLAY).putExtra("untrusted", "discard"), reports::add)
        assertEquals(1, context.starts.size)
        val routed = context.starts.single()
        assertEquals(ComponentName(HostIdentity.PACKAGE, HostComponentMapping.SERVICE), routed.component)
        assertEquals(Intent.ACTION_MEDIA_BUTTON, routed.action)
        assertFalse(routed.hasExtra("untrusted"))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY, routed.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)!!.keyCode)
        assertTrue(HostMediaButtons.consumeResumeRequest())
        assertFalse(HostMediaButtons.consumeResumeRequest())
        context.rejectStart = true
        HostMediaButtons.receive(context, key(KeyEvent.KEYCODE_MEDIA_PLAY), reports::add)
        assertTrue(reports.last().contains("start_rejected"))
        assertFalse(HostMediaButtons.consumeResumeRequest())
    }

    @Test fun liveEventsReachTheBoundSessionAndOldDisposalCannotUnbindItsReplacement() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = ReceiverContext(base)
        val first = MediaSession(base, "MeiloXMediaButtonTest1")
        val second = MediaSession(base, "MeiloXMediaButtonTest2")
        val delivered = CountDownLatch(1)
        val received = mutableListOf<Int>()
        second.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(intent: Intent): Boolean {
                synchronized(received) {
                    received += intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)!!.keyCode
                }
                delivered.countDown()
                return true
            }
        }, Handler(Looper.getMainLooper()))
        val old = HostMediaButtons.bind(base, first.sessionToken)
        val current = HostMediaButtons.bind(base, second.sessionToken)
        try {
            old.close()
            HostMediaButtons.receive(context, key(KeyEvent.KEYCODE_MEDIA_PAUSE)) {}
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_PAUSE), synchronized(received) { received.toList() })
            assertTrue(context.starts.isEmpty())
            current.close()
            HostMediaButtons.receive(context, key(KeyEvent.KEYCODE_MEDIA_PLAY)) {}
            assertEquals(1, context.starts.size)
            assertTrue(HostMediaButtons.consumeResumeRequest())
        } finally {
            old.close()
            current.close()
            HostMediaButtons.consumeResumeRequest()
            first.release()
            second.release()
        }
    }
}
