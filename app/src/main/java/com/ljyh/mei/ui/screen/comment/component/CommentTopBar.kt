package com.ljyh.mei.ui.screen.comment.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.ljyh.mei.R
import com.ljyh.mei.data.model.api.CommentSortType
import com.ljyh.mei.ui.navigation.meiloxHeaderAction
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.glass.rememberGlasmioMenuAnchorState
import io.github.neoruaa.glasmio.scaffold.GlasmioHeaderAction
import io.github.neoruaa.glasmio.ui.GlasmioMenu
import io.github.neoruaa.glasmio.ui.GlasmioMenuItem

/** The page owns the popup while its button participates in the shared navigation bar. */
@Composable
fun CommentSortAction(
    sortType: CommentSortType,
    onSortTypeChange: (CommentSortType) -> Unit,
): GlasmioHeaderAction {
    var expanded by remember { mutableStateOf(false) }
    val anchor = rememberGlasmioMenuAnchorState()
    val label = commentSortLabel(sortType)
    GlasmioMenu(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        itemCount = CommentSortType.entries.size,
        menuAnchorState = anchor,
        externalAnchorBounds = anchor.liveBounds,
        anchor = {},
    ) { childBackdrop, close ->
        CommentSortType.entries.forEach { type ->
            GlasmioMenuItem(
                title = commentSortLabel(type),
                onClick = {
                    onSortTypeChange(type)
                    close()
                },
                systemName = if (sortType == type) "checkmark" else null,
                backdrop = childBackdrop,
            )
        }
    }
    return meiloxHeaderAction(
        key = "sort",
        contentDescription = label,
        onClick = { expanded = true },
        visualKey = sortType,
        menuAnchor = anchor,
    ) { Text(label, color = LocalGlassColors.current.accent) }
}

@Composable
private fun commentSortLabel(type: CommentSortType): String = stringResource(
    when (type) {
        CommentSortType.RECOMMEND -> R.string.comment_sort_recommended
        CommentSortType.HOT -> R.string.comment_sort_hot
        CommentSortType.TIME -> R.string.comment_sort_latest
    },
)
