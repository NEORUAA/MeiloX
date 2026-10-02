package com.ljyh.mei.parasite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.BuildConfig
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Cross-UID read grants for synthetic files only, with no chooser or external recipient. */
class HostLogShareProviderDeviceTest {
    @Test fun hostProviderGrantsOnlyTheFixtureUrisAndRevokesThemAfterReadback() {
        assumeTrue("Requires the explicit work-probe APK and enabled TV module", BuildConfig.PARASITE_WORK_PROBE)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val externalNonce = InstrumentationRegistry.getArguments().getString("logShareNonce")
        assumeTrue("Requires an external ADB fixture trigger", externalNonce != null)
        val nonce = checkNotNull(externalNonce)
        assertEquals(nonce, UUID.fromString(nonce).toString())
        val received = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        val selected = AtomicReference<List<Uri>>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra("nonce") != nonce) return
                if (intent.action == HostWorkProbeReceiver.LOG_SHARE_CLEANED) {
                    if (!intent.getBooleanExtra("passed", false)) error.compareAndSet(null, AssertionError("Host verification failed"))
                    cleaned.countDown()
                    return
                }
                var passed = false
                try {
                    val hostUid = intent.getIntExtra("hostUid", -1)
                    assertTrue(hostUid > 0)
                    assertNotEquals(context.applicationInfo.uid, hostUid)
                    val uris = intent.getParcelableArrayListExtra("uris", Uri::class.java)!!
                    selected.set(uris)
                    assertEquals(2, uris.size)
                    for (uri in uris) {
                        assertEquals("${HostIdentity.PACKAGE}.fileprovider", uri.authority)
                        assertEquals("Synthetic host log", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
                            assertTrue(it.moveToFirst())
                            assertTrue(it.getString(0).startsWith("log-share-fixture-"))
                        }
                        assertThrows(SecurityException::class.java) {
                            context.contentResolver.openInputStream(uri.buildUpon().appendPath("ungranted").build())
                        }
                    }
                    passed = true
                } catch (failure: Throwable) { error.set(failure) }
                finally {
                    context.sendBroadcast(Intent(HostWorkProbeReceiver.LOG_SHARE_ACK).setPackage(HostIdentity.PACKAGE)
                        .putExtra("nonce", nonce).putExtra("passed", passed))
                    received.countDown()
                }
            }
        }
        val filter = IntentFilter(HostWorkProbeReceiver.LOG_SHARE_FIXTURE).apply { addAction(HostWorkProbeReceiver.LOG_SHARE_CLEANED) }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        try {
            instrumentation.sendStatus(2, Bundle().apply { putString("log_share_fixture_ready", nonce) })
            assertTrue("Fixture read callback did not arrive", received.await(45, TimeUnit.SECONDS))
            assertTrue("Host cleanup callback did not arrive", cleaned.await(15, TimeUnit.SECONDS))
            error.get()?.let { throw it }
            for (uri in selected.get()) assertThrows(SecurityException::class.java) { context.contentResolver.openInputStream(uri) }
        } finally { context.unregisterReceiver(receiver) }
    }
}
