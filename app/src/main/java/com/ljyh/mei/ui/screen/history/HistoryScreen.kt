package com.ljyh.mei.ui.screen.history

import android.text.format.DateUtils
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.ui.glass.GlassCard
import com.ljyh.mei.ui.glass.GlassIconButton
import com.ljyh.mei.ui.glass.IosListRow
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.IosTypography
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.screen.main.library.component.groupedLazyItems

@OptIn(UnstableApi::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current
    val state by viewModel.state.collectAsState()
    val historyList = state.items
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()

    IosPinnedListPage(
        title = stringResource(R.string.listening_history),
        onNavigateBack = navController::navigateUp,
        bottomPadding = insets.calculateBottomPadding(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        actions = {
            GlassIconButton(viewModel::refresh) {
                SfIcon(SfSymbol.ArrowClockwise, stringResource(R.string.refresh))
            }
            if (state.canClearLocalHistory) {
                GlassIconButton(viewModel::clearHistory) {
                    SfIcon("trash", stringResource(R.string.clear_history))
                }
            }
        },
    ) {
        when {
            state.isRefreshing && historyList.isEmpty() -> item {
                Box(
                    Modifier.fillMaxWidth().padding(top = 62.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            }

            historyList.isEmpty() -> item {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    EmptyHistoryState(state.error)
                }
            }

            else -> groupedLazyItems(
                items = historyList,
                key = ListeningHistoryEntry::key,
                contentType = "history-item",
                firstItemTopPadding = 10.dp,
            ) { item, index ->
                IosListRow(
                    title = item.song.title,
                    subtitle = item.song.artists.joinToString(" / ") { it.name },
                    detail = item.playedAt?.let(::relativeTime),
                    showTopSeparator = index > 0,
                    leading = {
                        AsyncImage(
                            model = item.song.coverUrl,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp).clip(ContinuousRoundedRectangle(10.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    },
                    onClick = {
                        playerConnection?.playQueue(
                            ListQueue(
                                id = "history",
                                title = navController.context.getString(R.string.listening_history),
                                items = historyList.map { it.song.id.toString() to null },
                                startIndex = index,
                                position = 0,
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyHistoryState(error: String?) {
    val colors = LocalGlassColors.current
    GlassCard(Modifier.fillMaxWidth().padding(top = 42.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 52.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SfIcon(SfSymbol.Clock, null, size = 48.dp, tint = colors.tertiaryContent)
            androidx.compose.material3.Text(
                text = error?.let { stringResource(R.string.load_failed_message, it) }
                    ?: stringResource(R.string.no_listening_history),
                style = IosTypography.headline,
                color = colors.content,
            )
            if (error == null) {
                androidx.compose.material3.Text(
                    text = stringResource(R.string.no_listening_history_description),
                    style = IosTypography.subheadline,
                    color = colors.secondaryContent,
                )
            }
        }
    }
}

private fun relativeTime(timestamp: Long): String = DateUtils.getRelativeTimeSpanString(
    timestamp,
    System.currentTimeMillis(),
    DateUtils.MINUTE_IN_MILLIS,
    DateUtils.FORMAT_ABBREV_RELATIVE,
).toString()
