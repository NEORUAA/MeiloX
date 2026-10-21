package com.ljyh.mei.ui.component.player.component

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.ljyh.mei.ui.glass.IosSheetTopToolbar
import com.ljyh.mei.ui.glass.IosSheetTopToolbarButton
import com.ljyh.mei.ui.glass.LocalGlassBackdrop
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.glass.defaultGlassColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerLyricSearchToolbarTest {
    @get:Rule val compose = createComposeRule()

    @Test fun leadingSearchMatchesSettingsAndRetainsBothActions() {
        val showSearch = mutableStateOf(true)
        var searches = 0
        var clears = 0
        var settings = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassColors provides defaultGlassColors(true),
                    LocalGlassBackdrop provides emptyBackdrop(),
                ) {
                    IosSheetTopToolbar(
                        title = "More Actions",
                        modifier = Modifier.width(360.dp),
                        navigation = if (showSearch.value) {
                            {
                                IosSheetTopToolbarButton(
                                    onClick = { searches++ },
                                    onLongClick = { clears++ },
                                ) { SfIcon(systemName = "quote.bubble", contentDescription = "Find lyrics") }
                            }
                        } else null,
                        actions = {
                            IosSheetTopToolbarButton(onClick = { settings++ }) {
                                SfIcon(SfSymbol.Settings, "Settings")
                            }
                        },
                    )
                }
            }
        }
        val search = compose.onNodeWithContentDescription("Find lyrics")
        val gear = compose.onNodeWithContentDescription("Settings")
        val a = search.getUnclippedBoundsInRoot()
        val b = gear.getUnclippedBoundsInRoot()
        assertTrue(a.right < b.left)
        assertEquals((a.right - a.left).value, (b.right - b.left).value, 0.01f)
        assertEquals((a.bottom - a.top).value, (b.bottom - b.top).value, 0.01f)
        search.performClick()
        search.performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        gear.performClick()
        compose.runOnIdle {
            assertEquals(1, searches)
            assertEquals(1, clears)
            assertEquals(1, settings)
            showSearch.value = false
        }
        compose.onNodeWithContentDescription("Find lyrics").assertDoesNotExist()
        gear.performClick()
        compose.runOnIdle { assertEquals(2, settings) }
    }
}
