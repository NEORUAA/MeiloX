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
import com.ljyh.mei.data.network.neteaseSessionType
import com.ljyh.mei.data.network.shouldExplainLegacyWebSession
import com.ljyh.mei.ui.glass.IosAlertButtonRole
import com.ljyh.mei.ui.glass.IosAlertButtonSpec
import com.ljyh.mei.ui.glass.IosAlertDialog
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.utils.dataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun rememberNeteaseSessionType(): NeteaseSessionType {
    val context = LocalContext.current
    val flow = remember(context) { context.dataStore.data.map { it.neteaseSessionType() } }
    return flow.collectAsStateWithLifecycle(initialValue = NeteaseSessionType.None).value
}

@Composable
fun NeteaseLegacyWebSessionNotice() {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val preferences by context.dataStore.data.collectAsStateWithLifecycle(initialValue = null)
    var handledThisLaunch by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (!handledThisLaunch && preferences?.shouldExplainLegacyWebSession() == true) {
        NeteaseLegacyWebSessionDialog(
            onDismiss = { handledThisLaunch = true },
            onDoNotRemind = {
                handledThisLaunch = true
                scope.launch {
                    context.dataStore.edit { it[NeteaseWebSessionNoticeDismissedKey] = true }
                }
            },
            onSignInAgain = {
                handledThisLaunch = true
                Screen.NeteaseLogin.navigate(navController)
            },
        )
    }
}

@Composable
internal fun NeteaseLegacyWebSessionDialog(
    onDismiss: () -> Unit,
    onDoNotRemind: () -> Unit,
    onSignInAgain: () -> Unit,
) {
    IosAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.netease_web_session_notice_title),
        message = stringResource(R.string.netease_web_session_notice_message),
        buttons = listOf(
            IosAlertButtonSpec(
                label = stringResource(R.string.netease_web_session_do_not_remind),
                role = IosAlertButtonRole.Cancel,
                onClick = onDoNotRemind,
            ),
            IosAlertButtonSpec(
                label = stringResource(R.string.netease_web_session_sign_in_again),
                onClick = onSignInAgain,
            ),
        ),
    )
}
