package com.ljyh.mei.parasite

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.session.MediaSession
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.runtime.ComponentRuntime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HostComponentRuntime internal constructor(
    private val enabled: Boolean,
    private val wrap: (Context) -> Context,
    private val report: (String) -> Unit,
) : ComponentRuntime {
    @Inject constructor() : this(
        BuildConfig.PARASITE_APP_ENABLED, HostRuntimeProbe::wrap, { HostRuntimeProbe.report(it) },
    )

    override fun wrapComponent(base: Context): Context =
        if (enabled && base.packageName == HostIdentity.PACKAGE) wrap(base) else base

    override fun activityCreated(activity: Activity, restored: Boolean) {
        if (enabled && activity.packageName == HostIdentity.PACKAGE) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            report("app_activity_created restored=$restored")
        }
    }

    override val deferMediaButtonsUntilRestored: Boolean get() = enabled

    override fun consumePlaybackResumeRequest(): Boolean = enabled && HostMediaButtons.consumeResumeRequest()

    override fun bindMediaButtons(context: Context, token: MediaSession.Token): AutoCloseable? =
        if (enabled) HostMediaButtons.bind(context, token) else null

    override fun playbackServiceCreated(sessionCount: Int) {
        if (enabled) report("app_music_service_created sessions=$sessionCount")
    }
}
