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
import androidx.compose.ui.platform.LocalContext
import com.ljyh.mei.R
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.NeteaseRefreshTokenKey
import com.ljyh.mei.constants.UserNicknameKey
import com.ljyh.mei.ui.component.GlobalProfileAvatarButton
import com.ljyh.mei.ui.glass.IosGroupedList
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.IosListRow
import com.ljyh.mei.ui.glass.IosTypography
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.account.logoutNetease
import com.ljyh.mei.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingScreen(
    @Suppress("UNUSED_PARAMETER") scrollBehavior: TopAppBarScrollBehavior,
) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val (cookie) = rememberPreference(CookieKey, "")
    val (refreshToken) = rememberPreference(NeteaseRefreshTokenKey, "")
    val (userNickname) = rememberPreference(UserNicknameKey, "")
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    IosPinnedListPage(
        title = stringResource(R.string.settings),
        bottomPadding = insets.calculateBottomPadding(),
        actions = { GlobalProfileAvatarButton() },
    ) {
        item { SettingsSectionTitle(stringResource(R.string.settings_account)) }
        item {
            IosGroupedList {
                if (cookie.isBlank() || refreshToken.isBlank()) {
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
                    SettingsEntry(stringResource(R.string.pc_qr_login), "viewfinder") {
                        Screen.PcQrLogin.navigate(navController)
                    }
                    SettingsEntry(stringResource(R.string.netease_logout), "rectangle.portrait.and.arrow.forward") {
                        logoutNetease(context)
                    }
                }
            }
        }
        item { SettingsSectionTitle(stringResource(R.string.settings_application)) }
        item {
            IosGroupedList {
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
            IosGroupedList {
                SettingsEntry(stringResource(R.string.private_messages), "message", false) { Screen.PrivateMessages.navigate(navController) }
                SettingsEntry(stringResource(R.string.listen_together), "person.2.wave.2") { Screen.ListenTogether.navigate(navController) }
                SettingsEntry(stringResource(R.string.song_recognition), "waveform.badge.magnifyingglass") { Screen.SongRecognition.navigate(navController) }
            }
        }
        item { SettingsSectionTitle(stringResource(R.string.settings_information)) }
        item {
            IosGroupedList {
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
        IosGroupedList(content = content)
    }
}

@Composable
private fun SettingsEntry(
    title: String,
    systemName: String,
    showTopSeparator: Boolean = true,
    onClick: () -> Unit,
) = IosListRow(
    title = title,
    systemName = systemName,
    showTopSeparator = showTopSeparator,
    onClick = onClick,
)
