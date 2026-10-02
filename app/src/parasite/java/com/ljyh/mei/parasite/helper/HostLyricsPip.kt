package com.ljyh.mei.parasite.helper

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import com.ljyh.mei.runtime.LyricsPipSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object HostLyricsPip {
    private var active: Endpoint? = null

    fun enter(activity: Activity, source: LyricsPipSource) {
        active?.close()
        val endpoint = Endpoint(activity.applicationContext, source).also { active = it }
        runCatching {
            activity.startActivityForResult(Intent().setComponent(ComponentName(MicrophoneProtocol.MODULE, LyricsPipProtocol.ACTIVITY))
                .putExtras(Bundle().apply { putBinder(LyricsPipProtocol.ENDPOINT, endpoint) }), 705)
        }.onFailure { endpoint.close() }
    }

    private class Endpoint(private val context: Context, private var source: LyricsPipSource?) : Binder(), AutoCloseable {
        private val main = Handler(Looper.getMainLooper())
        private var peer: IBinder? = null
        private val death = IBinder.DeathRecipient { main.post { close() } }
        private val activationTimeout = Runnable { if (peer == null) close() }

        init { main.postDelayed(activationTimeout, 20_000L) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(LyricsPipProtocol.DESCRIPTOR); return true }
            if (code !in LyricsPipProtocol.FRAME..LyricsPipProtocol.CLOSE) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(LyricsPipProtocol.DESCRIPTOR)
            val callers = context.packageManager.getPackagesForUid(getCallingUid())?.toList()
            require(callers == listOf(MicrophoneProtocol.MODULE)) { "Untrusted PiP caller" }
            when (code) {
                LyricsPipProtocol.FRAME -> {
                    val lifetime = requireNotNull(data.readStrongBinder())
                    val coverRevision = data.readLong()
                    data.enforceNoDataAvail()
                    val frame = onMain {
                        if (source == null) null else {
                            if (peer == null) { lifetime.linkToDeath(death, 0); peer = lifetime }
                            check(peer == lifetime) { "PiP lifetime changed" }
                            source?.frame(coverRevision)
                        }
                    }
                    reply!!.writeNoException()
                    reply.writeBundle(frame)
                }
                LyricsPipProtocol.COMMAND -> {
                    val command = data.readInt()
                    data.enforceNoDataAvail()
                    require(command in 0..2)
                    onMain { source?.command(command) }
                    reply!!.writeNoException()
                }
                LyricsPipProtocol.CLOSE -> {
                    data.enforceNoDataAvail()
                    main.post { close() }
                    reply!!.writeNoException()
                }
            }
            return true
        }

        private fun <T> onMain(block: () -> T): T? {
            if (Looper.myLooper() == Looper.getMainLooper()) return block()
            val latch = CountDownLatch(1)
            val pending = AtomicBoolean(true)
            var value: T? = null
            var error: Throwable? = null
            val work = Runnable {
                if (!pending.compareAndSet(true, false)) return@Runnable
                try { value = block() } catch (failure: Throwable) { error = failure } finally { latch.countDown() }
            }
            main.post(work)
            if (!latch.await(2, TimeUnit.SECONDS)) {
                pending.set(false)
                main.removeCallbacks(work)
                error("PiP host response timed out")
            }
            error?.let { throw it }
            return value
        }

        override fun close() {
            check(Looper.myLooper() == Looper.getMainLooper())
            main.removeCallbacks(activationTimeout)
            source?.close()
            source = null
            runCatching { peer?.unlinkToDeath(death, 0) }
            peer = null
            if (active === this) active = null
        }
    }
}
