package com.ljyh.mei.parasite

import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.playback.PlaybackReportSink
import dagger.Module
import dagger.Provides

/** Parasite bindings; standalone supplies its own bindings when flavors are separated. */
@Module
object RuntimeBackendModule {
    @Provides fun sessions(host: HostSessionBridge): SessionStore = host
    @Provides fun playbackReports(host: HostPlaybackReportBridge): PlaybackReportSink = host
}
