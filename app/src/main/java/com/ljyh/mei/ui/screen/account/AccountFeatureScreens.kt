package com.ljyh.mei.ui.screen.account

import com.ljyh.mei.ui.component.sheet.MeiloXBottomSheet
import com.ljyh.mei.ui.component.sheet.rememberMeiloXSheetDismissal
import com.ljyh.mei.ui.component.utils.rememberAlertContent
import io.github.neoruaa.glasmio.core.*
import io.github.neoruaa.glasmio.glass.*
import io.github.neoruaa.glasmio.icons.*
import io.github.neoruaa.glasmio.ui.*
import io.github.neoruaa.glasmio.scaffold.*
import com.ljyh.mei.ui.navigation.meiloxHeaderAction
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import com.ljyh.mei.ui.navigation.MeiloXPinnedPage

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.ljyh.mei.ui.screen.main.library.component.groupedLazyItems
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
            item {
                GlasmioGroupedList {
                    GlasmioListRow(
                        stringResource(R.string.netease_login),
                        onClick = { Screen.NeteaseLogin.navigate(nav) },
                    )
                }
            }
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
        verticalArrangement = Arrangement.spacedBy(0.dp),
        actions = { listOf(meiloxHeaderAction("refresh", stringResource(R.string.refresh), viewModel::refresh) {
            SfIcon("arrow.clockwise", null)
        }) },
    ) {
        item(key = "account-device-tabs") {
            Box(Modifier.padding(top = 10.dp)) {
                GlasmioSegmentedControl(
                    items = listOf(AccountDeviceTab.Devices to stringResource(R.string.account_devices),
                        AccountDeviceTab.Authorizations to stringResource(R.string.account_authorizations),
                        AccountDeviceTab.History to stringResource(R.string.account_authorization_history)),
                    selected = state.tab, onSelected = viewModel::select, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (state.loading) item {
            Box(Modifier.padding(top = 10.dp)) { AccountFeatureLoading() }
        }
        state.error?.let { message -> item {
            Box(Modifier.padding(top = 10.dp)) { AccountFeatureError(message, viewModel::refresh) }
        } }
        accountDeviceRecordItems(
            state = state,
            enabled = !busy,
            onRemoveDevice = { pendingDevice = it },
            onRevokeAuthorization = { pendingApp = it },
        )
        if (state.tab != AccountDeviceTab.Devices && state.more) {
            item(key = "account-load-more") {
                Box(Modifier.padding(top = 10.dp)) {
                    GlasmioButton(viewModel::loadMore) { Text(stringResource(R.string.account_load_more)) }
                }
            }
        }
        if (!state.loading && state.error == null &&
            (if (state.tab == AccountDeviceTab.Devices) state.devices.isEmpty() else state.applications.isEmpty())) {
            item { Text(stringResource(R.string.account_no_records), Modifier.padding(top = 10.dp).padding(24.dp)) }
        }
    }
    val confirmationVisible = (pendingDevice != null || pendingApp != null) && !showCaptcha
    val confirmation = rememberAlertContent((pendingDevice to pendingApp).takeIf { confirmationVisible })
    confirmation?.let { (device, application) ->
        GlasmioAlertDialog(
            visible = confirmationVisible,
            onDismissRequest = ::dismiss,
            title = stringResource(if (device != null) R.string.account_remove_device else R.string.account_revoke_authorization),
            message = device?.name ?: application?.name,
            buttons = listOf(
                GlasmioAlertButtonSpec(stringResource(R.string.cancel), ::dismiss, GlasmioAlertButtonRole.Cancel, !busy && confirmationVisible),
                GlasmioAlertButtonSpec(stringResource(R.string.confirm), ::performAction, GlasmioAlertButtonRole.Destructive, !busy && confirmationVisible),
            ),
        )
    }
    val captchaVisible = showCaptcha && pendingDevice != null
    val captchaContent = rememberAlertContent((captcha to actionError).takeIf { captchaVisible })
    val captchaDismissal = rememberMeiloXSheetDismissal(
        onDismiss = ::dismiss,
        skipPartiallyExpanded = true,
        visible = captchaVisible,
        confirmValueChange = { !busy || it != GlasmioSheetValue.Hidden },
    )
    if (captchaContent != null) {
        val title = stringResource(R.string.account_verification_code)
        MeiloXBottomSheet(
            onDismissRequest = captchaDismissal::onDismissed,
            visible = captchaVisible,
            sheetState = captchaDismissal.state,
            title = title,
            fitContent = true,
            dismissOnClickOutside = !busy,
            dismissOnBackPress = !busy,
            topBar = { GlasmioSheetTopToolbar(title) },
        ) {
            val padding = LocalGlasmioSheetContentPadding.current
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp,
                top = padding.calculateTopPadding() + 20.dp,
                bottom = padding.calculateBottomPadding() + 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                GlasmioGroupedList { GlasmioTextField(captchaContent.first, { captcha = it.filter(Char::isDigit).take(8) },
                    placeholder = stringResource(R.string.account_verification_code), enabled = !busy && captchaVisible,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) }
                captchaContent.second?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlasmioButton(onClick = {
                        scope.launch {
                            sending = true
                            try { viewModel.sendCode(); smsWait = 60 }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { actionError = failure.message }
                            finally { sending = false }
                        }
                    }, enabled = !busy && !sending && smsWait == 0 && captchaVisible) {
                        Text(if (smsWait > 0) "${smsWait}s" else stringResource(R.string.account_send_code))
                    }
                    GlasmioButton(::performAction, enabled = !busy && captcha.isNotBlank() && captchaVisible) { Text(stringResource(R.string.confirm)) }
                }
            }
        }
    }
}

/** Keep accumulated authorization pages virtualized while sharing the standard grouped-row shell. */
internal fun LazyListScope.accountDeviceRecordItems(
    state: AccountDevicesState,
    onRemoveDevice: (AccountDevice) -> Unit,
    onRevokeAuthorization: (AccountAuthorization) -> Unit,
    enabled: Boolean = true,
    topPadding: Dp = 10.dp,
) {
    if (state.tab == AccountDeviceTab.Devices) {
        groupedLazyItems(
            items = state.devices,
            key = { "device:${it.key}" },
            contentType = "account-device",
            firstItemTopPadding = topPadding,
        ) { device, index ->
            val active = device.lastActiveTime.takeIf { it > 0 }?.let {
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(if (it < 1_000_000_000_000L) it * 1000 else it))
            }
            GlasmioListRow(
                title = device.name.ifBlank { device.os },
                subtitle = listOfNotNull(device.region.takeIf(String::isNotBlank), active)
                    .joinToString(" · ").takeIf(String::isNotBlank),
                systemName = "iphone",
                showTopSeparator = index > 0,
                trailing = {
                    if (device.current) {
                        Text(stringResource(R.string.account_current_device), style = IosTypography.caption)
                    } else {
                        AccountDestructiveIconAction(
                            description = stringResource(R.string.account_remove_device),
                            enabled = enabled,
                            onClick = { onRemoveDevice(device) },
                        )
                    }
                },
            )
        }
    } else {
        groupedLazyItems(
            items = state.applications,
            key = { "application:${state.tab}:${it.clientId}" },
            contentType = "account-authorization",
            firstItemTopPadding = topPadding,
        ) { application, index ->
            GlasmioListRow(
                title = application.name.ifBlank { stringResource(R.string.account_authorized_app) },
                subtitle = application.description.takeIf(String::isNotBlank),
                showTopSeparator = index > 0,
                trailing = if (state.tab == AccountDeviceTab.Authorizations) {
                    {
                        AccountDestructiveIconAction(
                            description = stringResource(R.string.account_revoke_authorization),
                            enabled = enabled,
                            onClick = { onRevokeAuthorization(application) },
                        )
                    }
                } else null,
            )
        }
    }
}

@Composable
private fun AccountDestructiveIconAction(description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp)
            .alpha(if (enabled) 1f else .4f)
            .clickable(enabled = enabled, role = Role.Button, interactionSource = null, indication = null, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        // Match the Demo's borderless grouped-row symbol accessory, with a full touch target.
        SfIcon(
            "trash", null, size = 22.dp, fontSize = 17.sp,
            weight = FontWeight.Normal, tint = LocalGlassColors.current.destructive,
            fontFeatureSettings = "'ss16' 1", fitGlyphToBounds = false,
        )
    }
}
