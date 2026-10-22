package com.ljyh.mei.ui.screen.search

import androidx.activity.BackEventCompat
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real activity navigation; search requests are read-only and changed preferences are restored. */
@RunWith(AndroidJUnit4::class)
class SearchNavigationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var originalSearchHistory: String? = null

    @Before
    fun startsAtHome() {
        originalSearchHistory = runBlocking(Dispatchers.IO) { compose.activity.dataStore.data.first()[SearchHistoryKey] }
        // A new activity starts at Home; LastSelectedTab only affects the tab highlight.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(text(R.string.app_tab_home))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertHome()
    }

    @After
    fun restoreSearchHistory() = runBlocking<Unit>(Dispatchers.IO) {
        compose.activity.dataStore.edit { preferences ->
            originalSearchHistory?.let { preferences[SearchHistoryKey] = it }
                ?: preferences.remove(SearchHistoryKey)
        }
    }

    @Test
    fun recentQueriesCanBeReusedRemovedAndCleared() {
        val query = "MeiloX search history check"
        runBlocking(Dispatchers.IO) { compose.activity.clearSearchHistory() }
        openSearch()
        searchInput().performTextInput(query)
        searchInput().performImeAction()
        compose.waitUntil(10_000) {
            runBlocking(Dispatchers.IO) {
                decodeSearchHistory(compose.activity.dataStore.data.first()[SearchHistoryKey]).firstOrNull() == query
            }
        }
        navigateBack()
        compose.onNodeWithText(query).assertIsDisplayed().performClick()
        assertResultQuery(query)
        navigateBack()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.search_history_remove, query)).performClick()
        compose.onNodeWithText(query).assertDoesNotExist()
        runBlocking(Dispatchers.IO) {
            compose.activity.recordSearchHistory("First recent query")
            compose.activity.recordSearchHistory("Second recent query")
        }
        compose.onNodeWithText(text(R.string.search_history_clear)).performClick()
        compose.waitUntil(5_000) {
            runBlocking(Dispatchers.IO) { decodeSearchHistory(compose.activity.dataStore.data.first()[SearchHistoryKey]).isEmpty() }
        }
        compose.onNodeWithText(text(R.string.search_history_title)).assertDoesNotExist()
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
        assertResultQuery("Codex navigation regression")

        val updatedQuery = "Updated search query"
        searchInput().performTextReplacement("  $updatedQuery  ")
        searchInput().performImeAction()
        assertResultQuery(updatedQuery)
        compose.waitUntil(5_000) {
            runBlocking(Dispatchers.IO) {
                decodeSearchHistory(compose.activity.dataStore.data.first()[SearchHistoryKey]).firstOrNull() == updatedQuery
            }
        }
        searchInput().performClick().performTextReplacement("Unsubmitted draft")
        compose.onNodeWithContentDescription(text(R.string.cancel)).performClick()
        assertResultQuery(updatedQuery)

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
        // The IME can still be moving after scrolling; invoke the destination action directly.
        compose.onNodeWithText(text(R.string.search_category_rankings))
            .performSemanticsAction(SemanticsActions.OnClick) { it() }

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
                if (index > 0) toggle().performSemanticsAction(SemanticsActions.OnClick) { it() }
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
                    toggle().performSemanticsAction(SemanticsActions.OnClick) { it() }
                    compose.waitUntil(timeoutMillis = 5_000) {
                        toggle().fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == ToggleableState.Off
                    }
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

    private fun assertResultQuery(query: String) {
        compose.onAllNodesWithText(text(R.string.search_details_title))[0].assertIsDisplayed()
        searchInput().assertIsDisplayed().assertIsNotFocused().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(query)),
        )
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
