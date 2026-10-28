package com.ljyh.mei.ui.screen.playlist

import io.github.neoruaa.glasmio.ui.glasmioGroupedListBackground
import com.ljyh.mei.ui.component.sheet.rememberMeiloXSheetDismissal
import androidx.compose.foundation.layout.fillMaxSize
import com.kyant.capsule.ContinuousRoundedRectangle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
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
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioHeaderScrollEdge
import io.github.neoruaa.glasmio.ui.LocalGlasmioSheetContentPadding
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioSheetButton
import io.github.neoruaa.glasmio.ui.GlasmioSheetTopToolbar
import io.github.neoruaa.glasmio.ui.GlasmioSheetValue
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import kotlinx.coroutines.flow.collect
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun PlaylistOrderSheet(
    state: PlaylistOrderState,
    onMove: (Int, Int) -> Unit,
    onVisibleTracks: (List<Long>) -> Unit,
    onSave: () -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
    visible: Boolean = true,
) {
    val current by rememberUpdatedState(state)
    val currentVisible by rememberUpdatedState(visible)
    val move by rememberUpdatedState(onMove)
    val loadVisible by rememberUpdatedState(onVisibleTracks)
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val dismissal = rememberMeiloXSheetDismissal(
        onDismiss,
        confirmValueChange = { !visible || !current.saving || it != GlasmioSheetValue.Hidden },
        visible = visible,
    )
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        if (currentVisible && !current.saving) {
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
            .collect { if (currentVisible) loadVisible(it) }
    }
    val topBarBlurProgress = rememberGlasmioHeaderScrollEdge(listState)
    GlasmioBottomSheet(
        visible = visible,
        onDismissRequest = dismissal::onDismissed,
        sheetState = dismissal.state,
        skipPartiallyExpanded = false,
        fitContent = false,
        title = stringResource(R.string.playlist_order_title),
        topBarBlurProgress = topBarBlurProgress,
        topBar = {
            GlasmioSheetTopToolbar(
                title = stringResource(R.string.playlist_order_title),
                navigation = {
                    GlasmioSheetButton(onClick = { dismissal.requestDismiss() }, enabled = visible && !state.saving) {
                        Text(stringResource(R.string.playlist_order_cancel))
                    }
                },
                actions = {
                    GlasmioSheetButton(
                        onClick = onSave,
                        enabled = visible && !state.loading && !state.saving &&
                            state.error != PlaylistOrderError.LoadFailed && state.error != PlaylistOrderError.PlaylistChanged,
                        modifier = Modifier.testTag("playlist-order-save"),
                    ) {
                        if (state.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.playlist_order_done))
                    }
                },
            )
        },
    ) {
        val colors = LocalGlassColors.current
        val sheetPadding = LocalGlasmioSheetContentPadding.current
        val moveUp = stringResource(R.string.playlist_order_move_up)
        val moveDown = stringResource(R.string.playlist_order_move_down)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("playlist-order-list"),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = sheetPadding.calculateTopPadding() + 8.dp,
                bottom = sheetPadding.calculateBottomPadding() + 16.dp,
            ),
        ) {
            item(key = "order-hint") {
                Text(
                    stringResource(R.string.playlist_order_hint),
                    color = colors.secondaryContent,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            state.error?.let { error ->
                item(key = "order-error") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text(stringResource(when (error) {
                            PlaylistOrderError.LoadFailed -> R.string.playlist_order_load_failed
                            PlaylistOrderError.SaveFailed -> R.string.playlist_order_save_failed
                            PlaylistOrderError.PlaylistChanged -> R.string.playlist_order_changed
                        }), color = colors.secondaryContent, fontSize = 14.sp)
                        if (error != PlaylistOrderError.SaveFailed) {
                            GlasmioSheetButton(onClick = onReload, modifier = Modifier.padding(top = 8.dp)) {
                                Text(stringResource(R.string.playlist_order_reload))
                            }
                        }
                    }
                }
            }
            if (state.loading) {
                item(key = "order-loading") {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }
            } else {
                itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                    val groupShape = ContinuousRoundedRectangle(
                        topStart = if (index == 0) 26.dp else 0.dp,
                        topEnd = if (index == 0) 26.dp else 0.dp,
                        bottomStart = if (index == state.items.lastIndex) 26.dp else 0.dp,
                        bottomEnd = if (index == state.items.lastIndex) 26.dp else 0.dp,
                    )
                    Column {
                        ReorderableItem(reorderState, key = item.id) {
                            Row(
                                Modifier.fillMaxWidth().glasmioGroupedListBackground(groupShape)
                                    .padding(horizontal = 20.dp, vertical = 8.dp).testTag("playlist-order-${item.id}")
                                    .semantics {
                                        customActions = if (state.saving || !visible) emptyList() else buildList {
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
                                    modifier = Modifier.size(42.dp).clip(ContinuousRoundedRectangle(8.dp))
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
                                    Modifier.size(48.dp).then(if (state.saving || !visible) Modifier else Modifier.longPressDraggableHandle(
                                        onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                                    )),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    SfIcon("line.3.horizontal", stringResource(R.string.playlist_order_drag),
                                        tint = colors.secondaryContent, size = 20.dp)
                                }
                            }
                        }
                        if (index < state.items.lastIndex) HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = colors.separator,
                        )
                    }
                }
            }
        }
    }
}
