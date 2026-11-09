package com.ljyh.mei.ui.screen.source

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.http.SslError
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import com.google.gson.JsonObject
import com.ljyh.mei.R
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.platform.SourceWebEffects
import com.ljyh.mei.source.platform.SourceWebPromptBridge
import com.ljyh.mei.source.platform.SourceWebRequest
import com.ljyh.mei.source.platform.SourceWebTransport
import com.ljyh.mei.source.platform.createSourceWebProfile
import com.ljyh.mei.source.platform.clearSourceWebProfile
import com.ljyh.mei.ui.navigation.MeiloXPinnedPage
import com.ljyh.mei.ui.navigation.meiloxHeaderAction
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.core.swipeBackExcluded
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.scaffold.GlasmioHeaderCollapseDefaults
import io.github.neoruaa.glasmio.ui.GlasmioButton
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Mount inside the owning screen's overlay layer; this never creates an Activity or a global route. */
@Composable
fun SourceWebEffectHost(effects: SourceWebEffects, modifier: Modifier = Modifier) {
    val request by effects.pending.collectAsStateWithLifecycle()
    request?.let { page -> androidx.compose.runtime.key(page) { SourceWebPage(page, modifier) } }
}

@Composable
private fun SourceWebPage(page: SourceWebRequest, modifier: Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val dark = LocalGlassColors.current.isDark
    val profileName = remember(page) {
        val identity = "${page.scope.providerId}\n${page.scope.instanceId.value}"
        "meilox-source-session-" + MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    var title by remember(page) { mutableStateOf(page.recipe.title) }
    var webView by remember(page) { mutableStateOf<WebView?>(null) }
    var transport by remember(page) { mutableStateOf<SourceWebTransport?>(null) }
    var bridge by remember(page) { mutableStateOf<SourceWebPromptBridge?>(null) }
    var documentScript by remember(page) { mutableStateOf<ScriptHandler?>(null) }
    var canGoBack by remember(page) { mutableStateOf(false) }
    var canPanHorizontally by remember(page) { mutableStateOf(false) }
    var progress by remember(page) { mutableIntStateOf(0) }
    var scrollEdge by remember(page) { mutableFloatStateOf(0f) }
    var pageError by remember(page) { mutableStateOf(false) }
    var initialized by remember(page) { mutableStateOf(false) }
    var finishing by remember(page) { mutableStateOf(false) }
    var lastGesture by remember(page) { mutableStateOf(0L) }
    var fileCallback by remember(page) { mutableStateOf<ValueCallback<Array<android.net.Uri>>?>(null) }
    var fileOrigin by remember(page) { mutableStateOf<String?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = fileCallback
        val origin = fileOrigin
        fileCallback = null
        fileOrigin = null
        val selected = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)?.toList().orEmpty()
        val network = transport
        if (callback != null && origin != null && network != null && page.isActive && selected.isNotEmpty()) {
            coroutineScope.launch {
                try {
                    network.registerSelectedFiles(context, selected, origin)
                    page.scope.ensureCurrent()
                    callback.onReceiveValue(selected.toTypedArray())
                } catch (cancelled: CancellationException) {
                    runCatching { callback.onReceiveValue(null) }
                    throw cancelled
                } catch (_: Exception) { runCatching { callback.onReceiveValue(null) } }
            }
        } else runCatching { callback?.onReceiveValue(null) }
    }
    val scrollEdgeDistance = with(LocalDensity.current) { GlasmioHeaderCollapseDefaults.EnterBreakpoint.toPx() }
    // This full-screen dialog owns its window and has no navigation-page player insets.
    val bottomPadding = WindowInsets.systemBars.union(WindowInsets.ime).asPaddingValues().calculateBottomPadding()

    val finish: () -> Unit = {
        if (!finishing && page.isActive) {
            finishing = true
            coroutineScope.launch {
                try {
                    val cookies = transport?.selectedCookies() ?: JsonObject()
                    if (page.recipe.auth && (cookies.getAsJsonObject("cookies")?.size() ?: 0) == 0) {
                        pageError = true
                        finishing = false
                    } else page.finish(cookies)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { page.fail(SourceErrorCode.PLUGIN) }
            }
        }
    }
    val goBack: () -> Unit = {
        if (webView?.canGoBack() == true) webView?.goBack()
        else if (page.recipe.auth) page.fail(SourceErrorCode.UNAVAILABLE)
        else finish()
    }
    BackHandler { goBack() }

    MeiloXPinnedPage(
        title = title,
        modifier = modifier.fillMaxSize(),
        onNavigateBack = goBack,
        bottomPadding = bottomPadding,
        topBarBlurProgress = scrollEdge,
        actions = {
            buildList {
                add(meiloxHeaderAction("refresh", stringResource(R.string.refresh), enabled = initialized && !finishing,
                    onClick = { pageError = false; webView?.reload() }) { SfIcon("arrow.clockwise", null) })
                if (page.recipe.auth) add(meiloxHeaderAction("done", stringResource(R.string.done), enabled = initialized && !finishing,
                    onClick = finish) { SfIcon("checkmark", null) })
            }
        },
    ) { pagePadding ->
        val padding = PaddingValues(top = pagePadding.calculateTopPadding(), bottom = pagePadding.calculateBottomPadding())
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                factory = { viewContext ->
                    WebView(viewContext).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        setLayerType(View.LAYER_TYPE_HARDWARE, null)
                        val profile = createSourceWebProfile(profileName)
                        WebViewCompat.setProfile(this, profileName)
                        profile.serviceWorkerController.serviceWorkerWebSettings.apply {
                            blockNetworkLoads = false
                            allowFileAccess = false
                            allowContentAccess = false
                            cacheMode = WebSettings.LOAD_DEFAULT
                        }
                        val network = SourceWebTransport(page, profile.cookieManager)
                        val promptScope = CoroutineScope(coroutineScope.coroutineContext + SupervisorJob(coroutineScope.coroutineContext[Job]))
                        transport = network
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            blockNetworkLoads = false
                            cacheMode = WebSettings.LOAD_DEFAULT
                            useWideViewPort = false
                            loadWithOverviewMode = false
                            allowFileAccess = false
                            allowContentAccess = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            javaScriptCanOpenWindowsAutomatically = false
                            setSupportMultipleWindows(false)
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            mediaPlaybackRequiresUserGesture = true
                        }
                        profile.cookieManager.setAcceptCookie(true)
                        profile.cookieManager.setAcceptThirdPartyCookies(this, true)
                        setDownloadListener { _, _, _, _, _ -> }
                        setOnTouchListener { _, event ->
                            if (event.actionMasked == MotionEvent.ACTION_UP) lastGesture = SystemClock.elapsedRealtime()
                            false
                        }
                        setOnScrollChangeListener { _, _, scrollY, _, _ ->
                            scrollEdge = (scrollY / scrollEdgeDistance.coerceAtLeast(1f)).coerceIn(0f, 1f)
                            canPanHorizontally = canScrollHorizontally(-1) || canScrollHorizontally(1)
                        }
                        val open: suspend (String, Boolean) -> Boolean = { value, gesture ->
                            page.scope.ensureCurrent()
                            val target = runCatching { page.recipe.checkedPageUrl(value, url) }.getOrNull()
                            if (target != null) { loadUrl(target); true }
                            else {
                                val navigation = page.recipe.navigation(value, page.owner)
                                val mapped = if (gesture && navigation != null) page.owner.navigate(navigation) else null
                                if (mapped == null) false else {
                                    page.scope.ensureCurrent()
                                    loadUrl(page.recipe.checkedPageUrl(mapped))
                                    true
                                }
                            }
                        }
                        val prompts = SourceWebPromptBridge(viewContext, page, network, promptScope, dark,
                            setTitle = { title = it }, open = open, closePage = goBack,
                            hasRecentGesture = { lastGesture > 0 && SystemClock.elapsedRealtime() - lastGesture in 0..1500 })
                        bridge = prompts
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                return !page.isActive
                            }
                            @Deprecated("Required for WebView implementations using the legacy callback")
                            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = !page.isActive
                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                pageError = false
                                canGoBack = view.canGoBack()
                            }
                            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { canGoBack = view.canGoBack() }
                            override fun onPageFinished(view: WebView, url: String?) {
                                canGoBack = view.canGoBack()
                                canPanHorizontally = view.canScrollHorizontally(-1) || view.canScrollHorizontally(1)
                            }
                            override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
                                canPanHorizontally = view.canScrollHorizontally(-1) || view.canScrollHorizontally(1)
                            }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) pageError = true
                            }
                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                if (request.isForMainFrame) pageError = true
                            }
                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) { handler.cancel() }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean =
                                prompts.onPrompt(view, url, message, result)
                            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
                                callback.invoke(origin, false, false)
                            }
                            override fun onShowFileChooser(webView: WebView?, callback: ValueCallback<Array<android.net.Uri>>?, params: FileChooserParams?): Boolean {
                                if (callback == null || params == null || !page.isActive || fileCallback != null) {
                                    callback?.onReceiveValue(null)
                                    return true
                                }
                                try {
                                    fileOrigin = network.fileChooserOrigin(webView?.url ?: page.recipe.url)
                                    fileCallback = callback
                                    filePicker.launch(params.createIntent())
                                } catch (_: Exception) {
                                    fileCallback = null
                                    fileOrigin = null
                                    callback.onReceiveValue(null)
                                }
                                return true
                            }
                        }
                        webView = this
                        val view = this
                        page.attach {
                            runCatching { fileCallback?.onReceiveValue(null) }
                            fileCallback = null
                            fileOrigin = null
                            promptScope.cancel()
                            network.close()
                            prompts.close()
                            documentScript?.remove()
                            documentScript = null
                            view.stopLoading()
                            view.webChromeClient = null
                            view.destroy()
                            clearSourceWebProfile(profile)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize().swipeBackExcluded(canGoBack || canPanHorizontally),
                onRelease = { page.dispose() },
            )
            if (!initialized || progress in 1..99) CircularProgressIndicator(Modifier.align(Alignment.TopCenter))
            if (pageError) Column(Modifier.align(Alignment.Center).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GlasmioButton(onClick = { pageError = false; webView?.reload() }) { Text(stringResource(R.string.retry)) }
            }
        }
    }

    LaunchedEffect(webView, transport) {
        val view = webView ?: return@LaunchedEffect
        val network = transport ?: return@LaunchedEffect
        try {
            network.installCookies()
            page.scope.ensureCurrent()
            initialized = true
            view.loadUrl(page.recipe.url)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { page.fail(SourceErrorCode.PLUGIN) }
    }
    DisposableEffect(page) { onDispose { page.dispose() } }
}
