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
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.NeteaseCsrfKey
import com.ljyh.mei.constants.NeteaseMusicAKey
import com.ljyh.mei.constants.NeteaseRefreshTokenKey
import com.ljyh.mei.constants.UserAvatarUrlKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.constants.UserNicknameKey
import com.ljyh.mei.data.repository.MeloXRepository
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
import com.ljyh.mei.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NeteaseLoginScreen(viewModel: NeteaseLoginViewModel = hiltViewModel()) {
    val navController = LocalNavController.current
    var showMobileLoginSheet by remember { mutableStateOf(true) }
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
            }
        }
    }

    if (showMobileLoginSheet) {
        NeteaseMobileLoginSheet(
            onDismiss = { showMobileLoginSheet = false },
            onSubmitPassword = viewModel::loginWithMobilePassword,
            onRequestSmsCode = viewModel::requestMobileSmsCode,
            onSubmitSms = viewModel::loginWithMobileSms,
            onLoginSuccess = navController::navigateUp,
        )
    }
}

/*
 * The legacy Cookie form is intentionally kept private for source compatibility with
 * earlier builds, but the application login flow no longer exposes it.
 */
@Composable
private fun LegacyNeteaseCookieLoginEntry(
    showCookieLoginSheet: Boolean,
    onShowCookieLoginSheet: () -> Unit,
) {
    if (!showCookieLoginSheet) {
            GlassButton(
                onClick = onShowCookieLoginSheet,
                modifier = Modifier
                    .padding(
                        start = 18.dp,
                        end = 18.dp,
                        bottom = 12.dp,
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

    suspend fun completeLogin(musicU: String) {
        context.dataStore.edit {
            it[CookieKey] = musicU
            it.remove(NeteaseCsrfKey)
            it.remove(NeteaseMusicAKey)
            it.remove(NeteaseRefreshTokenKey)
        }
        runCatching { repository.accountProfile() }.getOrNull()?.let { profile ->
            context.dataStore.edit { preferences ->
                preferences[UserIdKey] = profile.id.toString()
                preferences[UserNicknameKey] = profile.nickname
                profile.avatarUrl?.let { preferences[UserAvatarUrlKey] = it }
            }
        }
    }

    suspend fun loginWithCookie(musicU: String): Boolean {
        val previousPreferences = context.dataStore.data.first()
        val previousCookie = previousPreferences[CookieKey]
        val previousUserId = previousPreferences[UserIdKey]
        val previousNickname = previousPreferences[UserNicknameKey]
        val previousAvatarUrl = previousPreferences[UserAvatarUrlKey]

        context.dataStore.edit { it[CookieKey] = musicU }
        val profile = runCatching { repository.accountProfile() }.getOrNull()
        if (profile == null) {
            context.dataStore.edit { preferences ->
                if (previousCookie == null) preferences.remove(CookieKey)
                else preferences[CookieKey] = previousCookie
                if (previousUserId == null) preferences.remove(UserIdKey)
                else preferences[UserIdKey] = previousUserId
                if (previousNickname == null) preferences.remove(UserNicknameKey)
                else preferences[UserNicknameKey] = previousNickname
                if (previousAvatarUrl == null) preferences.remove(UserAvatarUrlKey)
                else preferences[UserAvatarUrlKey] = previousAvatarUrl
            }
            return false
        }

        context.dataStore.edit { preferences ->
            preferences[CookieKey] = musicU
            preferences.remove(NeteaseCsrfKey)
            preferences.remove(NeteaseMusicAKey)
            preferences.remove(NeteaseRefreshTokenKey)
            preferences[UserIdKey] = profile.id.toString()
            preferences[UserNicknameKey] = profile.nickname
            if (profile.avatarUrl == null) preferences.remove(UserAvatarUrlKey)
            else preferences[UserAvatarUrlKey] = profile.avatarUrl
        }
        return true
    }
}

fun logoutNetease(context: android.content.Context) {
    CookieManager.getInstance().removeAllCookies {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            context.dataStore.edit { preferences ->
                preferences.remove(CookieKey)
                preferences.remove(NeteaseCsrfKey)
                preferences.remove(NeteaseMusicAKey)
                preferences.remove(NeteaseRefreshTokenKey)
                preferences.remove(UserIdKey)
                preferences.remove(UserNicknameKey)
                preferences.remove(UserAvatarUrlKey)
            }
        }
    }
}
