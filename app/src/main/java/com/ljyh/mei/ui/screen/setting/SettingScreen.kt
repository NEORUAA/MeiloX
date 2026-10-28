package com.ljyh.mei.ui.screen.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ljyh.mei.R
import com.ljyh.mei.constants.UserNicknameKey
import com.ljyh.mei.ui.component.globalProfileHeaderAction
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.data.network.NeteaseSessionType
import com.ljyh.mei.ui.screen.account.rememberNeteaseSessionType
import androidx.compose.ui.draw.alpha
import com.ljyh.mei.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingScreen(
    @Suppress("UNUSED_PARAMETER") scrollBehavior: TopAppBarScrollBehavior,
) {
    val navController = LocalNavController.current
    val sessionType = rememberNeteaseSessionType()
    val (userNickname) = rememberPreference(UserNicknameKey, "")
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    MeiloXPinnedListPage(
        title = stringResource(R.string.settings),
        bottomPadding = insets.calculateBottomPadding(),
        actions = { listOf(globalProfileHeaderAction()) },
    ) {
        item { SettingsSectionTitle(stringResource(R.string.settings_account)) }
        item {
            GlasmioGroupedList {
                if (sessionType == NeteaseSessionType.None) {
                    SettingsEntry(stringResource(R.string.netease_login), "person.crop.circle", false) {
                        Screen.NeteaseLogin.navigate(navController)
                    }
                } else {
                    SettingsEntry(
                        userNickname.ifBlank { stringResource(R.string.account_home) },
                        "person.crop.circle",
                        false,
                    ) {
                        Screen.AccountHome.navigate(navController)
                    }
                    SettingsEntry(stringResource(R.string.netease_musician), "mic") {
                        Screen.NeteaseMusician.navigate(navController)
                    }
                    SettingsEntry(stringResource(R.string.account_security), "shield") {
                        Screen.AccountSecurity.navigate(navController)
                    }
                    val canAuthorize = sessionType == NeteaseSessionType.Mobile
                    GlasmioListRow(
                        title = stringResource(R.string.pc_qr_login),
                        systemName = "viewfinder",
                        subtitle = if (canAuthorize) null else stringResource(R.string.pc_qr_login_mobile_only),
                        modifier = Modifier.alpha(if (canAuthorize) 1f else 0.38f),
                        onClick = if (canAuthorize) {
                            { Screen.PcQrLogin.navigate(navController) }
                        } else null,
                    )
                }
            }
        }
        item { SettingsSectionTitle(stringResource(R.string.settings_application)) }
        item {
            GlasmioGroupedList {
                SettingsEntry(stringResource(R.string.general_settings), "gearshape", false) { Screen.GeneralSettings.navigate(navController) }
                SettingsEntry(stringResource(R.string.settings_appearance), "paintbrush") { Screen.AppearanceSettings.navigate(navController) }
                SettingsEntry(stringResource(R.string.settings_content), "rectangle.grid.1x2") { Screen.ContentSettings.navigate(navController) }
                SettingsEntry(stringResource(R.string.settings_playback), "waveform") { Screen.PlaySettings.navigate(navController) }
                SettingsEntry(stringResource(R.string.lyrics_settings), "quote.bubble") { Screen.LyricsSettings.navigate(navController) }
                SettingsEntry(stringResource(R.string.settings_downloads), "arrow.down.circle") { Screen.DownloadSettings.navigate(navController) }
                SettingsEntry(stringResource(R.string.storage_management), "internaldrive.fill") { Screen.StorageManagement.navigate(navController) }
            }
        }
        item { SettingsSectionTitle(stringResource(R.string.settings_extensions)) }
        item {
            GlasmioGroupedList {
                SettingsEntry(stringResource(R.string.private_messages), "message", false) { Screen.PrivateMessages.navigate(navController) }
                SettingsEntry(stringResource(R.string.listen_together), "person.2.wave.2") { Screen.ListenTogether.navigate(navController) }
                SettingsEntry(stringResource(R.string.song_recognition), "waveform.badge.magnifyingglass") { Screen.SongRecognition.navigate(navController) }
            }
        }
        item { SettingsSectionTitle(stringResource(R.string.settings_information)) }
        item {
            GlasmioGroupedList {
                SettingsEntry(stringResource(R.string.settings_about), "info.circle", false) { Screen.About.navigate(navController) }
            }
        }
    }
}

@Composable
internal fun SettingsSectionTitle(title: String) {
    Text(
        title,
        style = IosTypography.subheadline,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, start = 16.dp),
    )
}

@Composable
fun SettingsGroup(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionTitle(title)
        GlasmioGroupedList(content = content)
    }
}

@Composable
private fun SettingsEntry(
    title: String,
    systemName: String,
    showTopSeparator: Boolean = true,
    onClick: () -> Unit,
) = GlasmioListRow(
    title = title,
    systemName = systemName,
    showTopSeparator = showTopSeparator,
    onClick = onClick,
)
