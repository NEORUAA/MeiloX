package com.ljyh.mei.parasite

import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.di.DaggerAppComponent
import org.junit.Assert.assertSame
import org.junit.Test

class BackendGraphDeviceTest {
    @Test fun commonConsumersAndHostAdaptersShareOneSessionAndReportSink() {
        val graph = DaggerAppComponent.factory().create(InstrumentationRegistry.getInstrumentation().targetContext)
        val account = graph.account()
        try {
            assertSame(graph.sessions(), graph.sessions())
            assertSame(graph.sessions(), graph.hostRequests().sessions)
            assertSame(graph.sessions(), graph.playbackReports().sessions)
            assertSame(graph.sessions(), account.sessions)
            assertSame(graph.playbackReports(), graph.hostPlaybackReports())
            assertSame(graph.runtime(), graph.runtime())
            org.junit.Assert.assertTrue(graph.runtime() is HostComponentRuntime)
        } finally { account.close() }
    }
}
