package com.ljyh.mei.ui.component.player.component.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ljyh.mei.R
import com.ljyh.mei.playback.SleepTimerState
import com.ljyh.mei.ui.component.player.PlayerViewModel
import com.ljyh.mei.ui.glass.IosGroupedList
import com.ljyh.mei.ui.glass.IosListRow
import com.ljyh.mei.ui.glass.IosMenuItem
import com.ljyh.mei.ui.glass.IosModalSheet
import com.ljyh.mei.ui.glass.IosPopupMenu
import com.ljyh.mei.ui.glass.IosSheetTopToolbar
import com.ljyh.mei.ui.glass.IosSheetTopToolbarButton
import com.ljyh.mei.ui.glass.IosSheetButton
import com.ljyh.mei.ui.glass.IosTypography
import java.util.Locale
import com.ljyh.mei.ui.glass.IosReorderableGroup
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.model.MoreAction
import com.ljyh.mei.ui.model.PlayerAction
import com.ljyh.mei.ui.model.SortOrder

@Composable
fun MoreActionsSheet(
    onDismissRequest: () -> Unit,
    onActionClick: (MoreAction) -> Unit,
    viewModel: PlayerViewModel,
    onFindLyrics: (() -> Unit)? = null,
    onFindLyricsLongClick: (() -> Unit)? = null,
) {
    val sleepTimer = LocalPlayerConnection.current?.service?.sleepTimer
    val sortOrder by viewModel.moreSortOrder.collectAsState()
    val moreActions by viewModel.sortedMoreActions.collectAsState()
    var showSortOptions by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<List<MoreAction>?>(null) }

    IosModalSheet(
        onDismissRequest = onDismissRequest,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (draft != null) IosSheetTopToolbar(
                title = stringResource(R.string.more_actions_order_title),
                navigation = {
                    IosSheetTopToolbarButton(onClick = { draft = null }) {
                        SfIcon(SfSymbol.Close, stringResource(R.string.cancel))
                    }
                },
                actions = {
                    IosSheetButton(onClick = {
                        draft?.let(viewModel::saveMoreActionOrder)
                        draft = null
                    }) { Text(stringResource(R.string.more_actions_order_done)) }
                },
            ) else IosSheetTopToolbar(
                title = stringResource(R.string.more_actions_title),
                navigation = onFindLyrics?.let { findLyrics ->
                    {
                        IosSheetTopToolbarButton(
                            onClick = findLyrics,
                            onLongClick = onFindLyricsLongClick,
                        ) {
                            SfIcon(
                                systemName = PlayerAction.LYRICS.systemName,
                                contentDescription = stringResource(R.string.player_find_lyrics),
                            )
                        }
                    }
                },
                actions = {
                    IosPopupMenu(
                        expanded = showSortOptions,
                        onExpandedChange = { showSortOptions = it },
                        itemCount = 4,
                        keepAnchorVisible = false,
                        anchor = { openMenu ->
                            IosSheetTopToolbarButton(
                                onClick = openMenu,
                            ) {
                                SfIcon(
                                    SfSymbol.Settings,
                                    stringResource(R.string.more_actions_sort),
                                )
                            }
                        },
                    ) { childBackdrop, close ->
                        SortOptionMenuItem(
                            title = stringResource(R.string.more_actions_sort_frequency),
                            selected = sortOrder == SortOrder.FREQUENCY,
                            backdrop = childBackdrop,
                            onClick = {
                                viewModel.setMoreSortOrder(SortOrder.FREQUENCY)
                                close()
                            },
                        )
                        SortOptionMenuItem(
                            title = stringResource(R.string.more_actions_sort_risk),
                            selected = sortOrder == SortOrder.RISK,
                            backdrop = childBackdrop,
                            onClick = {
                                viewModel.setMoreSortOrder(SortOrder.RISK)
                                close()
                            },
                        )
                        SortOptionMenuItem(
                            title = stringResource(R.string.more_actions_sort_custom),
                            selected = sortOrder == SortOrder.CUSTOM,
                            backdrop = childBackdrop,
                            onClick = {
                                viewModel.setMoreSortOrder(SortOrder.CUSTOM)
                                close()
                            },
                        )
                        IosMenuItem(
                            title = stringResource(R.string.more_actions_order_title),
                            systemName = "line.3.horizontal", backdrop = childBackdrop,
                            onClick = { draft = moreActions.toList(); close() },
                        )
                    }
                },
            )
            LazyColumn(
                // Size to content, capped by the sheet's available height (fill = false);
                // a hardcoded max clipped longer lists instead of matching them.
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                item {
                    val editing = draft
                    if (editing != null) {
                        IosReorderableGroup(
                            items = editing,
                            onReorder = { draft = it },
                            dragHandleContentDescription = stringResource(R.string.reorder_drag_handle),
                            moveUpLabel = stringResource(R.string.move_up),
                            moveDownLabel = stringResource(R.string.move_down),
                        ) { action ->
                            SfIcon(action.systemName, null, tint = LocalGlassColors.current.accent)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(action.labelRes), modifier = Modifier.weight(1f),
                                color = LocalGlassColors.current.content)
                        }
                    } else IosGroupedList {
                        moreActions.forEachIndexed { index, action ->
                            IosListRow(
                                title = stringResource(action.labelRes),
                                systemName = action.systemName,
                                detail = if (action == MoreAction.SLEEP_TIMER && sleepTimer?.state == SleepTimerState.EndOfTrack) {
                                    stringResource(R.string.sleep_timer_track_short)
                                } else null,
                                trailing = if (action == MoreAction.SLEEP_TIMER && sleepTimer?.state is SleepTimerState.Countdown) {
                                    {
                                        val seconds = (sleepTimer.remainingMillis + 999L) / 1_000L
                                        Text(
                                            text = String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60),
                                            style = IosTypography.subheadline.copy(fontFeatureSettings = "tnum"),
                                            color = LocalGlassColors.current.secondaryContent,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        SfIcon("chevron.forward", null, size = 12.dp, tint = LocalGlassColors.current.separator)
                                    }
                                } else null,
                                showTopSeparator = index > 0,
                                onClick = { onActionClick(action) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SortOptionMenuItem(
    title: String,
    selected: Boolean,
    backdrop: com.kyant.backdrop.Backdrop,
    onClick: () -> Unit,
) {
    IosMenuItem(
        title = title,
        onClick = onClick,
        systemName = if (selected) "checkmark" else null,
        backdrop = backdrop,
    )
}
