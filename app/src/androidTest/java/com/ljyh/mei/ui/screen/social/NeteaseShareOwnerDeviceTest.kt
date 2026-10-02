package com.ljyh.mei.ui.screen.social

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.R
import com.ljyh.mei.data.model.MediaMetadata
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Original sheet and captured Compose actions; all accounts and sends are in-memory. */
class NeteaseShareOwnerDeviceTest {
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private var identity = SessionIdentity(17, true, false)
    private lateinit var sessions: SessionStore
    private lateinit var accounts: AccountStore
    private lateinit var scope: CoroutineScope
    private lateinit var models: ViewModelStore
    private lateinit var model: NeteaseShareViewModel
    private val source = Source()
    private var dismissed = 0
    private var renderedSession: SessionStamp? = null

    private class Source : SocialSource {
        val writes = mutableListOf<Pair<SessionStamp, List<Long>>>()
        override suspend fun privateConversations(session: SessionStamp, offset: Int, limit: Int): List<PrivateConversation> =
            error("Unrelated conversations")
        override suspend fun privateMessages(session: SessionStamp, userId: Long, before: Long, limit: Int): List<PrivateMessage> =
            error("Unrelated history")
        override suspend fun messageContacts(session: SessionStamp, pageSize: Int, maximumCount: Int) =
            listOf(MessageContact(9, "Fixture contact", null, null, null))
        override suspend fun sendPrivateText(session: SessionStamp, message: String, userIds: List<Long>) =
            error("Unrelated text send")
        override suspend fun sendPrivateResource(session: SessionStamp, resource: ShareResource, userIds: List<Long>, message: String) {
            writes += session to userIds
        }
        override suspend fun shareToTimeline(session: SessionStamp, resource: ShareResource, message: String) {
            writes += session to emptyList<Long>()
        }
    }

    @Before fun showOriginalSheet() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            sessions = SessionStore().apply { bind { identity } }
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            accounts = AccountStore(sessions, { owner ->
                AccountProfile(owner.identity.userId, "Fixture account", null, null, null, null, null, null, null, null)
            }, scope)
            models = ViewModelStore()
            model = NeteaseShareViewModel(source, accounts)
            models.put("share", model)
            activity.setContent {
                MaterialTheme {
                    GlassBackdropHost(
                        modifier = Modifier.fillMaxSize(),
                        sampledContent = { Box(Modifier.fillMaxSize().background(Color.White)) },
                        overlayContent = {
                            val rendered = model.state.collectAsState().value.session
                            NeteaseShareSheet(
                                MediaMetadata(11, "Fixture song", "", emptyList(), 60_000, MediaMetadata.Album(1, "Fixture")),
                                onDismiss = { dismissed++ }, viewModel = model,
                            )
                            SideEffect { renderedSession = rendered }
                        },
                    )
                }
            }
        }
        await { model.state.value.session?.identity?.userId == 17L && findAction(label(R.string.netease_timeline_share)) != null }
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

    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    // Inspect actual dialog roots without Espresso's removed API 37 InputManager method.
    private fun roots(view: View): List<ViewRootForTest> = buildList {
        if (view is ViewRootForTest && view.isAttachedToWindow) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(roots(view.getChildAt(index)))
    }

    private fun button(text: String): SemanticsNode? = WindowInspector.getGlobalWindowViews()
        .flatMap(::roots)
        .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
        .firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true &&
                node.config.contains(SemanticsActions.OnClick)
        }

    private fun findAction(text: String): (() -> Boolean)? = button(text)?.config?.getOrNull(SemanticsActions.OnClick)?.action

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
        fail("Timed out waiting for rendered share state")
    }

    private fun action(id: Int): () -> Unit {
        await { findAction(label(id)) != null }
        return onMain {
            // Semantics forwards to a mutable ClickableNode. Retain the actual rendered
            // callback, not that forwarding wrapper, to exercise an obsolete UI action.
            val element = checkNotNull(button(label(id))).layoutInfo.getModifierInfo().map { it.modifier }
                .single { it.javaClass.name == "androidx.compose.foundation.ClickableElement" }
            @Suppress("UNCHECKED_CAST")
            (element.javaClass.getDeclaredField("onClick").apply { isAccessible = true }.get(element) as () -> Unit)
        }
    }

    private fun click(text: String) {
        await { findAction(text) != null }
        onMain { assertTrue(checkNotNull(findAction(text)).invoke()) }
    }

    private fun replaceAuthorization(changeAccount: Boolean = true) {
        onMain {
            sessions.beginTransition().use {
                if (changeAccount) identity = SessionIdentity(18, true, false)
            }
        }
        await { model.state.value.session == sessions.snapshot() && renderedSession == sessions.snapshot() }
    }

    @Test fun retainedTimelineActionCannotSendUnderAReplacementAccount() {
        click(label(R.string.netease_timeline_share))
        val retained = action(R.string.publish)
        replaceAuthorization()
        onMain { retained() }
        onMain { assertTrue(source.writes.isEmpty()); assertEquals(0, dismissed) }
    }

    @Test fun retainedPrivateActionCannotAdoptAReplacementWithTheSameContact() {
        click(label(R.string.netease_private_message))
        await { model.state.value.contacts.isNotEmpty() }
        click("Fixture contact")
        val retained = action(R.string.send)
        replaceAuthorization()
        await { model.state.value.contacts.isNotEmpty() }
        onMain { retained() }
        onMain { assertTrue(source.writes.isEmpty()); assertEquals(0, dismissed) }
    }

    @Test fun retainedTimelineActionCannotAdoptSameAccountReauthorization() {
        click(label(R.string.netease_timeline_share))
        val retained = action(R.string.publish)
        replaceAuthorization(changeAccount = false)
        onMain { retained() }
        onMain { assertTrue(source.writes.isEmpty()); assertEquals(0, dismissed) }
    }

    @Test fun currentRenderedTimelineActionStillSendsExactlyOnce() {
        click(label(R.string.netease_timeline_share))
        click(label(R.string.publish))
        await { source.writes.isNotEmpty() && dismissed == 1 }
        onMain {
            assertEquals(listOf(sessions.snapshot() to emptyList<Long>()), source.writes)
            assertEquals(1, dismissed)
        }
    }

    @Test fun currentRenderedPrivateActionStillSendsToTheSelectedContact() {
        click(label(R.string.netease_private_message))
        await { model.state.value.contacts.isNotEmpty() }
        click("Fixture contact")
        click(label(R.string.send))
        await { source.writes.isNotEmpty() && dismissed == 1 }
        onMain { assertEquals(listOf(sessions.snapshot() to listOf(9L)), source.writes) }
    }

    @Test fun replacementRenderedActionWorksAfterTheOldActionIsRejected() {
        click(label(R.string.netease_timeline_share))
        val retained = action(R.string.publish)
        replaceAuthorization()
        onMain { retained(); assertTrue(source.writes.isEmpty()) }
        click(label(R.string.publish))
        await { source.writes.isNotEmpty() && dismissed == 1 }
        onMain { assertEquals(listOf(sessions.snapshot() to emptyList<Long>()), source.writes) }
    }
}
