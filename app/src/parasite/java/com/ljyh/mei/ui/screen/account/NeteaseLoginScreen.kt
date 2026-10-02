package com.ljyh.mei.ui.screen.account

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ljyh.mei.R
import com.ljyh.mei.di.AppGraph
import com.ljyh.mei.parasite.HostLoginStatus
import com.ljyh.mei.parasite.HostSessionBridge
import com.ljyh.mei.ui.glass.GlassIconButton
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.IosTypography
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import javax.inject.Inject

@Composable
fun NeteaseLoginScreen(
    viewModel: NeteaseLoginViewModel = viewModel(),
    onNavigateBack: (() -> Unit)? = null,
) {
    val navigateBack = onNavigateBack ?: LocalNavController.current.let { { it.navigateUp(); Unit } }
    val state by viewModel.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val colors = LocalGlassColors.current
    val statusText = stringResource(when (state.status) {
        HostLoginStatus.IDLE, HostLoginStatus.LOADING -> R.string.netease_login_loading
        HostLoginStatus.WAITING -> R.string.netease_login_waiting
        HostLoginStatus.CONFIRMING -> R.string.netease_login_confirming
        HostLoginStatus.EXPIRED -> R.string.netease_login_expired
        HostLoginStatus.ERROR -> R.string.netease_login_error
        HostLoginStatus.SUCCESS -> R.string.netease_login_detected
    })

    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.start()
                Lifecycle.Event.ON_STOP -> viewModel.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) viewModel.start()
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.stop()
        }
    }
    LaunchedEffect(state.status) {
        if (state.status == HostLoginStatus.SUCCESS) navigateBack()
    }

    IosPinnedListPage(
        title = stringResource(R.string.netease_login),
        showsLargeTitle = false,
        bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
        onNavigateBack = navigateBack,
    ) {
        item(key = "netease-login-qr") {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Box(Modifier.size(248.dp), contentAlignment = Alignment.Center) {
                    state.qr?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.netease_login_qr),
                            modifier = Modifier.size(248.dp).background(Color.White).padding(12.dp),
                            filterQuality = FilterQuality.None,
                        )
                    } ?: when (state.status) {
                        HostLoginStatus.IDLE, HostLoginStatus.LOADING -> CircularProgressIndicator()
                        HostLoginStatus.SUCCESS -> SfIcon("checkmark.circle", null, size = 48.dp)
                        else -> SfIcon(SfSymbol.Warning, null, size = 48.dp, tint = colors.secondaryContent)
                    }
                }
                Text(statusText, style = IosTypography.body, color = colors.secondaryContent)
                GlassIconButton(
                    onClick = viewModel::refresh,
                    enabled = state.status !in setOf(HostLoginStatus.LOADING, HostLoginStatus.SUCCESS),
                ) {
                    SfIcon(SfSymbol.ArrowClockwise, stringResource(R.string.netease_login_refresh))
                }
            }
        }
    }
}

class NeteaseLoginViewModel @Inject constructor(sessions: HostSessionBridge) : ViewModel() {
    private val login = sessions.newLogin()
    val state = login.state
    fun start() = login.start()
    fun refresh() = login.refresh()
    fun stop() = login.close()
    override fun onCleared() = login.close()
}

fun logoutNetease(context: Context, expected: com.ljyh.mei.data.session.SessionStamp) {
    runCatching { AppGraph.component.hostRequests().sessions.logout(expected) }.onFailure {
        Toast.makeText(context, R.string.netease_logout_error, Toast.LENGTH_SHORT).show()
    }
}
