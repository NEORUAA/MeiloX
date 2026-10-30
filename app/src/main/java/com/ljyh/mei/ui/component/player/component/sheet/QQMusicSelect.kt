package com.ljyh.mei.ui.component.player.component.sheet

import com.ljyh.mei.ui.component.sheet.MeiloXBottomSheet
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.ui.component.player.PlayerViewModel
import io.github.neoruaa.glasmio.ui.GlasmioSheetButton
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.LocalGlasmioSheetContentPadding
import io.github.neoruaa.glasmio.ui.glasmioGroupedListBackground
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioHeaderScrollEdge
import com.ljyh.mei.ui.component.sheet.rememberMeiloXSheetDismissal
import io.github.neoruaa.glasmio.ui.GlasmioSheetTopToolbar
import io.github.neoruaa.glasmio.ui.GlasmioSheetToolbarButton
import io.github.neoruaa.glasmio.ui.GlasmioSheetState
import io.github.neoruaa.glasmio.ui.rememberGlasmioSheetState
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.model.LyricCandidate
import com.ljyh.mei.ui.model.LyricSource
import com.ljyh.mei.ui.model.LyricVariant
import com.ljyh.mei.utils.TimeUtils.formatDuration
import com.ljyh.mei.utils.TimeUtils.formatSeconds
import kotlin.math.abs

@Composable
fun QQMusicSelectSheet(
    viewmodel: PlayerViewModel,
    mediaMetadata: MediaMetadata,
    onDismiss: () -> Unit,
) {
    val searchResult by viewmodel.searchResult.collectAsState()
    val candidates by viewmodel.lyricCandidates.collectAsState()
    val offsetMs by viewmodel.lyricOffsetMs.collectAsState()
    val sheetState = rememberGlasmioSheetState(skipPartiallyExpanded = false)
    val connection = LocalPlayerConnection.current
    val playingMetadata = connection?.mediaMetadata?.collectAsState()?.value
    val closeRequested = connection != null && playingMetadata?.id != mediaMetadata.id
    val retainedCandidates = remember(mediaMetadata.id) { mutableStateOf(candidates) }
    val retainedOffset = remember(mediaMetadata.id) { mutableStateOf(offsetMs) }
    val retainedSearch = remember(mediaMetadata.id) { mutableStateOf(searchResult) }
    SideEffect {
        if (!closeRequested) {
            retainedCandidates.value = candidates
            retainedOffset.value = offsetMs
            retainedSearch.value = searchResult
        }
    }
    val displayedSearch = if (closeRequested) retainedSearch.value else searchResult
    LyricSelectionSheet(
        mediaMetadata = mediaMetadata,
        candidates = if (closeRequested) retainedCandidates.value else candidates,
        offsetMs = if (closeRequested) retainedOffset.value else offsetMs,
        isLoading = displayedSearch is Resource.Loading,
        errorMessage = (displayedSearch as? Resource.Error)?.message,
        onSelectCandidate = { viewmodel.selectLyricCandidate(mediaMetadata, it) },
        onAdjustOffset = { viewmodel.adjustLyricOffset(mediaMetadata, it) },
        onResetOffset = { viewmodel.resetLyricOffset(mediaMetadata) },
        onResetSource = { viewmodel.resetLyricSource(mediaMetadata) },
        onDismiss = onDismiss,
        sheetState = sheetState,
        closeRequested = closeRequested,
    )
}

@Composable
internal fun LyricSelectionSheet(
    mediaMetadata: MediaMetadata,
    candidates: List<LyricCandidate>,
    offsetMs: Int,
    isLoading: Boolean,
    errorMessage: String?,
    onSelectCandidate: (LyricCandidate) -> Unit,
    onAdjustOffset: (Int) -> Unit,
    onResetOffset: () -> Unit,
    onResetSource: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: GlasmioSheetState = rememberGlasmioSheetState(skipPartiallyExpanded = false),
    closeRequested: Boolean = false,
) {
    val dismissal = rememberMeiloXSheetDismissal(onDismiss, sheetState = sheetState)
    val listState = rememberLazyListState()
    val closing = dismissal.isDismissing
    fun closeSheet(afterClose: () -> Unit = {}) {
        dismissal.requestDismiss(afterClose)
    }

    LaunchedEffect(closeRequested) {
        if (closeRequested) closeSheet()
    }

    MeiloXBottomSheet(
        onDismissRequest = dismissal::onDismissed,
        skipPartiallyExpanded = false,
        fitContent = false,
        sheetState = sheetState,
        title = stringResource(R.string.lyric_selection_title),
        topBarBlurProgress = rememberGlasmioHeaderScrollEdge(listState),
        topBar = {
            GlasmioSheetTopToolbar(
                title = stringResource(R.string.lyric_selection_title),
                actions = {
                    GlasmioSheetToolbarButton(onClick = { closeSheet() }, enabled = !closing) {
                        SfIcon("xmark", stringResource(R.string.lyric_selection_close), size = 20.dp)
                    }
                },
            )
        },
    ) {
        val sheetPadding = LocalGlasmioSheetContentPadding.current
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("lyric-selection-list"),
            contentPadding = PaddingValues(
                start = 16.dp, top = sheetPadding.calculateTopPadding() + 8.dp,
                end = 16.dp, bottom = sheetPadding.calculateBottomPadding() + 16.dp,
            ),
        ) {
            item(key = "reference") {
                CurrentReferenceHeader(
                    mediaMetadata.title,
                    mediaMetadata.artists.firstOrNull()?.name.orEmpty(),
                    mediaMetadata.duration,
                )
            }
            item(key = "timing") {
                GlasmioGroupedList(Modifier.padding(top = 24.dp)) {
                    LyricTimingControls(
                        offsetMs = offsetMs,
                        enabled = !closing,
                        onAdjustOffset = onAdjustOffset,
                        onResetOffset = onResetOffset,
                        onResetSource = onResetSource,
                    )
                }
            }
            if (candidates.isNotEmpty()) item(key = "candidate-section-gap") { Spacer(Modifier.height(24.dp)) }
            itemsIndexed(candidates, key = { _, candidate -> candidate.key }) { index, candidate ->
                val shape = when {
                    candidates.size == 1 -> ContinuousRoundedRectangle(26.dp)
                    index == 0 -> ContinuousRoundedRectangle(topStart = 26.dp, topEnd = 26.dp)
                    index == candidates.lastIndex -> ContinuousRoundedRectangle(bottomStart = 26.dp, bottomEnd = 26.dp)
                    else -> RectangleShape
                }
                val separator = LocalGlassColors.current.separator
                Box(Modifier.fillMaxWidth().glasmioGroupedListBackground(shape)) {
                    LyricCandidateItem(
                        modifier = Modifier.drawBehind {
                            if (index > 0) drawLine(
                                separator, Offset(16.dp.toPx(), 0.5.dp.toPx()),
                                Offset(size.width - 16.dp.toPx(), 0.5.dp.toPx()), 1.dp.toPx(),
                            )
                        },
                        candidate = candidate,
                        targetDuration = mediaMetadata.duration,
                        targetTitle = mediaMetadata.title,
                        enabled = !closing,
                        onClick = { closeSheet { onSelectCandidate(candidate) } },
                    )
                }
            }
            // Search status supplements native candidates instead of replacing them.
            if (isLoading) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                }
            } else if (errorMessage != null) {
                item(key = "error") {
                    Text(errorMessage, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp))
                }
            } else if (candidates.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.lyric_selection_empty),
                        color = LocalGlassColors.current.secondaryContent,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricTimingControls(
    offsetMs: Int,
    enabled: Boolean,
    onAdjustOffset: (Int) -> Unit,
    onResetOffset: () -> Unit,
    onResetSource: () -> Unit,
) {
    val colors = LocalGlassColors.current
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.lyric_selection_delay), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlasmioSheetButton(onClick = { onAdjustOffset(-500) }, enabled = enabled) {
                Text(stringResource(R.string.lyric_selection_earlier))
            }
            Text(
                stringResource(R.string.lyric_selection_offset, offsetMs / 1000.0),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("lyric-selection-offset"),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
                color = colors.content,
            )
            GlasmioSheetButton(onClick = { onAdjustOffset(500) }, enabled = enabled) {
                Text(stringResource(R.string.lyric_selection_later))
            }
        }
        Text(
            stringResource(R.string.lyric_selection_delay_hint),
            style = MaterialTheme.typography.bodySmall,
            color = colors.secondaryContent,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlasmioSheetButton(onClick = onResetOffset, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.lyric_selection_reset_delay), style = MaterialTheme.typography.labelLarge)
            }
            GlasmioSheetButton(onClick = onResetSource, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.lyric_selection_reset_source), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun CurrentReferenceHeader(title: String, artist: String, duration: Long) {
    GlasmioGroupedList {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatDuration(duration),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(text = artist, style = MaterialTheme.typography.bodySmall, color = LocalGlassColors.current.secondaryContent)
        }
    }
}

@Composable
private fun LyricCandidateItem(
    modifier: Modifier = Modifier,
    candidate: LyricCandidate,
    targetDuration: Long,
    targetTitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val candidateSeconds = candidate.durationMs / 1000
    val targetSeconds = targetDuration / 1000
    val durationDiff = abs(targetSeconds - candidateSeconds)
    val isDurationMatch = durationDiff <= 3
    val isTitleMatch = candidate.title.contains(targetTitle, ignoreCase = true) ||
        targetTitle.contains(candidate.title, ignoreCase = true)
    val isHighlyRecommended = isDurationMatch && isTitleMatch
    val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant
    val (sourceIcon, sourceName) = when (candidate.source) {
        LyricSource.NetEaseCloudMusic -> R.drawable.netease to R.string.lyric_selection_source_netease
        LyricSource.QQMusic -> R.drawable.qq to R.string.lyric_selection_source_qq
        LyricSource.AM -> R.drawable.am to R.string.lyric_selection_source_am
        LyricSource.Empty, LyricSource.Loading -> R.drawable.empty to R.string.lyric_selection_source_other
    }
    val versionName = candidate.variant?.let { variant ->
        stringResource(when (variant) {
            LyricVariant.AppleMusic -> R.string.lyric_version_ttml
            LyricVariant.NeteaseYrc, LyricVariant.QqQrc -> R.string.lyric_version_word
            LyricVariant.NeteaseLrc, LyricVariant.QqLrc -> R.string.lyric_version_line
        })
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("lyric-candidate-${candidate.key}")
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = candidate.coverUrl,
            contentDescription = null,
            modifier = Modifier
                .size(52.dp)
                .clip(ContinuousRoundedRectangle(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = candidate.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isHighlyRecommended) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (candidate.isSelected) {
                    Spacer(Modifier.width(8.dp))
                    SfIcon("checkmark", stringResource(R.string.lyric_version_selected), size = 16.dp)
                } else if (isHighlyRecommended && candidate.variant == null) {
                    Spacer(Modifier.width(8.dp))
                    Surface(color = MaterialTheme.colorScheme.primary, shape = ContinuousRoundedRectangle(4.dp)) {
                        Text(
                            stringResource(R.string.lyric_selection_best_match),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    painterResource(sourceIcon),
                    contentDescription = stringResource(sourceName),
                    tint = secondaryColor,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = if (versionName != null) "${stringResource(sourceName)} · $versionName" else
                        listOf(candidate.artist, candidate.album).filter(String::isNotBlank).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (candidate.preview.isNotBlank()) {
                Text(
                    text = candidate.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalGlassColors.current.secondaryContent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatSeconds(candidateSeconds),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isDurationMatch) FontWeight.Bold else FontWeight.Normal,
                color = if (isDurationMatch) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (durationDiff != 0L && durationDiff < 60) {
                Text(
                    text = "${if (candidateSeconds > targetSeconds) "+" else "-"}${durationDiff}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isDurationMatch) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                )
            }
        }
    }
}
