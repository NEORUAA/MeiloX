package com.ljyh.mei.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.calvin.reorderable.ReorderableColumn

/**
 * Figma 5707:47196 grouped rows with a trailing iOS grabber.
 *
 * The grabber is the only drag target, so vertical scrolling remains available from the rest of
 * each row. The caller receives the complete reordered list and owns persistence.
 */
@Composable
fun <T> IosReorderableGroup(
    items: List<T>,
    onReorder: (List<T>) -> Unit,
    dragHandleContentDescription: String,
    moveUpLabel: String,
    moveDownLabel: String,
    modifier: Modifier = Modifier,
    itemContent: @Composable RowScope.(T) -> Unit,
) {
    if (items.isEmpty()) return

    val colors = LocalGlassColors.current
    val hapticFeedback = LocalHapticFeedback.current
    val commitMove: (Int, Int) -> Boolean = { fromIndex, toIndex ->
        if (fromIndex !in items.indices || toIndex !in items.indices || fromIndex == toIndex) {
            false
        } else {
            val reordered = items.toMutableList()
            val moved = reordered.removeAt(fromIndex)
            reordered.add(toIndex, moved)
            onReorder(reordered)
            true
        }
    }

    IosGroupedList(modifier = modifier) {
        ReorderableColumn(
            list = items,
            onSettle = { fromIndex, toIndex -> commitMove(fromIndex, toIndex) },
            onMove = {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { index, item, _ ->
            ReorderableItem(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .drawBehind {
                            if (index > 0) {
                                val inset = 16.dp.toPx()
                                drawLine(
                                    color = colors.separator,
                                    start = Offset(inset, 0f),
                                    end = Offset(size.width - inset, 0f),
                                    strokeWidth = 1.dp.toPx(),
                                )
                            }
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    itemContent(item)
                    val accessibilityActions = buildList {
                        if (index > 0) {
                            add(
                                CustomAccessibilityAction(moveUpLabel) {
                                    commitMove(index, index - 1)
                                },
                            )
                        }
                        if (index < items.lastIndex) {
                            add(
                                CustomAccessibilityAction(moveDownLabel) {
                                    commitMove(index, index + 1)
                                },
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .width(38.dp)
                            .fillMaxHeight()
                            .draggableHandle(
                                onDragStarted = {
                                    hapticFeedback.performHapticFeedback(
                                        HapticFeedbackType.GestureThresholdActivate,
                                    )
                                },
                                onDragStopped = {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                },
                            )
                            .semantics {
                                contentDescription = dragHandleContentDescription
                                role = Role.Button
                                customActions = accessibilityActions
                            }
                            .padding(start = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        SfIcon(
                            systemName = "line.3.horizontal",
                            contentDescription = null,
                            tint = colors.tertiaryContent,
                            size = 22.dp,
                            fontSize = 17.sp,
                        )
                    }
                }
            }
        }
    }
}
