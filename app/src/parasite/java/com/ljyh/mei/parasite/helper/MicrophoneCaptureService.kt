package com.ljyh.mei.parasite.helper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.ljyh.mei.R
import com.ljyh.mei.recognition.SongRecognitionRecorder
import java.io.DataOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MicrophoneCaptureService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var token: String? = null
    private var uid = -1
    private var recording: Job? = null
    private var writer: ParcelFileDescriptor? = null
    private var client: IBinder? = null
    private var clientDeath: IBinder.DeathRecipient? = null
    private var sequence = 0L
    private val timeout = Runnable { shutdown() }

    private val endpoint = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(MicrophoneProtocol.DESCRIPTOR); return true }
            if (code != MicrophoneProtocol.RECORD && code != MicrophoneProtocol.CLOSE) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(MicrophoneProtocol.DESCRIPTOR)
            val caller = HelperCaller.requireHost(this@MicrophoneCaptureService)
            val grant = data.readString()
            synchronized(lock) {
                check(grant != null && token == grant && caller == uid) { "Recording authorization expired" }
                when (code) {
                    MicrophoneProtocol.RECORD -> {
                        val seconds = data.readInt()
                        val lifetime = requireNotNull(data.readStrongBinder())
                        data.enforceNoDataAvail()
                        require(seconds in 1..MicrophoneProtocol.MAX_SECONDS)
                        check(recording == null) { "Recording already in progress" }
                        if (client == null) {
                            val death = IBinder.DeathRecipient { handler.post { shutdown(grant) } }
                            lifetime.linkToDeath(death, 0)
                            client = lifetime
                            clientDeath = death
                        }
                        check(client == lifetime) { "Recording lifetime changed" }
                        val pipe = ParcelFileDescriptor.createReliablePipe()
                        val expected = ++sequence
                        writer = pipe[1]
                        recording = scope.launch(start = CoroutineStart.LAZY) {
                            try {
                                val samples = SongRecognitionRecorder(this@MicrophoneCaptureService).record(seconds)
                                DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])).use { output ->
                                    output.writeInt(samples.size)
                                    samples.forEach(output::writeFloat)
                                }
                            } catch (error: Exception) {
                                runCatching { pipe[1].closeWithError("Microphone capture failed") }
                            } finally {
                                synchronized(lock) {
                                    if (sequence == expected) { recording = null; writer = null }
                                }
                                handler.post {
                                    synchronized(lock) { if (sequence == expected && token == grant) expireAfter(45_000) }
                                }
                            }
                        }
                        recording!!.start()
                        handler.post {
                            synchronized(lock) { if (sequence == expected && token == grant) expireAfter((seconds + 10) * 1_000L) }
                        }
                        reply!!.writeNoException()
                        reply.writeTypedObject(pipe[0], 0)
                        pipe[0].close()
                    }
                    MicrophoneProtocol.CLOSE -> {
                        data.enforceNoDataAvail()
                        handler.post { shutdown(grant) }
                        reply!!.writeNoException()
                    }
                }
            }
            return true
        }
    }

    override fun onBind(intent: Intent): IBinder = endpoint

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val grant = intent?.getStringExtra(MicrophoneProtocol.TOKEN)
        val owner = grant?.let { MicrophoneGrants.instance.consume(it) }
        if (owner == null) { if (token == null) stopSelf(); return START_NOT_STICKY }
        synchronized(lock) {
            releaseCapture()
            token = grant
            uid = owner
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.song_recognition), NotificationManager.IMPORTANCE_LOW))
        startForeground(704, Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.song_recognition)).setOngoing(true).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        expireAfter(20_000)
        return START_NOT_STICKY
    }

    private fun expireAfter(milliseconds: Long) {
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, milliseconds)
    }

    private fun shutdown(expected: String? = null) {
        synchronized(lock) {
            if (expected != null && expected != token) return
            releaseCapture()
            token = null
            uid = -1
        }
        handler.removeCallbacks(timeout)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseCapture() {
        sequence++
        recording?.cancel()
        recording = null
        runCatching { writer?.closeWithError("Recording cancelled") }
        writer = null
        clientDeath?.let { death -> runCatching { client?.unlinkToDeath(death, 0) } }
        clientDeath = null
        client = null
    }

    override fun onDestroy() {
        shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private companion object { const val CHANNEL = "recognition_capture" }
}
