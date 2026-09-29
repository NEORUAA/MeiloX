package com.ljyh.mei.parasite

import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.playback.PlaybackReportSink
import com.ljyh.mei.runtime.ComponentRuntime
import dagger.Module
import dagger.Provides
import javax.inject.Named
import okhttp3.Call

/** Parasite bindings; standalone supplies its own bindings when flavors are separated. */
@Module
object RuntimeBackendModule {
    @Provides fun sessions(host: HostSessionBridge): SessionStore = host
    @Provides fun playbackReports(host: HostPlaybackReportBridge): PlaybackReportSink = host
    @Provides fun components(host: HostComponentRuntime): ComponentRuntime = host

    @Provides @Named("NetEaseApiCalls")
    fun apiCalls(host: HostCallFactory): Call.Factory = host

    @Provides @Named("NetEaseWeApiCalls")
    fun weApiCalls(host: HostCallFactory): Call.Factory = host

    @Provides @Named("AudioMatchCalls")
    fun audioMatchCalls(host: HostCallFactory): Call.Factory = host
}
