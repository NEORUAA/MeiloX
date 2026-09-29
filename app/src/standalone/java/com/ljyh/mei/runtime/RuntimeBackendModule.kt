package com.ljyh.mei.runtime

import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.playback.PlaybackReportSink
import com.ljyh.mei.standalone.DataStoreAccountPersistence
import com.ljyh.mei.standalone.StandaloneAccountPersistence
import com.ljyh.mei.standalone.StandalonePlaybackReports
import com.ljyh.mei.standalone.StandaloneSessionStore
import com.ljyh.mei.standalone.StandaloneTransport
import dagger.Module
import dagger.Provides
import javax.inject.Named
import okhttp3.Call

@Module
object RuntimeBackendModule {
    @Provides internal fun persistence(store: DataStoreAccountPersistence): StandaloneAccountPersistence = store
    @Provides fun sessions(store: StandaloneSessionStore): SessionStore = store
    @Provides internal fun playbackReports(reports: StandalonePlaybackReports): PlaybackReportSink = reports
    @Provides fun components(runtime: StandaloneComponentRuntime): ComponentRuntime = runtime

    @Provides @Named("NetEaseApiCalls")
    internal fun apiCalls(transport: StandaloneTransport): Call.Factory = transport.business

    @Provides @Named("NetEaseWeApiCalls")
    internal fun weApiCalls(transport: StandaloneTransport): Call.Factory = transport.business

    @Provides @Named("AudioMatchCalls")
    internal fun audioMatchCalls(transport: StandaloneTransport): Call.Factory = transport.audioMatch
}
