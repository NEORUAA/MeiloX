package com.ljyh.mei.ui.screen.song

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
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
import com.ljyh.mei.data.model.melox.SongWiki
import com.ljyh.mei.data.model.melox.SongWikiAttribute
import com.ljyh.mei.data.model.melox.SongWikiPlaylistReference
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
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Original wiki callbacks; URI dispatch is captured locally and never opens a browser. */
@RunWith(Parameterized::class)
class SongWikiNavigationOwnerDeviceTest(private val target: Target) {
    enum class Target { Playlist, Contribution }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun targets(): Collection<Array<Any>> = Target.entries.map { arrayOf<Any>(it) }
    }

    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var sessions: SessionStore
    private lateinit var models: ViewModelStore
    private lateinit var wikiModel: SongWikiViewModel
    private lateinit var navigator: MeiNavigator
    private lateinit var contributionTitle: String
    private var identity = SessionIdentity(17, true, false)
    private val songId = mutableStateOf(10L)
    private var renderedState: SongWikiUiState? = null
    private val forbiddenCalls = CopyOnWriteArrayList<String>()
    private val reads = mutableListOf<Pair<Long, SessionStamp>>()
    private val openedUris = mutableListOf<String>()
    private var readGate: CompletableDeferred<Unit>? = null
    private val playlist = SongWikiPlaylistReference(91, "Fixture wiki playlist", null, 0)
    private val contributionUrl = "https://fixture.invalid/wiki"
    private val wiki = SongWiki(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), listOf(playlist), contributionUrl)
    private var reply = wiki
    private val closedUriHandler = object : UriHandler {
        override fun openUri(uri: String) { openedUris += uri }
    }

    private fun showOriginalScreen() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            contributionTitle = activity.getString(R.string.song_wiki_contribute)
            sessions = SessionStore().apply { bind { identity } }
            wikiModel = SongWikiViewModel({ id, owner ->
                check(id == 10L || id == 20L)
                sessions.requireCurrent(owner)
                reads += id to owner
                readGate?.await()
                reply
            }, sessions)
            models = ViewModelStore().apply { put("wiki", wikiModel) }
            val owner = object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
                override val viewModelStore = models
                override val defaultViewModelProviderFactory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        forbiddenCalls += modelClass.name
                        error("Wiki navigation fixtures cannot resolve fallback models")
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
                    LocalUriHandler provides closedUriHandler,
                ) {
                    MaterialTheme {
                        GlassBackdropHost(
                            modifier = Modifier.fillMaxSize(),
                            sampledContent = { Box(Modifier.fillMaxSize().background(Color.White)) },
                            overlayContent = {
                                val rendered = wikiModel.state.collectAsState().value
                                SongWikiScreen(songId.value, wikiModel)
                                SideEffect { renderedState = rendered }
                            },
                        )
                    }
                }
            }
        }
        await { hasRenderedRow() }
    }

    @After fun closeFixtures() {
        if (::scenario.isInitialized) {
            scenario.onActivity {
                readGate?.complete(Unit)
                if (::models.isInitialized) models.clear()
            }
            scenario.close()
        }
        assertTrue("A wiki fixture resolved unrelated models: $forbiddenCalls", forbiddenCalls.isEmpty())
    }

    private fun hasRenderedRow(): Boolean {
        val state = wikiModel.state.value
        return state.session == sessions.snapshot() && state.songId == songId.value && !state.isLoading &&
            state.wiki === reply && renderedState === state && row() != null
    }

    private fun roots(view: View): List<ViewRootForTest> = buildList {
        if (view is ViewRootForTest && view.isAttachedToWindow) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(roots(view.getChildAt(index)))
    }

    private fun row(): SemanticsNode? = WindowInspector.getGlobalWindowViews().flatMap(::roots)
        .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
        .firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any {
                it.text == if (target == Target.Playlist) playlist.title else contributionTitle
            } == true && node.config.contains(SemanticsActions.OnClick)
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
        fail("Timed out waiting for the original wiki $target render")
    }

    private fun action(): () -> Unit = onMain {
        // Retain this render's concrete callback, not mutable semantics forwarding state.
        val element = checkNotNull(row()).layoutInfo.getModifierInfo().map { it.modifier }
            .single { it.javaClass.name == "androidx.compose.foundation.ClickableElement" }
        @Suppress("UNCHECKED_CAST")
        (element.javaClass.getDeclaredField("onClick").apply { isAccessible = true }.get(element) as () -> Unit)
    }

    private fun assertRejected(retained: () -> Unit) = onMain {
        retained()
        assertEquals("A retired wiki $target callback navigated", "fixture-root", navigator.currentRoute)
        assertTrue("A retired contribution callback dispatched a URI", openedUris.isEmpty())
    }

    private fun assertCurrentNavigation() {
        val current = action()
        onMain {
            current()
            if (target == Target.Playlist) {
                assertEquals("${Screen.PlayList.route}/${playlist.id}", navigator.currentRoute)
                assertTrue(openedUris.isEmpty())
            } else {
                assertEquals("fixture-root", navigator.currentRoute)
                assertEquals(listOf(contributionUrl), openedUris)
            }
            assertTrue(forbiddenCalls.isEmpty())
        }
    }

    @Test fun authenticatedControlsNavigate() {
        showOriginalScreen()
        onMain {
            assertTrue(reads.isNotEmpty())
            assertTrue(reads.all { it == songId.value to sessions.snapshot() })
        }
        assertCurrentNavigation()
    }

    @Test fun currentGuestControlsRemainAvailable() {
        identity = SessionIdentity(0, false, true)
        showOriginalScreen()
        onMain { assertTrue(reads.isNotEmpty()); assertTrue(reads.all { it.second.identity.anonymous }) }
        assertCurrentNavigation()
    }

    @Test fun replacementAccountRetiresItsOriginalCallback() {
        showOriginalScreen()
        val retained = action()
        onMain { sessions.beginTransition().use { identity = identity.copy(userId = 18) } }
        assertRejected(retained)
        await { hasRenderedRow() }
        onMain { assertSame(wiki, wikiModel.state.value.wiki) }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun sameAccountReauthorizationRetiresItsOriginalCallback() {
        showOriginalScreen()
        val retained = action()
        val before = onMain { wikiModel.state.value }
        onMain { sessions.invalidate() }
        assertRejected(retained)
        await { hasRenderedRow() }
        onMain {
            assertEquals(before.session?.identity, wikiModel.state.value.session?.identity)
            assertNotEquals(before.session, wikiModel.state.value.session)
            assertSame(before.wiki, wikiModel.state.value.wiki)
        }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun sameStampRecoveryRetiresItsOriginalCallback() {
        showOriginalScreen()
        val retained = action()
        val before = onMain { wikiModel.state.value }
        onMain {
            sessions.setRecoveryRequired(true)
            retained()
            assertEquals("fixture-root", navigator.currentRoute)
            assertTrue(openedUris.isEmpty())
        }
        await { wikiModel.state.value.session == null && renderedState === wikiModel.state.value }
        onMain { sessions.setRecoveryRequired(false) }
        await { hasRenderedRow() }
        onMain {
            assertEquals(before.session, wikiModel.state.value.session)
            assertSame(before.wiki, wikiModel.state.value.wiki)
        }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun songReentryDoesNotReviveItsOriginalCallback() {
        showOriginalScreen()
        val retained = action()
        val before = onMain { wikiModel.state.value }
        onMain { songId.value = 20 }
        await { hasRenderedRow() && wikiModel.state.value.songId == 20L }
        assertRejected(retained)
        onMain { songId.value = 10 }
        await { hasRenderedRow() && wikiModel.state.value.songId == 10L && wikiModel.state.value !== before }
        onMain {
            assertEquals(before.session, wikiModel.state.value.session)
            assertSame(before.wiki, wikiModel.state.value.wiki)
        }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun pendingReplacementSongRetiresItsOriginalCallback() {
        showOriginalScreen()
        val retained = action()
        val before = onMain { wikiModel.state.value }
        onMain { readGate = CompletableDeferred(); songId.value = 20 }
        await {
            wikiModel.state.value.songId == 20L && wikiModel.state.value.isLoading &&
                renderedState === wikiModel.state.value
        }
        assertRejected(retained)
        onMain { checkNotNull(readGate).complete(Unit) }
        await { hasRenderedRow() }
        onMain {
            assertEquals(before.session, wikiModel.state.value.session)
            assertSame(before.wiki, wikiModel.state.value.wiki)
        }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun removedTargetCannotReviveItsOriginalCallback() {
        showOriginalScreen()
        val retained = action()
        onMain {
            reply = wiki.copy(relatedPlaylists = emptyList(), contributionUrl = null,
                attributes = listOf(SongWikiAttribute("fixture", "Fixture information", "Fixture body")))
            songId.value = 20
        }
        await {
            wikiModel.state.value.songId == 20L && wikiModel.state.value.wiki === reply &&
                renderedState === wikiModel.state.value
        }
        onMain { assertNull(row()) }
        assertRejected(retained)
    }

    @Test fun clearedModelRetiresItsOriginalCallback() {
        showOriginalScreen()
        val retained = action()
        onMain { models.clear() }
        assertRejected(retained)
        await { wikiModel.state.value.session == null && renderedState === wikiModel.state.value }
        assertRejected(retained)
    }
}
