package com.ljyh.mei.ui.navigation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeloXNavigationTest {
    @Test
    fun defaultLayoutMatchesMeloXNavigationDefaults() {
        val layout = NavigationLayout()

        assertEquals(
            listOf(AppTab.Recommended, AppTab.Music, AppTab.Podcasts),
            layout.normalizedHomeTabs,
        )
        assertEquals(listOf(LibraryPage.Cloud), layout.normalizedSeparatedPages)
        assertEquals(
            listOf(
                AppTab.Home,
                AppTab.Explore,
                AppTab.Library,
                AppTab.LibraryCloud,
                AppTab.Search,
            ),
            layout.visibleTabs,
        )
    }

    @Test
    fun disabledFeaturesRemoveDependentDestinations() {
        val enabled = setOf(ContentFeature.Downloads, ContentFeature.ListeningHistory)
        val layout = NavigationLayout(
            homeTabs = listOf(AppTab.Recommended, AppTab.Podcasts, AppTab.LibraryCloud),
            separatedLibraryPages = listOf(
                LibraryPage.Podcasts,
                LibraryPage.Cloud,
                LibraryPage.History,
            ),
            enabledFeatures = enabled,
        )

        assertFalse(AppTab.Podcasts in layout.normalizedHomeTabs)
        assertFalse(AppTab.LibraryCloud in layout.visibleTabs)
        assertFalse(AppTab.LibraryPodcasts in layout.visibleTabs)
        assertTrue(AppTab.LibraryHistory in layout.visibleTabs)
    }

    @Test
    fun albumAndPodcastPlacementsRemainIndependentlyConfigurable() {
        val layout = NavigationLayout(
            homeTabs = listOf(AppTab.Recommended, AppTab.LibraryAlbums),
            separatedLibraryPages = listOf(LibraryPage.Albums, LibraryPage.Podcasts),
        )

        assertTrue(AppTab.LibraryAlbums in AppTab.configurablePages)
        assertTrue(AppTab.LibraryPodcasts in AppTab.configurablePages)
        assertEquals(AppTab.LibraryAlbums, AppTab.fromLibraryPage(LibraryPage.Albums))
        assertEquals(AppTab.LibraryPodcasts, AppTab.fromLibraryPage(LibraryPage.Podcasts))
        assertEquals(listOf(LibraryPage.Albums, LibraryPage.Podcasts), layout.normalizedSeparatedPages)
        assertEquals(listOf(AppTab.Recommended, AppTab.LibraryAlbums), layout.normalizedHomeTabs)
        assertEquals(AppPagePlacement.Home, layout.placementOf(AppTab.LibraryAlbums))
        assertEquals(AppPagePlacement.TabBar, layout.placementOf(AppTab.LibraryPodcasts))
        assertFalse(AppTab.LibraryAlbums in layout.visibleTabs)
        assertTrue(AppTab.LibraryPodcasts in layout.visibleTabs)

        val embeddedPodcastLayout = layout.copy(separatedLibraryPages = listOf(LibraryPage.Albums))
        assertEquals(AppPagePlacement.Home, embeddedPodcastLayout.placementOf(AppTab.LibraryAlbums))
        assertEquals(AppPagePlacement.Library, embeddedPodcastLayout.placementOf(AppTab.LibraryPodcasts))
    }

    @Test
    fun placementsRespectHomeAndSeparatedLibraryPages() {
        val layout = NavigationLayout()

        assertEquals(AppPagePlacement.Home, layout.placementOf(AppTab.Recommended))
        assertEquals(AppPagePlacement.Home, layout.placementOf(AppTab.Music))
        assertEquals(AppPagePlacement.Library, layout.placementOf(AppTab.LibrarySongs))
        assertEquals(AppPagePlacement.TabBar, layout.placementOf(AppTab.LibraryCloud))
    }

    private fun navigationViewport(
        range: Float = 300f,
        distanceToStart: () -> Float = { Float.POSITIVE_INFINITY },
        isAtStart: () -> Boolean = { false },
        isAtEnd: () -> Boolean = { false },
    ) = object : BottomNavigationViewport {
        override val rangePx: Float get() = range
        override val distanceToStartPx: Float get() = distanceToStart()
        override val atStart: Boolean get() = isAtStart()
        override val atEnd: Boolean get() = isAtEnd()
        override val isScrolling: Boolean get() = false
    }

    private fun newState(
        range: Float = 300f,
        compact: Boolean = false,
        activeDistance: Float = 64f,
        atStart: () -> Boolean = { false },
        distanceToStart: () -> Float = { Float.POSITIVE_INFINITY },
        atEnd: () -> Boolean = { false },
    ) = MeiloXNavigationScrollState(compact).apply {
        bind(true, activeDistance, navigationViewport(range, distanceToStart, atStart, atEnd))
    }

    private fun drag(state: MeiloXNavigationScrollState, distance: Float) {
        val delta = Offset(0f, -distance)
        assertEquals(Offset.Zero, state.nestedScrollConnection.onPreScroll(delta, NestedScrollSource.UserInput))
        assertEquals(Offset.Zero, state.nestedScrollConnection.onPostScroll(
            delta, Offset.Zero, NestedScrollSource.UserInput))
    }

    @Test fun nativeDistanceAndEligibilityRemainInPhysicalPoints() {
        for (density in listOf(1f, 2.75f)) for (eligible in listOf(false, true)) {
            val state = newState(range = 96f * density - if (eligible) 0f else .01f,
                activeDistance = 64f * density)
            drag(state, 63f * density)
            assertFalse(state.compactTarget)
            assertEquals(if (eligible) 63f / 64f else 0f, state.progress, .00001f)
            drag(state, density)
            assertEquals(eligible, state.compactTarget)
        }
    }

    @Test fun onlyAUserFlingCanContinueTheScrollAwayDriver() = runBlocking {
        val state = newState()
        val connection = state.nestedScrollConnection
        connection.onPostScroll(Offset(0f, -100f), Offset.Zero, NestedScrollSource.SideEffect)
        assertEquals(0f, state.progress, 0f)
        drag(state, 16f)
        connection.onPostScroll(Offset(0f, -100f), Offset.Zero, NestedScrollSource.SideEffect)
        assertEquals(.25f, state.progress, 0f)
        assertEquals(Velocity.Zero, connection.onPreFling(Velocity(0f, -2000f)))
        assertFalse(state.isTracking)
        connection.onPostScroll(Offset(0f, -48f), Offset.Zero, NestedScrollSource.SideEffect)
        assertTrue(state.compactTarget)
        assertEquals(Velocity.Zero, connection.onPostFling(Velocity(0f, -2000f), Velocity.Zero))
        assertTrue(state.compactTarget)

        val partial = newState()
        drag(partial, 32f)
        partial.finishGesture()
        assertEquals(0f, partial.progress, 0f)
        assertFalse(partial.compactTarget)

        val reverse = newState(compact = true)
        val reverseConnection = reverse.nestedScrollConnection
        reverseConnection.onPostScroll(Offset(0f, 100f), Offset.Zero, NestedScrollSource.SideEffect)
        assertTrue(reverse.compactTarget)
        drag(reverse, -16f)
        reverseConnection.onPostScroll(Offset(0f, 100f), Offset.Zero, NestedScrollSource.SideEffect)
        reverseConnection.onPreScroll(Offset(0f, 100f), NestedScrollSource.UserInput)
        reverseConnection.onPostScroll(Offset.Zero, Offset(0f, 100f), NestedScrollSource.UserInput)
        assertTrue("Neither programmatic nor unconsumed interior motion counts toward reveal", reverse.compactTarget)
        assertEquals(Velocity.Zero, reverseConnection.onPreFling(Velocity(0f, 2000f)))
        reverseConnection.onPostScroll(Offset(0f, 47f), Offset.Zero, NestedScrollSource.SideEffect)
        assertTrue(reverse.compactTarget)
        reverseConnection.onPostScroll(Offset(0f, 1f), Offset.Zero, NestedScrollSource.SideEffect)
        assertFalse("A user fling can finish the reverse consumed-distance threshold", reverse.compactTarget)
        assertEquals(Velocity.Zero, reverseConnection.onPostFling(Velocity(0f, 2000f), Velocity.Zero))
    }

    @Test fun interiorReverseRevealsAndShortTopCrossingRemainsStrict() {
        for (density in listOf(1f, 2.75f)) {
            val interior = newState(compact = true, activeDistance = 64f * density)
            drag(interior, -63f * density)
            assertTrue("An incomplete downward drag preserves compact controls", interior.compactTarget)
            drag(interior, -density)
            assertFalse("Consumed downward motion reveals without reaching the top", interior.compactTarget)
            drag(interior, 64f * density)
            assertFalse("Reveal stays expanded through the remainder of the same gesture", interior.compactTarget)
        }

        var atStart = false
        var distance = 20f
        val state = newState(compact = true, atStart = { atStart }, distanceToStart = { distance })
        val connection = state.nestedScrollConnection
        connection.onPreScroll(Offset(0f, 20f), NestedScrollSource.UserInput)
        distance = 0f
        atStart = true
        connection.onPostScroll(Offset(0f, 20f), Offset.Zero, NestedScrollSource.UserInput)
        assertTrue("Reaching the edge exactly is not an overscroll", state.compactTarget)
        connection.onPreScroll(Offset(0f, 1f), NestedScrollSource.UserInput)
        connection.onPostScroll(Offset.Zero, Offset(0f, 1f), NestedScrollSource.UserInput)
        assertFalse(state.compactTarget)
        atStart = false
        distance = 64f
        drag(state, 64f)
        assertFalse("A reset interaction cannot reactivate inside the same gesture", state.compactTarget)
        state.finishGesture()
        assertFalse(state.compactTarget)
    }

    @Test fun opposingMotionAndGestureEndDiscardSmallReverseJitter() {
        val state = newState(compact = true)
        drag(state, -40f)
        drag(state, 40f)
        drag(state, -40f)
        assertTrue("Downward ticks do not accumulate through opposing motion", state.compactTarget)
        state.finishGesture()
        drag(state, -24f)
        assertTrue("A new gesture cannot inherit a previous partial reveal", state.compactTarget)
        drag(state, -40f)
        assertFalse(state.compactTarget)

        val sameGesture = newState()
        drag(sameGesture, 64f)
        assertTrue(sameGesture.compactTarget)
        drag(sameGesture, -64f)
        assertFalse("A direction change after compaction can also reveal within one drag", sameGesture.compactTarget)
    }

    @Test fun anUnconsumedStartCrossingCanRevealAcrossMultipleLazyItems() {
        var atStart = false
        val state = newState(compact = true, atStart = { atStart })
        val connection = state.nestedScrollConnection
        connection.onPreScroll(Offset(0f, 400f), NestedScrollSource.UserInput)
        atStart = true
        connection.onPostScroll(Offset(0f, 300f), Offset(0f, 100f), NestedScrollSource.UserInput)
        assertFalse("Actual unconsumed top motion must work when the initial offset was not measurable", state.compactTarget)
    }

    @Test fun aFurtherAwayEdgeGestureResetsCompactWithoutConsumingTheBounce() {
        for (range in listOf(300f, 20f)) {
            val state = newState(range = range, compact = true, atEnd = { true })
            val connection = state.nestedScrollConnection
            assertEquals(Offset.Zero, connection.onPreScroll(Offset(0f, -12f), NestedScrollSource.UserInput))
            assertEquals(Offset.Zero, connection.onPostScroll(
                Offset.Zero, Offset(0f, -12f), NestedScrollSource.UserInput))
            assertFalse("An edge reset precedes new-interaction eligibility", state.compactTarget)
        }
    }

    @Test fun changingHostsCancelsTheOldSessionAndExplicitExpansionKeepsRoutesExternal() {
        val state = newState()
        drag(state, 24f)
        state.finishGesture()
        state.bind(true, 64f, navigationViewport(range = 500f))
        state.nestedScrollConnection.onPostScroll(
            Offset(0f, -200f), Offset.Zero, NestedScrollSource.SideEffect)
        assertEquals(0f, state.progress, 0f)
        drag(state, 64f)
        assertTrue(state.compactTarget)
        drag(state, -48f)
        assertTrue(state.compactTarget)
        state.bind(false, 64f, navigationViewport(range = 500f))
        drag(state, 100f)
        assertTrue("Disabling a hidden page's driver preserves the host's compact target", state.compactTarget)
        state.bind(true, 64f, navigationViewport(range = 500f))
        drag(state, -16f)
        assertTrue("Disabling the driver discards a partial reverse gesture", state.compactTarget)
        state.expand()
        assertFalse(state.compactTarget)
    }
}
