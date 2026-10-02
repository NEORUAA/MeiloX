package com.ljyh.mei.ui.screen.listentogether

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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.R
import com.ljyh.mei.data.model.melox.ListenTogetherCommand
import com.ljyh.mei.data.model.melox.ListenTogetherPlaybackSnapshot
import com.ljyh.mei.data.model.melox.ListenTogetherRoom
import com.ljyh.mei.data.model.melox.ListenTogetherStatus
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.repository.ListenTogetherSource
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.playback.ListenTogetherStore
import com.ljyh.mei.ui.glass.GlassBackdropHost
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.navigation.MeiNavigator
import com.ljyh.mei.ui.navigation.MeiRoute
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Original screen and retained rendered callbacks; rooms and transport stay in-memory. */
class ListenTogetherOwnerDeviceTest {
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var sessions: SessionStore
    private lateinit var store: ListenTogetherStore
    private var identity = SessionIdentity(17, true, false)
    private var renderedSession: SessionStamp? = null
    private val source = Source()

    private data class Call(val name: String, val owner: SessionStamp, val room: String)

    private class Source : ListenTogetherSource {
        val calls = mutableListOf<Call>()
        var active: ListenTogetherRoom? = null
        override suspend fun listenTogetherStatus(session: SessionStamp) = ListenTogetherStatus(active != null, active, null)
        override suspend fun createListenTogetherRoom(session: SessionStamp): ListenTogetherRoom {
            calls += Call("create", session, "fixture-room")
            return room(session).also { active = it }
        }
        override suspend fun checkListenTogetherRoom(session: SessionStamp, roomId: String): Pair<Boolean, String?> {
            calls += Call("check", session, roomId)
            return true to null
        }
        override suspend fun acceptListenTogetherRoom(session: SessionStamp, roomId: String, inviterId: String): ListenTogetherRoom {
            check(inviterId == "9")
            calls += Call("join", session, roomId)
            return room(session).also { active = it }
        }
        override suspend fun reportListenTogetherCommand(session: SessionStamp, roomId: String, command: ListenTogetherCommand,
            progressMs: Long, isPlaying: Boolean, formerSongId: Long?, targetSongId: Long, clientSequence: Long) = Unit
        override suspend fun listenTogetherPlayback(session: SessionStamp, roomId: String) =
            ListenTogetherPlaybackSnapshot(listOf(11), "ORDER", null)
        override suspend fun reportListenTogetherPlaylist(session: SessionStamp, roomId: String, version: Long,
            displaySongIds: List<Long>, randomSongIds: List<Long>) = Unit
        override suspend fun sendListenTogetherHeartbeat(session: SessionStamp, roomId: String, songId: Long,
            isPlaying: Boolean, progressMs: Long): Int? = 3
        override suspend fun endListenTogetherRoom(session: SessionStamp, roomId: String) {
            calls += Call("end", session, roomId)
            active = null
        }
        private fun room(owner: SessionStamp) = ListenTogetherRoom("fixture-room", owner.identity.userId.toString(), emptyList(), null, null)
    }

    private fun player(): Player {
        val item = MediaItem.Builder().setMediaId("11").build()
        var position = 42L
        var playing = true
        var shuffle = false
        return Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getCurrentMediaItem", "getMediaItemAt" -> item
                "getMediaItemCount" -> 1
                "getCurrentMediaItemIndex" -> 0
                "getCurrentPosition" -> position
                "getPlaybackState" -> Player.STATE_READY
                "isPlaying", "getPlayWhenReady" -> playing
                "getShuffleModeEnabled" -> shuffle
                "addListener", "removeListener" -> null
                "seekTo" -> { position = args!![1] as Long; null }
                "setShuffleModeEnabled" -> { shuffle = args!![0] as Boolean; null }
                "play" -> { playing = true; null }
                "pause" -> { playing = false; null }
                else -> error("Unexpected synthetic player method: ${method.name}")
            }
        } as Player
    }

    @Before fun showOriginalScreen() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            sessions = SessionStore().apply { bind { identity } }
            val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, _ ->
                error("Unexpected synthetic API: ${method.name}")
            } as ApiService
            store = ListenTogetherStore(activity, source, api, sessions, CoroutineScope(SupervisorJob() + Dispatchers.Main))
            store.attachPlayer(player())
            activity.setContent {
                val backStack = rememberNavBackStack(MeiRoute("fixture-root"), MeiRoute("fixture-listen"))
                val navigator = remember {
                    MeiNavigator(activity, backStack).apply {
                        previousBackStackEntry!!.savedStateHandle["listen_invitation"] =
                            "https://st.music.163.com/listen-together/share?roomId=fixture-room&inviterId=9&songId=11"
                    }
                }
                CompositionLocalProvider(
                    LocalNavController provides navigator,
                    LocalPlayerAwareWindowInsets provides WindowInsets(0, 0, 0, 0),
                ) {
                    MaterialTheme {
                        GlassBackdropHost(
                            modifier = Modifier.fillMaxSize(),
                            sampledContent = { Box(Modifier.fillMaxSize().background(Color.White)) },
                            overlayContent = {
                                val rendered = store.state.collectAsState().value.session
                                ListenTogetherScreen(store)
                                SideEffect { renderedSession = rendered }
                            },
                        )
                    }
                }
            }
        }
        await { store.state.value.session == sessions.snapshot() && renderedSession == sessions.snapshot() &&
            !store.state.value.isLoading && button(R.string.listen_create) != null && button(R.string.listen_join) != null }
    }

    @After fun closeFixtures() {
        if (::scenario.isInitialized) {
            scenario.onActivity { if (::store.isInitialized) store.close() }
            scenario.close()
        }
    }

    private fun roots(view: View): List<ViewRootForTest> = buildList {
        if (view is ViewRootForTest && view.isAttachedToWindow) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(roots(view.getChildAt(index)))
    }

    private fun button(id: Int): SemanticsNode? {
        val text = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
        return WindowInspector.getGlobalWindowViews().flatMap(::roots)
            .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
            .firstOrNull { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true &&
                    node.config.contains(SemanticsActions.OnClick) && !node.config.contains(SemanticsProperties.Disabled)
            }
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
        fail("Timed out waiting for rendered Listen Together state")
    }

    private fun action(id: Int): () -> Unit {
        await { button(id) != null }
        return onMain {
            // Semantics forwards to a mutable node; retain the callback from this render.
            val element = checkNotNull(button(id)).layoutInfo.getModifierInfo().map { it.modifier }
                .single { it.javaClass.name == "androidx.compose.foundation.ClickableElement" }
            @Suppress("UNCHECKED_CAST")
            (element.javaClass.getDeclaredField("onClick").apply { isAccessible = true }.get(element) as () -> Unit)
        }
    }

    private fun click(id: Int) {
        val current = action(id)
        onMain { current() }
    }

    private fun replaceAuthorization(changeAccount: Boolean) {
        onMain {
            source.active = null
            sessions.beginTransition().use { if (changeAccount) identity = SessionIdentity(18, true, false) }
        }
        await { store.state.value.session == sessions.snapshot() && renderedSession == sessions.snapshot() &&
            !store.state.value.isLoading && store.state.value.room == null && button(R.string.listen_create) != null }
    }

    private fun rejectRetained(retained: () -> Unit) {
        onMain { source.calls.clear(); retained() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        onMain { assertTrue("Retained UI callback dispatched a room action", source.calls.isEmpty()) }
    }

    private fun checkRetainedCreate(changeAccount: Boolean) {
        val retained = action(R.string.listen_create)
        replaceAuthorization(changeAccount)
        rejectRetained(retained)
        onMain { assertNull(store.state.value.room) }
        click(R.string.listen_create)
        await { store.state.value.room != null && !store.state.value.isLoading }
        onMain { assertEquals(listOf(Call("create", sessions.snapshot(), "fixture-room")), source.calls) }
    }

    private fun checkRetainedJoin(changeAccount: Boolean) {
        val retained = action(R.string.listen_join)
        replaceAuthorization(changeAccount)
        rejectRetained(retained)
        onMain { assertNull(store.state.value.room) }
        click(R.string.listen_join)
        await { store.state.value.room != null && !store.state.value.isLoading }
        onMain { assertEquals(listOf(Call("check", sessions.snapshot(), "fixture-room"), Call("join", sessions.snapshot(), "fixture-room")), source.calls) }
    }

    private fun checkRetainedEnd(changeAccount: Boolean) {
        click(R.string.listen_create)
        await { store.state.value.room != null && !store.state.value.isLoading && button(R.string.listen_end) != null }
        val retained = action(R.string.listen_end)
        replaceAuthorization(changeAccount)
        click(R.string.listen_create)
        await { store.state.value.room != null && !store.state.value.isLoading && button(R.string.listen_end) != null }
        rejectRetained(retained)
        onMain { assertEquals("fixture-room", store.state.value.room?.id) }
        click(R.string.listen_end)
        await { store.state.value.room == null && !store.state.value.isLoading }
        onMain { assertEquals(listOf(Call("end", sessions.snapshot(), "fixture-room")), source.calls) }
    }

    @Test fun retainedCreateCannotAdoptAReplacementAccount() = checkRetainedCreate(changeAccount = true)
    @Test fun retainedCreateCannotAdoptSameAccountReauthorization() = checkRetainedCreate(changeAccount = false)
    @Test fun retainedJoinCannotAdoptAReplacementAccount() = checkRetainedJoin(changeAccount = true)
    @Test fun retainedJoinCannotAdoptSameAccountReauthorization() = checkRetainedJoin(changeAccount = false)
    @Test fun retainedEndCannotEndAReplacementAccountsRoom() = checkRetainedEnd(changeAccount = true)
    @Test fun retainedEndCannotEndASameAccountReauthorizedRoom() = checkRetainedEnd(changeAccount = false)
}
