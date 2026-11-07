package com.ljyh.mei.ui.screen.account

import com.ljyh.mei.ui.component.sheet.MeiloXBottomSheet
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.R
import com.ljyh.mei.source.shared.SharedOnlineRepository
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.glass.GlassSurfaceStyle
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import com.ljyh.mei.ui.navigation.meiloxHeaderAction
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import com.ljyh.mei.ui.navigation.MeiloXPinnedPage
import io.github.neoruaa.glasmio.scaffold.glasmioAdaptiveContent
import io.github.neoruaa.glasmio.ui.GlasmioTextField
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.navigation.MeiNavigator
import com.ljyh.mei.ui.screen.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
                    onClick = { Screen.NeteaseWebLogin.navigate(navController) { viewModel.providerId?.let { addQuery("providerId", it) } } },
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
            onLoginSuccess = navController::finishNeteaseLogin,
        )
    }
}

@Composable
fun NeteaseWebLoginScreen(viewModel: NeteaseLoginViewModel = hiltViewModel()) {
    val navController = LocalNavController.current
    var showCookieLoginSheet by remember { mutableStateOf(false) }
    var detected by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val bottomPadding = LocalPlayerAwareWindowInsets.current.union(WindowInsets.ime)
        .asPaddingValues().calculateBottomPadding()

    LaunchedEffect(viewModel, attempt) {
        try {
            viewModel.webLogin()
            detected = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    MeiloXPinnedPage(
        title = stringResource(R.string.netease_web_login),
        bottomPadding = bottomPadding,
        onNavigateBack = navController::navigateUp,
        actions = {
            if (detected) listOf(meiloxHeaderAction("done", stringResource(R.string.done), navController::finishNeteaseLogin) {
                Text(stringResource(R.string.done), color = LocalGlassColors.current.accent)
            }) else emptyList()
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!detected && !failed) CircularProgressIndicator(Modifier.align(Alignment.Center))
            if (failed) GlasmioButton(
                onClick = { failed = false; attempt++ },
                modifier = Modifier.align(Alignment.Center),
            ) { Text(stringResource(R.string.retry)) }
            if (!detected) GlasmioButton(
                onClick = { showCookieLoginSheet = true },
                modifier = Modifier.align(Alignment.BottomCenter).glasmioAdaptiveContent()
                    .padding(horizontal = 18.dp, vertical = 12.dp).fillMaxWidth(),
                style = GlassSurfaceStyle.Navigation,
            ) {
                SfIcon("key", null, size = 19.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.netease_cookie_login), fontWeight = FontWeight.SemiBold)
            }
        }
    }
    if (showCookieLoginSheet) NeteaseCookieLoginSheet(
        onDismiss = { showCookieLoginSheet = false },
        onSubmit = viewModel::loginWithCookie,
        onLoginSuccess = { showCookieLoginSheet = false; detected = true; navController.finishNeteaseLogin() },
    )
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

    MeiloXBottomSheet(
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
    onlineRepository: SharedOnlineRepository,
    savedStateHandle: androidx.lifecycle.SavedStateHandle,
) : ViewModel() {
    private val repository = onlineRepository.screen("source-login", savedStateHandle["providerId"])
    val providerId: String? get() = repository.providerId
    init { viewModelScope.launch { runCatching { repository.features() } } }
    suspend fun webLogin() = repository.webLogin()

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
    ): Boolean {
        val authenticated = repository.requestMobileSmsCode(phone, countryCode)
        return authenticated
    }

    suspend fun loginWithMobileSms(
        phone: String,
        countryCode: String,
        code: String,
    ) {
        repository.loginWithMobileSms(phone, countryCode, code)
    }

    suspend fun loginWithCookie(musicU: String): Boolean = try {
        repository.importCookie(musicU)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal interface OnlineLogoutEntryPoint {
    fun onlineRepository(): SharedOnlineRepository
}

suspend fun logoutNetease(context: android.content.Context, providerId: String? = null) {
    dagger.hilt.android.EntryPointAccessors.fromApplication(context, OnlineLogoutEntryPoint::class.java)
        .onlineRepository().screen("source-logout", providerId).logout()
}
