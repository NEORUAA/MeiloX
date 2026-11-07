package com.ljyh.mei.ui.screen.source

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.ljyh.mei.data.network.NeteaseUrsCaptcha
import com.ljyh.mei.R
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.platform.SourcePlatformEffects
import com.ljyh.mei.source.platform.SourcePlatformRequest
import com.ljyh.mei.ui.screen.account.PcQrCameraPreview
import com.ljyh.mei.ui.screen.account.readPcQrImage
import com.ljyh.mei.ui.screen.account.SourceSmsInstructions
import com.ljyh.mei.ui.component.sheet.MeiloXBottomSheet
import com.ljyh.mei.ui.component.sheet.rememberMeiloXSheetDismissal
import com.ljyh.mei.ui.component.sheet.rememberMeiloXSheetScrollEdge
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Android launchers keep results attached to the request that opened them, including cancellation. */
@Composable
fun SourcePlatformEffectHost(effects: SourcePlatformEffects) {
    val pending by effects.pending.collectAsStateWithLifecycle()
    val page by effects.web.pending.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    DisposableEffect(pending) {
        val owner = pending
        onDispose { owner?.fail(SourceErrorCode.CANCELLED) }
    }
    var pickerOwner by remember { mutableStateOf<SourcePlatformRequest?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val owner = pickerOwner
        pickerOwner = null
        if (owner?.active == true) {
            if (uri == null) owner.fail(SourceErrorCode.CANCELLED)
            else coroutineScope.launch {
                try { owner.finish(effects.selected(owner, uri)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { owner.fail(SourceErrorCode.UNAVAILABLE) }
            }
        }
    }
    LaunchedEffect(pending, pickerOwner) {
        val request = pending ?: return@LaunchedEffect
        if (request.effect.type == "files.pick" && pickerOwner == null) {
            pickerOwner = request
            filePicker.launch(arrayOf("audio/*"))
        } else if (request.effect.type == "sdk.call") {
            val name = request.effect.parameters.get("name")?.asString
            if (name == "netease.ursCaptcha") {
                val activity = context.activity() ?: run { request.fail(SourceErrorCode.UNAVAILABLE); return@LaunchedEffect }
                val type = request.effect.parameters.getAsJsonObject("arguments")?.get("captchaType")?.asString.orEmpty()
                try { request.finish(JsonPrimitive(NeteaseUrsCaptcha.validate(activity, type, request.scope.token))) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { request.fail(SourceErrorCode.UNAVAILABLE) }
            }
        }
    }
    pending?.takeIf { it.effect.type == "qr.scan" }?.let { request ->
        key(request) { SourceQrScanner(request) }
    }
    pending?.takeIf { it.effect.type == "sms.compose" }?.let { request ->
        key(request) { SourceSmsComposer(request) }
    }
    if (page != null) Dialog(
        onDismissRequest = { page?.fail(SourceErrorCode.CANCELLED) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) { SourceWebEffectHost(effects.web, Modifier.fillMaxSize()) }
}

/** The shared effect keeps the original outgoing-SMS instructions and explicit confirmation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceSmsComposer(request: SourcePlatformRequest) {
    val destination = request.effect.parameters.get("destination")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
    val body = request.effect.parameters.get("body")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
    val valid = destination.length in 1..64 && destination.removePrefix("+").all { it in '0'..'9' } &&
        destination.removePrefix("+").isNotEmpty() && body.length in 1..1000 && '\u0000' !in body
    LaunchedEffect(request) { if (!valid) request.fail(SourceErrorCode.INVALID_RESPONSE) }
    if (!valid) return
    var resultStatus by remember { mutableStateOf<String?>(null) }
    val dismissal = rememberMeiloXSheetDismissal({
        val status = resultStatus
        if (status == null) request.fail(SourceErrorCode.CANCELLED)
        else request.finish(JsonObject().apply { addProperty("status", status) })
    })
    val scroll = rememberScrollState()
    val scrollProgress = rememberMeiloXSheetScrollEdge(scroll)
    var error by remember { mutableStateOf<String?>(null) }
    fun complete(status: String) {
        if (dismissal.isDismissing) return
        resultStatus = status
        dismissal.requestDismiss()
    }
    MeiloXBottomSheet(
        onDismissRequest = dismissal::onDismissed,
        sheetState = dismissal.state,
        skipPartiallyExpanded = false,
        fitContent = false,
        title = stringResource(R.string.netease_sms_up_title),
        contentWindowInsets = { WindowInsets.statusBars.union(WindowInsets.ime) },
        topBarBlurProgress = scrollProgress,
        topBar = {
            GlasmioSheetTopToolbar(stringResource(R.string.netease_sms_up_title), actions = {
                GlasmioSheetToolbarButton(onClick = { dismissal.requestDismiss() }) {
                    SfIcon("xmark", stringResource(R.string.cancel))
                }
            })
        },
    ) {
        val padding = LocalGlasmioSheetContentPadding.current
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)
            .padding(top = padding.calculateTopPadding()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SourceSmsInstructions(destination, body, enabled = request.active,
                onRetry = { complete("retry") }, onSmsAppUnavailable = { error = it })
            error?.let { Text(it, style = IosTypography.subheadline, color = LocalGlassColors.current.destructive) }
        }
        Box(Modifier.fillMaxWidth().padding(bottom = padding.calculateBottomPadding()).padding(16.dp)) {
            GlasmioButton(onClick = { complete("sent") }, modifier = Modifier.fillMaxWidth(),
                size = GlasmioButtonSize.Large, enabled = request.active, emphasis = GlassEmphasis.Prominent) {
                Text(stringResource(R.string.netease_sms_up_continue), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SourceQrScanner(request: SourcePlatformRequest) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var permitted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    val image = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && request.active) scope.launch {
            val value = readPcQrImage(context, uri)
            if (!value.isNullOrBlank()) request.finish(JsonPrimitive(value.take(8192)))
        }
    }
    Dialog(onDismissRequest = { request.fail(SourceErrorCode.CANCELLED) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            GlasmioSheetTopToolbar("扫描二维码", actions = {
                GlasmioSheetToolbarButton(onClick = { request.fail(SourceErrorCode.CANCELLED) }) { SfIcon("xmark", "关闭") }
            })
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (permitted) PcQrCameraPreview(request.active, { values ->
                    values.firstOrNull { it.length <= 8192 }?.let { request.finish(JsonPrimitive(it)) }
                }, { request.fail(SourceErrorCode.UNAVAILABLE) })
                else GlasmioButton(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("允许使用相机") }
            }
            GlasmioButton(onClick = { image.launch("image/*") }, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("从图片识别") }
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
