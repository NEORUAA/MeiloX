/*
 * Camera lifecycle and ImageAnalysis handling are adapted from Android Camera Samples.
 * Copyright 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0.
 */
package com.ljyh.mei.ui.screen.account

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.NeteaseRefreshTokenKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.ui.glass.GlassButton
import com.ljyh.mei.ui.glass.GlassCard
import com.ljyh.mei.ui.glass.GlassEmphasis
import com.ljyh.mei.ui.glass.GlassIconButton
import com.ljyh.mei.ui.glass.GlassSegmentedControl
import com.ljyh.mei.ui.glass.IosAlertButtonRole
import com.ljyh.mei.ui.glass.IosAlertButtonSpec
import com.ljyh.mei.ui.glass.IosAlertDialog
import com.ljyh.mei.ui.glass.IosGroupedList
import com.ljyh.mei.ui.glass.IosModalSheet
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.IosTextField
import com.ljyh.mei.ui.glass.IosTypography
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.utils.rememberPreference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import timber.log.Timber

@Composable
fun PcQrLoginScreen(viewModel: PcQrLoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val cookie by rememberPreference(CookieKey, "")
    val refreshToken by rememberPreference(NeteaseRefreshTokenKey, "")
    val userId by rememberPreference(UserIdKey, "")
    val isSignedIn = cookie.isNotBlank() && userId.toLongOrNull()?.let { it > 0 } == true
    val hasMobileSession = isSignedIn && refreshToken.isNotBlank()
    val hasCamera = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    var permissionGranted by remember {
        mutableStateOf(context.hasCameraPermission())
    }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var cameraRetryKey by rememberSaveable { mutableIntStateOf(0) }
    var showMobileLoginSheet by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionRequested = true
        permissionGranted = granted
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = context.hasCameraPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(hasMobileSession, hasCamera) {
        if (hasMobileSession && hasCamera && !permissionGranted && !permissionRequested) {
            permissionRequested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(hasMobileSession) {
        if (hasMobileSession) viewModel.prepare()
    }

    LaunchedEffect(state.phase) {
        when (state.phase) {
            PcQrLoginPhase.Success -> {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.pc_qr_login_success),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                navController.navigateUp()
            }

            PcQrLoginPhase.Exit -> navController.navigateUp()
            else -> Unit
        }
    }

    BackHandler(onBack = viewModel::requestExit)

    IosPinnedListPage(
        title = stringResource(R.string.pc_qr_login),
        onNavigateBack = viewModel::requestExit,
        bottomPadding = insets.calculateBottomPadding(),
    ) {
        item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                GlassCard(
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = 520.dp)
                        .aspectRatio(0.78f),
                ) {
                    when {
                        !isSignedIn -> AccountRequiredContent(onBack = viewModel::requestExit)
                        !hasMobileSession -> MobileSessionRequiredContent(
                            onLogin = { showMobileLoginSheet = true },
                        )
                        !hasCamera -> UnavailableCameraContent()
                        !permissionGranted -> CameraPermissionContent(
                            permanentlyDenied = permissionRequested &&
                                !context.shouldShowCameraPermissionRationale(),
                            onRequestPermission = {
                                if (permissionRequested && !context.shouldShowCameraPermissionRationale()) {
                                    context.openApplicationSettings()
                                } else {
                                    permissionRequested = true
                                    permissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                        )

                        else -> key(cameraRetryKey) {
                            PcQrCameraPreview(
                                analysisEnabled = state.isAnalyzing,
                                onQrValue = viewModel::onQrValue,
                                onCameraError = viewModel::reportCameraError,
                            )
                        }
                    }

                    if (hasMobileSession && hasCamera && permissionGranted) {
                        ScannerReticle()
                    }

                    when {
                        hasMobileSession &&
                            state.phase == PcQrLoginPhase.Preparing && state.error == null -> {
                            BusyCameraOverlay(stringResource(R.string.pc_qr_login_preparing_security))
                        }

                        state.phase == PcQrLoginPhase.MarkingScanned -> {
                            BusyCameraOverlay(stringResource(R.string.pc_qr_login_marking_scanned))
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.pc_qr_login_scan_tip),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                style = IosTypography.subheadline,
                color = LocalGlassColors.current.secondaryContent,
                textAlign = TextAlign.Center,
            )
        }

        state.error?.takeUnless {
            state.isDialogVisible && it.kind == PcQrLoginErrorKind.Request
        }?.let { error ->
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = error.localizedMessage(),
                        color = MaterialTheme.colorScheme.error,
                        style = IosTypography.subheadline,
                        textAlign = TextAlign.Center,
                    )
                    if (error.kind in setOf(PcQrLoginErrorKind.Request, PcQrLoginErrorKind.Camera)) {
                        GlassButton(
                            onClick = {
                                if (error.kind == PcQrLoginErrorKind.Camera) {
                                    cameraRetryKey++
                                    viewModel.dismissError()
                                } else {
                                    viewModel.retry()
                                }
                            },
                        ) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }
        }
    }

    if (state.isDialogVisible) {
        val session = state.session ?: return
        val busy = state.phase != PcQrLoginPhase.AwaitingConfirmation
        val platform = session.platform.ifBlank { stringResource(R.string.pc_qr_login_generic_platform) }
        IosAlertDialog(
            onDismissRequest = {},
            title = stringResource(R.string.pc_qr_login_confirm_title),
            message = stringResource(R.string.pc_qr_login_confirm_message, platform),
            buttons = listOf(
                IosAlertButtonSpec(
                    label = stringResource(R.string.cancel),
                    onClick = viewModel::cancel,
                    role = IosAlertButtonRole.Cancel,
                    enabled = !busy,
                ),
                IosAlertButtonSpec(
                    label = stringResource(R.string.pc_qr_login_confirm),
                    onClick = viewModel::authorize,
                    enabled = !busy,
                ),
            ),
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp),
                )
            }
            state.error?.takeIf { it.kind == PcQrLoginErrorKind.Request }?.let { error ->
                Text(
                    text = error.localizedMessage(),
                    modifier = Modifier.padding(bottom = 12.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = IosTypography.caption,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    if (showMobileLoginSheet) {
        NeteaseMobileLoginSheet(
            onDismiss = { showMobileLoginSheet = false },
            onSubmitPassword = viewModel::loginWithMobilePassword,
            onRequestSmsCode = viewModel::requestMobileSmsCode,
            onSubmitSms = viewModel::loginWithMobileSms,
            onLoginSuccess = { showMobileLoginSheet = false },
        )
    }
}

@Composable
private fun AccountRequiredContent(onBack: () -> Unit) {
    PlaceholderContent(
        icon = "person.crop.circle.badge.exclamationmark",
        title = stringResource(R.string.pc_qr_login_account_required),
        actionLabel = stringResource(R.string.navigation_back),
        onAction = onBack,
    )
}

@Composable
private fun MobileSessionRequiredContent(onLogin: () -> Unit) {
    PlaceholderContent(
        icon = "iphone",
        title = stringResource(R.string.pc_qr_login_mobile_session_required),
        actionLabel = stringResource(R.string.netease_mobile_login),
        onAction = onLogin,
    )
}

private enum class NeteaseMobileLoginMethod {
    Sms,
    Password,
}

@Composable
internal fun NeteaseMobileLoginSheet(
    onDismiss: () -> Unit,
    onSubmitPassword: suspend (phone: String, countryCode: String, password: String) -> Unit,
    onRequestSmsCode: suspend (
        activity: Activity,
        phone: String,
        countryCode: String,
    ) -> Unit,
    onSubmitSms: suspend (phone: String, countryCode: String, code: String) -> Unit,
    onLoginSuccess: () -> Unit,
) {
    var loginMethod by rememberSaveable { mutableStateOf(NeteaseMobileLoginMethod.Sms) }
    var countryCode by rememberSaveable { mutableStateOf("86") }
    var phone by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var smsCode by rememberSaveable { mutableStateOf("") }
    var smsCountdown by rememberSaveable { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var isSendingCode by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val colors = LocalGlassColors.current
    val invalidInputMessage = stringResource(R.string.netease_mobile_login_invalid_input)
    val loginFailedMessage = stringResource(R.string.netease_mobile_login_failed)
    val smsRequestFailedMessage = stringResource(R.string.netease_mobile_login_sms_request_failed)
    val activityUnavailableMessage = stringResource(R.string.netease_mobile_login_activity_unavailable)
    val isBusy = isSubmitting || isSendingCode

    LaunchedEffect(smsCountdown) {
        if (smsCountdown > 0) {
            delay(1_000)
            smsCountdown -= 1
        }
    }

    fun normalizedInput(): Pair<String, String>? {
        val normalizedCountryCode = countryCode.trim().removePrefix("+")
        val normalizedPhone = phone.trim()
        if (
            normalizedCountryCode.length !in 1..4 ||
            !normalizedCountryCode.all(Char::isDigit) ||
            normalizedPhone.length !in 5..20 ||
            !normalizedPhone.all(Char::isDigit)
        ) {
            errorMessage = invalidInputMessage
            return null
        }
        countryCode = normalizedCountryCode
        phone = normalizedPhone
        return normalizedPhone to normalizedCountryCode
    }

    val requestSmsCode = request@{
        if (isBusy || smsCountdown > 0) return@request
        val (normalizedPhone, normalizedCountryCode) = normalizedInput() ?: return@request
        val activity = context.findActivity()
        if (activity == null) {
            errorMessage = activityUnavailableMessage
            return@request
        }
        errorMessage = null
        focusManager.clearFocus()
        scope.launch {
            isSendingCode = true
            runCatching {
                onRequestSmsCode(activity, normalizedPhone, normalizedCountryCode)
            }.onSuccess {
                smsCountdown = 60
                isSendingCode = false
            }.onFailure { error ->
                errorMessage = error.message?.takeIf(String::isNotBlank) ?: smsRequestFailedMessage
                isSendingCode = false
            }
        }
    }

    val submit = submit@{
        val (normalizedPhone, normalizedCountryCode) = normalizedInput() ?: return@submit
        val credentialsValid = when (loginMethod) {
            NeteaseMobileLoginMethod.Sms ->
                smsCode.trim().length in 4..8 && smsCode.trim().all(Char::isDigit)
            NeteaseMobileLoginMethod.Password -> password.isNotEmpty()
        }
        if (!credentialsValid) {
            errorMessage = invalidInputMessage
            return@submit
        }
        errorMessage = null
        focusManager.clearFocus()
        scope.launch {
            isSubmitting = true
            runCatching {
                when (loginMethod) {
                    NeteaseMobileLoginMethod.Sms -> onSubmitSms(
                        normalizedPhone,
                        normalizedCountryCode,
                        smsCode.trim(),
                    )
                    NeteaseMobileLoginMethod.Password -> onSubmitPassword(
                        normalizedPhone,
                        normalizedCountryCode,
                        password,
                    )
                }
            }.onSuccess {
                onLoginSuccess()
            }.onFailure { error ->
                errorMessage = error.message?.takeIf(String::isNotBlank) ?: loginFailedMessage
                isSubmitting = false
            }
        }
    }

    IosModalSheet(
        onDismissRequest = { if (!isBusy) onDismiss() },
        contentWindowInsets = { WindowInsets.statusBars.union(WindowInsets.ime) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.netease_mobile_login),
                style = IosTypography.title2,
                fontWeight = FontWeight.Bold,
            )

            GlassSegmentedControl(
                items = listOf(
                    NeteaseMobileLoginMethod.Sms to
                        stringResource(R.string.netease_mobile_login_method_sms),
                    NeteaseMobileLoginMethod.Password to
                        stringResource(R.string.netease_mobile_login_method_password),
                ),
                selected = loginMethod,
                onSelected = { method ->
                    if (!isBusy) {
                        loginMethod = method
                        errorMessage = null
                        focusManager.clearFocus()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            IosGroupedList {
                IosTextField(
                    value = countryCode,
                    onValueChange = {
                        countryCode = it
                        errorMessage = null
                    },
                    placeholder = stringResource(R.string.netease_mobile_login_country_code),
                    enabled = !isBusy,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                )
                IosTextField(
                    value = phone,
                    onValueChange = {
                        phone = it
                        errorMessage = null
                    },
                    placeholder = stringResource(R.string.netease_mobile_login_phone),
                    enabled = !isBusy,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Next,
                    ),
                )
                when (loginMethod) {
                    NeteaseMobileLoginMethod.Sms -> {
                        val canRequestCode = countryCode.isNotBlank() && phone.isNotBlank() &&
                            !isBusy && smsCountdown == 0
                        IosTextField(
                            value = smsCode,
                            onValueChange = {
                                smsCode = it.filter(Char::isDigit).take(8)
                                errorMessage = null
                            },
                            placeholder = stringResource(R.string.netease_mobile_login_sms_code),
                            enabled = !isBusy,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            trailing = {
                                val label = when {
                                    isSendingCode -> stringResource(
                                        R.string.netease_mobile_login_sms_sending,
                                    )
                                    smsCountdown > 0 -> stringResource(
                                        R.string.netease_mobile_login_sms_countdown,
                                        smsCountdown,
                                    )
                                    else -> stringResource(R.string.netease_mobile_login_sms_send)
                                }
                                Text(
                                    text = label,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable(
                                            enabled = canRequestCode,
                                            onClick = requestSmsCode,
                                        )
                                        .padding(horizontal = 8.dp, vertical = 7.dp),
                                    style = IosTypography.subheadline,
                                    color = if (canRequestCode) colors.accent
                                    else colors.tertiaryContent,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            },
                        )
                    }
                    NeteaseMobileLoginMethod.Password -> IosTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            errorMessage = null
                        },
                        placeholder = stringResource(R.string.netease_mobile_login_password),
                        enabled = !isBusy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                    )
                }
            }

            IosGroupedList {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.netease_mobile_login_tip_title),
                        style = IosTypography.headline,
                    )
                    Text(
                        text = stringResource(
                            if (loginMethod == NeteaseMobileLoginMethod.Sms) {
                                R.string.netease_mobile_login_sms_tip
                            } else {
                                R.string.netease_mobile_login_tip
                            },
                        ),
                        style = IosTypography.subheadline,
                        color = colors.secondaryContent,
                    )
                }
            }

            errorMessage?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(horizontal = 4.dp),
                    style = IosTypography.subheadline,
                    color = colors.destructive,
                )
            }

            GlassButton(
                onClick = submit,
                modifier = Modifier.fillMaxWidth(),
                enabled = countryCode.isNotBlank() && phone.isNotBlank() &&
                    when (loginMethod) {
                        NeteaseMobileLoginMethod.Sms -> smsCode.length in 4..8
                        NeteaseMobileLoginMethod.Password -> password.isNotEmpty()
                    } && !isBusy,
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
                        if (isSubmitting) R.string.netease_mobile_login_signing_in
                        else R.string.netease_mobile_login_submit,
                    ),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun UnavailableCameraContent() {
    PlaceholderContent(
        icon = "viewfinder",
        title = stringResource(R.string.pc_qr_login_camera_unavailable),
    )
}

@Composable
private fun CameraPermissionContent(
    permanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
) {
    PlaceholderContent(
        icon = "viewfinder",
        title = stringResource(R.string.pc_qr_login_camera_permission),
        actionLabel = stringResource(
            if (permanentlyDenied) R.string.pc_qr_login_open_settings
            else R.string.pc_qr_login_allow_camera,
        ),
        onAction = onRequestPermission,
    )
}

@Composable
private fun PlaceholderContent(
    icon: String,
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SfIcon(
            systemName = icon,
            contentDescription = null,
            size = 60.dp,
        )
        Text(
            text = title,
            modifier = Modifier.padding(top = 16.dp),
            style = IosTypography.headline,
            color = LocalGlassColors.current.content,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            GlassButton(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                emphasis = GlassEmphasis.Prominent,
            ) {
                Text(actionLabel, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ScannerReticle() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(210.dp)
                .border(
                    width = 3.dp,
                    color = Color.White.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(24.dp),
                ),
        )
    }
}

@Composable
private fun BusyCameraOverlay(label: String) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = Color.White)
        Text(
            text = label,
            modifier = Modifier.padding(top = 14.dp),
            color = Color.White,
            style = IosTypography.headline,
        )
    }
}

@Composable
private fun PcQrCameraPreview(
    analysisEnabled: Boolean,
    onQrValue: (String) -> Unit,
    onCameraError: (String?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestAnalysisEnabled by rememberUpdatedState(analysisEnabled)
    val latestOnQrValue by rememberUpdatedState(onQrValue)
    val latestOnCameraError by rememberUpdatedState(onCameraError)
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val controller = remember(previewView, lifecycleOwner) {
        PcQrCameraController(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            isAnalysisEnabled = { latestAnalysisEnabled },
            onQrValue = { latestOnQrValue(it) },
            onCameraError = { latestOnCameraError(it) },
        )
    }

    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.openCamera()
                Lifecycle.Event.ON_PAUSE -> controller.closeCamera()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            controller.openCamera()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.release()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize().clip(ContinuousRoundedRectangle(28.dp)),
        )
        if (controller.hasFlashUnit) {
            GlassIconButton(
                onClick = controller::toggleTorch,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                emphasis = if (controller.torchEnabled) GlassEmphasis.Prominent else GlassEmphasis.Regular,
            ) {
                SfIcon(
                    systemName = "bolt.fill",
                    contentDescription = stringResource(R.string.pc_qr_login_flashlight),
                    tint = if (controller.torchEnabled) Color.White else LocalGlassColors.current.content,
                )
            }
        }
    }
}

@Stable
private class PcQrCameraController(
    context: Context,
    private val lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    private val isAnalysisEnabled: () -> Boolean,
    private val onQrValue: (String) -> Unit,
    private val onCameraError: (String?) -> Unit,
) {
    private val appContext = context.applicationContext
    private val providerScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build(),
    )
    private val preview = Preview.Builder().build().apply {
        surfaceProvider = previewView.surfaceProvider
    }
    private val imageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .apply { setAnalyzer(analysisExecutor, ::analyze) }

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var released = false
    var hasFlashUnit by mutableStateOf(false)
        private set
    var torchEnabled by mutableStateOf(false)
        private set

    fun openCamera() {
        if (released) return
        providerScope.launch {
            try {
                val provider = ProcessCameraProvider.getInstance(appContext).await()
                if (released) return@launch
                cameraProvider = provider
                provider.unbind(preview, imageAnalysis)
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                ).also { boundCamera ->
                    hasFlashUnit = boundCamera.cameraInfo.hasFlashUnit()
                }
            } catch (error: Exception) {
                Timber.e(error, "Failed to bind PC QR login camera")
                onCameraError(error.message)
            }
        }
    }

    fun closeCamera() {
        cameraProvider?.unbind(preview, imageAnalysis)
        camera = null
        hasFlashUnit = false
        torchEnabled = false
    }

    fun toggleTorch() {
        val boundCamera = camera ?: return
        if (!hasFlashUnit) return
        val nextValue = !torchEnabled
        val future = boundCamera.cameraControl.enableTorch(nextValue)
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { torchEnabled = nextValue }
                    .onFailure { error -> Timber.w(error, "Failed to change camera torch state") }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    fun release() {
        if (released) return
        released = true
        closeCamera()
        providerScope.cancel()
        scanner.close()
        analysisExecutor.shutdown()
    }

    @android.annotation.SuppressLint("UnsafeOptInUsageError")
    private fun analyze(imageProxy: ImageProxy) {
        if (!isAnalysisEnabled()) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees,
        )
        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                barcodes.firstNotNullOfOrNull { barcode -> barcode.rawValue?.takeIf(String::isNotBlank) }
                    ?.let(onQrValue)
            }
            .addOnFailureListener { error ->
                Timber.w(error, "PC login QR analysis failed")
            }
            .addOnCompleteListener { imageProxy.close() }
    }
}

@Composable
private fun PcQrLoginError.localizedMessage(): String = when (kind) {
    PcQrLoginErrorKind.InvalidQr -> stringResource(R.string.pc_qr_login_invalid_qr)
    PcQrLoginErrorKind.Expired -> stringResource(R.string.pc_qr_login_expired)
    PcQrLoginErrorKind.CancelFailed -> stringResource(R.string.pc_qr_login_cancel_failed)
    PcQrLoginErrorKind.Camera -> message?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.pc_qr_login_camera_error)
    PcQrLoginErrorKind.Request -> message?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.pc_qr_login_request_failed)
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.shouldShowCameraPermissionRationale(): Boolean =
    findActivity()?.let {
        ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
    } == true

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.openApplicationSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
