package com.ljyh.mei.ui.screen.account

import io.github.neoruaa.glasmio.core.*
import io.github.neoruaa.glasmio.glass.*
import io.github.neoruaa.glasmio.icons.*
import io.github.neoruaa.glasmio.ui.*
import io.github.neoruaa.glasmio.scaffold.*
import com.ljyh.mei.ui.navigation.meiloxHeaderAction
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import com.ljyh.mei.ui.navigation.MeiloXPinnedPage

import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.ljyh.mei.R
import com.ljyh.mei.data.network.NeteaseSessionType
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.screen.Screen

@Composable
fun AccountSecurityScreen(viewModel: AccountSecurityViewModel = hiltViewModel()) {
    val navController = LocalNavController.current
    val sessionType = rememberNeteaseSessionType()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openPage: (OfficialNeteasePage) -> Unit = { page ->
        Screen.AccountWeb.navigate(navController) { addPath(page.name) }
    }
    MeiloXPinnedListPage(
        title = stringResource(R.string.account_security),
        onNavigateBack = navController::navigateUp,
        bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
        actions = { listOf(meiloxHeaderAction("refresh", stringResource(R.string.refresh), viewModel::refresh) {
            SfIcon("arrow.clockwise", null)
        }) },
    ) {
        item {
            GlasmioGroupedList {
                if (sessionType == NeteaseSessionType.None) {
                    GlasmioListRow(
                        title = stringResource(R.string.netease_login),
                        systemName = "person.crop.circle",
                        onClick = { Screen.NeteaseLogin.navigate(navController) },
                    )
                } else {
                    GlasmioListRow(
                        title = stringResource(R.string.account_phone_binding),
                        systemName = "iphone",
                        detail = state.data?.let { if (it.phoneBound) it.phone else stringResource(R.string.account_not_bound) },
                        onClick = { openPage(OfficialNeteasePage.PhoneBinding) },
                    )
                    GlasmioListRow(
                        title = stringResource(R.string.account_third_party_binding),
                        systemName = "person.2",
                        onClick = { Screen.AccountBindings.navigate(navController) },
                    )
                    GlasmioListRow(
                        title = stringResource(R.string.account_login_protection),
                        systemName = "shield",
                        detail = state.data?.protectionEnabled?.let {
                            stringResource(if (it) R.string.account_enabled else R.string.account_disabled)
                        },
                        onClick = { openPage(OfficialNeteasePage.LoginProtection) },
                    )
                    GlasmioListRow(
                        title = stringResource(R.string.account_device_management),
                        systemName = "iphone",
                        detail = state.data?.deviceCount?.let { stringResource(R.string.account_device_count, it) },
                        onClick = { Screen.AccountDevices.navigate(navController) },
                    )
                    GlasmioListRow(
                        title = stringResource(R.string.account_authorizations),
                        systemName = "person.2",
                        onClick = { Screen.AccountAuthorizations.navigate(navController) },
                    )
                    GlasmioListRow(
                        title = stringResource(R.string.account_security_center),
                        systemName = "shield",
                        onClick = { openPage(OfficialNeteasePage.SecurityCenter) },
                    )
                }
            }
        }
        if (state.loading) item { AccountFeatureLoading() }
        val error = state.error ?: state.data?.errors?.firstOrNull()
        if (error != null) item { AccountFeatureError(error, viewModel::refresh) }
    }
}
