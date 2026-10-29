package com.ljyh.mei.ui.component.player.component.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioHeaderScrollEdge
import io.github.neoruaa.glasmio.ui.LocalGlasmioSheetContentPadding
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
import com.ljyh.mei.ui.component.sheet.rememberMeiloXSheetDismissal
import com.ljyh.mei.ui.component.player.PlayerViewModel
import com.ljyh.mei.ui.component.player.component.PrivateFmModeSelector
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioMenuItem
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioMenu
import io.github.neoruaa.glasmio.ui.GlasmioSheetTopToolbar
import io.github.neoruaa.glasmio.ui.GlasmioSheetToolbarButton
import io.github.neoruaa.glasmio.ui.GlasmioSheetButton
import io.github.neoruaa.glasmio.core.IosTypography
import java.util.Locale
import io.github.neoruaa.glasmio.ui.GlasmioReorderableGroup
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.model.MoreAction
import com.ljyh.mei.ui.model.PlayerAction
import com.ljyh.mei.ui.model.SortOrder

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun MoreActionsSheet(
    onDismissRequest: () -> Unit,
    onActionClick: (MoreAction) -> Unit,
    viewModel: PlayerViewModel,
    onFindLyrics: (() -> Unit)? = null,
    onFindLyricsLongClick: (() -> Unit)? = null,
    onChoosePrivateFmScene: (() -> Unit)? = null,
) {
    val connection = LocalPlayerConnection.current
    val sleepTimer = connection?.service?.sleepTimer
    val sortOrder by viewModel.moreSortOrder.collectAsState()
    val moreActions by viewModel.sortedMoreActions.collectAsState()
    var showSortOptions by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<List<MoreAction>?>(null) }

    val listState = rememberLazyListState()
    val dismissal = rememberMeiloXSheetDismissal(onDismissRequest)

    GlasmioBottomSheet(
        onDismissRequest = dismissal::onDismissed,
        sheetState = dismissal.state,
        skipPartiallyExpanded = false,
        fitContent = false,
        title = stringResource(R.string.more_actions_title),
        topBarBlurProgress = rememberGlasmioHeaderScrollEdge(listState),
        topBar = {
            if (draft != null) GlasmioSheetTopToolbar(
                title = stringResource(R.string.more_actions_order_title),
                navigation = {
                    GlasmioSheetToolbarButton(onClick = { draft = null }) {
                        SfIcon(SfSymbol.Close, stringResource(R.string.cancel))
                    }
                },
                actions = {
                    GlasmioSheetButton(onClick = {
                        draft?.let(viewModel::saveMoreActionOrder)
                        draft = null
                    }) { Text(stringResource(R.string.more_actions_order_done)) }
                },
            ) else GlasmioSheetTopToolbar(
                title = stringResource(R.string.more_actions_title),
                navigation = onFindLyrics?.let { findLyrics ->
                    {
                        GlasmioSheetToolbarButton(
                            onClick = { dismissal.requestDismiss { findLyrics() } },
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
                    GlasmioMenu(
                        expanded = showSortOptions,
                        onExpandedChange = { showSortOptions = it },
                        itemCount = 4,
                        keepAnchorVisible = false,
                        anchor = { openMenu ->
                            GlasmioSheetToolbarButton(
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
                        GlasmioMenuItem(
                            title = stringResource(R.string.more_actions_order_title),
                            systemName = "line.3.horizontal", backdrop = childBackdrop,
                            onClick = { draft = moreActions.toList(); close() },
                        )
                    }
                },
            )
        },
    ) {
        val sheetPadding = LocalGlasmioSheetContentPadding.current
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = sheetPadding.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = sheetPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (draft == null && connection != null && onChoosePrivateFmScene != null) {
                item(key = "private-fm-mode") {
                    PrivateFmModeSelector(
                        connection = connection,
                        backdrop = LocalGlassBackdrop.current,
                        onChooseScene = { dismissal.requestDismiss { onChoosePrivateFmScene() } },
                    )
                }
            }
            item {
                val editing = draft
                if (editing != null) {
                    GlasmioReorderableGroup(
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
                } else GlasmioGroupedList {
                    moreActions.forEachIndexed { index, action ->
                        GlasmioListRow(
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
                            onClick = { dismissal.requestDismiss { onActionClick(action) } },
                        )
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
    backdrop: io.github.neoruaa.glasmio.glass.render.Backdrop,
    onClick: () -> Unit,
) {
    GlasmioMenuItem(
        title = title,
        onClick = onClick,
        systemName = if (selected) "checkmark" else null,
        backdrop = backdrop,
    )
}
