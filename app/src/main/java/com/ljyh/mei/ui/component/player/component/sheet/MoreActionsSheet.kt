package com.ljyh.mei.ui.component.player.component.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import com.ljyh.mei.ui.component.player.PlayerViewModel
import com.ljyh.mei.ui.glass.IosGroupedList
import com.ljyh.mei.ui.glass.IosListRow
import com.ljyh.mei.ui.glass.IosModalSheet
import com.ljyh.mei.ui.glass.IosMenuItem
import com.ljyh.mei.ui.glass.IosPopupMenu
import com.ljyh.mei.ui.glass.IosSheetTopToolbar
import com.ljyh.mei.ui.glass.IosSheetTopToolbarButton
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.model.MoreAction
import com.ljyh.mei.ui.model.SortOrder

@Composable
fun MoreActionsSheet(
    onDismissRequest: () -> Unit,
    onActionClick: (MoreAction) -> Unit,
    viewModel: PlayerViewModel,
) {
    val sortOrder by viewModel.moreSortOrder.collectAsState()
    val moreActions by viewModel.sortedMoreActions.collectAsState()
    var showSortOptions by remember { mutableStateOf(false) }

    IosModalSheet(
        onDismissRequest = onDismissRequest,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            IosSheetTopToolbar(
                title = stringResource(R.string.more_actions_title),
                actions = {
                    IosPopupMenu(
                        expanded = showSortOptions,
                        onExpandedChange = { showSortOptions = it },
                        itemCount = 2,
                        keepAnchorVisible = true,
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
                    IosGroupedList {
                        moreActions.forEachIndexed { index, action ->
                            IosListRow(
                                title = stringResource(action.labelRes),
                                systemName = action.systemName,
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
