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
import com.ljyh.mei.ui.glass.GlassIconButton
import com.ljyh.mei.ui.glass.GlassButton
import com.ljyh.mei.ui.glass.LocalGlassDimensions
import com.ljyh.mei.ui.glass.IosContextMenu
import com.ljyh.mei.ui.glass.IosMenuItem
import com.ljyh.mei.ui.glass.IosPopupMenu
import com.ljyh.mei.ui.glass.IosTopToolbar
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol

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
        IosTopToolbar(
            title = if (total > 0) stringResource(R.string.comment_title_count, total)
            else stringResource(R.string.comment_title),
            navigation = {
                GlassIconButton(onClick = onBack) {
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
    IosPopupMenu(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        itemCount = CommentSortType.entries.size,
        anchor = { onClick ->
            GlassButton(
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
            IosMenuItem(
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
