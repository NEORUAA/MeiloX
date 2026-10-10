package com.ljyh.mei.ui.screen.search

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.MainActivity
import com.ljyh.mei.R
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real activity and navigation; only read-only search requests are made. */
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
