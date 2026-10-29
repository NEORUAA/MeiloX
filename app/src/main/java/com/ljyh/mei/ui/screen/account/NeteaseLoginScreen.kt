package com.ljyh.mei.ui.screen.account

import io.github.neoruaa.glasmio.ui.GlasmioButtonSize
import com.ljyh.mei.ui.component.sheet.rememberMeiloXSheetScrollEdge
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import io.github.neoruaa.glasmio.ui.LocalGlasmioSheetContentPadding
import io.github.neoruaa.glasmio.ui.GlasmioSheetToolbarButton
import io.github.neoruaa.glasmio.ui.GlasmioSheetTopToolbar
import io.github.neoruaa.glasmio.ui.GlasmioSheetValue
import com.ljyh.mei.ui.component.sheet.rememberMeiloXSheetDismissal
import android.annotation.SuppressLint
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
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
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.glass.GlassSurfaceStyle
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import com.ljyh.mei.ui.navigation.meiloxHeaderAction
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import com.ljyh.mei.ui.navigation.MeiloXPinnedPage
import io.github.neoruaa.glasmio.scaffold.GlasmioHeaderCollapseDefaults
import io.github.neoruaa.glasmio.ui.GlasmioTextField
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.navigation.MeiNavigator
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

    MeiloXPinnedListPage(
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
                GlasmioButton(
                    onClick = { showMobileLoginSheet = true },
                    modifier = Modifier.fillMaxWidth(),
                    style = GlassSurfaceStyle.Navigation,
                    emphasis = GlassEmphasis.Prominent,
                ) {
                    Text(
                        stringResource(R.string.netease_mobile_login),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                GlasmioButton(
                    onClick = { Screen.NeteaseWebLogin.navigate(navController) },
                    modifier = Modifier.fillMaxWidth(),
                    style = GlassSurfaceStyle.Navigation,
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
    var canGoBack by remember { mutableStateOf(false) }
    var scrollEdge by remember { mutableFloatStateOf(0f) }
    val scrollEdgeDistance = with(LocalDensity.current) { GlasmioHeaderCollapseDefaults.EnterBreakpoint.toPx() }
    var detected by remember { mutableStateOf(false) }
    var verificationFailed by remember { mutableStateOf(false) }
    var showCookieLoginSheet by remember { mutableStateOf(false) }
    val cookieLogin = remember(viewModel) { WebCookieLoginPoller() }
    val cookieManager = remember { CookieManager.getInstance() }
    val bottomPadding = LocalPlayerAwareWindowInsets.current.union(WindowInsets.ime)
        .asPaddingValues().calculateBottomPadding()
    val goBack = {
        if (webView?.canGoBack() == true) webView?.goBack() else navController.navigateUp()
        Unit
    }
    BackHandler(enabled = canGoBack) { goBack() }

    LaunchedEffect(webView, showCookieLoginSheet) {
        if (webView != null && !detected && !showCookieLoginSheet) {
            detected = cookieLogin.poll(
                readMusicU = {
                    musicUFromCookieHeader(cookieManager.getCookie("https://music.163.com").orEmpty())
                },
                verify = { musicU ->
                    viewModel.loginWithCookie(musicU).also { verificationFailed = !it }
                },
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        MeiloXPinnedPage(
            title = stringResource(R.string.netease_web_login),
            subtitle = stringResource(when {
                detected -> R.string.netease_login_detected
                verificationFailed -> R.string.netease_cookie_login_verification_failed
                else -> R.string.netease_login_waiting
            }),
            topBarBlurProgress = scrollEdge,
            bottomPadding = bottomPadding,
            onNavigateBack = goBack,
            actions = {
                if (detected) listOf(meiloxHeaderAction(
                    "done", stringResource(R.string.done), navController::finishNeteaseLogin,
                ) { Text(stringResource(R.string.done), color = LocalGlassColors.current.accent) })
                else emptyList()
            },
        ) { pagePadding ->
            val padding = PaddingValues(
                top = pagePadding.calculateTopPadding(), bottom = pagePadding.calculateBottomPadding(),
            )
            AndroidView(
                factory = { currentContext ->
                    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                    WebView(currentContext).apply {
                        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                        setOnScrollChangeListener { _, _, scrollY, _, _ ->
                            scrollEdge = (scrollY / scrollEdgeDistance.coerceAtLeast(1f)).coerceIn(0f, 1f)
                        }
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString += " Mei/1.0"
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                canGoBack = view?.canGoBack() == true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                canGoBack = view?.canGoBack() == true
                            }
                        }
                        loadUrl("https://music.163.com/#/login")
                        webView = this
                    }
                },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
        if (!detected && !showCookieLoginSheet) {
            GlasmioButton(
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
                style = GlassSurfaceStyle.Navigation,
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
            canGoBack = false
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
    val dismissal = rememberMeiloXSheetDismissal(
        onDismiss,
        confirmValueChange = { !isSubmitting || it != GlasmioSheetValue.Hidden },
    )
    val scrollState = rememberScrollState()
    val scrollProgress = rememberMeiloXSheetScrollEdge(scrollState)
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
                isSubmitting = false
                dismissal.requestDismiss(onLoginSuccess)
            } else {
                errorMessage = verificationFailedMessage
                isSubmitting = false
            }
        }
    }

    GlasmioBottomSheet(
        onDismissRequest = dismissal::onDismissed,
        sheetState = dismissal.state,
        skipPartiallyExpanded = false,
        fitContent = false,
        title = stringResource(R.string.netease_cookie_login),
        contentWindowInsets = { WindowInsets.statusBars.union(WindowInsets.ime) },
        topBarBlurProgress = scrollProgress,
        topBar = {
            GlasmioSheetTopToolbar(stringResource(R.string.netease_cookie_login), actions = {
                GlasmioSheetToolbarButton(
                    onClick = { dismissal.requestDismiss() },
                    enabled = !isSubmitting,
                ) { SfIcon("xmark", stringResource(R.string.cancel)) }
            })
        },
    ) {
        val colors = LocalGlassColors.current
        val padding = LocalGlasmioSheetContentPadding.current
        Column(
            modifier = Modifier
                .fillMaxWidth().weight(1f)
                .verticalScroll(scrollState)
                .padding(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GlasmioGroupedList {
                GlasmioTextField(
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

            GlasmioGroupedList {
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

        }
        Box(Modifier.fillMaxWidth().padding(bottom = padding.calculateBottomPadding()).padding(16.dp)) {
            GlasmioButton(
                onClick = submit,
                modifier = Modifier.fillMaxWidth(),
                size = GlasmioButtonSize.Large,
                style = GlassSurfaceStyle.Navigation,
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
        currentCoroutineContext().ensureActive()
        context.dataStore.edit { preferences ->
            currentCoroutineContext().ensureActive()
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
