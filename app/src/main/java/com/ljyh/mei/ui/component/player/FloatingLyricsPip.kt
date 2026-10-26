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
import com.ljyh.mei.ui.component.utils.LifecycleStartedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.ljyh.mei.R
import com.ljyh.mei.constants.FloatingLyricsFontScaleKey
import com.ljyh.mei.constants.FloatingLyricsNextLineKey
import com.ljyh.mei.constants.FloatingLyricsTranslationKey
import com.ljyh.mei.playback.MusicService
import com.ljyh.mei.playback.PlayerConnection
import io.github.neoruaa.glasmio.ui.GlasmioCard
import io.github.neoruaa.glasmio.core.LocalGlassColors
import com.ljyh.mei.utils.rememberPreference
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

fun enterFloatingLyricsPip(context: Context, isPlaying: Boolean) {
    val activity = context as? Activity ?: return
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
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val metadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val lyricData by viewModel.lyric.collectAsStateWithLifecycle()
    val (showsTranslation) = rememberPreference(FloatingLyricsTranslationKey, true)
    val (showsNextLine) = rememberPreference(FloatingLyricsNextLineKey, true)
    val (fontScale) = rememberPreference(FloatingLyricsFontScaleKey, 1f)
    var position by remember { mutableLongStateOf(playerConnection.player.currentPosition) }

    LaunchedEffect(metadata?.id) {
        metadata?.let { viewModel.lyricManager.loadLyrics(it) }
    }
    LifecycleStartedEffect(isPlaying, playerConnection) {
        (context as? Activity)?.setPictureInPictureParams(
            floatingLyricsPipParams(context, isPlaying),
        )
        while (isActive) {
            position = playerConnection.player.currentPosition
            delay(if (isPlaying) 120L else 500L)
        }
    }

    val lines = lyricData.lyricLine.lines
    val activeIndex = lines.indexOfLast { position >= it.start }.coerceAtLeast(0)
    val activeLine = lines.getOrNull(activeIndex)
    val nextLine = lines.getOrNull(activeIndex + 1)
    val title = activeLine?.primaryText()?.takeIf(String::isNotBlank)
        ?: metadata?.title
        ?: context.getString(R.string.lyrics_waiting)

    FloatingLyricsPipContent(
        title = title,
        translation = activeLine?.translationText()?.takeIf { showsTranslation && it.isNotBlank() },
        nextLine = nextLine?.primaryText()?.takeIf { showsNextLine && it.isNotBlank() },
        fontScale = fontScale,
    )
}

@Composable
internal fun FloatingLyricsPipContent(
    title: String,
    translation: String?,
    nextLine: String?,
    fontScale: Float,
) {
    val colors = LocalGlassColors.current
    Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
        GlasmioCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimatedContent(title, label = "Floating lyric") { line ->
                    Text(
                        text = line,
                        color = colors.content,
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
                        color = colors.secondaryContent,
                        fontSize = (12f * fontScale).sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                nextLine?.let {
                    Text(
                        text = it,
                        color = colors.secondaryContent,
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

@Composable
fun FloatingLyricsPipBackdrop(playerConnection: PlayerConnection) {
    val metadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Color(0xFF151217))) {
        AsyncImage(
            model = metadata?.coverUrl,
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

private fun ISyncedLine.primaryText(): String = when (this) {
    is KaraokeLine -> syllables.joinToString("") { it.content }
    is SyncedLine -> content
    else -> ""
}

private fun ISyncedLine.translationText(): String? = when (this) {
    is KaraokeLine -> translation
    is SyncedLine -> translation
    else -> null
}
