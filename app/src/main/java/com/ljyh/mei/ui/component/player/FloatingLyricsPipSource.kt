package com.ljyh.mei.ui.component.player

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.ljyh.mei.R
import com.ljyh.mei.constants.FloatingLyricsFontScaleKey
import com.ljyh.mei.constants.FloatingLyricsNextLineKey
import com.ljyh.mei.constants.FloatingLyricsTranslationKey
import com.ljyh.mei.runtime.LyricsPipSource
import com.ljyh.mei.ui.component.player.state.PlayerStateContainer
import com.ljyh.mei.ui.glass.GlassColors
import com.ljyh.mei.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.io.Closeable
import kotlin.math.roundToInt

/** Host-only owner of lyrics/settings/artwork. The helper never constructs a playback graph. */
internal class FloatingLyricsPipSource(
    private val activity: Activity,
    private val state: PlayerStateContainer,
    private val colors: () -> GlassColors,
) : LyricsPipSource, DefaultLifecycleObserver {
    private val player = state.playerConnection
    private val sessions = player.service.accountSessions
    private val owner = sessions.snapshot()
    private val closed = AtomicBoolean()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var preferences = emptyPreferences()
    private var cover: Bitmap? = null
    private var coverRevision = 0L
    @Volatile private var invalidation: Closeable? = null

    init {
        invalidation = sessions.onInvalidated { close() }
        if (closed.get()) invalidation?.close()
        (activity as? LifecycleOwner)?.lifecycle?.addObserver(this)
        scope.launch { activity.dataStore.data.collect { preferences = it } }
        scope.launch {
            player.mediaMetadata.collectLatest { metadata ->
                cover = null
                coverRevision++
                metadata ?: return@collectLatest
                state.playerViewModel.lyricManager.loadLyrics(metadata)
                val bitmap = runCatching {
                    SingletonImageLoader.get(activity).execute(ImageRequest.Builder(activity)
                        .data(metadata.coverUrl).size(256, 256).allowHardware(false).build()).image?.toBitmap()
                }.getOrNull() ?: return@collectLatest
                ensureActive()
                if (closed.get() || runCatching { sessions.snapshot() }.getOrNull() != owner) return@collectLatest
                val ratio = 256f / maxOf(bitmap.width, bitmap.height).coerceAtLeast(256)
                cover = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
                    (bitmap.height * ratio).roundToInt().coerceAtLeast(1), true)
                coverRevision++
            }
        }
    }

    override fun frame(previousCoverRevision: Long): Bundle? {
        if (closed.get() || sessions.recoveryRequired.value) return null
        return runCatching {
            sessions.withCurrent(owner) {
                val metadata = player.mediaMetadata.value
                val (title, translation, next) = floatingLyricsPipText(
                    state.playerViewModel.lyric.value.lyricLine.lines, player.player.currentPosition,
                    metadata?.title ?: activity.getString(R.string.lyrics_waiting),
                    preferences[FloatingLyricsTranslationKey] != false, preferences[FloatingLyricsNextLineKey] != false,
                )
                val palette = colors()
                Bundle().apply {
                    putString("title", title)
                    putString("translation", translation)
                    putString("next", next)
                    putFloat("fontScale", preferences[FloatingLyricsFontScaleKey] ?: 1f)
                    putBoolean("playing", player.isPlaying.value)
                    putBoolean("dark", palette.isDark)
                    putInt("accent", palette.accent.toArgb())
                    putLong("coverRevision", coverRevision)
                    if (previousCoverRevision != coverRevision) putParcelable("cover", cover)
                }
            }
        }.getOrNull()
    }

    override fun command(command: Int) {
        if (closed.get() || sessions.recoveryRequired.value) return
        runCatching {
            sessions.withCurrent(owner) {
                when (command) {
                    0 -> if (player.player.isPlaying) player.player.pause() else player.player.play()
                    1 -> if (player.player.hasPreviousMediaItem()) player.player.seekToPreviousMediaItem() else player.player.seekTo(0L)
                    2 -> if (player.player.hasNextMediaItem()) player.player.seekToNextMediaItem()
                    else -> error("Unknown PiP command")
                }
            }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) = close()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        invalidation?.close()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            (activity as? LifecycleOwner)?.lifecycle?.removeObserver(this)
            cover = null
        }
    }
}
