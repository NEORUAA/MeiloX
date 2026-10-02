package com.ljyh.mei.ui.screen.account

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
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.test.core.app.ActivityScenario
import com.ljyh.mei.R
import com.ljyh.mei.data.model.melox.AccountDetail
import com.ljyh.mei.data.model.melox.AccountPlaylist
import com.ljyh.mei.data.model.melox.AccountProfile
import com.ljyh.mei.data.session.AccountStore
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.ui.glass.GlassBackdropHost
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.navigation.MeiNavigator
import com.ljyh.mei.ui.navigation.MeiRoute
import com.ljyh.mei.ui.screen.Screen
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Original account controls with private fixture identities; no graph, account, or transport. */
@RunWith(Parameterized::class)
class AccountHomeNavigationOwnerDeviceTest(private val target: Target) {
    enum class Target { Rankings, Playlist }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun targets(): Collection<Array<Any>> = Target.entries.map { arrayOf<Any>(it) }
    }

    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var sessions: SessionStore
    private lateinit var accounts: AccountStore
    private lateinit var scope: CoroutineScope
    private lateinit var models: ViewModelStore
    private lateinit var home: AccountHomeViewModel
    private lateinit var navigator: MeiNavigator
    private lateinit var rankTitle: String
    private lateinit var loginTitle: String
    private var identity = SessionIdentity(17, true, false)
    private var renderedState: AccountHomeState? = null
    private val forbiddenCalls = CopyOnWriteArrayList<String>()
    private val reads = mutableListOf<SessionStamp>()
    private val profiles = mutableMapOf<Long, AccountProfile>()
    private val playlist = AccountPlaylist(91, "Fixture account playlist", null, 1, 9, "Fixture creator")
    private var playlists = listOf(playlist)
    private var detailGate: CompletableDeferred<Unit>? = null

    private fun profile(id: Long): AccountProfile = profiles.getOrPut(id) {
        AccountProfile(id, "Fixture account $id", null, null, null, null, null, null, null, null)
    }

    private fun showOriginalScreen() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            rankTitle = activity.getString(R.string.account_listening_rank)
            loginTitle = activity.getString(R.string.netease_login)
            sessions = SessionStore().apply { bind { identity } }
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            accounts = AccountStore(sessions, { owner ->
                sessions.requireCurrent(owner)
                reads += owner
                profile(owner.identity.userId)
            }, scope)
            home = AccountHomeViewModel(
                accounts,
                { userId, owner ->
                    sessions.requireCurrent(owner)
                    reads += owner
                    detailGate?.await()
                    AccountDetail(profile(userId), 1, 10, null)
                },
                { _, owner ->
                    sessions.requireCurrent(owner)
                    reads += owner
                    playlists
                },
                { "Fixture unavailable profile" },
            )
            models = ViewModelStore().apply { put("account-home", home) }
            val owner = object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
                override val viewModelStore = models
                override val defaultViewModelProviderFactory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        forbiddenCalls += modelClass.name
                        error("Account navigation fixtures cannot resolve fallback models")
                    }
                }
            }
            activity.setContent {
                val backStack = rememberNavBackStack(MeiRoute("fixture-root"))
                navigator = remember { MeiNavigator(activity, backStack) }
                CompositionLocalProvider(
                    LocalNavController provides navigator,
                    LocalViewModelStoreOwner provides owner,
                    LocalPlayerAwareWindowInsets provides WindowInsets(0, 0, 0, 0),
                    LocalPlayerConnection provides null,
                ) {
                    MaterialTheme {
                        GlassBackdropHost(
                            modifier = Modifier.fillMaxSize(),
                            sampledContent = { Box(Modifier.fillMaxSize().background(Color.White)) },
                            overlayContent = {
                                val rendered = home.state.collectAsState().value
                                AccountHomeScreen(home)
                                SideEffect { renderedState = rendered }
                            },
                        )
                    }
                }
            }
        }
        if (identity.authenticated) await { hasRenderedControls() }
        else await {
            home.state.value.requiresLogin && !home.state.value.loading &&
                renderedState === home.state.value && row(loginTitle) != null
        }
    }

    @After fun closeFixtures() {
        if (::scenario.isInitialized) {
            scenario.onActivity {
                detailGate?.complete(Unit)
                if (::models.isInitialized) models.clear()
                if (::accounts.isInitialized) accounts.close()
                if (::scope.isInitialized) scope.cancel()
            }
            scenario.close()
        }
        assertTrue("A navigation fixture resolved unrelated models: $forbiddenCalls", forbiddenCalls.isEmpty())
    }

    private fun hasRenderedControls(): Boolean {
        val state = home.state.value
        return state.session == sessions.snapshot() && !state.loading && !state.requiresLogin &&
            state.profile == profile(identity.userId) && state.detail?.profile == profile(identity.userId) &&
            state.playlists == playlists && renderedState === state && row(rankTitle) != null &&
            (playlists.isEmpty() || row(playlist.name) != null)
    }

    private fun roots(view: View): List<ViewRootForTest> = buildList {
        if (view is ViewRootForTest && view.isAttachedToWindow) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(roots(view.getChildAt(index)))
    }

    private fun row(title: String): SemanticsNode? = WindowInspector.getGlobalWindowViews().flatMap(::roots)
        .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
        .firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == title } == true &&
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
        fail("Timed out waiting for the original account $target render")
    }

    private fun action(): () -> Unit = onMain {
        // Retain this render's concrete callback, not mutable semantics forwarding state.
        val element = checkNotNull(row(if (target == Target.Rankings) rankTitle else playlist.name))
            .layoutInfo.getModifierInfo().map { it.modifier }
            .single { it.javaClass.name == "androidx.compose.foundation.ClickableElement" }
        @Suppress("UNCHECKED_CAST")
        (element.javaClass.getDeclaredField("onClick").apply { isAccessible = true }.get(element) as () -> Unit)
    }

    private fun assertRejected(retained: () -> Unit) = onMain {
        retained()
        assertEquals("A retired account $target callback navigated", "fixture-root", navigator.currentRoute)
    }

    private fun assertCurrentNavigation() {
        val current = action()
        onMain {
            current()
            val expected = if (target == Target.Rankings) "${Screen.AccountListeningRank.route}/${identity.userId}"
                else "${Screen.PlayList.route}/${playlist.id}"
            assertEquals(expected, navigator.currentRoute)
            assertTrue(forbiddenCalls.isEmpty())
        }
    }

    @Test fun authenticatedControlsNavigate() {
        showOriginalScreen()
        onMain {
            assertTrue(reads.isNotEmpty())
            assertTrue(reads.all { it == sessions.snapshot() })
        }
        assertCurrentNavigation()
    }

    @Test fun guestCannotRenderProtectedAccountControls() {
        identity = SessionIdentity(0, false, true)
        showOriginalScreen()
        onMain {
            assertNull(row(rankTitle))
            assertNull(row(playlist.name))
            assertTrue(reads.isEmpty())
            assertEquals("fixture-root", navigator.currentRoute)
        }
    }

    @Test fun replacementAccountRetiresItsOriginalCallback() {
        showOriginalScreen()
        val retained = action()
        onMain { sessions.beginTransition().use { identity = identity.copy(userId = 18) } }
        assertRejected(retained)
        await { hasRenderedControls() }
        onMain { assertSame(playlist, home.state.value.playlists.single()) }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun sameAccountReauthorizationRetiresItsOriginalCallback() {
        showOriginalScreen()
        val retained = action()
        val before = onMain { home.state.value }
        onMain { sessions.invalidate() }
        assertRejected(retained)
        await { hasRenderedControls() }
        onMain {
            assertEquals(before.session?.identity, home.state.value.session?.identity)
            assertNotEquals(before.session, home.state.value.session)
            assertSame(before.profile, home.state.value.profile)
            assertSame(playlist, home.state.value.playlists.single())
        }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun sameStampRecoveryRetiresItsOriginalCallback() {
        showOriginalScreen()
        val retained = action()
        val before = onMain { home.state.value }
        onMain {
            sessions.setRecoveryRequired(true)
            retained()
            assertEquals("fixture-root", navigator.currentRoute)
        }
        await { home.state.value.requiresLogin && renderedState === home.state.value }
        onMain { sessions.setRecoveryRequired(false) }
        await { hasRenderedControls() }
        onMain {
            assertEquals(before.session, home.state.value.session)
            assertSame(before.profile, home.state.value.profile)
            assertSame(playlist, home.state.value.playlists.single())
        }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun identicalResourceRefreshRetiresItsOriginalCallback() {
        showOriginalScreen()
        val retained = action()
        val before = onMain { home.state.value }
        onMain { home.refresh() }
        assertRejected(retained)
        await {
            hasRenderedControls() && home.state.value.revision > before.revision &&
                home.state.value.detail !== before.detail
        }
        onMain {
            val current = home.state.value
            assertSame(before.profile, current.profile)
            assertSame(playlist, current.playlists.single())
            assertEquals(before.copy(revision = current.revision), current)
        }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun profileRefreshRetiresItsOriginalCallbackWithinTheSameSession() {
        showOriginalScreen()
        val retained = action()
        val before = onMain { home.state.value }
        onMain {
            profiles[identity.userId] = profile(identity.userId).copy(nickname = "Replacement fixture profile")
            home.refresh()
        }
        assertRejected(retained)
        await { hasRenderedControls() && home.state.value.profile?.nickname == "Replacement fixture profile" }
        onMain { assertEquals(before.session, home.state.value.session) }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun removedPlaylistDoesNotReviveItsOriginalCallback() {
        showOriginalScreen()
        val retained = action()
        val before = onMain { home.state.value }
        onMain { playlists = emptyList(); home.refresh() }
        assertRejected(retained)
        await { hasRenderedControls() && home.state.value.revision > before.revision }
        onMain {
            assertEquals(before.session, home.state.value.session)
            assertNull(row(playlist.name))
        }
        assertRejected(retained)
        if (target == Target.Rankings) assertCurrentNavigation()
    }

    @Test fun pendingRefreshRetiresItsOriginalCallbackBeforeReplacementLoads() {
        showOriginalScreen()
        val retained = action()
        val before = onMain { home.state.value }
        onMain { detailGate = CompletableDeferred(); home.refresh() }
        assertRejected(retained)
        await { home.state.value.loading && renderedState === home.state.value }
        assertRejected(retained)
        onMain { checkNotNull(detailGate).complete(Unit) }
        await {
            hasRenderedControls() && home.state.value.revision > before.revision &&
                home.state.value.detail !== before.detail
        }
        onMain { assertSame(playlist, home.state.value.playlists.single()) }
        assertRejected(retained)
        assertCurrentNavigation()
    }
}
