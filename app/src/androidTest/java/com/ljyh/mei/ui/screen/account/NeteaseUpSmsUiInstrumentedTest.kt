package com.ljyh.mei.ui.screen.account

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonObject
import com.ljyh.mei.MainActivity
import com.ljyh.mei.R
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.platform.SourcePlatformRequest
import com.ljyh.mei.ui.screen.source.SourceSmsComposer
import com.ljyh.mei.ui.theme.MusicTheme
import io.github.neoruaa.glasmio.glass.GlasmioBackdropHost
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
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
