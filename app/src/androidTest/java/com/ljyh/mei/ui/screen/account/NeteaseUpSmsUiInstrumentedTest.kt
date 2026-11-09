package com.ljyh.mei.ui.screen.account

import androidx.activity.compose.setContent
import android.net.Uri
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebViewFeature
import androidx.webkit.Profile
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.MainActivity
import com.ljyh.mei.R
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.platform.SourcePlatformRequest
import com.ljyh.mei.source.platform.SourceWebEffects
import com.ljyh.mei.source.platform.SourceWebOwner
import com.ljyh.mei.source.platform.SourceWebRecipe
import com.ljyh.mei.source.platform.SourceWebRequest
import com.ljyh.mei.source.platform.SourceWebTransport
import com.ljyh.mei.source.platform.createSourceWebProfile
import com.ljyh.mei.source.platform.clearSourceWebProfile
import com.ljyh.mei.ui.screen.source.SourceSmsComposer
import com.ljyh.mei.ui.screen.source.SourceWebEffectHost
import com.ljyh.mei.ui.theme.MusicTheme
import io.github.neoruaa.glasmio.glass.GlasmioBackdropHost
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local fixtures: neither SMS nor login requests are sent. */
@RunWith(AndroidJUnit4::class)
class NeteaseUpSmsUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val destination = "1069000000"
    private val body = "fixture-up-sms-challenge"

    @Test fun outgoingSmsInstructionsCanBeReadAndConfirmedWithoutAnIncomingCode() {
        val request = showOutgoingChallenge()
        compose.onNodeWithText(compose.activity.getString(R.string.netease_sms_up_destination, destination))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.netease_sms_up_content, body))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.netease_sms_up_continue))
            .performScrollTo().assertIsEnabled().performClick()
        assertEquals("sent", outcome(request))
    }

    @Test fun refreshRequestsAnotherChallengeInsteadOfClaimingSmsWasSent() {
        val request = showOutgoingChallenge()
        compose.onNodeWithText(compose.activity.getString(R.string.netease_sms_up_retry))
            .performScrollTo().performClick()
        assertEquals("retry", outcome(request))
    }

    @Test fun outgoingSmsCanFinishLoginFromTheRequestCodeAction() {
        val signedIn = AtomicBoolean()
        showLogin(authenticated = true, signedIn = signedIn)
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("13000000000")
        compose.onNodeWithText(compose.activity.getString(R.string.netease_mobile_login_sms_send))
            .performScrollTo().performClick()
        compose.waitUntil(3_000) { signedIn.get() }
    }

    @Test fun requestingAnIncomingCodeDoesNotClaimLoginSuccess() {
        val signedIn = AtomicBoolean()
        showLogin(authenticated = false, signedIn = signedIn)
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("13000000000")
        compose.onNodeWithText(compose.activity.getString(R.string.netease_mobile_login_sms_send))
            .performScrollTo().performClick()
        compose.waitForIdle()
        assertFalse(signedIn.get())
        compose.onNodeWithText(compose.activity.getString(R.string.netease_mobile_login_submit)).assertExists()
    }

    @Test fun webLoginDialogOwnsInsetsOutsideNavigationPageAndReleasesOnCancellation() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
        val effects = SourceWebEffects()
        val scope = SourceEffectScope("fixture", ProviderInstanceId("fixture:web"), "1", null, "login",
            "webLogin", "fixture:web-screen", "fixture-web-token", active = { true }, validate = {})
        val owner = SourceWebOwner(setOf("fixture.invalid"), sdkNamespace = "fixture:web")
        val effect = SourceHostEffect("web.auth", JsonObject().apply {
            addProperty("url", "https://fixture.invalid/")
            addProperty("title", "Fixture web login")
            add("cookieNames", JsonArray().apply { add("fixture_session") })
        })
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val operation = jobs.launch { effects.handle(effect, scope, owner) }
        try {
            content {
                val pending by effects.pending.collectAsState()
                if (pending != null) Dialog(
                    onDismissRequest = { operation.cancel() },
                    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
                ) { SourceWebEffectHost(effects, Modifier.fillMaxSize()) }
            }
            compose.waitUntil(10_000) { effects.pending.value != null }
            compose.onNode(isDialog()).assertIsDisplayed()
            lateinit var browser: WebView
            compose.runOnUiThread {
                val views = java.util.ArrayDeque(WindowInspector.getGlobalWindowViews())
                var found: WebView? = null
                while (views.isNotEmpty()) {
                    val view = views.removeFirst()
                    if (view is WebView) found = view
                    else if (view is ViewGroup) repeat(view.childCount) { views.addLast(view.getChildAt(it)) }
                }
                browser = checkNotNull(found)
            }
            compose.waitUntil(20_000) {
                var settled = false
                compose.runOnUiThread { settled = browser.url == "https://fixture.invalid/" && browser.progress == 100 }
                settled
            }
            compose.runOnUiThread {
                val html = com.google.gson.JsonPrimitive("""
                    <html style="height:100%"><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                    <body id="height-fixture" style="position:absolute;height:100%;width:100%;overflow:scroll;margin:0;background:orange">Fixture</body></html>
                """.trimIndent())
                browser.evaluateJavascript("document.open(); document.write($html); document.close();", null)
            }
            val geometry = AtomicReference<JsonArray?>()
            val probing = AtomicBoolean()
            compose.waitUntil(5_000) {
                compose.runOnUiThread {
                    if (probing.compareAndSet(false, true)) browser.evaluateJavascript("""
                        document.getElementById('height-fixture') ?
                        [innerHeight, document.documentElement.getBoundingClientRect().height, document.body.getBoundingClientRect().height] : null
                    """.trimIndent()) { value ->
                        val measured = JsonParser.parseString(value)
                        if (measured.isJsonArray) geometry.set(measured.asJsonArray)
                        probing.set(false)
                    }
                }
                geometry.get() != null
            }
            val heights = checkNotNull(geometry.get()).map { it.asDouble }
            assertTrue("A percentage-height document must have a nonzero viewport", heights[0] > 0)
            assertEquals(heights[0], heights[1], 2.0)
            assertEquals(heights[0], heights[2], 2.0)
            runBlocking { operation.cancelAndJoin() }
            compose.waitUntil(3_000) { effects.pending.value == null }
            compose.onNode(isDialog()).assertDoesNotExist()
        } finally {
            jobs.cancel()
            runBlocking { operation.cancelAndJoin() }
        }
    }

    @Test fun rejectedServerCookieDoesNotBlockPageAndRetiredRequestsStayFenced() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
        val profileName = "meilox-source-session-" + MessageDigest.getInstance("SHA-256")
            .digest("source-cookie-regression".toByteArray()).joinToString("") { "%02x".format(it) }
        val host = "xui.ptlogin2.fixture.invalid"
        val active = AtomicBoolean(true)
        val scope = SourceEffectScope("fixture", ProviderInstanceId("fixture:cookies"), "1", null, "login",
            "webLogin", "fixture:cookie-screen", "fixture-cookie-token", active = active::get,
            validate = { if (!active.get()) throw CancellationException("Fixture owner retired") })
        val owner = SourceWebOwner(setOf(host), sdkNamespace = "fixture:cookies")
        val effect = SourceHostEffect("web.auth", JsonObject().apply {
            addProperty("url", "https://$host/")
            add("cookieNames", JsonArray().apply { add("accepted_session"); add("rejected_session") })
        })
        val page = SourceWebRequest(SourceWebRecipe.parse(effect, owner), scope, owner)
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .addHeader("Set-Cookie", "rejected_session=wrong; Domain=ui.ptlogin2.fixture.invalid; Path=/; Secure")
                .addHeader("Set-Cookie", "accepted_session=kept; Domain=ptlogin2.fixture.invalid; Path=/; Secure")
                .body("fixture-page".toResponseBody()).build()
        }.build()
        lateinit var transport: SourceWebTransport
        lateinit var profile: Profile
        compose.runOnUiThread {
            profile = createSourceWebProfile(profileName)
            val cookies = profile.cookieManager
            cookies.setAcceptCookie(true)
            transport = SourceWebTransport(page, cookies, client)
        }
        fun flushFixtureProfile() {
            val cleared = CountDownLatch(1)
            val succeeded = AtomicBoolean()
            compose.runOnUiThread { clearSourceWebProfile(profile) { succeeded.set(it); cleared.countDown() } }
            assertTrue("The fixture cookie store must finish clearing", cleared.await(5, TimeUnit.SECONDS))
            assertTrue("The fixture browsing data must clear successfully", succeeded.get())
            assertTrue("The fixture session cookie must remain persistent", profile.cookieManager.hasCookies())
            assertEquals("kept", profile.cookieManager.getCookie("https://$host/")
                ?.split(';').orEmpty().firstOrNull { it.trim().startsWith("accepted_session=") }
                ?.substringAfter('='))
        }
        val request = object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse("https://$host/")
            override fun isForMainFrame() = true
            override fun isRedirect() = false
            override fun hasGesture() = false
            override fun getMethod() = "GET"
            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }
        try {
            val response = transport.intercept(request) { error("No redirect expected") }
            assertEquals(200, response.statusCode)
            assertEquals("fixture-page", response.data.bufferedReader().use { it.readText() })
            val cookies = runBlocking { transport.selectedCookies().getAsJsonObject("cookies") }
            assertEquals("kept", cookies.get("accepted_session").asString)
            assertFalse(cookies.has("rejected_session"))
            active.set(false)
            assertEquals(403, transport.intercept(request) { error("Retired owner cannot navigate") }.statusCode)
            active.set(true)
            page.fail(SourceErrorCode.CANCELLED)
            assertEquals(403, transport.intercept(request) { error("Cancelled request cannot navigate") }.statusCode)
            assertEquals(1, requests.get())
        } finally {
            transport.close()
            compose.runOnUiThread { page.dispose() }
            flushFixtureProfile()
        }
    }

    private fun showOutgoingChallenge(): SourcePlatformRequest {
        val scope = SourceEffectScope("fixture", ProviderInstanceId("fixture:instance"), "1", "account", "login",
            "requestMobileSmsCode", "fixture:screen", "fixture-token", active = { true }, validate = {})
        val request = SourcePlatformRequest(SourceHostEffect("sms.compose", JsonObject().apply {
            addProperty("destination", destination); addProperty("body", body)
        }), scope)
        content { SourceSmsComposer(request) }
        return request
    }

    private fun outcome(request: SourcePlatformRequest): String {
        compose.waitUntil(3_000) { request.result.isCompleted }
        val result = runBlocking { request.result.await() }
        assertTrue(result is SourceHostEffectResult.Success)
        return (result as SourceHostEffectResult.Success).result.asJsonObject.get("status").asString
    }

    private fun showLogin(authenticated: Boolean, signedIn: AtomicBoolean) = content {
        NeteaseMobileLoginSheet(
            onDismiss = {}, onSubmitPassword = { _, _, _ -> error("Unexpected password login") },
            onRequestSmsCode = { _, _, _ -> authenticated },
            onSubmitSms = { _, _, _ -> error("Unexpected incoming SMS login") },
            onLoginSuccess = { signedIn.set(true) },
        )
    }

    private fun content(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent {
                MusicTheme(seedColor = Color.Red, isDark = false) {
                    GlasmioBackdropHost(modifier = Modifier.fillMaxSize(), sampledContent = {}, overlayContent = { content() })
                }
            }
        }
    }
}
