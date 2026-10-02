package com.ljyh.mei.parasite

import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.di.DaggerAppComponent
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BackendGraphDeviceTest {
    @Test fun storageProbeReopensTheCurrentSchemaAndCleansOnlyItsFixture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sentinel = "__runtime_storage_probe___sentinel_${UUID.randomUUID()}"
        try {
            context.openOrCreateDatabase(sentinel, android.content.Context.MODE_PRIVATE, null).use {
                it.execSQL("CREATE TABLE marker (value INTEGER NOT NULL)")
                it.execSQL("INSERT INTO marker VALUES (7)")
            }
            val before = context.databaseList().filter { it.startsWith("__runtime_storage_probe__") }.toSet()
            repeat(2) { ModuleStorageProbe.verifyRoom(context) }
            assertEquals(before, context.databaseList().filter { it.startsWith("__runtime_storage_probe__") }.toSet())
            context.openOrCreateDatabase(sentinel, android.content.Context.MODE_PRIVATE, null).use {
                it.rawQuery("SELECT value FROM marker", null).use { cursor ->
                    check(cursor.moveToFirst())
                    assertEquals(7, cursor.getInt(0))
                }
            }
        } finally { context.deleteDatabase(sentinel) }
    }

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
