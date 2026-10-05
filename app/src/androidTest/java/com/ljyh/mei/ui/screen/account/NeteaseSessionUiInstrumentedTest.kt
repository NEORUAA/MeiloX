package com.ljyh.mei.ui.screen.account

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.ljyh.mei.MainActivity
import com.ljyh.mei.R
import com.ljyh.mei.ui.glass.GlassBackdropHost
import com.ljyh.mei.ui.navigation.MeiNavigator
import com.ljyh.mei.ui.navigation.MeiRoute
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.setting.NeteaseLogoutConfirmation
import com.ljyh.mei.ui.theme.MusicTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** UI callbacks use local fixtures only; no account data is changed or submitted. */
@RunWith(AndroidJUnit4::class)
class NeteaseSessionUiInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun text(resource: Int) = instrumentation.targetContext.getString(resource)

    @Test fun webLoginCompletionReturnsHomeAndRemovesLoginBranch() {
        instrumentation.runOnMainSync {
            val stack = NavBackStack<NavKey>(
                MeiRoute(Screen.Home.route),
                MeiRoute(Screen.Setting.route),
                MeiRoute(Screen.NeteaseLogin.route),
                MeiRoute(Screen.NeteaseWebLogin.route),
            )
            val navigator = MeiNavigator(instrumentation.targetContext, stack)

            navigator.finishNeteaseLogin()

            assertEquals(Screen.Home.route, navigator.currentRoute)
            assertEquals(listOf(MeiRoute(Screen.Home.route)), stack.toList())
            assertFalse(navigator.popBackStack())
        }
    }

    @Test fun mobileLoginCompletionReturnsHomeAndRemovesLoginBranch() {
        instrumentation.runOnMainSync {
            val stack = NavBackStack<NavKey>(
                MeiRoute(Screen.Home.route),
                MeiRoute(Screen.GeneralSettings.route),
                MeiRoute(Screen.NeteaseLogin.route),
            )
            val navigator = MeiNavigator(instrumentation.targetContext, stack)

            navigator.finishNeteaseLogin()

            assertEquals(listOf(MeiRoute(Screen.Home.route)), stack.toList())
            assertFalse(navigator.popBackStack())
        }
    }

    @Test fun cancellingLogoutNeverConfirmsIt() {
        var dismissed = false
        var confirmed = false
        show({
            NeteaseLogoutConfirmation(
                title = text(R.string.netease_logout_web),
                onDismiss = { dismissed = true }, onConfirm = { confirmed = true },
            )
        }) {
            findText(R.string.netease_logout_web)
            clickText(R.string.cancel)
            instrumentation.runOnMainSync { assertTrue(dismissed); assertFalse(confirmed) }
        }
    }

    @Test fun mobileLogoutRequiresExplicitConfirmation() {
        var confirmed = false
        show({
            NeteaseLogoutConfirmation(
                title = text(R.string.netease_logout),
                onDismiss = {}, onConfirm = { confirmed = true },
            )
        }) {
            instrumentation.runOnMainSync { assertFalse(confirmed) }
            clickText(R.string.netease_logout_confirm)
            instrumentation.runOnMainSync { assertTrue(confirmed) }
        }
    }

    @Test fun reminderSuppressionDoesNotRequestLogin() {
        var suppressed = false
        var signInRequested = false
        show({
            NeteaseLegacyWebSessionDialog(
                onDismiss = {}, onDoNotRemind = { suppressed = true },
                onSignInAgain = { signInRequested = true },
            )
        }) {
            findText(R.string.netease_web_session_notice_message)
            clickText(R.string.netease_web_session_do_not_remind)
            instrumentation.runOnMainSync { assertTrue(suppressed); assertFalse(signInRequested) }
        }
    }

    @Test fun reminderCanOpenLoginWithoutSigningOut() {
        var signInRequested = false
        show({
            NeteaseLegacyWebSessionDialog(
                onDismiss = {}, onDoNotRemind = {}, onSignInAgain = { signInRequested = true },
            )
        }) {
            clickText(R.string.netease_web_session_sign_in_again)
            instrumentation.runOnMainSync { assertTrue(signInRequested) }
        }
    }

    private fun show(content: @Composable () -> Unit, check: () -> Unit) {
        // API 37 can block an instrumentation UID from opening a background task.
        // Bring up only this target app through the authorized shell first.
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        var activity: MainActivity? = null
        try {
            instrumentation.uiAutomation.executeShellCommand(
                "am start -n ${instrumentation.targetContext.packageName}/${MainActivity::class.java.name}",
            ).use { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            }
            activity = monitor.waitForActivityWithTimeout(5_000) as? MainActivity
                ?: error("The target activity did not open")
            instrumentation.runOnMainSync {
                activity.setContent {
                    MusicTheme(seedColor = Color.Red, isDark = false) {
                        GlassBackdropHost(
                            modifier = Modifier.fillMaxSize(), sampledContent = {},
                            overlayContent = { content() },
                        )
                    }
                }
            }
            instrumentation.waitForIdleSync()
            check()
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun findText(resource: Int): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            dismissDebugCompatibilityWarning()
            val node = findNode(instrumentation.uiAutomation.rootInActiveWindow) {
                it.text?.toString() == text(resource)
            }
            if (node != null) return node
            SystemClock.sleep(50)
        }
        val root = instrumentation.uiAutomation.rootInActiveWindow
        fun labels(node: AccessibilityNodeInfo?): List<String> = if (node == null) emptyList() else
            listOfNotNull(node.text?.toString()) + (0 until node.childCount).flatMap { labels(node.getChild(it)) }
        error("UI text not found: ${text(resource)}; activePackage=${root?.packageName}; labels=${labels(root).take(12)}")
    }

    private fun dismissDebugCompatibilityWarning() {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return
        if (root.packageName == "android" && findNode(root) { it.text?.contains("16 KB") == true } != null) {
            findNode(root) { it.viewIdResourceName == "android:id/button2" }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    // Compose exposes virtual nodes; the API 37 text-search shortcut may skip them.
    private fun findNode(
        node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) {
            findNode(node.getChild(index), predicate)?.let { return it }
        }
        return null
    }

    private fun clickText(resource: Int) {
        var node = findText(resource)
        while (!node.isClickable) node = node.parent ?: error("UI action is not clickable")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
}
