package com.ljyh.mei.ui.screen.account

import android.content.Context
import android.os.Build
import android.webkit.JsPromptResult
import android.webkit.WebView
import android.widget.Toast
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.data.network.NeteaseAndroidClientProfile
import com.ljyh.mei.utils.dataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

internal class NeteaseWebBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val dark: Boolean,
    private val open: (WebView, String) -> Unit,
    private val close: () -> Unit,
    private val login: () -> Unit,
    private val setTitle: (String) -> Unit,
) {
    fun onPrompt(view: WebView, url: String?, message: String?, result: JsPromptResult): Boolean {
        if (url == null || !isNeteaseFeatureUrl(url)) return false
        val request = runCatching { JSONObject(message.orEmpty()) }.getOrNull() ?: return false
        if (!request.has("seq") || !request.has("method")) return false
        val sequence = request.optInt("seq")
        val method = request.optString("method")
        val parameters = request.optJSONObject("params") ?: JSONObject()
        result.confirm("")
        scope.launch {
            var error: JSONObject? = null
            val response = try {
                dispatch(view, method, parameters)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: UnsupportedMethod) {
                // Official H5 probes these methods and uses its browser implementation on 404.
                error = JSONObject().put("code", 404).put("message", "Method not found")
                JSONObject.NULL
            } catch (_: Exception) {
                error = JSONObject().put("code", 500).put("message", "Request failed")
                JSONObject.NULL
            }
            if (view.url == url) {
                view.evaluateJavascript(
                    "window.MNBCallback && window.MNBCallback($sequence,${error ?: "null"},$response);",
                    null,
                )
            }
        }
        return true
    }

    private suspend fun dispatch(view: WebView, method: String, parameters: JSONObject): Any {
        val density = context.resources.displayMetrics.density
        return when (method) {
            "_bridgeBuild" -> JSONObject().put("code", 200)
            "device.info" -> JSONObject()
                .put("deviceName", Build.MODEL).put("os", "android")
                .put("osVersion", Build.VERSION.RELEASE)
                .put("appVersion", NeteaseAndroidClientProfile.APP_VERSION)
                .put("appver", NeteaseAndroidClientProfile.APP_VERSION)
                .put("screenWidth", view.width / density).put("screenHeight", view.height / density)
                .put("statusBarHeight", 0).put("navigationBarHeight", 0)
            "page.info" -> JSONObject()
                .put("width", view.width / density).put("height", view.height / density)
                .put("statusBarHeight", 0).put("navigationBarHeight", 0)
                .put("theme", if (dark) "dark" else "light").put("url", view.url)
            "page.setTitle", "page.setUI" -> {
                parameters.optString("title").takeIf(String::isNotBlank)?.let(setTitle)
                JSONObject()
            }
            "navigator.openURL", "browser.open", "nm.login.openURL" -> {
                val target = parameters.optString("url")
                require(target.isNotBlank())
                open(view, target)
                JSONObject()
            }
            "navigator.pop" -> { close(); JSONObject() }
            "user.isLogin", "nm.user.isLogin" -> JSONObject().put("isLogin",
                !context.dataStore.data.first()[CookieKey].isNullOrBlank())
            "user.baseinfo", "nm.user.baseinfo", "baseUser.info", "nm.user.h5UserInfo" -> {
                val preferences = context.dataStore.data.first()
                val userId = preferences[UserIdKey]?.toLongOrNull() ?: 0L
                JSONObject().put("userId", userId).put("profile", JSONObject().put("userId", userId))
            }
            "user.login", "nm.user.login" -> { login(); JSONObject() }
            "toast.show" -> {
                val text = parameters.optString("message").ifBlank { parameters.optString("text") }
                if (text.isNotBlank()) Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                JSONObject()
            }
            "event.post", "flowpath.setRefer", "page.setGestureBackEnable",
            "page.didAppear", "page.didDisappear", "page.onBack", "page.onClose",
            "toast.showLoading", "toast.dismissLoading", "toast.hide" -> JSONObject()
            else -> throw UnsupportedMethod()
        }
    }

    private class UnsupportedMethod : Exception()
}
