package com.ljyh.mei.ui.screen.social

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.ViewModelStore
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.data.model.melox.AccountProfile
import com.ljyh.mei.data.model.melox.MessageContact
import com.ljyh.mei.data.model.melox.PrivateConversation
import com.ljyh.mei.data.model.melox.PrivateMessage
import com.ljyh.mei.data.model.melox.ShareResource
import com.ljyh.mei.data.repository.SocialSource
import com.ljyh.mei.data.session.AccountStore
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.ui.glass.GlassBackdropHost
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.navigation.MeiNavigator
import com.ljyh.mei.ui.navigation.MeiRoute
import com.ljyh.mei.ui.screen.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Original rows and retained rendered callbacks; private reads and accounts stay in-memory. */
class SocialNavigationOwnerDeviceTest {
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var sessions: SessionStore
    private lateinit var accounts: AccountStore
    private lateinit var scope: CoroutineScope
    private lateinit var models: ViewModelStore
    private lateinit var conversations: ConversationsViewModel
    private lateinit var contacts: MessageContactsViewModel
    private lateinit var navigator: MeiNavigator
    private var identity = SessionIdentity(17, true, false)
    private var renderedState: Any? = null

    private class Source : SocialSource {
        private fun contact(id: Long) = MessageContact(id, "Fixture contact $id", null, null, null)
        override suspend fun privateConversations(session: SessionStamp, offset: Int, limit: Int) = listOf(
            PrivateConversation("fixture", contact(session.identity.userId), contact(9), 0, "Fixture message", 0),
        )
        override suspend fun messageContacts(session: SessionStamp, pageSize: Int, maximumCount: Int) = listOf(contact(9))
        override suspend fun privateMessages(session: SessionStamp, userId: Long, before: Long, limit: Int): List<PrivateMessage> =
            error("Unrelated message history")
        override suspend fun sendPrivateText(session: SessionStamp, message: String, userIds: List<Long>): Unit =
            error("A navigation fixture cannot send text")
        override suspend fun sendPrivateResource(session: SessionStamp, resource: ShareResource, userIds: List<Long>, message: String): Unit =
            error("A navigation fixture cannot send a resource")
        override suspend fun shareToTimeline(session: SessionStamp, resource: ShareResource, message: String): Unit =
            error("A navigation fixture cannot publish a timeline")
    }

    private fun showOriginalRows(showContacts: Boolean) {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            sessions = SessionStore().apply { bind { identity } }
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            accounts = AccountStore(sessions, {
                AccountProfile(identity.userId, "Fixture account", null, null, null, null, null, null, null, null)
            }, scope)
            val source = Source()
            conversations = ConversationsViewModel(source, accounts)
            contacts = MessageContactsViewModel(source, accounts)
            models = ViewModelStore().apply { put("conversations", conversations); put("contacts", contacts) }
            activity.setContent {
                val backStack = rememberNavBackStack(MeiRoute("fixture-root"))
                navigator = remember { MeiNavigator(activity, backStack) }
                CompositionLocalProvider(
                    LocalNavController provides navigator,
                    LocalPlayerAwareWindowInsets provides WindowInsets(0, 0, 0, 0),
                ) {
                    MaterialTheme {
                        GlassBackdropHost(
                            modifier = Modifier.fillMaxSize(),
                            sampledContent = { Box(Modifier.fillMaxSize().background(Color.White)) },
                            overlayContent = {
                                val rendered = if (showContacts) contacts.state.collectAsState().value
                                    else conversations.state.collectAsState().value
                                if (showContacts) MessageContactsScreen(contacts) else ConversationsScreen(conversations)
                                SideEffect { renderedState = rendered }
                            },
                        )
                    }
                }
            }
        }
        await { hasRenderedRows(showContacts) }
    }

    @After fun closeFixtures() {
        if (::scenario.isInitialized) {
            scenario.onActivity {
                if (::models.isInitialized) models.clear()
                if (::accounts.isInitialized) accounts.close()
                if (::scope.isInitialized) scope.cancel()
            }
            scenario.close()
        }
    }

    private fun hasRenderedRows(showContacts: Boolean): Boolean {
        val state = if (showContacts) contacts.state.value else conversations.state.value
        val owner = if (showContacts) contacts.state.value.session else conversations.state.value.session
        val loaded = if (showContacts) !contacts.state.value.isLoading && contacts.state.value.contacts.isNotEmpty()
            else !conversations.state.value.isLoading && conversations.state.value.conversations.isNotEmpty()
        return owner == sessions.snapshot() && loaded && renderedState === state && row() != null
    }

    private fun roots(view: View): List<ViewRootForTest> = buildList {
        if (view is ViewRootForTest && view.isAttachedToWindow) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(roots(view.getChildAt(index)))
    }

    private fun row(): SemanticsNode? = WindowInspector.getGlobalWindowViews().flatMap(::roots)
        .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
        .firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Fixture contact 9" } == true &&
                node.config.contains(SemanticsActions.OnClick)
        }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        scenario.onActivity { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            if (onMain(condition)) return
            SystemClock.sleep(16)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("Timed out waiting for original private-message rows")
    }

    private fun action(): () -> Unit = onMain {
        // Semantics forwards to a mutable node; retain the callback from this render.
        val element = checkNotNull(row()).layoutInfo.getModifierInfo().map { it.modifier }
            .single { it.javaClass.name == "androidx.compose.foundation.ClickableElement" }
        @Suppress("UNCHECKED_CAST")
        (element.javaClass.getDeclaredField("onClick").apply { isAccessible = true }.get(element) as () -> Unit)
    }

    private fun assertRejected(retained: () -> Unit) = onMain {
        retained()
        assertEquals("Retained row navigated using a different account", "fixture-root", navigator.currentRoute)
    }

    private fun assertCurrentNavigation() {
        val current = action()
        onMain {
            current()
            assertEquals("${Screen.PrivateConversation.route}/9", navigator.currentRoute)
        }
    }

    private fun checkReplacement(showContacts: Boolean, changeAccount: Boolean) {
        showOriginalRows(showContacts)
        val retained = action()
        onMain { sessions.beginTransition().use { if (changeAccount) identity = identity.copy(userId = 18) } }
        await { hasRenderedRows(showContacts) }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    private fun checkRecovery(showContacts: Boolean) {
        showOriginalRows(showContacts)
        val retained = action()
        onMain { sessions.setRecoveryRequired(true); retained(); assertEquals("fixture-root", navigator.currentRoute) }
        await {
            if (showContacts) contacts.state.value.session == null && renderedState === contacts.state.value
            else conversations.state.value.session == null && renderedState === conversations.state.value
        }
        onMain { sessions.setRecoveryRequired(false) }
        await { hasRenderedRows(showContacts) }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun retainedConversationCannotNavigateForAReplacementAccount() = checkReplacement(false, true)
    @Test fun retainedConversationCannotNavigateAfterSameAccountReauthorization() = checkReplacement(false, false)
    @Test fun retainedContactCannotNavigateForAReplacementAccount() = checkReplacement(true, true)
    @Test fun retainedContactCannotNavigateAfterSameAccountReauthorization() = checkReplacement(true, false)
    @Test fun retainedConversationCannotNavigateAcrossRecovery() = checkRecovery(false)
    @Test fun retainedContactCannotNavigateAcrossRecovery() = checkRecovery(true)
}
