package com.ljyh.mei.ui.screen.account

import io.github.neoruaa.glasmio.core.*
import io.github.neoruaa.glasmio.glass.*
import io.github.neoruaa.glasmio.icons.*
import io.github.neoruaa.glasmio.ui.*
import io.github.neoruaa.glasmio.scaffold.*
import com.ljyh.mei.ui.navigation.MeiloXPinnedPage
import com.ljyh.mei.ui.navigation.meiloxHeaderAction

import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.JsPromptResult
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebSettings
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ljyh.mei.R
import com.ljyh.mei.data.network.NeteaseSessionType
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.utils.dataStore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

enum class OfficialNeteasePage(@StringRes val titleRes: Int, val url: String) {
    Musician(R.string.netease_musician, "https://music.163.com/m/at/6006b917f6d4c156ffc7071c"),
    MusicianCenter(R.string.musician_workbench, "https://music.163.com/musician/artist/home"),
    MusicianApply(R.string.musician_apply, "https://music.163.com/musician/artist/apply"),
    PhoneBinding(R.string.account_phone_binding, "https://music.163.com/st/platform/bind"),
    ThirdPartyBinding(R.string.account_third_party_binding, "https://music.163.com/#/user/binding"),
    LoginProtection(R.string.account_login_protection, "https://music.163.com/st/platform/protect"),
    DeviceManagement(R.string.account_device_management, "https://st.music.163.com/g/device?full_screen=true&keep_status_bar=true"),
    SecurityCenter(R.string.account_security_center, "https://st.music.163.com/st/user-new/securitycenter/index.html"),
}

@Composable
fun OfficialNeteasePageScreen(page: OfficialNeteasePage) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val sessionType = rememberNeteaseSessionType()
    val initialTitle = stringResource(page.titleRes)
    var title by remember(page) { mutableStateOf(initialTitle) }
    val scope = rememberCoroutineScope()
    val dark = LocalGlassColors.current.isDark
    var webView by remember(page) { mutableStateOf<WebView?>(null) }
    var canGoBack by remember(page) { mutableStateOf(false) }
    var progress by remember(page) { mutableIntStateOf(0) }
    var scrollEdge by remember(page) { mutableFloatStateOf(0f) }
    val scrollEdgeDistance = with(LocalDensity.current) { GlasmioHeaderCollapseDefaults.EnterBreakpoint.toPx() }
    val bottomPadding = LocalPlayerAwareWindowInsets.current.union(WindowInsets.ime)
        .asPaddingValues().calculateBottomPadding()
    var pageError by remember(page) { mutableStateOf<String?>(null) }
    var loadedSession by remember(page) { mutableStateOf<Pair<WebView, String>?>(null) }
    val preferences by context.dataStore.data.collectAsStateWithLifecycle(initialValue = null)
    val sessionCookies = preferences?.let(::featureSessionCookies)
    var fileCallback by remember(page) { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
        )
        fileCallback = null
    }
    val goBack = {
        if (webView?.canGoBack() == true) webView?.goBack() else navController.navigateUp()
        Unit
    }
    BackHandler(enabled = canGoBack) { goBack() }

    MeiloXPinnedPage(
        title = title,
        onNavigateBack = goBack,
        bottomPadding = bottomPadding,
        topBarBlurProgress = scrollEdge,
        actions = {
            listOf(meiloxHeaderAction(
                key = "refresh", contentDescription = stringResource(R.string.refresh),
                enabled = webView != null && sessionType != NeteaseSessionType.None,
                onClick = { pageError = null; webView?.reload() },
            ) { SfIcon("arrow.clockwise", null) })
        },
    ) { pagePadding ->
        // Embedded pages own their horizontal layout; the shared scaffold supplies chrome insets.
        val padding = PaddingValues(
            top = pagePadding.calculateTopPadding(), bottom = pagePadding.calculateBottomPadding(),
        )
        if (sessionType == NeteaseSessionType.None) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                GlasmioButton(onClick = { Screen.NeteaseLogin.navigate(navController) }) {
                    Text(stringResource(R.string.netease_login))
                }
            }
        } else {
            Box(Modifier.fillMaxSize().padding(padding)) {
                AndroidView(
                    factory = { viewContext ->
                        WebView(viewContext).apply {
                            // Chromium must render into the page's sampled Compose layer.
                            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                                scrollEdge = (scrollY / scrollEdgeDistance.coerceAtLeast(1f)).coerceIn(0f, 1f)
                            }
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.allowFileAccess = false
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.userAgentString = WebSettings.getDefaultUserAgent(viewContext) +
                                " CloudMusic/0.1.2 NeteaseMusic/9.2.10"
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            val bridge = NeteaseWebBridge(
                                viewContext, scope, dark,
                                open = { view, target ->
                                    val url = runCatching { java.net.URI(view.url).resolve(target).toString() }.getOrDefault(target)
                                    if (isNeteaseFeatureUrl(url)) view.loadUrl(url)
                                    else if (target.startsWith("orpheus://")) {
                                        val uri = Uri.parse(target)
                                        val nested = uri.getQueryParameter("url")
                                        if (nested != null && isNeteaseFeatureUrl(nested)) view.loadUrl(nested)
                                        else if (uri.host == "rnpage" && target.contains("ReactNativeMusician")) {
                                            view.loadUrl(OfficialNeteasePage.MusicianApply.url)
                                        }
                                        else if (target.contains("login", true)) Screen.NeteaseLogin.navigate(navController)
                                        else if (target.contains("close", true)) goBack()
                                    } else runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                },
                                close = goBack,
                                login = { Screen.NeteaseLogin.navigate(navController) },
                                setTitle = { title = it },
                            )
                            webViewClient = object : WebViewClient() {
                                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                    canGoBack = view?.canGoBack() == true
                                }

                                override fun onPageCommitVisible(view: WebView?, url: String?) {
                                    view?.fitNeteaseViewport(url)
                                    if (url?.substringBefore('?') == OfficialNeteasePage.Musician.url) {
                                        view?.adaptMusicianNavigation()
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    canGoBack = view?.canGoBack() == true
                                    view?.fitNeteaseViewport(url)
                                }

                                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                    if (request?.isForMainFrame == true) pageError = error?.description?.toString()
                                }

                                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                                    if (request?.isForMainFrame == true) pageError = "HTTP ${response?.statusCode}"
                                }

                                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                    val target = url ?: return false
                                    if (page == OfficialNeteasePage.PhoneBinding &&
                                        target.startsWith("https://st.music.163.com/app-upgrade/")) return true
                                    if (target.startsWith("https://") || target.startsWith("http://")) return false
                                    if (target.startsWith("orpheus://")) {
                                        val uri = Uri.parse(target)
                                        val nested = uri.getQueryParameter("url")
                                        if (nested != null && isNeteaseFeatureUrl(nested)) view?.loadUrl(nested)
                                        else if (uri.host == "rnpage" && target.contains("ReactNativeMusician")) {
                                            view?.loadUrl(OfficialNeteasePage.MusicianApply.url)
                                        }
                                        else if (target.contains("login", ignoreCase = true)) Screen.NeteaseLogin.navigate(navController)
                                        else if (target.contains("account/settings") || target.contains("bindaccount")) Screen.AccountSecurity.navigate(navController)
                                        return true
                                    }
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
                                    return true
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
                                    if (view != null && result != null && bridge.onPrompt(view, url, message, result)) return true
                                    return super.onJsPrompt(view, url, message, defaultValue, result)
                                }

                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                }

                                override fun onShowFileChooser(
                                    webView: WebView?,
                                    filePathCallback: ValueCallback<Array<Uri>>?,
                                    fileChooserParams: FileChooserParams?,
                                ): Boolean {
                                    fileCallback?.onReceiveValue(null)
                                    fileCallback = filePathCallback
                                    return runCatching {
                                        filePicker.launch(fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                            type = "*/*"
                                            addCategory(Intent.CATEGORY_OPENABLE)
                                        })
                                        true
                                    }.getOrElse {
                                        fileCallback?.onReceiveValue(null)
                                        fileCallback = null
                                        false
                                    }
                                }
                            }
                            webView = this
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { view ->
                        if (webView === view) {
                            webView = null
                            canGoBack = false
                        }
                        view.stopLoading()
                        view.destroy()
                    },
                )
                if (progress in 1..99) {
                    CircularProgressIndicator(Modifier.align(Alignment.TopCenter))
                }
                pageError?.let { message ->
                    Column(Modifier.align(Alignment.Center).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(message)
                        GlasmioButton({ pageError = null; webView?.reload() }) { Text(stringResource(R.string.retry)) }
                    }
                }
            }
        }
    }

    LaunchedEffect(webView, sessionCookies, page) {
        val view = webView ?: return@LaunchedEffect
        val cookies = sessionCookies ?: return@LaunchedEffect
        if (cookies["MUSIC_U"].isNullOrBlank()) return@LaunchedEffect
        val cookieManager = CookieManager.getInstance()
        val previous = cookieManager.getCookie("https://music.163.com").orEmpty()
        val changed = musicUFromCookieHeader(previous) != cookies["MUSIC_U"]
        for (name in listOf("MUSIC_U", "MUSIC_A", "URS_APPID", "__csrf", "deviceId", "sDeviceId", "os", "appver")) {
            val value = cookies[name]
            if (name == "__csrf" && value == null && !changed) continue
            val cookie = if (value != null) "$name=$value; Domain=.music.163.com; Path=/; Secure"
                else "$name=; Domain=.music.163.com; Path=/; Secure; Max-Age=0"
            suspendCancellableCoroutine<Unit> { continuation ->
                cookieManager.setCookie("https://music.163.com", cookie) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
        cookieManager.flush()
        val session = view to cookies.getValue("MUSIC_U")
        if (loadedSession != session) {
            loadedSession = session
            pageError = null
            view.loadUrl(page.url, mapOf("X-Requested-With" to "com.netease.cloudmusic"))
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            fileCallback?.onReceiveValue(null)
            fileCallback = null
        }
    }
}

private fun WebView.adaptMusicianNavigation() {
    evaluateJavascript("""
        (function() {
            if (window.__meiloxMusicianNavigation) return;
            window.__meiloxMusicianNavigation = true;
            document.addEventListener('click', function(event) {
                if (!event.target.closest('.actm-r-MusicianSettleButton')) return;
                event.preventDefault();
                event.stopImmediatePropagation();
                window.location.href = '${OfficialNeteasePage.MusicianApply.url}';
            }, true);
        })();
    """.trimIndent(), null)
}

private fun WebView.fitNeteaseViewport(url: String?) {
    if (url == null || !isNeteaseFeatureUrl(url)) return
    evaluateJavascript("""
        (function() {
            var root = document.documentElement;
            var body = document.body;
            if (!root || !body) return;
            var activity = document.getElementById('j-tms-content-box');
            var fixRoot = !window.__meiloxViewportFixed && root.getBoundingClientRect().height <= 1;
            var fixActivity = activity && !activity.__meiloxViewportFixed && activity.getBoundingClientRect().height <= 1;
            if (!fixRoot && !fixActivity) return;
            if (fixRoot) window.__meiloxViewportFixed = true;
            if (fixActivity) activity.__meiloxViewportFixed = true;
            function resize() {
                if (fixRoot) {
                    root.style.setProperty('height', window.innerHeight + 'px', 'important');
                    body.style.setProperty('height', '100%', 'important');
                    body.style.setProperty('min-height', '100%', 'important');
                }
                if (fixActivity) {
                    activity.style.setProperty('height', window.innerHeight + 'px', 'important');
                    activity.style.setProperty('min-height', window.innerHeight + 'px', 'important');
                }
            }
            resize();
            window.addEventListener('resize', resize);
        })();
    """.trimIndent(), null)
}

internal fun isNeteaseFeatureUrl(value: String): Boolean = runCatching {
    val uri = java.net.URI(value)
    uri.scheme == "https" && (uri.host == "music.163.com" || uri.host?.endsWith(".music.163.com") == true)
}.getOrDefault(false)
