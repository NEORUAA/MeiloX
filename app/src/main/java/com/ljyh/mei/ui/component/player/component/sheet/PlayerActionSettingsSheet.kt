package com.ljyh.mei.ui.component.player.component.sheet

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ljyh.mei.R
import com.ljyh.mei.constants.PlayerActionKey
import io.github.neoruaa.glasmio.ui.GlasmioActionSheetContent
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.icons.SfIcon
import com.ljyh.mei.ui.model.PlayerAction
import com.ljyh.mei.utils.rememberPreference

@Composable
fun PlayerActionSettingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val (actionString, setActionString) = rememberPreference(
        PlayerActionKey,
        PlayerAction.toSettings(PlayerAction.defaultActions),
    )
    val selected = remember(actionString) { PlayerAction.fromSettings(actionString).toMutableStateList() }
    val available = PlayerAction.entries.filterNot(selected::contains)

    fun save() = setActionString(PlayerAction.toSettings(selected))

    GlasmioBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        GlasmioActionSheetContent(
            title = stringResource(R.string.player_action_customize),
            message = stringResource(R.string.player_action_customize_hint),
        ) {
            GlasmioListRow(
                title = stringResource(R.string.player_action_visible),
                detail = "${selected.size}/5",
                showTopSeparator = false,
            )
            selected.forEach { action ->
                PlayerActionRow(action, selected = true) {
                            if (selected.size > 1) {
                                selected.remove(action)
                                save()
                            } else {
                                Toast.makeText(context, R.string.player_action_keep_one, Toast.LENGTH_SHORT).show()
                            }
                }
            }
            GlasmioListRow(title = stringResource(R.string.player_action_available))
            available.forEach { action ->
                PlayerActionRow(action, selected = false) {
                            if (selected.size < 5) {
                                selected.add(action)
                                save()
                            } else {
                                Toast.makeText(context, R.string.player_action_max_five, Toast.LENGTH_SHORT).show()
                            }
                }
            }
        }
    }
}

@Composable
private fun PlayerActionRow(action: PlayerAction, selected: Boolean, onClick: () -> Unit) {
    GlasmioListRow(
        title = stringResource(action.labelRes),
        systemName = action.systemName,
        onClick = onClick,
        trailing = {
            SfIcon(if (selected) "minus.circle.fill" else "plus.circle.fill", null, size = 18.dp)
        },
    )
}
