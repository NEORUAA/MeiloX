package com.ljyh.mei.parasite.helper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.ljyh.mei.recognition.RecognitionCapture
import com.ljyh.mei.recognition.RecognitionRecording
import java.io.DataInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class HostRecognitionCapture(private val context: Context) : RecognitionCapture {
    private var nonce: String? = null
    private var pending: Lease? = null

    override fun permissionIntent(): Intent {
        close()
        val token = UUID.randomUUID().toString().also { nonce = it }
        return Intent().setComponent(ComponentName(MicrophoneProtocol.MODULE, MicrophoneProtocol.ACTIVITY))
            .putExtra(MicrophoneProtocol.TOKEN, token)
    }

    override fun acceptPermission(result: Intent?): Boolean {
        val token = result?.getStringExtra(MicrophoneProtocol.TOKEN)
        if (token == null || nonce != token) return false
        nonce = null
        pending = Lease(context, token)
        return true
    }

    override fun open(): RecognitionRecording = requireNotNull(pending) { "Microphone authorization is required" }
        .also { pending = null }

    override fun discardPermission(result: Intent?) {
        val token = result?.getStringExtra(MicrophoneProtocol.TOKEN)
        if (token != null && runCatching { UUID.fromString(token).toString() == token }.getOrDefault(false)) {
            Lease(context, token).close()
        }
        close()
    }

    override fun close() {
        nonce = null
        pending?.close()
        pending = null
    }

    private class Lease(private val context: Context, private val token: String) : RecognitionRecording {
        private val handler = Handler(Looper.getMainLooper())
        private val connected = CompletableDeferred<IBinder>()
        private val closed = AtomicBoolean()
        private val lifetime = Binder()
        private var bound = false
        private var remote: IBinder? = null
        private val bindingTimeout = Runnable {
            connected.completeExceptionally(IllegalStateException("Microphone helper did not connect"))
            unbind()
        }
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                handler.removeCallbacks(bindingTimeout)
                remote = service
                if (closed.get()) { sendClose(service); unbind() }
                else connected.complete(service)
            }
            override fun onServiceDisconnected(name: ComponentName) {
                connected.completeExceptionally(IllegalStateException("Microphone helper disconnected"))
                close()
            }
            override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
            override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
        }

        init {
            check(Looper.myLooper() == Looper.getMainLooper())
            bound = context.bindService(Intent().setComponent(ComponentName(MicrophoneProtocol.MODULE, MicrophoneProtocol.SERVICE)),
                connection, Context.BIND_AUTO_CREATE)
            if (!bound) connected.completeExceptionally(IllegalStateException("Microphone helper is unavailable"))
            else handler.postDelayed(bindingTimeout, 3_000)
        }

        override suspend fun record(seconds: Int): FloatArray = withContext(Dispatchers.IO) {
            require(seconds in 1..MicrophoneProtocol.MAX_SECONDS)
            check(!closed.get()) { "Recording session closed" }
            val endpoint = withTimeout(3_000) { connected.await() }
            val pipe = transact(endpoint, MicrophoneProtocol.RECORD, {
                writeInt(seconds)
                writeStrongBinder(lifetime)
            }) { requireNotNull(readTypedObject(ParcelFileDescriptor.CREATOR)) }
            val stream = DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(pipe))
            coroutineScope {
                suspendCancellableCoroutine { continuation ->
                    val reader = launch(Dispatchers.IO) {
                        try {
                            val samples = stream.use {
                                val size = it.readInt()
                                check(size == seconds * MicrophoneProtocol.SAMPLE_RATE) { "Invalid microphone sample count" }
                                FloatArray(size) { _ -> it.readFloat().also { sample -> check(sample.isFinite()) } }
                            }
                            if (continuation.isActive) continuation.resume(samples)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                    continuation.invokeOnCancellation {
                        runCatching { stream.close() }
                        reader.cancel()
                        close()
                    }
                }
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            handler.post {
                remote?.let { sendClose(it); unbind() }
                // A pending connection sends CLOSE before unbinding; an absent helper times out.
                if (!bound) connected.cancel()
            }
        }

        private fun sendClose(endpoint: IBinder) {
            runCatching { transact(endpoint, MicrophoneProtocol.CLOSE, {}, { Unit }) }
        }

        private fun unbind() {
            handler.removeCallbacks(bindingTimeout)
            if (bound) { bound = false; runCatching { context.unbindService(connection) } }
            remote = null
        }

        private fun <T> transact(endpoint: IBinder, code: Int, payload: Parcel.() -> Unit, result: Parcel.() -> T): T {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(MicrophoneProtocol.DESCRIPTOR)
                data.writeString(token)
                data.payload()
                check(endpoint.transact(code, data, reply, 0)) { "Microphone protocol unavailable" }
                reply.readException()
                return reply.result()
            } finally { data.recycle(); reply.recycle() }
        }
    }
}
