package com.ljyh.mei.ui.screen.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ljyh.mei.R
import com.ljyh.mei.constants.NeteaseWebSessionNoticeDismissedKey
import com.ljyh.mei.data.network.NeteaseSessionType
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.utils.dataStore
import kotlinx.coroutines.launch

@Composable
fun rememberNeteaseSessionType(providerId: String? = null): NeteaseSessionType = when (rememberSourceSessionState(providerId).kind) {
    com.ljyh.mei.source.shared.OnlineSessionKind.None -> NeteaseSessionType.None
    com.ljyh.mei.source.shared.OnlineSessionKind.Mobile -> NeteaseSessionType.Mobile
    else -> NeteaseSessionType.Web
}

@Composable
fun NeteaseLegacyWebSessionNotice() {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val preferences by context.dataStore.data.collectAsStateWithLifecycle(initialValue = null)
    val session = rememberSourceSessionState()
    val sourceDismissedKey = remember(session.providerId) {
        androidx.datastore.preferences.core.booleanPreferencesKey("source_session_notice_dismissed:" + session.providerId)
    }
    var handledThisLaunch by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    NeteaseLegacyWebSessionDialog(
        visible = !handledThisLaunch && session.providerId == com.ljyh.mei.source.api.ProviderIds.NETEASE &&
            session.kind == com.ljyh.mei.source.shared.OnlineSessionKind.Web &&
            preferences?.get(sourceDismissedKey) != true && preferences?.get(NeteaseWebSessionNoticeDismissedKey) != true,
        onDismiss = { handledThisLaunch = true },
        onDoNotRemind = {
            handledThisLaunch = true
            scope.launch {
                context.dataStore.edit { it[sourceDismissedKey] = true }
            }
        },
        onSignInAgain = {
            handledThisLaunch = true
            Screen.NeteaseLogin.navigate(navController) { addQuery("providerId", session.providerId) }
        },
    )
}

@Composable
internal fun NeteaseLegacyWebSessionDialog(
    onDismiss: () -> Unit,
    onDoNotRemind: () -> Unit,
    onSignInAgain: () -> Unit,
    visible: Boolean = true,
) {
    GlasmioAlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.netease_web_session_notice_title),
        message = stringResource(R.string.netease_web_session_notice_message),
        buttons = listOf(
            GlasmioAlertButtonSpec(
                label = stringResource(R.string.netease_web_session_do_not_remind),
                role = GlasmioAlertButtonRole.Cancel,
                onClick = onDoNotRemind,
            ),
            GlasmioAlertButtonSpec(
                label = stringResource(R.string.netease_web_session_sign_in_again),
                onClick = onSignInAgain,
            ),
        ),
    )
}
