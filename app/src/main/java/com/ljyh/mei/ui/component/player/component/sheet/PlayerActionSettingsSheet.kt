package com.ljyh.mei.ui.component.player.component.sheet

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ljyh.mei.R
import com.ljyh.mei.constants.PlayerActionKey
import com.ljyh.mei.ui.component.sheet.rememberMeiloXSheetDismissal
import com.ljyh.mei.ui.model.PlayerAction
import com.ljyh.mei.utils.rememberPreference
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioHeaderScrollEdge
import io.github.neoruaa.glasmio.ui.*

@Composable
fun PlayerActionSettingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val (actionString, setActionString) = rememberPreference(
        PlayerActionKey, PlayerAction.toSettings(PlayerAction.defaultActions),
    )
    val selected = remember(actionString) { PlayerAction.fromSettings(actionString).toMutableStateList() }
    val available = PlayerAction.entries.filterNot(selected::contains)
    fun save() = setActionString(PlayerAction.toSettings(selected))
    val dismissal = rememberMeiloXSheetDismissal(onDismiss)
    val listState = rememberLazyListState()
    val title = stringResource(R.string.player_action_customize)
    GlasmioBottomSheet(
        onDismissRequest = dismissal::onDismissed,
        sheetState = dismissal.state,
        skipPartiallyExpanded = false,
        title = title,
        topBarBlurProgress = rememberGlasmioHeaderScrollEdge(listState),
        topBar = {
            GlasmioSheetTopToolbar(title, actions = {
                GlasmioSheetToolbarButton(onClick = { dismissal.requestDismiss() }) {
                    SfIcon("xmark", stringResource(R.string.cancel))
                }
            })
        },
    ) {
        val padding = LocalGlasmioSheetContentPadding.current
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f), state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item("selected") {
                Column {
                    GlasmioSectionTitle("${stringResource(R.string.player_action_visible)} ${selected.size}/5")
                    GlasmioGroupedList {
                        selected.forEachIndexed { index, action ->
                            PlayerActionRow(action, selected = true, showSeparator = index > 0) {
                                if (selected.size > 1) { selected.remove(action); save() }
                                else Toast.makeText(context, R.string.player_action_keep_one, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    GlasmioGroupedTableFooter(stringResource(R.string.player_action_customize_hint))
                }
            }
            if (available.isNotEmpty()) item("available") {
                Column {
                    GlasmioSectionTitle(stringResource(R.string.player_action_available))
                    GlasmioGroupedList {
                        available.forEachIndexed { index, action ->
                            PlayerActionRow(action, selected = false, showSeparator = index > 0) {
                                if (selected.size < 5) { selected.add(action); save() }
                                else Toast.makeText(context, R.string.player_action_max_five, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerActionRow(action: PlayerAction, selected: Boolean, showSeparator: Boolean, onClick: () -> Unit) {
    GlasmioListRow(
        title = stringResource(action.labelRes), systemName = action.systemName,
        onClick = onClick, showTopSeparator = showSeparator,
        trailing = { SfIcon(if (selected) "minus.circle.fill" else "plus.circle.fill", null, size = 18.dp) },
    )
}
