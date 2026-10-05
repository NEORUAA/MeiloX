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
import androidx.datastore.preferences.core.edit
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.R
import com.ljyh.mei.constants.UserAvatarUrlKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.constants.UserNicknameKey
import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.data.network.clearNeteaseAccountSession
import com.ljyh.mei.data.network.setNeteaseWebSession
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
import com.ljyh.mei.ui.navigation.MeiNavigator
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import javax.inject.Inject

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NeteaseLoginScreen(viewModel: NeteaseLoginViewModel = hiltViewModel()) {
    val navController = LocalNavController.current
    var showMobileLoginSheet by remember { mutableStateOf(false) }
    val bottomPadding = LocalPlayerAwareWindowInsets.current
        .asPaddingValues()
        .calculateBottomPadding()

    IosPinnedListPage(
        title = stringResource(R.string.netease_login),
        subtitle = stringResource(R.string.netease_mobile_login_page_subtitle),
        showsLargeTitle = false,
        bottomPadding = bottomPadding,
        onNavigateBack = navController::navigateUp,
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                SfIcon("iphone", null, size = 56.dp)
                Text(
                    text = stringResource(R.string.netease_mobile_login_page_tip),
                    style = IosTypography.body,
                    color = LocalGlassColors.current.secondaryContent,
                )
                GlassButton(
                    onClick = { showMobileLoginSheet = true },
                    modifier = Modifier.fillMaxWidth(),
                    style = GlassSurfaceStyle.Standard,
                    emphasis = GlassEmphasis.Prominent,
                ) {
                    Text(
                        stringResource(R.string.netease_mobile_login),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                GlassButton(
                    onClick = { Screen.NeteaseWebLogin.navigate(navController) },
                    modifier = Modifier.fillMaxWidth(),
                    style = GlassSurfaceStyle.Standard,
                ) {
                    SfIcon("globe", null, size = 19.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.netease_web_login),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }

    if (showMobileLoginSheet) {
        NeteaseMobileLoginSheet(
            onDismiss = { showMobileLoginSheet = false },
            onSubmitPassword = viewModel::loginWithMobilePassword,
            onRequestSmsCode = viewModel::requestMobileSmsCode,
            onSubmitSms = viewModel::loginWithMobileSms,
            onSubmitUpSms = viewModel::loginWithUpSms,
            onLoginSuccess = navController::finishNeteaseLogin,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NeteaseWebLoginScreen(viewModel: NeteaseLoginViewModel = hiltViewModel()) {
    val navController = LocalNavController.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var detected by remember { mutableStateOf(false) }
    var verificationFailed by remember { mutableStateOf(false) }
    var attemptedCookie by remember { mutableStateOf<String?>(null) }
    var showCookieLoginSheet by remember { mutableStateOf(false) }
    val cookieManager = remember { CookieManager.getInstance() }
    val bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()

    LaunchedEffect(webView) {
        while (webView != null && !detected) {
            val musicU = cookieManager.getCookie("https://music.163.com").orEmpty()
                .split(';').map(String::trim)
                .firstOrNull { it.startsWith("MUSIC_U=") }
                ?.substringAfter('=')?.takeIf(String::isNotBlank)
            if (!showCookieLoginSheet && musicU != null && musicU != attemptedCookie) {
                attemptedCookie = musicU
                detected = viewModel.loginWithCookie(musicU)
                verificationFailed = !detected
            }
            delay(500)
        }
    }

    Box(Modifier.fillMaxSize()) {
        IosPinnedListPage(
            title = stringResource(R.string.netease_web_login),
            subtitle = stringResource(when {
                detected -> R.string.netease_login_detected
                verificationFailed -> R.string.netease_cookie_login_verification_failed
                else -> R.string.netease_login_waiting
            }),
            showsLargeTitle = false,
            horizontalContentPadding = 0.dp,
            bottomPadding = bottomPadding,
            onNavigateBack = navController::navigateUp,
            actions = {
                if (detected) {
                    GlassButton(
                        onClick = navController::finishNeteaseLogin,
                        emphasis = GlassEmphasis.Prominent,
                    ) { Text(stringResource(R.string.done)) }
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
                            settings.userAgentString += " Mei/1.0"
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
                onClick = { showCookieLoginSheet = true },
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
            onDismiss = { showCookieLoginSheet = false },
            onSubmit = viewModel::loginWithCookie,
            onLoginSuccess = {
                showCookieLoginSheet = false
                navController.finishNeteaseLogin()
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

internal fun MeiNavigator.finishNeteaseLogin() {
    // A successful login leaves both the entry page and its WebView child behind.
    navigateTopLevel(Screen.Home.route)
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

@HiltViewModel
class NeteaseLoginViewModel @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val repository: MeloXRepository,
) : ViewModel() {
    suspend fun loginWithMobilePassword(
        phone: String,
        countryCode: String,
        password: String,
    ) {
        repository.loginWithMobilePassword(phone, countryCode, password)
    }

    suspend fun requestMobileSmsCode(
        activity: android.app.Activity,
        phone: String,
        countryCode: String,
    ) {
        repository.requestMobileSmsCode(activity, phone, countryCode)
    }

    suspend fun loginWithMobileSms(
        phone: String,
        countryCode: String,
        code: String,
    ) {
        repository.loginWithMobileSms(phone, countryCode, code)
    }

    suspend fun loginWithUpSms(challenge: com.ljyh.mei.data.network.NeteaseUrsUpSmsChallenge) {
        repository.loginWithUpSms(challenge)
    }

    suspend fun loginWithCookie(musicU: String): Boolean {
        val profile = try {
            repository.accountProfile(webCookie = musicU)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return false
        }
        context.dataStore.edit { preferences ->
            preferences.setNeteaseWebSession(musicU)
            preferences[UserIdKey] = profile.id.toString()
            preferences[UserNicknameKey] = profile.nickname
            if (profile.avatarUrl == null) preferences.remove(UserAvatarUrlKey)
            else preferences[UserAvatarUrlKey] = profile.avatarUrl
        }
        return true
    }
}

suspend fun logoutNetease(context: android.content.Context) {
    withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            CookieManager.getInstance().removeAllCookies {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }
    context.dataStore.edit { it.clearNeteaseAccountSession() }
}
