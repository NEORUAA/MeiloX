package com.ljyh.mei.ui.screen.comment.component

import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import com.ljyh.mei.R
import com.ljyh.mei.data.model.api.CommentSortType
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.core.LocalGlassDimensions
import io.github.neoruaa.glasmio.ui.GlasmioContextMenu
import io.github.neoruaa.glasmio.ui.GlasmioMenuItem
import io.github.neoruaa.glasmio.ui.GlasmioMenu
import io.github.neoruaa.glasmio.ui.GlasmioTopBar
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentTopBar(
    total: Int,
    sortType: CommentSortType,
    onSortTypeChange: (CommentSortType) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.layout.Box(modifier) {
        GlasmioTopBar(
            title = if (total > 0) stringResource(R.string.comment_title_count, total)
            else stringResource(R.string.comment_title),
            navigation = {
                GlasmioIconButton(onClick = onBack) {
                    SfIcon(SfSymbol.ChevronBack, stringResource(R.string.navigation_back), mirrored = true)
                }
            },
        actions = {
            CommentSortAction(sortType, onSortTypeChange)
        },
        )
    }
}

@Composable
fun CommentSortAction(
    sortType: CommentSortType,
    onSortTypeChange: (CommentSortType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    GlasmioMenu(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        itemCount = CommentSortType.entries.size,
        anchor = { onClick ->
            GlasmioButton(
                onClick = onClick,
                modifier = Modifier.height(LocalGlassDimensions.current.iconButtonSize),
            ) {
                Text(
                    text = commentSortLabel(sortType),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
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
}

@Composable
private fun commentSortLabel(type: CommentSortType): String = stringResource(
    when (type) {
        CommentSortType.RECOMMEND -> R.string.comment_sort_recommended
        CommentSortType.HOT -> R.string.comment_sort_hot
        CommentSortType.TIME -> R.string.comment_sort_latest
    },
)
