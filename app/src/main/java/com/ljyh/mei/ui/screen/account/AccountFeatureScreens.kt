package com.ljyh.mei.ui.screen.account

import io.github.neoruaa.glasmio.core.*
import io.github.neoruaa.glasmio.glass.*
import io.github.neoruaa.glasmio.icons.*
import io.github.neoruaa.glasmio.ui.*
import io.github.neoruaa.glasmio.scaffold.*
import com.ljyh.mei.ui.navigation.meiloxHeaderAction
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import com.ljyh.mei.ui.navigation.MeiloXPinnedPage

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ljyh.mei.R
import com.ljyh.mei.data.model.melox.AccountAuthorization
import com.ljyh.mei.data.model.melox.AccountDevice
import com.ljyh.mei.data.network.NeteaseSessionType
import com.ljyh.mei.data.repository.AccountFeatureException
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.screen.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun AccountFeatureLoading() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
internal fun AccountFeatureError(message: String, retry: () -> Unit) {
    GlasmioCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message, color = MaterialTheme.colorScheme.error)
            GlasmioButton(retry) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Composable
fun AccountBindingsScreen(viewModel: AccountSecurityViewModel = hiltViewModel()) {
    val nav = LocalNavController.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    MeiloXPinnedListPage(
        title = stringResource(R.string.account_third_party_binding),
        bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
        onNavigateBack = nav::navigateUp,
        actions = { listOf(meiloxHeaderAction("refresh", stringResource(R.string.refresh), viewModel::refresh) {
            SfIcon("arrow.clockwise", null)
        }) },
    ) {
        if (state.loading) item { AccountFeatureLoading() }
        item {
            GlasmioGroupedList {
                listOf(10, 5, 2).forEachIndexed { index, type ->
                    val bindings = state.data?.bindings
                    val binding = bindings?.firstOrNull { it.type == type }
                    val title = stringResource(when (type) {
                        10 -> R.string.account_binding_wechat
                        5 -> R.string.account_binding_qq
                        2 -> R.string.account_binding_weibo
                        else -> R.string.account_binding_email
                    })
                    GlasmioListRow(
                        title = title,
                        showTopSeparator = index > 0,
                        detail = if (bindings == null) null else if (binding?.bound == true) binding.displayName.takeIf(String::isNotBlank)
                            ?: stringResource(R.string.account_bound) else stringResource(R.string.account_not_bound),
                        onClick = { Screen.AccountWeb.navigate(nav) {
                            addPath(OfficialNeteasePage.ThirdPartyBinding.name)
                        } },
                    )
                }
            }
        }
        val error = state.error ?: state.data?.errors?.firstOrNull()
        if (error != null) item { AccountFeatureError(error, viewModel::refresh) }
    }
}

@Composable
fun MusicianAccountScreen(viewModel: MusicianAccountViewModel = hiltViewModel()) {
    val nav = LocalNavController.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session = rememberNeteaseSessionType()
    fun open(page: OfficialNeteasePage) = Screen.AccountWeb.navigate(nav) { addPath(page.name) }
    MeiloXPinnedListPage(
        title = stringResource(R.string.netease_musician),
        bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
        onNavigateBack = nav::navigateUp,
        actions = { listOf(meiloxHeaderAction("refresh", stringResource(R.string.refresh), viewModel::refresh) {
            SfIcon("arrow.clockwise", null)
        }) },
    ) {
        if (session == NeteaseSessionType.None) {
            item { GlasmioListRow(stringResource(R.string.netease_login), onClick = { Screen.NeteaseLogin.navigate(nav) }) }
        } else {
            if (state.loading) item { AccountFeatureLoading() }
            state.data?.let { account ->
                item {
                    GlasmioGroupedList {
                        GlasmioListRow(stringResource(R.string.netease_musician), detail = stringResource(
                            if (account.musician) R.string.musician_registered else R.string.musician_not_registered),
                            systemName = "mic", showTopSeparator = false)
                        if (account.dj) GlasmioListRow(stringResource(R.string.musician_podcaster), detail = stringResource(R.string.account_enabled))
                        if (account.creator) GlasmioListRow(stringResource(R.string.musician_creator), detail = stringResource(R.string.account_enabled))
                    }
                }
            }
            item {
                GlasmioGroupedList {
                    val page = when (state.data?.musician) {
                        true -> OfficialNeteasePage.MusicianCenter
                        false -> OfficialNeteasePage.MusicianApply
                        null -> OfficialNeteasePage.Musician
                    }
                    GlasmioListRow(stringResource(page.titleRes), systemName = "mic", showTopSeparator = false,
                        onClick = { open(page) })
                }
            }
            state.error?.let { message -> item { AccountFeatureError(message, viewModel::refresh) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDevicesScreen(
    initialTab: AccountDeviceTab = AccountDeviceTab.Devices,
    viewModel: AccountDevicesViewModel = hiltViewModel(),
) {
    val nav = LocalNavController.current
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pendingDevice by remember { mutableStateOf<AccountDevice?>(null) }
    var pendingApp by remember { mutableStateOf<AccountAuthorization?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showCaptcha by remember { mutableStateOf(false) }
    var captcha by remember { mutableStateOf("") }
    var actionError by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var smsWait by remember { mutableIntStateOf(0) }
    LaunchedEffect(initialTab) { viewModel.select(initialTab) }
    LaunchedEffect(smsWait) { if (smsWait > 0) { delay(1000); smsWait-- } }
    LaunchedEffect(state.devices, state.applications) {
        if (!busy && pendingDevice != null && state.devices.none { it.key == pendingDevice?.key }) pendingDevice = null
        if (!busy && pendingApp != null && state.applications.none { it.clientId == pendingApp?.clientId }) pendingApp = null
    }
    fun dismiss() { if (!busy) { pendingDevice = null; pendingApp = null; showCaptcha = false; captcha = ""; actionError = null } }
    fun performAction() {
        if (busy) return
        val device = pendingDevice
        val application = pendingApp
        scope.launch {
            busy = true; actionError = null
            try {
                if (device != null) viewModel.removeDevice(device, captcha)
                else if (application != null) viewModel.revoke(application)
                pendingDevice = null; pendingApp = null; showCaptcha = false; captcha = ""
                Toast.makeText(context, context.getString(R.string.account_action_complete), Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (device != null && failure is AccountFeatureException && failure.code == 401) showCaptcha = true
                else { actionError = failure.message; Toast.makeText(context, failure.message, Toast.LENGTH_LONG).show() }
            } finally { busy = false }
        }
    }
    MeiloXPinnedListPage(
        title = stringResource(R.string.account_device_management),
        bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
        onNavigateBack = nav::navigateUp,
        actions = { listOf(meiloxHeaderAction("refresh", stringResource(R.string.refresh), viewModel::refresh) {
            SfIcon("arrow.clockwise", null)
        }) },
    ) {
        item {
            GlasmioSegmentedControl(
                items = listOf(AccountDeviceTab.Devices to stringResource(R.string.account_devices),
                    AccountDeviceTab.Authorizations to stringResource(R.string.account_authorizations),
                    AccountDeviceTab.History to stringResource(R.string.account_authorization_history)),
                selected = state.tab, onSelected = viewModel::select, modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.loading) item { AccountFeatureLoading() }
        state.error?.let { message -> item { AccountFeatureError(message, viewModel::refresh) } }
        if (state.tab == AccountDeviceTab.Devices) {
            state.devices.forEach { device -> item(key = "device:${device.key}") {
                GlasmioGroupedList {
                    val active = device.lastActiveTime.takeIf { it > 0 }?.let {
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(if (it < 1_000_000_000_000L) it * 1000 else it))
                    }
                    GlasmioListRow(
                        title = device.name.ifBlank { device.os },
                        subtitle = listOfNotNull(device.region.takeIf(String::isNotBlank), active).joinToString(" · ").takeIf(String::isNotBlank),
                        systemName = "iphone", showTopSeparator = false,
                        trailing = {
                            if (device.current) Text(stringResource(R.string.account_current_device), style = IosTypography.caption)
                            else GlasmioButton({ pendingDevice = device }) { Text(stringResource(R.string.account_remove_device)) }
                        },
                    )
                }
            } }
        } else {
            state.applications.forEach { application -> item(key = "application:${application.clientId}") {
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = application.name.ifBlank { stringResource(R.string.account_authorized_app) },
                        subtitle = application.description.takeIf(String::isNotBlank), showTopSeparator = false,
                        trailing = { if (state.tab == AccountDeviceTab.Authorizations) GlasmioButton({ pendingApp = application }) {
                            Text(stringResource(R.string.account_revoke_authorization))
                        } },
                    )
                }
            } }
            if (state.more) item { GlasmioButton(viewModel::loadMore) { Text(stringResource(R.string.account_load_more)) } }
        }
        if (!state.loading && state.error == null &&
            (if (state.tab == AccountDeviceTab.Devices) state.devices.isEmpty() else state.applications.isEmpty())) {
            item { Text(stringResource(R.string.account_no_records), Modifier.padding(24.dp)) }
        }
    }
    if ((pendingDevice != null || pendingApp != null) && !showCaptcha) {
        GlasmioAlertDialog(
            onDismissRequest = ::dismiss,
            title = stringResource(if (pendingDevice != null) R.string.account_remove_device else R.string.account_revoke_authorization),
            message = pendingDevice?.name ?: pendingApp?.name,
            buttons = listOf(
                GlasmioAlertButtonSpec(stringResource(R.string.cancel), ::dismiss, GlasmioAlertButtonRole.Cancel, !busy),
                GlasmioAlertButtonSpec(stringResource(R.string.confirm), ::performAction, GlasmioAlertButtonRole.Destructive, !busy),
            ),
        )
    }
    if (showCaptcha && pendingDevice != null) {
        val title = stringResource(R.string.account_verification_code)
        GlasmioBottomSheet(onDismissRequest = ::dismiss, title = title, fitContent = true,
            dismissOnClickOutside = !busy, dismissOnBackPress = !busy,
            topBar = { GlasmioSheetTopToolbar(title) },
        ) {
            val padding = LocalGlasmioSheetContentPadding.current
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp,
                top = padding.calculateTopPadding() + 20.dp,
                bottom = padding.calculateBottomPadding() + 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                GlasmioGroupedList { GlasmioTextField(captcha, { captcha = it.filter(Char::isDigit).take(8) },
                    placeholder = stringResource(R.string.account_verification_code), enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) }
                actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlasmioButton(onClick = {
                        scope.launch {
                            sending = true
                            try { viewModel.sendCode(); smsWait = 60 }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { actionError = failure.message }
                            finally { sending = false }
                        }
                    }, enabled = !sending && smsWait == 0) {
                        Text(if (smsWait > 0) "${smsWait}s" else stringResource(R.string.account_send_code))
                    }
                    GlasmioButton(::performAction, enabled = !busy && captcha.isNotBlank()) { Text(stringResource(R.string.confirm)) }
                }
            }
        }
    }
}
