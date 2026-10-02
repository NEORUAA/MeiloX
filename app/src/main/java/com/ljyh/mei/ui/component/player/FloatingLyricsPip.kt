package com.ljyh.mei.ui.component.player

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Rational
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.ljyh.mei.R
import com.ljyh.mei.constants.FloatingLyricsFontScaleKey
import com.ljyh.mei.constants.FloatingLyricsNextLineKey
import com.ljyh.mei.constants.FloatingLyricsTranslationKey
import com.ljyh.mei.playback.MusicService
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.di.AppGraph
import com.ljyh.mei.ui.component.player.state.PlayerStateContainer
import com.ljyh.mei.ui.glass.GlassColors
import com.ljyh.mei.ui.glass.GlassCard
import com.ljyh.mei.utils.rememberPreference
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

fun enterFloatingLyricsPip(context: Context, isPlaying: Boolean, state: PlayerStateContainer, colors: () -> GlassColors) {
    val activity = context as? Activity ?: return
    val runtime = AppGraph.component.runtime()
    if (runtime.usesLyricsPipHelper) {
        runCatching { FloatingLyricsPipSource(activity, state, colors) }.getOrNull()?.let { source ->
            runCatching { runtime.enterLyricsPip(activity, source) }.onFailure { source.close() }
        }
        return
    }
    activity.enterPictureInPictureMode(floatingLyricsPipParams(activity, isPlaying))
}

fun floatingLyricsPipParams(context: Context, isPlaying: Boolean): PictureInPictureParams {
    fun action(
        requestCode: Int,
        serviceAction: String,
        iconRes: Int,
        titleRes: Int,
    ): RemoteAction {
        val title = context.getString(titleRes)
        val intent = Intent(context, MusicService::class.java).setAction(serviceAction)
        val pendingIntent = PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return RemoteAction(Icon.createWithResource(context, iconRes), title, title, pendingIntent)
    }
    return PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .setSeamlessResizeEnabled(true)
        .setActions(
            listOf(
                action(701, MusicService.ACTION_PREVIOUS, android.R.drawable.ic_media_previous, R.string.pip_previous),
                action(
                    702,
                    MusicService.ACTION_TOGGLE_PLAYBACK,
                    if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (isPlaying) R.string.pip_pause else R.string.pip_play,
                ),
                action(703, MusicService.ACTION_NEXT, android.R.drawable.ic_media_next, R.string.pip_next),
            ),
        )
        .build()
}

@Composable
fun FloatingLyricsPipScreen(
    playerConnection: PlayerConnection,
    viewModel: PlayerViewModel = viewModel(),
) {
    val context = LocalContext.current
    val metadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val lyricData by viewModel.lyric.collectAsState()
    val (showsTranslation) = rememberPreference(FloatingLyricsTranslationKey, true)
    val (showsNextLine) = rememberPreference(FloatingLyricsNextLineKey, true)
    val (fontScale) = rememberPreference(FloatingLyricsFontScaleKey, 1f)
    var position by remember { mutableLongStateOf(playerConnection.player.currentPosition) }

    LaunchedEffect(metadata?.id, metadata?.source) {
        metadata?.let { viewModel.lyricManager.loadLyrics(it) }
    }
    LaunchedEffect(isPlaying) {
        (context as? Activity)?.setPictureInPictureParams(
            floatingLyricsPipParams(context, isPlaying),
        )
        while (isActive) {
            position = playerConnection.player.currentPosition
            delay(if (isPlaying) 120L else 500L)
        }
    }

    val (title, translation, next) = floatingLyricsPipText(
        lyricData.lyricLine.lines, position, metadata?.title ?: context.getString(R.string.lyrics_waiting),
        showsTranslation, showsNextLine,
    )
    FloatingLyricsPipContent(title, translation, next, fontScale)
}

@Composable
fun FloatingLyricsPipContent(title: String, translation: String?, next: String?, fontScale: Float) {
    Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
        GlassCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimatedContent(title, label = "Floating lyric") { line ->
                    Text(
                        text = line,
                        color = Color.White,
                        fontSize = (18f * fontScale).sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                translation?.let {
                    Text(
                        text = it,
                        color = Color.White.copy(alpha = .74f),
                        fontSize = (12f * fontScale).sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                next?.let {
                    Text(
                        text = it,
                        color = Color.White.copy(alpha = .48f),
                        fontSize = (11f * fontScale).sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

internal fun floatingLyricsPipText(
    lines: List<ISyncedLine>, position: Long, fallback: String, showsTranslation: Boolean, showsNext: Boolean,
): Triple<String, String?, String?> {
    val index = lines.indexOfLast { position >= it.start }.coerceAtLeast(0)
    val active = lines.getOrNull(index)
    return Triple(
        active?.primaryText()?.takeIf(String::isNotBlank) ?: fallback,
        active?.translationText()?.takeIf { showsTranslation && it.isNotBlank() },
        lines.getOrNull(index + 1)?.primaryText()?.takeIf { showsNext && it.isNotBlank() },
    )
}

@Composable
fun FloatingLyricsPipBackdrop(playerConnection: PlayerConnection) {
    val metadata by playerConnection.mediaMetadata.collectAsState()
    FloatingLyricsPipBackdrop(metadata?.coverUrl)
}

@Composable
fun FloatingLyricsPipBackdrop(cover: Any?) {
    Box(Modifier.fillMaxSize().background(Color(0xFF151217))) {
        AsyncImage(
            model = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    listOf(Color.Black.copy(alpha = .84f), Color.Black.copy(alpha = .44f)),
                ),
            ),
        )
    }
}

internal fun ISyncedLine.primaryText(): String = when (this) {
    is KaraokeLine -> syllables.joinToString("") { it.content }
    is SyncedLine -> content
    else -> ""
}

internal fun ISyncedLine.translationText(): String? = when (this) {
    is KaraokeLine -> translation
    is SyncedLine -> translation
    else -> null
}
