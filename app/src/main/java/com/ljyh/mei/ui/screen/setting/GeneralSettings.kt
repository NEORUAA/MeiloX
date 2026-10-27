package com.ljyh.mei.ui.screen.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ljyh.mei.R
import com.ljyh.mei.constants.AppAppearance
import com.ljyh.mei.constants.AppAppearanceKey
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.RecognizeClipboardLinksKey
import io.github.neoruaa.glasmio.ui.GlasmioCard
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioSwitch
import io.github.neoruaa.glasmio.scaffold.GlasmioPinnedListPage
import io.github.neoruaa.glasmio.ui.GlasmioPopUpButton
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.utils.rememberEnumPreference
import com.ljyh.mei.utils.rememberPreference
import com.ljyh.mei.utils.setClipboard
import com.ljyh.mei.data.network.NeteaseSessionType
import com.ljyh.mei.ui.screen.account.logoutNetease
import com.ljyh.mei.ui.screen.account.rememberNeteaseSessionType
import kotlinx.coroutines.launch

@Composable
fun GeneralSettings() {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val (appearance, setAppearance) = rememberEnumPreference(AppAppearanceKey, AppAppearance.System)
    val (recognizeClipboard, setRecognizeClipboard) = rememberPreference(RecognizeClipboardLinksKey, false)
    val (cookie) = rememberPreference(CookieKey, "")
    val sessionType = rememberNeteaseSessionType()
    var showLogoutConfirmation by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val logoutTitle = stringResource(
        if (sessionType == NeteaseSessionType.Mobile) R.string.netease_logout
        else R.string.netease_logout_web,
    )

    GlasmioPinnedListPage(
        title = stringResource(R.string.general_settings),
        onNavigateBack = navController::navigateUp,
        bottomPadding = insets.calculateBottomPadding(),
    ) {
        item {
            SettingsGroup(stringResource(R.string.general_appearance)) {
                GeneralChoice(
                    title = stringResource(R.string.general_theme),
                    systemName = when (appearance) {
                        AppAppearance.System -> "circle.lefthalf.filled"
                        AppAppearance.Light -> "sun.max.fill"
                        AppAppearance.Dark -> "moon.fill"
                    },
                    selected = appearance,
                    values = AppAppearance.entries,
                    valueLabel = { themeLabel(it) },
                    onSelected = setAppearance,
                )
                Text(
                    stringResource(R.string.general_theme_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
        item {
            SettingsGroup(stringResource(R.string.general_clipboard)) {
                GeneralToggle(R.string.general_recognize_clipboard, R.string.general_recognize_clipboard_description, "document.on.clipboard", recognizeClipboard, setRecognizeClipboard)
            }
        }
        item {
            SettingsGroup(stringResource(R.string.settings_account)) {
                GlasmioCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (cookie.isNotBlank()) 1f else 0.38f),
                    onClick = if (cookie.isNotBlank()) {
                        { setClipboard(context, cookie, "MUSIC_U") }
                    } else {
                        null
                    },
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SfIcon("document.on.clipboard", null, size = 21.dp)
                        Column(
                            Modifier.weight(1f).padding(horizontal = 13.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(stringResource(R.string.general_export_music_u))
                            Text(
                                stringResource(R.string.general_export_music_u_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        SfIcon("chevron.forward", null, size = 15.dp, tint = LocalGlassColors.current.separator)
                    }
                }
                if (sessionType != NeteaseSessionType.None) {
                    GlasmioListRow(
                        title = logoutTitle,
                        systemName = "rectangle.portrait.and.arrow.forward",
                        onClick = { showLogoutConfirmation = true },
                    )
                }
            }
        }
    }
    NeteaseLogoutConfirmation(
        visible = showLogoutConfirmation && sessionType != NeteaseSessionType.None,
        title = logoutTitle,
        onDismiss = { showLogoutConfirmation = false },
        onConfirm = {
            showLogoutConfirmation = false
            scope.launch { logoutNetease(context) }
        },
    )
}

@Composable
internal fun NeteaseLogoutConfirmation(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit, visible: Boolean = true) {
    GlasmioAlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = title,
        message = stringResource(R.string.netease_logout_confirm_message),
        buttons = listOf(
            GlasmioAlertButtonSpec(
                label = stringResource(R.string.cancel),
                role = GlasmioAlertButtonRole.Cancel,
                onClick = onDismiss,
            ),
            GlasmioAlertButtonSpec(
                label = stringResource(R.string.netease_logout_confirm),
                role = GlasmioAlertButtonRole.Destructive,
                onClick = onConfirm,
            ),
        ),
    )
}

@Composable
private fun GeneralToggle(
    titleRes: Int,
    descriptionRes: Int,
    systemName: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    GlasmioCard(Modifier.fillMaxWidth(), onClick = { onCheckedChange(!checked) }) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            SfIcon(systemName, null)
            Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
                Text(stringResource(titleRes))
                Text(stringResource(descriptionRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GlasmioSwitch(checked, onCheckedChange)
        }
    }
}

@Composable
private fun <T> GeneralChoice(
    title: String,
    systemName: String,
    selected: T,
    values: List<T>,
    valueLabel: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    enabled: Boolean = true,
) {
    GlasmioCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            SfIcon(systemName, null)
            Text(title, modifier = Modifier.weight(1f).padding(horizontal = 13.dp))
            GlasmioPopUpButton(
                selected = selected,
                items = values,
                onSelected = onSelected,
                label = valueLabel,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun themeLabel(appearance: AppAppearance): String = stringResource(
    when (appearance) {
        AppAppearance.System -> R.string.general_theme_system
        AppAppearance.Light -> R.string.general_theme_light
        AppAppearance.Dark -> R.string.general_theme_dark
    },
)
