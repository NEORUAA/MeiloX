package com.ljyh.mei.ui.screen.search

import androidx.activity.BackEventCompat
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.MainActivity
import com.ljyh.mei.R
import com.ljyh.mei.constants.PredictiveBackAnimationEnabledKey
import com.ljyh.mei.utils.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real activity navigation; search requests are read-only and changed preferences are restored. */
@RunWith(AndroidJUnit4::class)
class SearchNavigationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun startsAtHome() {
        // A new activity starts at Home; LastSelectedTab only affects the tab highlight.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(text(R.string.app_tab_home))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertHome()
    }

    @Test
    fun emptySearchBackRemovesTheToolbarWithTheDiscoveryPage() {
        openSearch()
        showDiscoveryCategory(R.string.search_browse_categories)

        navigateBack()

        assertHome()
        compose.onNodeWithText(text(R.string.search_browse_categories)).assertDoesNotExist()
    }

    @Test
    fun leavingTypedSearchClearsTheQueryBeforeReopening() {
        openSearch()
        searchInput().performTextInput("Codex navigation regression")

        navigateBack()
        assertHome()
        openSearch()

        assertEmptyQuery()
        compose.onNodeWithContentDescription(text(R.string.cancel)).performClick()
        assertHome()
    }

    @Test
    fun resultsBackRestoresDiscoveryWithoutRefocusingTheInput() {
        openSearch()
        searchInput().performTextInput("Codex navigation regression")
        searchInput().performImeAction()
        searchInput().assertDoesNotExist()

        navigateBack()

        searchInput().assertIsDisplayed().assertIsNotFocused()
        assertEmptyQuery()
        showDiscoveryCategory(R.string.search_browse_categories)

        navigateBack()
        assertHome()
    }

    @Test
    fun categoryNavigationHidesTheToolbarAndBackRestoresIt() {
        openSearch()
        showDiscoveryCategory(R.string.search_category_rankings)
        compose.onNodeWithText(text(R.string.search_category_rankings)).performClick()

        searchInput().assertDoesNotExist()

        navigateBack()

        searchInput().assertIsDisplayed().assertIsNotFocused()
        assertEmptyQuery()
        showDiscoveryCategory(R.string.search_browse_categories)

        navigateBack()
        assertHome()
    }

    @Test
    fun predictiveBackToggleUpdatesTheCurrentPageWithoutChangingTheBackStack() {
        val dataStore = compose.activity.dataStore
        val originalValue = runBlocking(Dispatchers.IO) {
            dataStore.data.first()[PredictiveBackAnimationEnabledKey]
        }

        fun toggleRow() = compose.onNodeWithText(text(R.string.appearance_predictive_back))
        fun toggle() = toggleRow().onChildren().filterToOne(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState),
        )
        fun bodyLeft() = toggleRow().fetchSemanticsNode().boundsInRoot.left
        fun openAppearance() {
            compose.onNodeWithContentDescription(text(R.string.settings)).performClick()
            compose.onNodeWithText(text(R.string.settings_appearance)).performClick()
            compose.onNode(
                hasScrollToNodeAction() and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
            ).performScrollToNode(hasText(text(R.string.appearance_predictive_back)))
        }
        fun startGesture() {
            compose.runOnUiThread {
                compose.activity.onBackPressedDispatcher.apply {
                    dispatchOnBackStarted(BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT))
                    dispatchOnBackProgressed(BackEventCompat(100f, 200f, 0.25f, BackEventCompat.EDGE_LEFT))
                }
            }
            compose.waitForIdle()
        }
        fun cancelGesture() {
            compose.runOnUiThread {
                compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled()
            }
            compose.waitForIdle()
        }

        try {
            // Apply OFF at Home before the display first observes a previous scene.
            runBlocking(Dispatchers.IO) {
                dataStore.edit { it[PredictiveBackAnimationEnabledKey] = false }
            }
            compose.waitForIdle()
            openAppearance()
            toggle().assertIsOff()

            listOf(false, true, false, true).forEachIndexed { index, enabled ->
                if (index > 0) toggle().performClick()
                val expectedState = if (enabled) ToggleableState.On else ToggleableState.Off
                compose.waitUntil(timeoutMillis = 5_000) {
                    toggle().fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == expectedState
                }
                if (enabled) toggle().assertIsOn() else toggle().assertIsOff()
                compose.waitForIdle()
                val restingLeft = bodyLeft()

                try {
                    startGesture()
                    if (enabled) {
                        compose.waitUntil(timeoutMillis = 5_000) { bodyLeft() > restingLeft + 8f }
                        assertTrue("An enabled gesture must preview the previous page", bodyLeft() > restingLeft + 8f)
                    } else {
                        assertEquals("A disabled gesture must leave the page in place", restingLeft, bodyLeft(), 1f)
                    }
                } finally {
                    cancelGesture()
                }
                assertEquals("Cancelling must restore the current page", restingLeft, bodyLeft(), 1f)
            }

            listOf(true, false).forEach { enabled ->
                if (!enabled) {
                    openAppearance()
                    toggle().performClick()
                    toggle().assertIsOff()
                }
                startGesture()
                navigateBack()
                compose.onNodeWithText(text(R.string.appearance_predictive_back)).assertDoesNotExist()
                compose.onNodeWithText(text(R.string.general_settings)).assertIsDisplayed()
                navigateBack()
                assertHome()
            }
        } finally {
            cancelGesture()
            runBlocking(Dispatchers.IO) {
                dataStore.edit {
                    if (originalValue == null) it.remove(PredictiveBackAnimationEnabledKey)
                    else it[PredictiveBackAnimationEnabledKey] = originalValue
                }
            }
        }
    }

    private fun text(resource: Int) = compose.activity.getString(resource)

    private fun searchInput() = compose.onNode(hasSetTextAction())

    private fun openSearch() {
        compose.onNodeWithContentDescription(text(R.string.app_tab_search)).performClick()
        searchInput().assertIsDisplayed()
    }

    private fun assertHome() {
        searchInput().assertDoesNotExist()
        compose.onAllNodesWithText(text(R.string.app_tab_home))[0].assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.app_tab_search)).assertIsDisplayed()
    }

    private fun assertEmptyQuery() {
        searchInput().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
    }

    private fun showDiscoveryCategory(resource: Int) {
        // The static categories remain available while network recommendations load or fail.
        compose.onNode(
            hasScrollToNodeAction() and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        ).performScrollToNode(hasText(text(resource)))
        compose.onNodeWithText(text(resource)).assertIsDisplayed()
    }

    private fun navigateBack() {
        // Test destination changes directly; system Back may first dismiss the IME.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
}
