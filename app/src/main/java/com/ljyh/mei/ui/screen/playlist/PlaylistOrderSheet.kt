package com.ljyh.mei.ui.screen.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ljyh.mei.R
import com.ljyh.mei.ui.glass.IosModalSheet
import com.ljyh.mei.ui.glass.IosSheetButton
import com.ljyh.mei.ui.glass.IosSheetTopToolbar
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import kotlinx.coroutines.flow.collect
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistOrderSheet(
    state: PlaylistOrderState,
    onMove: (Int, Int) -> Unit,
    onVisibleTracks: (List<Long>) -> Unit,
    onSave: () -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val current by rememberUpdatedState(state)
    val move by rememberUpdatedState(onMove)
    val loadVisible by rememberUpdatedState(onVisibleTracks)
    val colors = LocalGlassColors.current
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !current.saving || it != SheetValue.Hidden },
    )
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        if (!current.saving) {
            val fromIndex = current.items.indexOfFirst { it.id == from.key }
            val toIndex = current.items.indexOfFirst { it.id == to.key }
            if (fromIndex >= 0 && toIndex >= 0) {
                move(fromIndex, toIndex)
                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long } }
            .collect { loadVisible(it) }
    }
    IosModalSheet(
        onDismissRequest = { if (!state.saving) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.82f)) {
            IosSheetTopToolbar(
                title = stringResource(R.string.playlist_order_title),
                navigation = {
                    IosSheetButton(onClick = onDismiss, enabled = !state.saving) {
                        Text(stringResource(R.string.playlist_order_cancel))
                    }
                },
                actions = {
                    IosSheetButton(
                        onClick = onSave,
                        enabled = !state.loading && !state.saving &&
                            state.error != PlaylistOrderError.LoadFailed && state.error != PlaylistOrderError.PlaylistChanged,
                        modifier = Modifier.testTag("playlist-order-save"),
                    ) {
                        if (state.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.playlist_order_done))
                    }
                },
            )
            Text(
                stringResource(R.string.playlist_order_hint),
                color = colors.secondaryContent,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
            )
            state.error?.let { error ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp)) {
                    Text(stringResource(when (error) {
                        PlaylistOrderError.LoadFailed -> R.string.playlist_order_load_failed
                        PlaylistOrderError.SaveFailed -> R.string.playlist_order_save_failed
                        PlaylistOrderError.PlaylistChanged -> R.string.playlist_order_changed
                    }), color = colors.secondaryContent, fontSize = 14.sp)
                    if (error != PlaylistOrderError.SaveFailed) {
                        IosSheetButton(onClick = onReload, modifier = Modifier.padding(top = 8.dp)) {
                            Text(stringResource(R.string.playlist_order_reload))
                        }
                    }
                }
            }
            if (state.loading) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            } else {
                val moveUp = stringResource(R.string.playlist_order_move_up)
                val moveDown = stringResource(R.string.playlist_order_move_down)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).testTag("playlist-order-list"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                        ReorderableItem(reorderState, key = item.id) { dragging ->
                            Column(Modifier.background(if (dragging) colors.elevatedBackground else androidx.compose.ui.graphics.Color.Transparent)) {
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("playlist-order-${item.id}")
                                        .semantics {
                                            customActions = if (state.saving) emptyList() else buildList {
                                                if (index > 0) add(CustomAccessibilityAction(moveUp) { onMove(index, index - 1); true })
                                                if (index < state.items.lastIndex) add(CustomAccessibilityAction(moveDown) { onMove(index, index + 1); true })
                                            }
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text("${index + 1}", Modifier.width(28.dp), color = colors.secondaryContent, fontSize = 13.sp)
                                    AsyncImage(
                                        model = item.track?.coverUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(8.dp))
                                            .background(colors.elevatedBackground),
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(item.track?.title ?: stringResource(R.string.playlist_order_unavailable),
                                            color = colors.content, fontWeight = FontWeight.Medium, fontSize = 16.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(item.track?.artists?.joinToString(" / ") { it.name } ?: item.id.toString(),
                                            color = colors.secondaryContent, fontSize = 12.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Box(
                                        Modifier.size(48.dp).then(if (state.saving) Modifier else Modifier.longPressDraggableHandle(
                                            onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                                        )),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        SfIcon("line.3.horizontal", stringResource(R.string.playlist_order_drag),
                                            tint = colors.secondaryContent, size = 20.dp)
                                    }
                                }
                                HorizontalDivider(color = colors.secondaryContent.copy(alpha = 0.12f))
                            }
                        }
                    }
                }
            }
        }
    }
}
