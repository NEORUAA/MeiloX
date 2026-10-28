package com.ljyh.mei.ui.navigation

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.MainActivity
import com.ljyh.mei.R
import com.ljyh.mei.constants.AllowConcurrentPlaybackKey
import com.ljyh.mei.constants.CloudMusicEnabledKey
import com.ljyh.mei.constants.CloudMusicTabEnabledKey
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.DownloadsEnabledKey
import com.ljyh.mei.constants.DownloadsTabEnabledKey
import com.ljyh.mei.constants.FindMusicTabEnabledKey
import com.ljyh.mei.constants.KeepBottomBarExpandedKey
import com.ljyh.mei.constants.LastSelectedTabKey
import com.ljyh.mei.constants.LibraryDefaultPageKey
import com.ljyh.mei.constants.LibraryStyle
import com.ljyh.mei.constants.LibraryStyleKey
import com.ljyh.mei.constants.LibraryTabEnabledKey
import com.ljyh.mei.constants.ListeningHistoryEnabledKey
import com.ljyh.mei.constants.ListeningHistoryTabEnabledKey
import com.ljyh.mei.constants.PodcastsEnabledKey
import com.ljyh.mei.constants.PodcastsTabEnabledKey
import com.ljyh.mei.constants.UserAvatarUrlKey
import com.ljyh.mei.constants.UserNicknameKey
import com.ljyh.mei.ui.screen.Index
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.utils.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import kotlin.math.abs

/** Exercises retained navigation entries and restores every preference changed by the tests. */
@RunWith(AndroidJUnit4::class)
class MainNavigationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val optionalTabs = linkedMapOf(
        Index.FindMusic to FindMusicTabEnabledKey,
        Index.Library to LibraryTabEnabledKey,
        Index.Podcasts to PodcastsTabEnabledKey,
        Index.Downloads to DownloadsTabEnabledKey,
        Index.Cloud to CloudMusicTabEnabledKey,
        Index.History to ListeningHistoryTabEnabledKey,
    )
    private val contentKeys = listOf(
        PodcastsEnabledKey, DownloadsEnabledKey, CloudMusicEnabledKey, ListeningHistoryEnabledKey,
    )
    private var avatarDescription = ""

    @Test
    fun concurrentPlaybackSettingPersistsAcrossRecreation() = withNavigationPreferences {
        setPreference(AllowConcurrentPlaybackKey, false)
        openTab(Index.Settings)
        compose.onNodeWithText(text(R.string.settings_playback)).performClick()
        compose.onNodeWithText(text(R.string.allow_concurrent_playback)).performClick()
        compose.waitUntil(5_000) {
            runBlocking(Dispatchers.IO) {
                compose.activity.dataStore.data.first()[AllowConcurrentPlaybackKey] == true
            }
        }
        compose.activityRule.scenario.recreate()
        val enabledToggle = isToggleable() and
            hasAnyAncestor(hasText(text(R.string.allow_concurrent_playback))) and
            SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On)
        compose.waitUntil(5_000) {
            compose.onAllNodes(enabledToggle).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.onNodeWithText(text(R.string.allow_concurrent_playback)).assertIsDisplayed().performClick()
        compose.waitUntil(5_000) {
            runBlocking(Dispatchers.IO) {
                compose.activity.dataStore.data.first()[AllowConcurrentPlaybackKey] == false
            }
        }
    }

    @Test
    fun primaryDestinationsRemainTopLevelAfterRecreation() = withNavigationPreferences {
        (listOf(Index.Home, Index.Settings) + optionalTabs.keys).forEach { destination ->
            openTab(Index.Home)
            showOnlyOptionalTab(destination)
            openTab(destination)
            assertDestinationChrome(destination, topLevel = true)

            // System appearance changes recreate MainActivity while keeping its navigation stack.
            compose.activityRule.scenario.recreate()

            assertDestinationChrome(destination, topLevel = true)
        }
    }

    @Test
    fun optionalTabChangesUpdateCurrentDestinationAcrossRecreation() = withNavigationPreferences {
        optionalTabs.forEach { (destination, key) ->
            showOnlyOptionalTab(destination)
            openTab(destination)
            assertDestinationChrome(destination, topLevel = true)

            setPreference(key, false)
            assertDestinationChrome(destination, topLevel = false)
            compose.activityRule.scenario.recreate()
            assertDestinationChrome(destination, topLevel = false)
            setPreference(key, true)
            assertDestinationChrome(destination, topLevel = true)
            openTab(Index.Home)
        }
    }

    @Test
    fun podcastsOpenedFromSearchRemainSecondaryAfterRecreation() = withNavigationPreferences {
        compose.onNodeWithContentDescription(text(R.string.app_tab_search)).performClick()
        compose.onNode(
            hasScrollToNodeAction() and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        ).performScrollToNode(hasText(text(R.string.search_category_podcasts)))
        // The IME can still be moving after scrolling; invoke the destination action directly.
        compose.onNodeWithText(text(R.string.search_category_podcasts))
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertDestinationChrome(Index.Podcasts, topLevel = false)

        compose.activityRule.scenario.recreate()

        assertDestinationChrome(Index.Podcasts, topLevel = false)
        compose.onNodeWithContentDescription(text(R.string.navigation_back)).performClick()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
    }

    @Test
    fun settingsTitlesFollowTheirPagesWhileBackButtonRemainsShared() = withNavigationPreferences {
        openTab(Index.Settings)
        val settingsPage = "MeiloXPage:${Screen.Setting.route}"
        val appearancePage = "MeiloXPage:${Screen.AppearanceSettings.route}"
        val settingsTitle = "MeiloXPageTitle:${Screen.Setting.route}"
        val appearanceTitle = "MeiloXPageTitle:${Screen.AppearanceSettings.route}"
        compose.onNode(
            hasScrollToNodeAction() and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        ).performScrollToNode(hasText(text(R.string.settings_appearance)))
        compose.onAllNodesWithContentDescription(avatarDescription).assertCountEquals(1)

        val density = compose.activity.resources.displayMetrics.density
        val avatarBounds = compose.onNodeWithContentDescription(avatarDescription)
            .fetchSemanticsNode().boundsInRoot
        assertEquals("The avatar action must retain a square hit target", avatarBounds.height, avatarBounds.width, density)
        assertEquals("The shared avatar action must retain its 44dp height", 44f * density, avatarBounds.height, density)
        fun pageAndTitleX(page: String, title: String): Float {
            compose.onNodeWithTag(title, useUnmergedTree = true)
                .assert(hasAnyAncestor(hasTestTag(page)))
            val pageX = compose.onNodeWithTag(page, useUnmergedTree = true)
                .fetchSemanticsNode().positionInRoot.x
            val titleX = compose.onNodeWithTag(title, useUnmergedTree = true)
                .fetchSemanticsNode().positionInRoot.x
            assertEquals("The title must use its own page transform", pageX, titleX, density)
            return pageX
        }

        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithText(text(R.string.settings_appearance)).performClick()
            compose.mainClock.advanceTimeBy(96)
            pageAndTitleX(settingsPage, settingsTitle)
            val incomingX = pageAndTitleX(appearancePage, appearanceTitle)
            val sharedBack = compose.onNodeWithContentDescription(text(R.string.navigation_back))
            sharedBack.assert(!hasAnyAncestor(hasTestTag(settingsPage)))
                .assert(!hasAnyAncestor(hasTestTag(appearancePage)))
            val backX = sharedBack.fetchSemanticsNode().boundsInRoot.center.x

            compose.mainClock.advanceTimeBy(96)
            pageAndTitleX(settingsPage, settingsTitle)
            val nextIncomingX = pageAndTitleX(appearancePage, appearanceTitle)
            assertTrue("The incoming page must still be moving", incomingX - nextIncomingX > 4f * density)
            assertEquals(
                "The shared back button must retain its screen anchor while its appearance scales",
                backX,
                sharedBack.fetchSemanticsNode().boundsInRoot.center.x,
                density,
            )
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription(text(R.string.navigation_back)).assertCountEquals(1)
        compose.onNodeWithTag(appearanceTitle, useUnmergedTree = true)
            .assert(hasAnyAncestor(hasTestTag(appearancePage)))
        compose.onAllNodesWithContentDescription(avatarDescription).assertCountEquals(0)

        compose.onNodeWithContentDescription(text(R.string.navigation_back)).performClick()
        assertDestinationChrome(Index.Settings, topLevel = true)
        compose.onAllNodesWithContentDescription(avatarDescription).assertCountEquals(1)
        compose.onAllNodesWithContentDescription(text(R.string.navigation_back)).assertCountEquals(0)
    }

    private fun withNavigationPreferences(block: () -> Unit) {
        val dataStore = compose.activity.dataStore
        val original = runBlocking(Dispatchers.IO) { dataStore.data.first() }
        val booleanKeys = optionalTabs.values + contentKeys + KeepBottomBarExpandedKey + AllowConcurrentPlaybackKey
        avatarDescription = if (original[CookieKey].isNullOrBlank() || original[UserAvatarUrlKey].isNullOrBlank()) {
            text(R.string.account_home)
        } else {
            original[UserNicknameKey].orEmpty().ifBlank { text(R.string.account_home) }
        }
        try {
            runBlocking(Dispatchers.IO) {
                dataStore.edit { preferences ->
                    contentKeys.forEach { preferences[it] = true }
                    preferences[KeepBottomBarExpandedKey] = true
                    preferences[LibraryStyleKey] = LibraryStyle.Default.name
                    preferences[LibraryDefaultPageKey] = ""
                }
            }
            showOnlyOptionalTab(null)
            assertDestinationChrome(Index.Home, topLevel = true)
            block()
        } finally {
            runBlocking(Dispatchers.IO) {
                dataStore.edit { preferences ->
                    booleanKeys.forEach { preferences.restore(it, original) }
                    preferences.restore(LibraryStyleKey, original)
                    preferences.restore(LibraryDefaultPageKey, original)
                    preferences.restore(LastSelectedTabKey, original)
                }
            }
        }
    }

    private fun showOnlyOptionalTab(destination: Index?) {
        runBlocking(Dispatchers.IO) {
            compose.activity.dataStore.edit { preferences ->
                optionalTabs.forEach { (tab, key) -> preferences[key] = tab == destination }
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            hasDescription(R.string.app_tab_search) && optionalTabs.keys.all { tab ->
                hasDescription(tab.labelRes) == (tab == destination)
            }
        }
    }

    private fun openTab(destination: Index) {
        compose.onNode(hasContentDescription(text(destination.labelRes)) and hasClickAction()).performClick()
    }

    private fun assertDestinationChrome(destination: Index, topLevel: Boolean) {
        val title = when (destination) {
            Index.Podcasts -> R.string.podcasts
            Index.Downloads -> R.string.download_management
            Index.Cloud -> R.string.cloud_music
            Index.History -> R.string.listening_history
            else -> destination.labelRes
        }
        val screenMidpoint = compose.activity.resources.displayMetrics.heightPixels / 2f
        // A bottom tab label alone must not satisfy the destination identity check.
        val pageTitle = hasText(text(title)) and !hasClickAction() and SemanticsMatcher("Title in the page header") {
            it.boundsInRoot.center.y < screenMidpoint
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            hasDescription(R.string.navigation_back) == !topLevel &&
                hasDescription(R.string.app_tab_search) == topLevel &&
                compose.onAllNodes(pageTitle).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() &&
                (!topLevel || compose.onAllNodesWithContentDescription(avatarDescription)
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty())
        }
        compose.onAllNodes(pageTitle)[0].assertIsDisplayed()
        if (topLevel) {
            compose.onNodeWithContentDescription(text(R.string.navigation_back)).assertDoesNotExist()
            compose.onNodeWithContentDescription(text(R.string.app_tab_search)).assertIsDisplayed()
            compose.onNodeWithContentDescription(avatarDescription).assertIsDisplayed()
        } else {
            compose.onNodeWithContentDescription(text(R.string.navigation_back)).assertIsDisplayed()
            compose.onNodeWithContentDescription(text(R.string.app_tab_search)).assertDoesNotExist()
        }
    }

    private fun setPreference(key: Preferences.Key<Boolean>, enabled: Boolean) = runBlocking<Unit>(Dispatchers.IO) {
        compose.activity.dataStore.edit { it[key] = enabled }
    }

    private fun hasDescription(resource: Int) = compose.onAllNodesWithContentDescription(text(resource))
        .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    private fun text(resource: Int) = compose.activity.getString(resource)

    private fun <T> MutablePreferences.restore(key: Preferences.Key<T>, original: Preferences) {
        original[key]?.let { this[key] = it } ?: remove(key)
    }
}
