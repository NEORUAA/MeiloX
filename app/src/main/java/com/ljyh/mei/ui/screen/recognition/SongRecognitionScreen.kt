package com.ljyh.mei.ui.screen.recognition

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.recognition.RecognitionDuration
import com.ljyh.mei.recognition.RecognitionPhase
import com.ljyh.mei.recognition.RecognitionSource
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioCard
import io.github.neoruaa.glasmio.ui.GlasmioSegmentedControl

@Composable
fun SongRecognitionScreen(viewModel: SongRecognitionViewModel = hiltViewModel(), providerId: String? = null) {
    LaunchedEffect(providerId) { viewModel.open(providerId) }
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val recognitionTitle = stringResource(R.string.song_recognition)
    var askedForNotifications by rememberSaveable { mutableStateOf(false) }
    var pendingSource by rememberSaveable { mutableStateOf<RecognitionSource?>(null) }
    val awaitingConsent = pendingSource != null
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        pendingSource = null
        val consent = result.data
        if (result.resultCode == Activity.RESULT_OK && consent != null) {
            viewModel.start(RecognitionSource.Playback, consent)
        } else {
            viewModel.showStartFailure(R.string.recognition_capture_denied)
        }
    }
    val startCapture: () -> Unit = start@{
        val source = pendingSource ?: return@start
        if (source == RecognitionSource.Playback) {
            try {
                val manager = checkNotNull(context.getSystemService(MediaProjectionManager::class.java))
                val request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                } else {
                    manager.createScreenCaptureIntent()
                }
                projectionLauncher.launch(request)
            } catch (_: RuntimeException) {
                pendingSource = null
                viewModel.showStartFailure(R.string.recognition_start_failed)
            }
        } else {
            pendingSource = null
            viewModel.start(source)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCapture()
        } else {
            pendingSource = null
            viewModel.showStartFailure(R.string.recognition_record_permission_denied)
        }
    }

    MeiloXPinnedListPage(
        title = recognitionTitle,
        onNavigateBack = navController::navigateUp,
        bottomPadding = insets.calculateBottomPadding(),
    ) {
        item {
            GlasmioSegmentedControl(
                items = listOf(
                    RecognitionSource.Microphone to stringResource(R.string.recognition_source_microphone),
                    RecognitionSource.Playback to stringResource(R.string.recognition_source_playback),
                ),
                selected = state.source,
                onSelected = { if (!awaitingConsent) viewModel.selectSource(it) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            GlasmioSegmentedControl(
                items = RecognitionDuration.entries.map { duration ->
                    duration to stringResource(duration.titleResource())
                },
                selected = state.duration,
                onSelected = { if (!awaitingConsent) viewModel.selectDuration(it) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            GlasmioCard(Modifier.fillMaxWidth()) {
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
                        text = stringResource(
                            when {
                                state.phase != RecognitionPhase.Ready -> state.phase.textResource()
                                state.playbackPanelOpen -> R.string.recognition_panel_ready
                                state.source == RecognitionSource.Playback -> R.string.recognition_playback_ready
                                else -> R.string.recognition_ready
                            },
                        ),
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
                    if (state.source == RecognitionSource.Playback) {
                        Text(
                            text = stringResource(R.string.recognition_playback_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (askedForNotifications && !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                        Text(
                            text = stringResource(R.string.recognition_notifications_disabled),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    GlasmioButton(
                        onClick = {
                            if (state.isWorking) {
                                viewModel.stop()
                            } else if (state.playbackPanelOpen) {
                                viewModel.startPlaybackRound()
                            } else {
                                pendingSource = state.source
                                val permissions = buildList {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                                        PackageManager.PERMISSION_GRANTED
                                    ) add(Manifest.permission.RECORD_AUDIO)
                                    if (!askedForNotifications &&
                                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                                        PackageManager.PERMISSION_GRANTED
                                    ) {
                                        askedForNotifications = true
                                        add(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                                if (permissions.isEmpty()) startCapture()
                                else permissionLauncher.launch(permissions.toTypedArray())
                            }
                        },
                        enabled = state.isAvailable && !awaitingConsent && state.phase != RecognitionPhase.Starting,
                        emphasis = GlassEmphasis.Prominent,
                    ) {
                        Text(stringResource(when {
                            state.isWorking -> R.string.recognition_stop
                            state.playbackPanelOpen && state.lastOutcome != null -> R.string.recognition_panel_retry
                            state.playbackPanelOpen -> R.string.recognition_panel_start
                            state.source == RecognitionSource.Playback -> R.string.recognition_panel_open
                            else -> R.string.recognition_start
                        }))
                    }
                    if (state.playbackPanelOpen) {
                        GlasmioButton(onClick = viewModel::closePlaybackPanel) {
                            Text(stringResource(R.string.recognition_panel_close))
                        }
                    }
                }
            }
        }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        items(state.results, key = { it.ref.toString() }) { song ->
            GlasmioCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    viewModel.media(song) { media ->
                        val lastPosition = song.durationMs.takeIf { it > 0 }?.minus(1) ?: Int.MAX_VALUE.toLong()
                        playerConnection?.playQueue(ListQueue(
                            "recognition", recognitionTitle, listOf(media.mediaId to media),
                            position = (song.startTimeMs ?: 0L)
                                .coerceIn(0L, minOf(lastPosition, Int.MAX_VALUE.toLong())).toInt(),
                        ))
                    }
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
    RecognitionPhase.Starting -> R.string.recognition_active
    RecognitionPhase.Listening -> R.string.recognition_listening
    RecognitionPhase.Fingerprinting -> R.string.recognition_fingerprinting
    RecognitionPhase.Matching -> R.string.recognition_matching
    RecognitionPhase.Results -> R.string.recognition_results
    RecognitionPhase.NoMatch -> R.string.recognition_no_match
    RecognitionPhase.NoAudio -> R.string.recognition_no_playback_audio
    RecognitionPhase.Failed -> R.string.recognition_failed
}
