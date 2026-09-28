package com.ljyh.mei.ui.screen.recognition

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.melox.RecognizedSong
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.recognition.RecognitionDuration
import com.ljyh.mei.ui.glass.GlassButton
import com.ljyh.mei.ui.glass.GlassCard
import com.ljyh.mei.ui.glass.GlassEmphasis
import com.ljyh.mei.ui.glass.GlassIconButton
import com.ljyh.mei.ui.glass.GlassSegmentedControl
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection

@Composable
fun SongRecognitionScreen(viewModel: SongRecognitionViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val recognitionTitle = stringResource(R.string.song_recognition)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.start() }

    IosPinnedListPage(
        title = recognitionTitle,
        onNavigateBack = navController::navigateUp,
        bottomPadding = insets.calculateBottomPadding(),
    ) {
        item {
            GlassSegmentedControl(
                items = RecognitionDuration.entries.map { duration ->
                    duration to stringResource(duration.titleResource())
                },
                selected = state.duration,
                onSelected = viewModel::selectDuration,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SfIcon(
                        systemName = "waveform.badge.magnifyingglass",
                        contentDescription = null,
                        size = 68.dp,
                    )
                    Text(
                        text = stringResource(state.phase.textResource()),
                        style = MaterialTheme.typography.titleLarge,
                        color = LocalGlassColors.current.content,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.recognition_privacy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    GlassButton(
                        onClick = {
                            if (state.isWorking) viewModel.stop()
                            else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        emphasis = GlassEmphasis.Prominent,
                    ) {
                        Text(stringResource(if (state.isWorking) R.string.recognition_stop else R.string.recognition_start))
                    }
                }
            }
        }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        items(state.results, key = { it.id }) { song ->
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val media = song.asMediaMetadata().toMediaItem()
                    playerConnection?.playQueue(ListQueue("recognition", recognitionTitle, listOf(media.mediaId to media)))
                },
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = song.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(58.dp).clip(ContinuousRoundedRectangle(14.dp)),
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            song.name,
                            color = LocalGlassColors.current.content,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            song.artists.joinToString(" / "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    SfIcon(SfSymbol.PlayFilled, stringResource(R.string.player_play))
                }
            }
        }
    }
}

private fun RecognitionDuration.titleResource(): Int = when (this) {
    RecognitionDuration.Quick -> R.string.recognition_quick
    RecognitionDuration.Balanced -> R.string.recognition_balanced
    RecognitionDuration.Extended -> R.string.recognition_extended
    RecognitionDuration.Continuous -> R.string.recognition_continuous
}

private fun RecognitionPhase.textResource(): Int = when (this) {
    RecognitionPhase.Ready -> R.string.recognition_ready
    RecognitionPhase.Listening -> R.string.recognition_listening
    RecognitionPhase.Fingerprinting -> R.string.recognition_fingerprinting
    RecognitionPhase.Matching -> R.string.recognition_matching
    RecognitionPhase.Results -> R.string.recognition_results
    RecognitionPhase.NoMatch -> R.string.recognition_no_match
    RecognitionPhase.Failed -> R.string.recognition_failed
}

private fun RecognizedSong.asMediaMetadata() = MediaMetadata(
    id = id,
    title = name,
    coverUrl = coverUrl.orEmpty(),
    artists = artists.map { MediaMetadata.Artist(it.hashCode().toLong(), it) },
    duration = durationMs,
    album = MediaMetadata.Album(album.hashCode().toLong(), album),
)
