package com.ljyh.mei.standalone

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.runtime.ComponentRuntime
import com.ljyh.mei.runtime.StandaloneComponentRuntime
import com.ljyh.mei.ui.screen.log.LogViewModel
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class StandaloneLogShareDeviceTest {
    @Test fun originalProviderReadsTheSelectedPrivateLogWithoutStagingACopy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "app_logs/log-share-fixture-${UUID.randomUUID()}.txt")
        file.parentFile.mkdirs()
        try {
            file.writeText("Synthetic standalone log")
            val uri = StandaloneComponentRuntime().logShareUri(context, file)
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.fileprovider", uri.authority)
            assertTrue(uri.path.orEmpty().startsWith("/my_logs/app_logs/"))
            val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            assertEquals("Synthetic standalone log", text)
            assertEquals(text, file.readText())
        } finally { file.delete() }
    }

    @Test fun sharedLogActionRetainsTheOriginalChooserAndReadGrantWithoutSending() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val file = File(base.filesDir, "app_logs/log-share-action-fixture-${UUID.randomUUID()}.txt")
        val launched = CountDownLatch(1)
        val intent = AtomicReference<Intent>()
        val context = object : ContextWrapper(base) {
            override fun startActivity(value: Intent) { intent.set(value); launched.countDown() }
        }
        val standalone = StandaloneComponentRuntime()
        val runtime = object : ComponentRuntime by standalone {
            override fun logShareUri(context: Context, file: File): Uri {
                check(Looper.myLooper() != Looper.getMainLooper())
                return standalone.logShareUri(context, file)
            }
        }
        val store = ViewModelStore()
        file.parentFile.mkdirs()
        try {
            file.writeText("Synthetic chooser log")
            instrumentation.runOnMainSync {
                val model = LogViewModel(context, runtime)
                store.put("log-share-action", model)
                model.readContent(file)
                model.shareCurrentFile()
            }
            assertTrue(launched.await(10, TimeUnit.SECONDS))
            val chooser = intent.get()
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
            assertEquals("${base.packageName}.fileprovider", uri.authority)
            assertEquals("Synthetic chooser log", base.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            file.delete()
        }
    }
}
