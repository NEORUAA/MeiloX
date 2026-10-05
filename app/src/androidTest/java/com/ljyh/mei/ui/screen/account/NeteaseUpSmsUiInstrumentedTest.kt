package com.ljyh.mei.ui.screen.account

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.MainActivity
import com.ljyh.mei.R
import com.ljyh.mei.data.network.NeteaseUrsUpSmsChallenge
import com.ljyh.mei.data.network.NeteaseUrsUpSmsRequiredException
import com.ljyh.mei.ui.glass.GlassBackdropHost
import com.ljyh.mei.ui.theme.MusicTheme
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local fixtures: neither SMS nor login requests are sent. */
@RunWith(AndroidJUnit4::class)
class NeteaseUpSmsUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val submitted = AtomicReference<NeteaseUrsUpSmsChallenge>()
    private val challenge = NeteaseUrsUpSmsChallenge("13000000000", "86", "1069000000",
        "fixture-up-sms-challenge", "i".repeat(192))

    @Test fun outgoingSmsInstructionsCanBeReadAndConfirmedWithoutAnIncomingCode() {
        showOutgoingChallenge()
        compose.onNodeWithText(compose.activity.getString(R.string.netease_sms_up_destination,
            challenge.destination)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.netease_sms_up_content,
            challenge.content)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.netease_sms_up_continue))
            .performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        assertSame(challenge, submitted.get())
    }

    @Test fun changingThePhoneDiscardsTheOldOutgoingSmsChallenge() {
        showOutgoingChallenge()
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("13000000001")
        compose.onNodeWithText(compose.activity.getString(R.string.netease_sms_up_continue))
            .assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.netease_mobile_login_sms_send))
            .assertExists()
        assertNull(submitted.get())
    }

    private fun showOutgoingChallenge() {
        compose.runOnUiThread {
            compose.activity.setContent {
                MusicTheme(seedColor = Color.Red, isDark = false) {
                    GlassBackdropHost(modifier = Modifier.fillMaxSize(), sampledContent = {}, overlayContent = {
                        NeteaseMobileLoginSheet(
                            onDismiss = {}, onSubmitPassword = { _, _, _ -> error("Unexpected password login") },
                            onRequestSmsCode = { _, _, _ -> throw NeteaseUrsUpSmsRequiredException(challenge) },
                            onSubmitSms = { _, _, _ -> error("Unexpected incoming SMS login") },
                            onSubmitUpSms = { submitted.set(it) }, onLoginSuccess = {},
                        )
                    })
                }
            }
        }
        compose.onAllNodes(hasSetTextAction())[1].performTextInput(challenge.phone)
        compose.onNodeWithText(compose.activity.getString(R.string.netease_mobile_login_sms_send))
            .performScrollTo().performClick()
        compose.waitForIdle()
    }
}
