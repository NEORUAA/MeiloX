package com.ljyh.mei.data.network

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Only the web-based behavior verification used by URS SMS login. */
internal object NeteaseUrsCaptcha {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun validate(activity: Activity, type: String): String = withContext(Dispatchers.Main) {
        check(!activity.isFinishing && !activity.isDestroyed) { "Sign-in screen closed" }
        suspendCancellableCoroutine { continuation ->
            val web = WebView(activity)
            val dialog = Dialog(activity)
            val handler = Handler(Looper.getMainLooper())
            var finished = false
            fun complete(value: String?, error: Throwable?) {
                handler.post {
                    if (finished) return@post
                    finished = true
                    dialog.setOnDismissListener(null)
                    dialog.dismiss()
                    web.stopLoading()
                    web.removeJavascriptInterface("JSInterface")
                    web.destroy()
                    if (continuation.isActive) {
                        if (error != null) continuation.resumeWithException(error)
                        else continuation.resume(checkNotNull(value))
                    }
                }
            }
            val bridge = CaptchaBridge(
                load = { handler.post { if (type == "1" || type == "2") web.evaluateJavascript("popupCaptcha()", null) } },
                ready = { handler.post { if (type != "1" && type != "2") web.evaluateJavascript("captchaVerify()", null) } },
                valid = { token -> complete(token, null) },
                failure = { complete(null, IOException(it)) },
            )
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
            }
            web.setBackgroundColor(Color.WHITE)
            web.addJavascriptInterface(bridge, "JSInterface")
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val host = request.url.host.orEmpty()
                    return request.url.scheme != "https" ||
                        !(host.endsWith(".126.net") || host.endsWith(".163.com") ||
                            host.endsWith(".163yun.com"))
                }
            }
            val width = minOf(activity.resources.displayMetrics.widthPixels,
                (360 * activity.resources.displayMetrics.density).toInt())
            dialog.setContentView(web, FrameLayout.LayoutParams(width,
                (420 * activity.resources.displayMetrics.density).toInt()))
            dialog.setOnCancelListener { complete(null, IOException("安全验证已取消")) }
            dialog.setOnDismissListener { complete(null, IOException("安全验证已关闭")) }
            continuation.invokeOnCancellation { complete(null, IOException("安全验证已取消")) }
            val captchaId = when (type) {
                "1" -> "314d356dc2a24c76972661b5f37a6cdf"
                "2" -> "4ba2dc25febe4a08a5462dd88c44c1e1"
                else -> "4ee324321f2e45c788dfd4773c3a1f76"
            }
            val url = Uri.parse("https://cstaticdun.126.net/api/v2/mobile.v2.13.5.html")
                .buildUpon().appendQueryParameter("captchaId", captchaId)
                .appendQueryParameter("os", "android")
                .appendQueryParameter("osVer", Build.VERSION.RELEASE)
                .appendQueryParameter("sdkVer", "3.4.3")
                .appendQueryParameter("popupStyles.width", (width / activity.resources.displayMetrics.density).toString())
                .appendQueryParameter("lang", "zh-CN")
                .appendQueryParameter("mobileTimeout", "15000")
                .appendQueryParameter("defaultFallback", "true")
                .appendQueryParameter("errorFallbackCount", "3")
                .apply { if (type != "1" && type != "2") appendQueryParameter("mode", "bind") }
                .build()
            dialog.show()
            web.loadUrl(url.toString())
        }
    }
}

/** Public methods must retain their JavaScript names after release shrinking. */
internal class CaptchaBridge(
    private val load: () -> Unit,
    private val ready: () -> Unit,
    private val valid: (String) -> Unit,
    private val failure: (String) -> Unit,
) {
    @JavascriptInterface fun onLoad() = load()
    @JavascriptInterface fun onReady(width: Int, height: Int) = ready()
    @JavascriptInterface fun onError(error: String) {
        val code = runCatching { JSONObject(error).optInt("code") }.getOrDefault(0)
        failure("安全验证加载失败 [$code]")
    }
    @JavascriptInterface fun onValidate(result: String, token: String, message: String, next: String) {
        if (next != "true" && token.isNotBlank()) valid(token)
    }
}
