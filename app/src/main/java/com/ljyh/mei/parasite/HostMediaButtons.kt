package com.ljyh.mei.parasite

import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSession
import android.view.KeyEvent

internal fun canResumeFromMediaButton(action: Int, keyCode: Int, repeatCount: Int): Boolean =
    action == KeyEvent.ACTION_DOWN && repeatCount == 0 && keyCode in setOf(
        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
    )

/** The registered TV receiver carries events; Media3 still interprets the keys. */
internal object HostMediaButtons {
    @Volatile private var controller: MediaController? = null
    @Volatile private var resumeRequested = false

    @Synchronized
    fun consumeResumeRequest(): Boolean = resumeRequested.also { resumeRequested = false }

    @Synchronized
    fun bind(context: Context, token: MediaSession.Token): AutoCloseable {
        val bound = MediaController(context, token)
        controller = bound
        return AutoCloseable { synchronized(this) { if (controller === bound) controller = null } }
    }

    fun receive(context: Context, intent: Intent?, report: (String) -> Unit) {
        if (context.packageName != HostIdentity.PACKAGE || intent?.action != Intent.ACTION_MEDIA_BUTTON) return
        val event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return
        val active = controller
        if (active != null) {
            active.dispatchMediaButtonEvent(event)
            return
        }
        // Match Media3's API-26+ receiver policy without querying undeclared service filters.
        if (!canResumeFromMediaButton(event.action, event.keyCode, event.repeatCount)) return
        val routed = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setClassName(HostIdentity.PACKAGE, HostComponentMapping.SERVICE)
            .putExtra(Intent.EXTRA_KEY_EVENT, event)
        try {
            resumeRequested = true
            context.startForegroundService(routed)
            report("module_media_button_resume")
        } catch (error: IllegalStateException) {
            resumeRequested = false
            report("module_media_button_start_rejected type=${error.javaClass.simpleName}")
        } catch (error: SecurityException) {
            resumeRequested = false
            report("module_media_button_start_rejected type=${error.javaClass.simpleName}")
        }
    }
}
