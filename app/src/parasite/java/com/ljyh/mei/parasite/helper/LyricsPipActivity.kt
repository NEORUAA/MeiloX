package com.ljyh.mei.parasite.helper

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import com.ljyh.mei.R
import com.ljyh.mei.ui.component.player.FloatingLyricsPipBackdrop
import com.ljyh.mei.ui.component.player.FloatingLyricsPipContent
import com.ljyh.mei.ui.glass.GlassBackdropHost
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.defaultGlassColors
import com.ljyh.mei.ui.theme.MusicTheme
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LyricsPipActivity : ComponentActivity() {
    private var endpoint: IBinder? = null
    private val lifetime = Binder()
    private val token = UUID.randomUUID().toString()
    private var entered = false
    private var wasInPip = false
    private var revision = -1L
    private var cover: Bitmap? = null
    private val frame = MutableStateFlow<Frame?>(null)
    private val death = IBinder.DeathRecipient { runOnUiThread { finish() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (runCatching { HelperCaller.requireActivity(this) }.isFailure) { finish(); return }
        endpoint = intent.extras?.getBinder(LyricsPipProtocol.ENDPOINT)
        if (runCatching { requireNotNull(endpoint).linkToDeath(death, 0) }.isFailure) { finish(); return }
        commands[token] = { command -> transact(LyricsPipProtocol.COMMAND, { writeInt(command) }) { Unit } }
        setContent {
            val current by frame.collectAsState()
            current?.let { content ->
                MusicTheme(seedColor = Color(content.accent), isDark = content.dark) {
                    CompositionLocalProvider(LocalGlassColors provides defaultGlassColors(content.dark, Color(content.accent))) {
                        GlassBackdropHost(
                            modifier = Modifier.fillMaxSize(),
                            sampledContent = { FloatingLyricsPipBackdrop(content.cover) },
                            overlayContent = { FloatingLyricsPipContent(content.title, content.translation, content.next, content.scale) },
                        )
                    }
                }
            }
        }
        lifecycleScope.launch {
            while (isActive && !isFinishing) {
                val next = withContext(Dispatchers.IO) { runCatching { readFrame() }.getOrNull() }
                if (next == null) { finish(); break }
                frame.value = next
                setPictureInPictureParams(params(next.playing))
                if (!entered) {
                    entered = true
                    window.decorView.doOnPreDraw {
                        if (!isFinishing && !enterPictureInPictureMode(params(next.playing))) finish()
                    }
                }
                delay(if (next.playing) 120L else 500L)
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) wasInPip = true
        else if (wasInPip) finish()
    }

    private fun readFrame(): Frame? {
        val value = transact(LyricsPipProtocol.FRAME, { writeStrongBinder(lifetime); writeLong(revision) }) {
            readBundle(Bitmap::class.java.classLoader)
        } ?: return null
        val coverRevision = value.getLong("coverRevision")
        if (coverRevision != revision) {
            cover = value.getParcelable("cover", Bitmap::class.java)
            require(cover == null || (cover!!.width <= 256 && cover!!.height <= 256))
            revision = coverRevision
        }
        val title = requireNotNull(value.getString("title")).also { require(it.length <= 16_384) }
        val scale = value.getFloat("fontScale", 1f).also { require(it.isFinite() && it in .25f..4f) }
        val translation = value.getString("translation")?.also { require(it.length <= 16_384) }
        val next = value.getString("next")?.also { require(it.length <= 16_384) }
        return Frame(title, translation, next, scale,
            value.getBoolean("playing"), value.getBoolean("dark"), value.getInt("accent"), cover)
    }

    private fun params(playing: Boolean): PictureInPictureParams {
        fun action(command: Int, icon: Int, title: Int): RemoteAction {
            val label = getString(title)
            val intent = Intent(this, LyricsPipActionReceiver::class.java)
                .setData(Uri.parse("meilox-pip://$token/$command")).putExtra("token", token).putExtra("command", command)
            return RemoteAction(Icon.createWithResource(this, icon), label, label,
                PendingIntent.getBroadcast(this, command, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).setSeamlessResizeEnabled(true)
            .setActions(listOf(
                action(1, android.R.drawable.ic_media_previous, R.string.pip_previous),
                action(0, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playing) R.string.pip_pause else R.string.pip_play),
                action(2, android.R.drawable.ic_media_next, R.string.pip_next),
            )).build()
    }

    private fun <T> transact(code: Int, payload: Parcel.() -> Unit, read: Parcel.() -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(LyricsPipProtocol.DESCRIPTOR)
            data.payload()
            check(requireNotNull(endpoint).transact(code, data, reply, 0))
            reply.readException()
            return reply.read()
        } finally { data.recycle(); reply.recycle() }
    }

    override fun onDestroy() {
        commands.remove(token)
        val remote = endpoint
        endpoint = null
        runCatching { remote?.unlinkToDeath(death, 0) }
        if (remote != null) {
            cleanupScope.launch {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(LyricsPipProtocol.DESCRIPTOR)
                    remote.transact(LyricsPipProtocol.CLOSE, data, reply, 0)
                } catch (_: Exception) { /* The host may have exited first. */ }
                finally { data.recycle(); reply.recycle() }
            }
        }
        cover = null
        super.onDestroy()
    }

    private data class Frame(val title: String, val translation: String?, val next: String?, val scale: Float,
        val playing: Boolean, val dark: Boolean, val accent: Int, val cover: Bitmap?)

    internal companion object {
        private val commands = ConcurrentHashMap<String, (Int) -> Unit>()
        private val cleanupScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        fun command(token: String?, command: Int) { if (token != null && command in 0..2) commands[token]?.invoke(command) }
    }
}

class LyricsPipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try { runCatching { LyricsPipActivity.command(intent.getStringExtra("token"), intent.getIntExtra("command", -1)) } }
            finally { pending.finish() }
        }
    }
}
