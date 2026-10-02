package com.ljyh.mei.ui.screen.account

import android.annotation.SuppressLint
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.R
import com.ljyh.mei.ui.glass.GlassButton
import com.ljyh.mei.ui.glass.GlassEmphasis
import com.ljyh.mei.ui.glass.GlassSurfaceStyle
import com.ljyh.mei.ui.glass.IosGroupedList
import com.ljyh.mei.ui.glass.IosModalSheet
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.IosTextField
import com.ljyh.mei.ui.glass.IosTypography
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NeteaseLoginScreen(viewModel: NeteaseLoginViewModel = viewModel()) {
    val navController = LocalNavController.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var detected by remember { mutableStateOf(false) }
    var showCookieLoginSheet by remember { mutableStateOf(false) }
    val cookieLogin = remember(viewModel) {
        WebCookieLoginPoller(isOwnerCurrent = viewModel::isLoginOwnerCurrent)
    }
    val cookieManager = remember { CookieManager.getInstance() }
    val bottomPadding = LocalPlayerAwareWindowInsets.current
        .asPaddingValues()
        .calculateBottomPadding()

    LaunchedEffect(webView, showCookieLoginSheet) {
        if (webView != null && !detected && !showCookieLoginSheet) {
            val succeeded = cookieLogin.poll(
                readMusicU = {
                    musicUFromCookieHeader(cookieManager.getCookie("https://music.163.com").orEmpty())
                },
                verify = viewModel::completeLogin,
            )
            if (succeeded) detected = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        IosPinnedListPage(
            title = stringResource(R.string.netease_login),
            subtitle = if (detected) stringResource(R.string.netease_login_detected)
            else stringResource(R.string.netease_login_waiting),
            showsLargeTitle = false,
            horizontalContentPadding = 0.dp,
            bottomPadding = bottomPadding,
            onNavigateBack = navController::navigateUp,
            actions = {
                if (detected) {
                    GlassButton(
                        onClick = navController::navigateUp,
                        style = GlassSurfaceStyle.Standard,
                        emphasis = GlassEmphasis.Prominent,
                    ) {
                        Text(stringResource(R.string.done))
                    }
                }
            },
        ) {
            item(key = "netease-login-webview") {
                AndroidView(
                    factory = { currentContext ->
                        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                        WebView(currentContext).apply {
                            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = settings.userAgentString + " Mei/1.0"
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)
                            webViewClient = WebViewClient()
                            loadUrl("https://music.163.com/#/login")
                            webView = this
                        }
                    },
                    modifier = Modifier.fillParentMaxSize(),
                )
            }
        }

        if (!detected && !showCookieLoginSheet) {
            GlassButton(
                onClick = {
                    cookieLogin.pauseForManualLogin()
                    showCookieLoginSheet = true
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 18.dp,
                        end = 18.dp,
                        bottom = bottomPadding + 12.dp,
                    )
                    .fillMaxWidth(),
                style = GlassSurfaceStyle.Standard,
            ) {
                SfIcon("key", null, size = 19.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.netease_cookie_login),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    if (showCookieLoginSheet) {
        NeteaseCookieLoginSheet(
            onDismiss = {
                cookieLogin.resumeWebLogin()
                showCookieLoginSheet = false
            },
            onSubmit = { musicU -> cookieLogin.loginManually(musicU, viewModel::loginWithCookie) },
            onLoginSuccess = {
                showCookieLoginSheet = false
                detected = true
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                webViewClient = WebViewClient()
                destroy()
            }
            webView = null
        }
    }
}

@Composable
private fun NeteaseCookieLoginSheet(
    onDismiss: () -> Unit,
    onSubmit: suspend (String) -> Boolean,
    onLoginSuccess: () -> Unit,
) {
    var cookieValue by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val colors = LocalGlassColors.current
    val invalidFormatMessage = stringResource(R.string.netease_cookie_login_invalid_format)
    val verificationFailedMessage = stringResource(R.string.netease_cookie_login_verification_failed)

    val submit = submit@{
        val normalizedValue = cookieValue.trim()
        if (
            normalizedValue.isEmpty() ||
            normalizedValue.any(Char::isWhitespace) ||
            '=' in normalizedValue ||
            ';' in normalizedValue
        ) {
            errorMessage = invalidFormatMessage
            return@submit
        }
        cookieValue = normalizedValue
        errorMessage = null
        focusManager.clearFocus()
        scope.launch {
            isSubmitting = true
            if (onSubmit(normalizedValue)) {
                onLoginSuccess()
            } else {
                errorMessage = verificationFailedMessage
                isSubmitting = false
            }
        }
    }

    IosModalSheet(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        contentWindowInsets = { WindowInsets.statusBars.union(WindowInsets.ime) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.netease_cookie_login),
                style = IosTypography.title2,
                fontWeight = FontWeight.Bold,
            )

            IosGroupedList {
                IosTextField(
                    value = cookieValue,
                    onValueChange = {
                        cookieValue = it
                        errorMessage = null
                    },
                    placeholder = stringResource(R.string.netease_cookie_login_placeholder),
                    enabled = !isSubmitting,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    trailing = {
                        if (cookieValue.isNotEmpty() && !isSubmitting) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clickable { cookieValue = "" },
                                contentAlignment = Alignment.Center,
                            ) {
                                SfIcon(
                                    "xmark.circle",
                                    stringResource(R.string.clear),
                                    size = 18.dp,
                                    tint = colors.secondaryContent,
                                )
                            }
                        }
                    },
                )
            }

            IosGroupedList {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.netease_cookie_login_tip_title),
                        style = IosTypography.headline,
                    )
                    Text(
                        text = stringResource(R.string.netease_cookie_login_tip_value),
                        style = IosTypography.subheadline,
                        color = colors.secondaryContent,
                    )
                    Text(
                        text = stringResource(R.string.netease_cookie_login_tip_excluded),
                        style = IosTypography.subheadline,
                        color = colors.secondaryContent,
                    )
                    Text(
                        text = stringResource(R.string.netease_cookie_login_tip_get),
                        style = IosTypography.subheadline,
                        color = colors.secondaryContent,
                    )
                }
            }

            errorMessage?.let {
                Text(
                    text = it,
                    style = IosTypography.subheadline,
                    color = colors.destructive,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            GlassButton(
                onClick = submit,
                modifier = Modifier.fillMaxWidth(),
                style = GlassSurfaceStyle.Standard,
                enabled = cookieValue.isNotBlank() && !isSubmitting,
                emphasis = GlassEmphasis.Prominent,
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = LocalContentColor.current,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    stringResource(
                        if (isSubmitting) R.string.netease_cookie_login_verifying
                        else R.string.netease_cookie_login_submit,
                    ),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

class NeteaseLoginViewModel @Inject internal constructor(
    private val accounts: com.ljyh.mei.standalone.StandaloneAccountController,
    private val sessions: com.ljyh.mei.standalone.StandaloneSessionStore,
) : ViewModel() {
    private var loginOwner = try { sessions.snapshot() } catch (_: IOException) { null }

    fun isLoginOwnerCurrent(): Boolean? {
        // Wait for the first readable owner, but never rebind a retired page.
        val owner = loginOwner ?: try {
            sessions.snapshot().also { loginOwner = it }
        } catch (_: IOException) {
            return null
        }
        return try {
            sessions.requireCurrent(owner)
            true
        } catch (_: IOException) {
            false
        }
    }

    suspend fun completeLogin(musicU: String): Boolean = loginWithCookie(musicU)

    suspend fun loginWithCookie(musicU: String): Boolean {
        if (isLoginOwnerCurrent() != true) return false
        val owner = loginOwner ?: return false
        return accounts.login(musicU, owner)
    }
}

fun logoutNetease(context: android.content.Context, expected: com.ljyh.mei.data.session.SessionStamp) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate).launch {
        try {
            com.ljyh.mei.di.AppGraph.component.standaloneSessions().logout(expected) {
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                    CookieManager.getInstance().removeAllCookies {
                        CookieManager.getInstance().flush()
                        continuation.resumeWith(Result.success(Unit))
                    }
                }
            }
        } catch (_: Exception) {
            android.widget.Toast.makeText(context, R.string.netease_logout_error, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
