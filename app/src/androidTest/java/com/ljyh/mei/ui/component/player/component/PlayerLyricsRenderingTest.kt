package com.ljyh.mei.ui.component.player.component

import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inspector.WindowInspector

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.ljyh.mei.R
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.ui.component.player.FloatingLyricsPipContent
import com.ljyh.mei.ui.component.player.component.applemusic.playerAdditiveBlend
import com.ljyh.mei.ui.component.player.component.applemusic.playerContentFade
import com.ljyh.mei.ui.component.player.component.sheet.LyricSelectionSheet
import com.ljyh.mei.ui.glass.IosModalSheet
import com.ljyh.mei.ui.glass.LocalBlurBackdrop
import com.ljyh.mei.ui.glass.LocalGlassBackdrop
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.defaultGlassColors
import com.ljyh.mei.ui.model.LyricCandidate
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricVariant
import com.ljyh.mei.ui.model.LyricSource
import com.ljyh.mei.utils.lyric.LRCParser
import com.ljyh.mei.utils.lyric.attachPhonetics
import com.ljyh.mei.utils.lyric.karaokeRenderTimeWindow
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeLineText
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class PlayerLyricsRenderingTest {
    @get:Rule val compose = createComposeRule()
    private val clickedLine = AtomicReference<ISyncedLine>()

    @Test
    fun floatingLyricsRemainReadableWhenTheCardThemeChanges() {
        val isDark = mutableStateOf(false)
        val lines = listOf("Current lyric", "Translated lyric", "Next lyric")
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassColors provides defaultGlassColors(isDark.value),
                    LocalGlassBackdrop provides emptyBackdrop(),
                ) {
                    Box(Modifier.width(320.dp).height(180.dp)) {
                        FloatingLyricsPipContent(lines[0], lines[1], lines[2], 1f)
                    }
                }
            }
        }

        fun assertReadable(dark: Boolean) {
            val background = defaultGlassColors(dark).elevatedBackground.luminance()
            for (line in lines) {
                val pixels = compose.onNodeWithText(line).assertIsDisplayed().captureToImage().toPixelMap()
                var foregroundPixels = 0
                for (y in 0 until pixels.height) {
                    for (x in 0 until pixels.width) {
                        if (kotlin.math.abs(pixels[x, y].luminance() - background) > 0.15f) foregroundPixels++
                    }
                }
                assertTrue("$line must contrast with its ${if (dark) "dark" else "light"} card", foregroundPixels > 20)
            }
        }

        assertReadable(dark = false)
        compose.runOnIdle { isDark.value = true }
        assertReadable(dark = true)
    }

    @Test
    fun wrappedKaraokeLyricsRenderAndSeekToTheirStart() {
        val line = KaraokeLine.MainKaraokeLine(
            syllables = listOf(
                KaraokeSyllable("Always ", 1_000, 2_000),
                KaraokeSyllable("follow ", 2_000, 3_000),
                KaraokeSyllable("your ", 3_000, 4_000),
                KaraokeSyllable("song  ", 4_000, 5_000),
            ),
            translation = "Keep singing",
            alignment = KaraokeAlignment.Start,
            start = 1_000,
            end = 5_000,
        )

        showLyrics(line)

        // Karaoke words are drawn on a Canvas. The merged translation node also
        // covers the wrapped canvas and exposes the real line's click action.
        // Trailing word spaces exercise the UI library's KaraokeSyllable.copy call.
        compose.onNodeWithText("Keep singing")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(100.dp)
            .performClick()
        compose.runOnIdle {
            assertSame(line, clickedLine.get())
            assertEquals(1_000, clickedLine.get().start)
        }
    }

    @Test
    fun syncedLyricsRenderAndSeekToTheirStart() {
        val line = SyncedLine(
            content = "A simple lyric line",
            translation = "Keep singing",
            start = 2_000,
            end = 5_000,
        )

        showLyrics(line)

        compose.onNodeWithText("A simple lyric line").assertIsDisplayed().performClick()
        compose.onNodeWithText("Keep singing").assertIsDisplayed()
        compose.runOnIdle {
            assertSame(line, clickedLine.get())
            assertEquals(2_000, clickedLine.get().start)
        }
    }

    @Test
    fun lineSyncedRomanizationKeepsLongLyricsWrappedAndSeekable() {
        val primary = "手のひら零れ落ちていくその花の色"
        val translation = "The colors spill from my hands"
        val phonetic = "te no hi ra ko bo re o chi te i ku so no ha na no i ro"
        val lyrics = attachPhonetics(
            LRCParser.parse("[00:05.97]$primary\n[00:12.00]Next line", "[00:05.97]$translation"),
            "[00:05.97]$phonetic",
        )
        val line = lyrics.lines.first()
        val data = LyricData(
            source = LyricSource.NetEaseCloudMusic,
            isVerbatim = false,
            lyricLine = lyrics.copy(lines = listOf(line)),
        )
        val width = mutableStateOf(320.dp)
        val fontScale = mutableStateOf(1f)
        val showPhonetic = mutableStateOf(true)
        val pressedLine = AtomicReference<ISyncedLine>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                MaterialTheme {
                    Box(Modifier.width(width.value).height(700.dp).testTag("line-lyrics-bounds")) {
                        PlayerLyricsView(
                            listState = rememberLazyListState(),
                            lyrics = data.lyricLine,
                            isVerbatim = data.isVerbatim,
                            currentPosition = { 8_000 },
                            onLineClicked = clickedLine::set,
                            onLinePressed = pressedLine::set,
                            isManualScrolling = false,
                            normalLineTextStyle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold),
                            translationLineTextStyle = TextStyle(fontSize = 18.sp),
                            phoneticTextStyle = TextStyle(fontSize = 13.sp),
                            showPhonetic = showPhonetic.value,
                            useBlurEffect = false,
                            offset = 32.dp,
                            keepAliveZone = 0.dp,
                        )
                    }
                }
            }
        }

        for ((widthDp, scale) in listOf(320 to 1f, 220 to 1f, 220 to 1.5f, 320 to 1f)) {
            compose.runOnIdle { width.value = widthDp.dp; fontScale.value = scale }
            val layouts = mutableListOf<TextLayoutResult>()
            val primaryNode = compose.onNodeWithText(primary, useUnmergedTree = true)
            primaryNode.assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertTrue("The full primary lyric must wrap at width=$widthDp scale=$scale", layout.lineCount > 1)
            assertFalse("The primary lyric must not overflow", layout.hasVisualOverflow)
            assertEquals(primary.length, layout.getLineEnd(layout.lineCount - 1))
            val bounds = primaryNode.getUnclippedBoundsInRoot()
            val container = compose.onNodeWithTag("line-lyrics-bounds").getUnclippedBoundsInRoot()
            assertTrue(bounds.left >= container.left && bounds.right <= container.right)
            compose.onNodeWithText(translation).assertIsDisplayed()
            compose.onNodeWithText(phonetic).assertIsDisplayed()
        }

        compose.onNodeWithText(primary).performClick()
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.runOnIdle {
            assertSame(line, clickedLine.get())
            assertSame(line, pressedLine.get())
            assertEquals(5_970, clickedLine.get().start)
            showPhonetic.value = false
        }
        compose.onNodeWithText(primary).assertIsDisplayed()
        compose.onNodeWithText(phonetic).assertDoesNotExist()
        compose.runOnIdle { showPhonetic.value = true }
        compose.onNodeWithText(phonetic).assertIsDisplayed()
    }

    @Test
    fun lyricFormatChangesPreserveOutgoingWrappingAndAccompaniment() {
        val primary = "手のひら零れ落ちていくその花の色"
        val lineSynced = attachPhonetics(
            LRCParser.parse("[00:01.00]$primary"),
            "[00:01.00]te no hi ra ko bo re o chi te i ku",
        )
        val accompaniment = KaraokeLine.AccompanimentKaraokeLine(
            syllables = listOf(KaraokeSyllable("Harmony", 1_000, 5_000)),
            translation = "Accompaniment translation",
            alignment = KaraokeAlignment.Start,
            start = 1_000,
            end = 5_000,
        )
        val verbatim = SyncedLyrics(lines = listOf(KaraokeLine.MainKaraokeLine(
            syllables = listOf(
                KaraokeSyllable("Word ", 1_000, 2_000),
                KaraokeSyllable("timing", 2_000, 5_000),
            ),
            translation = "Word-timed translation",
            alignment = KaraokeAlignment.Start,
            start = 1_000,
            end = 5_000,
            accompanimentLines = listOf(accompaniment),
        )))
        val data = mutableStateOf(lineSynced to false)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(220.dp).height(700.dp)) {
                    PlayerLyricsView(
                        listState = rememberLazyListState(),
                        lyrics = data.value.first,
                        isVerbatim = data.value.second,
                        currentPosition = { 3_000 },
                        onLineClicked = {},
                        onLinePressed = {},
                        isManualScrolling = false,
                        useBlurEffect = false,
                        offset = 32.dp,
                        keepAliveZone = 0.dp,
                    )
                }
            }
        }
        compose.onNodeWithText(primary).assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { data.value = verbatim to true }
            compose.mainClock.advanceTimeBy(64)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(primary, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Outgoing line-synced lyrics must keep wrapping", layouts.single().lineCount > 1)
            assertFalse(layouts.single().hasVisualOverflow)
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithText(primary).assertDoesNotExist()
            compose.onNodeWithText("Accompaniment translation").assertIsDisplayed()

            compose.runOnIdle { data.value = lineSynced to false }
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithText("Accompaniment translation").assertIsDisplayed()
            compose.onNodeWithText(primary).assertIsDisplayed()
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithText("Accompaniment translation").assertDoesNotExist()
            compose.onNodeWithText(primary).assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun lyricsKeepAdditiveBlendingThroughoutThePlayerFade() {
        val visibility = mutableStateOf(0.25f)
        val background = Color(0.10f, 0.25f, 0.40f)
        val line = SyncedLine(
            content = "MMMM",
            translation = "",
            start = 1_000,
            end = 5_000,
        )
        compose.setContent {
            MaterialTheme {
                Box(
                    Modifier
                        .width(260.dp)
                        .height(240.dp)
                        .background(background)
                        .testTag("lyrics-fade-background")
                ) {
                    Box(Modifier.fillMaxSize().playerContentFade(visibility)) {
                        PlayerLyricsView(
                            listState = rememberLazyListState(),
                            lyrics = SyncedLyrics(lines = listOf(line)),
                            currentPosition = { 0 },
                            onLineClicked = {},
                            onLinePressed = {},
                            isManualScrolling = false,
                            modifier = Modifier
                                .padding(vertical = 8.dp)
                                .graphicsLayer {
                                    blendMode = BlendMode.Plus
                                    compositingStrategy = CompositingStrategy.Offscreen
                                },
                            useBlurEffect = false,
                            showPhonetic = false,
                            offset = 48.dp,
                            keepAliveZone = 0.dp,
                        )
                    }
                }
            }
        }

        for (alpha in listOf(0.25f, 0.5f, 0.75f, 1f, 0.5f, 0f)) {
            compose.runOnIdle { visibility.value = alpha }
            val pixels = compose.onNodeWithTag("lyrics-fade-background")
                .captureToImage()
                .toPixelMap()
            // A solid glyph interior has the highest red channel. Capturing the
            // background too distinguishes additive blending from a flattened fade.
            var glyph = pixels[0, 0]
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    if (pixels[x, y].red > glyph.red) glyph = pixels[x, y]
                }
            }
            val contribution = 0.4f * alpha
            val tolerance = 0.03f
            assertEquals("Red contribution at visibility $alpha", background.red + contribution, glyph.red, tolerance)
            assertEquals("Green contribution at visibility $alpha", background.green + contribution, glyph.green, tolerance)
            assertEquals("Blue contribution at visibility $alpha", background.blue + contribution, glyph.blue, tolerance)
        }
    }

    @Test
    fun aSingleEntryLayerPreservesTheNestedLyricComposition() {
        val combined = mutableStateOf(false)
        val entryAlpha = mutableStateOf(1f)
        val playerAlpha = mutableStateOf(1f)
        val density = mutableStateOf(2.625f)
        val lyrics = SyncedLyrics(
            lines = listOf(
                SyncedLine("MMMM lyric wrapping across the viewport", "Translated first line", 1_000, 8_000),
                SyncedLine("A blurred upcoming line", "Translated upcoming line", 9_000, 14_000),
                SyncedLine("A third blurred line", null, 15_000, 20_000),
            ),
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density.value)) {
                MaterialTheme {
                    val backing = if (density.value < 3f) {
                        listOf(Color(0.05f, 0.15f, 0.35f), Color(0.25f, 0.40f, 0.15f))
                    } else {
                        listOf(Color(0.35f, 0.20f, 0.10f), Color(0.45f, 0.35f, 0.55f))
                    }
                    Box(
                        Modifier.width(246.5.dp).height(277.25.dp)
                            .background(Brush.linearGradient(backing))
                            .drawBehind {
                                assertTrue("Lyric composition parity requires GPU rendering", drawContext.canvas.nativeCanvas.isHardwareAccelerated)
                            }
                            .testTag("entry-layer-composition"),
                    ) {
                        Box(Modifier.fillMaxSize().playerContentFade(playerAlpha)) {
                            val entryModifier = Modifier.padding(vertical = 9.5.dp).graphicsLayer {
                                val fraction = entryAlpha.value
                                alpha = if (combined.value) fraction * fraction else fraction
                                blendMode = BlendMode.Plus
                                compositingStrategy = CompositingStrategy.Offscreen
                            }
                            PlayerLyricsView(
                                listState = rememberLazyListState(),
                                lyrics = lyrics,
                                currentPosition = { 3_500 },
                                onLineClicked = {}, onLinePressed = {}, isManualScrolling = false,
                                modifier = entryModifier,
                                // The reference retains the former repeated modifier. The
                                // candidate retains both insets and the inner buffer's clip.
                                listModifier = if (combined.value) {
                                    Modifier.padding(vertical = 9.5.dp).clipToBounds()
                                } else entryModifier,
                                normalLineTextStyle = TextStyle(fontSize = 25.sp, fontWeight = FontWeight.Bold),
                                translationLineTextStyle = TextStyle(fontSize = 17.sp),
                                useBlurEffect = true,
                                showPhonetic = false,
                                offset = if (playerAlpha.value < 1f) 0.dp else 32.dp,
                                keepAliveZone = 0.dp,
                                topFadeHeight = 24.dp,
                                bottomFadeHeight = 70.dp,
                                contentModifier = Modifier.graphicsLayer {
                                    translationY = if (playerAlpha.value < 1f) (-12.375).dp.toPx() else 8.5.dp.toPx()
                                },
                                controlsFadeRange = { height -> (0.58f * height)..(0.84f * height) },
                            )
                        }
                    }
                }
            }
        }
        for (scale in listOf(2.625f, 3f)) {
            for (fade in listOf(0.45f, 1f)) {
                compose.runOnIdle {
                    density.value = scale
                    playerAlpha.value = fade
                }
                // Density and translation can move the lookahead rows. Compare
                // their completed placements, not adjacent spring frames.
                compose.mainClock.advanceTimeBy(1_000)
                compose.waitForIdle()
                for (entry in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                    compose.runOnIdle {
                        entryAlpha.value = entry
                        combined.value = false
                    }
                    compose.mainClock.advanceTimeByFrame()
                    compose.waitForIdle()
                    val reference = compose.onNodeWithTag("entry-layer-composition").captureToImage().toPixelMap()
                    compose.runOnIdle { combined.value = true }
                    compose.mainClock.advanceTimeByFrame()
                    compose.waitForIdle()
                    val actual = compose.onNodeWithTag("entry-layer-composition").captureToImage().toPixelMap()
                    assertEquals(reference.width, actual.width)
                    assertEquals(reference.height, actual.height)
                    var maximumError = 0f
                    var totalError = 0.0
                    var brightestReference = 0f
                    for (y in 0 until reference.height) {
                        for (x in 0 until reference.width) {
                            val expected = reference[x, y]
                            val observed = actual[x, y]
                            brightestReference = maxOf(brightestReference, expected.red)
                            val error = maxOf(
                                kotlin.math.abs(expected.red - observed.red),
                                kotlin.math.abs(expected.green - observed.green),
                                kotlin.math.abs(expected.blue - observed.blue),
                                kotlin.math.abs(expected.alpha - observed.alpha),
                            )
                            maximumError = maxOf(maximumError, error)
                            totalError += error
                        }
                    }
                    val label = "density=$scale player=$fade entry=$entry"
                    if (fade == 1f && entry == 1f) {
                        assertTrue("$label must compare rendered glyphs", brightestReference > 0.9f)
                    }
                    // One fewer RGBA buffer may change a channel by at most its
                    // intermediate 8-bit quantization, including the final blend.
                    assertTrue("$label maximum pixel error: $maximumError", maximumError <= 2f / 255f)
                    val meanError = totalError / (reference.width * reference.height)
                    assertTrue("$label mean pixel error: $meanError", meanError <= 0.25 / 255)
                }
            }
        }
    }

    @Test
    fun boundedKaraokeClocksPreservePixelsAcrossAnimationTailsVocalsAndSeeks() {
        val short = KaraokeLine.MainKaraokeLine(
            syllables = listOf(KaraokeSyllable("字", 1_000, 1_050, phonetic = "zi")),
            translation = "Short translation", alignment = KaraokeAlignment.Start,
            start = 1_000, end = 1_050, phonetic = "Line phonetic",
        )
        val word = short.copy(
            syllables = listOf(
                KaraokeSyllable("sing", 3_000, 4_500, phonetic = "sing"),
                KaraokeSyllable("ing ", 4_500, 6_000, phonetic = "ing"),
            ),
            start = 3_000, end = 6_000,
        )
        val early = KaraokeLine.AccompanimentKaraokeLine(
            listOf(KaraokeSyllable("先 ", 200, 900, phonetic = "early")),
            "Early translation", KaraokeAlignment.Start, 200, 900,
        )
        val late = early.copy(
            syllables = listOf(KaraokeSyllable("後 ", 2_600, 4_000, phonetic = "late")),
            translation = "Late translation", start = 2_600, end = 4_000,
        )
        val vocals = short.copy(
            syllables = listOf(KaraokeSyllable("Lead ", 1_000, 2_000, phonetic = "lead")),
            start = 1_000, end = 2_000, accompanimentLines = listOf(early, late),
        )
        val negative = short.copy(
            syllables = listOf(
                KaraokeSyllable("前 ", -500, -400, phonetic = "before"),
                KaraokeSyllable("後 ", 300, 1_000, phonetic = "after"),
            ),
            start = -500, end = 1_000,
        )
        val cases = listOf(
            Triple("short syllable and 700ms tail", short,
                listOf(-1_000, 1_000, 1_699, 1_700, 1_701, 5_000, 1_400, 0)),
            Triple("word animation and backward seek", word,
                listOf(0, 3_000, 4_500, 10_000, 4_000)),
            Triple("nested vocal visibility boundaries", vocals,
                listOf(-5_000, -401, -400, -399, 1_499, 1_500, 1_501,
                    1_999, 2_000, 2_001, 4_599, 4_600, 4_601, 10_000, 1_200, -5_000)),
            Triple("negative timing offset", negative, listOf(-5_000, -500, 300, 5_000, 0)),
        )
        val caseIndex = mutableStateOf(0)
        val time = mutableStateOf(0)
        val bounded = mutableStateOf(false)
        val backing = Color(0.08f, 0.13f, 0.20f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(2.625f),
                LocalTextStyle provides TextStyle(fontSize = 16.sp),
            ) {
                MaterialTheme {
                    Box(
                        Modifier.width(260.dp).height(350.dp).background(backing)
                            .drawBehind {
                                assertTrue("Karaoke clock parity requires GPU rendering", drawContext.canvas.nativeCanvas.isHardwareAccelerated)
                            }
                            .testTag("bounded-karaoke-clock"),
                    ) {
                        val line = cases[caseIndex.value].second
                        key(line) {
                            val window = remember(line) { requireNotNull(karaokeRenderTimeWindow(line)) }
                            val sampled = remember(line) { derivedStateOf { window.sample(time.value) } }
                            // Nested vocal visibility remembers this provider. Keep its
                            // identity stable while switching only its observed clock.
                            val provider = remember(line) {
                                { if (bounded.value) sampled.value else time.value }
                            }
                            KaraokeLineText(
                                line = line,
                                currentTimeProvider = provider,
                                modifier = Modifier.fillMaxSize().padding(8.5.dp).playerAdditiveBlend(),
                                normalLineTextStyle = TextStyle(fontSize = 25.sp, fontWeight = FontWeight.Bold),
                                accompanimentLineTextStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                                phoneticTextStyle = TextStyle(fontSize = 12.sp),
                                blendMode = BlendMode.Plus,
                                showTranslation = true,
                                showPhonetic = true,
                            )
                        }
                    }
                }
            }
        }
        cases.forEachIndexed { index, (name, line, times) ->
            val window = requireNotNull(karaokeRenderTimeWindow(line))
            var firstReference: PixelMap? = null
            var maximumClockChange = 0f
            for (rawTime in times) {
                if (rawTime in window.first..window.last) assertEquals(rawTime, window.sample(rawTime))
                compose.runOnIdle {
                    caseIndex.value = index
                    time.value = rawTime
                    bounded.value = false
                }
                // Vocal entry/exit uses a 600ms Compose animation. Settle the same
                // raw-time transition before comparing its unchanged sampled target.
                compose.mainClock.advanceTimeBy(750)
                compose.waitForIdle()
                val reference = compose.onNodeWithTag("bounded-karaoke-clock").captureToImage().toPixelMap()
                compose.runOnIdle { bounded.value = true }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                val actual = compose.onNodeWithTag("bounded-karaoke-clock").captureToImage().toPixelMap()
                assertEquals(reference.width, actual.width)
                assertEquals(reference.height, actual.height)
                var maximumError = 0f
                var totalError = 0.0
                var renderedPixels = 0
                for (y in 0 until reference.height) {
                    for (x in 0 until reference.width) {
                        val expected = reference[x, y]
                        val observed = actual[x, y]
                        if (expected.red > backing.red + 0.05f) renderedPixels++
                        firstReference?.let {
                            maximumClockChange = maxOf(maximumClockChange, kotlin.math.abs(expected.red - it[x, y].red))
                        }
                        val error = maxOf(
                            kotlin.math.abs(expected.red - observed.red),
                            kotlin.math.abs(expected.green - observed.green),
                            kotlin.math.abs(expected.blue - observed.blue),
                            kotlin.math.abs(expected.alpha - observed.alpha),
                        )
                        maximumError = maxOf(maximumError, error)
                        totalError += error
                    }
                }
                if (firstReference == null) firstReference = reference
                val label = "$name raw=$rawTime sampled=${window.sample(rawTime)}"
                assertTrue("$label must compare rendered text", renderedPixels > 100)
                assertTrue("$label maximum pixel error: $maximumError", maximumError <= 2f / 255f)
                val meanError = totalError / (reference.width * reference.height)
                assertTrue("$label mean pixel error: $meanError", meanError <= 0.25 / 255)
            }
            assertTrue("$name must exercise changing karaoke pixels", maximumClockChange > 0.05f)
        }
    }

    @Test
    fun playerControlsKeepAdditiveBlendingThroughoutTheFade() {
        val visibility = mutableStateOf(0f)
        val background = Color(0.10f, 0.25f, 0.40f)
        compose.setContent {
            MaterialTheme {
                Box(
                    Modifier.width(96.dp).height(96.dp)
                        .background(background)
                        .testTag("controls-fade-background"),
                ) {
                    Box(Modifier.fillMaxSize().playerContentFade(visibility)) {
                        Box(
                            Modifier.fillMaxSize().playerAdditiveBlend()
                                .background(Color.White.copy(alpha = 0.4f)),
                        )
                    }
                }
            }
        }

        for (alpha in listOf(0f, 0.25f, 0.5f, 0.75f, 1f, 0.5f, 0.25f, 1f, 0f)) {
            compose.runOnIdle { visibility.value = alpha }
            val pixels = compose.onNodeWithTag("controls-fade-background")
                .captureToImage().toPixelMap()
            val center = pixels[pixels.width / 2, pixels.height / 2]
            val contribution = 0.4f * alpha
            // SrcOver would attenuate the colored background as the control fades in.
            assertEquals("Red contribution at visibility $alpha", background.red + contribution, center.red, 0.015f)
            assertEquals("Green contribution at visibility $alpha", background.green + contribution, center.green, 0.015f)
            assertEquals("Blue contribution at visibility $alpha", background.blue + contribution, center.blue, 0.015f)
        }
    }

    @Test
    fun lyricSelectionKeepsNativeCandidatesDuringSearchAndExposesTheirSources() {
        val expandSheet = mutableStateOf(false)
        val loading = mutableStateOf(true)
        val error = mutableStateOf<String?>(null)
        val candidates = listOf(
            lyricCandidate(LyricSource.NetEaseCloudMusic, "NetEase candidate"),
            lyricCandidate(LyricSource.QQMusic, "QQ candidate"),
            lyricCandidate(LyricSource.AM, "Apple candidate"),
        )
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassColors provides defaultGlassColors(false),
                    LocalGlassBackdrop provides emptyBackdrop(),
                    LocalBlurBackdrop provides emptyBackdrop(),
                ) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
                    LaunchedEffect(expandSheet.value) {
                        if (expandSheet.value) sheetState.expand()
                    }
                    LyricSelectionSheet(
                        mediaMetadata = lyricReference,
                        candidates = candidates,
                        offsetMs = 0,
                        isLoading = loading.value,
                        errorMessage = error.value,
                        onSelectCandidate = {},
                        onAdjustOffset = {},
                        onResetOffset = {},
                        onResetSource = {},
                        onDismiss = {},
                        sheetState = sheetState,
                    )
                }
            }
        }
        compose.runOnIdle { expandSheet.value = true }
        compose.waitForIdle()

        for (label in listOf(
            R.string.lyric_selection_source_netease,
            R.string.lyric_selection_source_qq,
            R.string.lyric_selection_source_am,
        )) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(text(label)))
            compose.onNodeWithContentDescription(text(label), useUnmergedTree = true)
                .performScrollTo()
                .assertIsDisplayed()
        }
        compose.runOnIdle {
            loading.value = false
            error.value = "Search unavailable"
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Search unavailable"))
        compose.onNodeWithText("Search unavailable").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(candidates.first().title))
        compose.onNodeWithText(candidates.first().title).assertIsDisplayed()
    }

    @Test
    fun lyricSelectionDistinguishesVersionsOfTheSameSongAndSelectsTheRequestedRow() {
        val expandSheet = mutableStateOf(false)
        val selected = AtomicReference<LyricCandidate>()
        val words = lyricCandidate(LyricSource.NetEaseCloudMusic, "Same song").copy(
            key = "netease-word", variant = LyricVariant.NeteaseYrc, preview = "Word timeline preview",
            isSelected = true,
        )
        val lines = words.copy(
            key = "netease-line", variant = LyricVariant.NeteaseLrc, preview = "Line timeline preview",
            isSelected = false,
        )
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassColors provides defaultGlassColors(false),
                    LocalGlassBackdrop provides emptyBackdrop(),
                    LocalBlurBackdrop provides emptyBackdrop(),
                ) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
                    LaunchedEffect(expandSheet.value) { if (expandSheet.value) sheetState.expand() }
                    LyricSelectionSheet(
                        mediaMetadata = lyricReference,
                        candidates = listOf(words, lines),
                        offsetMs = 500,
                        isLoading = false,
                        errorMessage = null,
                        onSelectCandidate = selected::set,
                        onAdjustOffset = {}, onResetOffset = {}, onResetSource = {}, onDismiss = {},
                        sheetState = sheetState,
                    )
                }
            }
        }
        compose.runOnIdle { expandSheet.value = true }
        for (candidate in listOf(words, lines)) {
            val tag = "lyric-candidate-${candidate.key}"
            compose.onNodeWithTag("lyric-selection-list").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithText(candidate.preview).assertIsDisplayed()
        }
        compose.onNodeWithTag("lyric-candidate-${lines.key}").performClick()
        compose.runOnIdle { assertEquals(lines, selected.get()) }
    }

    @Test
    fun lyricSelectionAdjustsTimingInHalfSecondsAndKeepsTheResetActionsSeparate() {
        val expandSheet = mutableStateOf(false)
        val offset = mutableStateOf(0)
        val adjustments = mutableListOf<Int>()
        var sourceResets = 0
        var timingResets = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassColors provides defaultGlassColors(false),
                    LocalGlassBackdrop provides emptyBackdrop(),
                    LocalBlurBackdrop provides emptyBackdrop(),
                ) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
                    LaunchedEffect(expandSheet.value) {
                        if (expandSheet.value) sheetState.expand()
                    }
                    LyricSelectionSheet(
                        mediaMetadata = lyricReference,
                        candidates = emptyList(),
                        offsetMs = offset.value,
                        isLoading = false,
                        errorMessage = null,
                        onSelectCandidate = {},
                        onAdjustOffset = {
                            adjustments += it
                            offset.value += it
                        },
                        onResetOffset = { timingResets++; offset.value = 0 },
                        onResetSource = { sourceResets++ },
                        onDismiss = {},
                        sheetState = sheetState,
                    )
                }
            }
        }
        compose.runOnIdle { expandSheet.value = true }
        compose.waitForIdle()

        fun click(resource: Int) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(resource)))
            compose.onNode(hasClickAction() and hasText(text(resource))).performClick()
        }
        click(R.string.lyric_selection_later)
        compose.onNodeWithTag("lyric-selection-offset")
            .assertIsDisplayed()
            .assertTextEquals(text(R.string.lyric_selection_offset, 0.5))
        click(R.string.lyric_selection_earlier)
        click(R.string.lyric_selection_earlier)
        compose.onNodeWithTag("lyric-selection-offset")
            .assertIsDisplayed()
            .assertTextEquals(text(R.string.lyric_selection_offset, -0.5))
        click(R.string.lyric_selection_reset_source)
        compose.runOnIdle { assertEquals(-500, offset.value) }
        click(R.string.lyric_selection_reset_delay)
        compose.runOnIdle {
            assertEquals(listOf(500, -500, -500), adjustments)
            assertEquals(1, sourceResets)
            assertEquals(1, timingResets)
            assertEquals(0, offset.value)
        }
    }

    @Test
    fun closingOrSelectingLyricsKeepsTheSheetComposedUntilItsHideAnimationFinishes() {
        val open = mutableStateOf(true)
        val sheet = AtomicReference<SheetState>()
        val selected = AtomicReference<LyricCandidate>()
        val candidate = lyricCandidate(LyricSource.AM, "Apple candidate")
        var dismissed = 0
        var disposed = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassColors provides defaultGlassColors(false),
                    LocalGlassBackdrop provides emptyBackdrop(),
                    LocalBlurBackdrop provides emptyBackdrop(),
                ) {
                    if (open.value) {
                        val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
                        SideEffect { sheet.set(state) }
                        DisposableEffect(Unit) { onDispose { disposed++ } }
                        LyricSelectionSheet(
                            mediaMetadata = lyricReference,
                            candidates = listOf(candidate),
                            offsetMs = 0,
                            isLoading = false,
                            errorMessage = null,
                            onSelectCandidate = selected::set,
                            onAdjustOffset = {},
                            onResetOffset = {},
                            onResetSource = {},
                            onDismiss = { dismissed++; open.value = false },
                            sheetState = state,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(sheet.get().isVisible) }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription(text(R.string.lyric_selection_close)).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals(0, dismissed)
            assertEquals(0, disposed)
            assertTrue(sheet.get().isVisible)
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle {
            assertEquals(1, dismissed)
            assertEquals(1, disposed)
            assertFalse(sheet.get().isVisible)
        }

        compose.mainClock.autoAdvance = true
        compose.runOnIdle { open.value = true }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(candidate.title))
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(candidate.title).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertSame(candidate, selected.get())
            assertEquals(1, dismissed)
            assertEquals(1, disposed)
            assertTrue(sheet.get().isVisible)
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle {
            assertEquals(2, dismissed)
            assertEquals(2, disposed)
            assertFalse(sheet.get().isVisible)
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun lyricSheetKeepsTheRealDialogNavigationBarTransparent() {
        val window = AtomicReference<Window?>()
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassColors provides defaultGlassColors(false),
                    LocalGlassBackdrop provides emptyBackdrop(),
                    LocalBlurBackdrop provides emptyBackdrop(),
                ) {
                    IosModalSheet(onDismissRequest = {}) {
                        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
                        SideEffect { window.set(dialogWindow) }
                        Text("Sheet content", Modifier.padding(24.dp))
                    }
                }
            }
        }

        compose.onNodeWithText("Sheet content").assertIsDisplayed()
        compose.runOnIdle {
            assertNotNull("The sheet must expose its actual dialog window", window.get())
            val actual = requireNotNull(window.get())
            assertTrue(actual.decorView.isAttachedToWindow)
            assertFalse(actual.isNavigationBarContrastEnforced)
            assertEquals(android.graphics.Color.TRANSPARENT, actual.navigationBarColor)
            assertEquals(android.graphics.Color.TRANSPARENT, actual.navigationBarDividerColor)
        }
    }

    @Test
    fun lyricSelectionScrollsBehindNavigationWhileItsLastCandidateRemainsSafe() {
        val expandSheet = mutableStateOf(false)
        val density = AtomicReference<Density>()
        val candidates = List(30) { index ->
            lyricCandidate(LyricSource.QQMusic, "Candidate ${index + 1}")
                .copy(key = "candidate-$index")
        }
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassColors provides defaultGlassColors(false),
                    LocalGlassBackdrop provides emptyBackdrop(),
                    LocalBlurBackdrop provides emptyBackdrop(),
                ) {
                    val currentDensity = LocalDensity.current
                    SideEffect { density.set(currentDensity) }
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
                    LaunchedEffect(expandSheet.value) {
                        if (expandSheet.value) sheetState.expand()
                    }
                    LyricSelectionSheet(
                        mediaMetadata = lyricReference,
                        candidates = candidates,
                        offsetMs = 0,
                        isLoading = false,
                        errorMessage = null,
                        onSelectCandidate = {},
                        onAdjustOffset = {},
                        onResetOffset = {},
                        onResetSource = {},
                        onDismiss = {},
                        sheetState = sheetState,
                    )
                }
            }
        }
        compose.runOnIdle { expandSheet.value = true }
        compose.waitForIdle()

        val list = compose.onNodeWithTag("lyric-selection-list")
        val rootBounds = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        val listBounds = list.fetchSemanticsNode().boundsInRoot
        assertEquals("The scrolling viewport must extend to the dialog bottom", rootBounds.bottom, listBounds.bottom, 1f)

        // Read the real sheet window's insets; no synthetic navigation area is supplied.
        var navigationBottom = 0
        compose.runOnIdle {
            fun findDialogWindow(view: View): Window? {
                if (view is DialogWindowProvider) return view.window
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) {
                        findDialogWindow(view.getChildAt(index))?.let { return it }
                    }
                }
                return null
            }
            val actual = WindowInspector.getGlobalWindowViews().mapNotNull(::findDialogWindow).single()
            navigationBottom = actual.decorView.rootWindowInsets
                .getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
        }

        // Reference and timing rows precede the candidates. Reaching the final index
        // must reveal the complete row plus its content-owned navigation padding.
        list.performScrollToIndex(candidates.size + 1)
        val lastCandidate = compose.onNodeWithText(candidates.last().title).assertIsDisplayed()
        val lastBounds = lastCandidate.getUnclippedBoundsInRoot()
        val lastTop = with(density.get()) { lastBounds.top.toPx() }
        val lastBottom = with(density.get()) { lastBounds.bottom.toPx() }
        val expectedEndPadding = with(density.get()) { 24.dp.toPx() } + navigationBottom
        assertTrue("The complete final candidate must fit inside the viewport", lastTop >= listBounds.top - 1f)
        assertEquals(
            "The final candidate must leave the navigation safe area inside the scrolling content",
            expectedEndPadding,
            rootBounds.bottom - lastBottom,
            2f,
        )
    }

    private val lyricReference = MediaMetadata(
        id = 1,
        title = "Reference track",
        coverUrl = "",
        artists = emptyList(),
        duration = 120_000,
        album = MediaMetadata.Album(1, "Reference album"),
    )

    private fun lyricCandidate(source: LyricSource, title: String) = LyricCandidate(
        key = source.name,
        source = source,
        title = title,
        artist = "Candidate artist",
        album = "Candidate album",
        coverUrl = "",
        durationMs = 120_000,
    )

    private fun text(resource: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resource, *args)

    private fun showLyrics(line: ISyncedLine) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(220.dp).height(480.dp)) {
                    PlayerLyricsView(
                        listState = rememberLazyListState(),
                        lyrics = SyncedLyrics(lines = listOf(line)),
                        currentPosition = { 3_500 },
                        onLineClicked = clickedLine::set,
                        onLinePressed = {},
                        isManualScrolling = false,
                        useBlurEffect = false,
                        showPhonetic = false,
                        offset = 32.dp,
                        keepAliveZone = 0.dp,
                    )
                }
            }
        }
    }

    @Test
    fun syncedTranslationSizeAndWeightUpdateIndependentlyOfPrimaryText() {
        val translationStyle = mutableStateOf(TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Normal))
        val line = SyncedLine("Primary lyric", "Translated lyric", 1_000, 5_000)
        compose.setContent { TranslationTestContent(line, translationStyle.value) }

        assertTypography("Translated lyric", 18, FontWeight.Normal)
        assertTypography("Primary lyric", 28, FontWeight.Bold)
        compose.runOnIdle {
            translationStyle.value = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        assertTypography("Translated lyric", 24, FontWeight.Bold)
        assertTypography("Primary lyric", 28, FontWeight.Bold)
        compose.runOnIdle {
            translationStyle.value = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal)
        }
        assertTypography("Translated lyric", 16, FontWeight.Normal)
    }

    @Test
    fun karaokeTranslationUpdatesWithoutChangingPhoneticText() {
        val translationStyle = mutableStateOf(TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Normal))
        val line = KaraokeLine.MainKaraokeLine(
            syllables = listOf(KaraokeSyllable("Primary lyric", 1_000, 5_000)),
            translation = "Translated lyric",
            alignment = KaraokeAlignment.Start,
            start = 1_000,
            end = 5_000,
            phonetic = "Phonetic lyric",
        )
        compose.setContent { TranslationTestContent(line, translationStyle.value) }

        assertTypography("Translated lyric", 18, FontWeight.Normal)
        assertTypography("Phonetic lyric", 13, FontWeight.Normal)
        compose.runOnIdle {
            translationStyle.value = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        assertTypography("Translated lyric", 22, FontWeight.Bold)
        assertTypography("Phonetic lyric", 13, FontWeight.Normal)
    }

    @Composable
    private fun TranslationTestContent(line: ISyncedLine, translationStyle: TextStyle) {
        MaterialTheme {
            Box(Modifier.width(320.dp).height(480.dp)) {
                PlayerLyricsView(
                    listState = rememberLazyListState(),
                    lyrics = SyncedLyrics(lines = listOf(line)),
                    currentPosition = { 3_500 },
                    onLineClicked = {},
                    onLinePressed = {},
                    isManualScrolling = false,
                    normalLineTextStyle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
                    phoneticTextStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal),
                    translationLineTextStyle = translationStyle,
                    useBlurEffect = false,
                    offset = 32.dp,
                    keepAliveZone = 0.dp,
                )
            }
        }
    }

    @Test
    fun wrappedSyncedTranslationsKeepEnoughSpaceAtLargeFontSizes() {
        assertTranslationLineSpacing(
            SyncedLine("Primary lyric", multilineTranslation, 1_000, 5_000)
        )
    }

    @Test
    fun wrappedKaraokeTranslationsKeepEnoughSpaceAtLargeFontSizes() {
        assertTranslationLineSpacing(
            KaraokeLine.MainKaraokeLine(
                syllables = listOf(KaraokeSyllable("Primary lyric", 1_000, 5_000)),
                translation = multilineTranslation,
                alignment = KaraokeAlignment.Start,
                start = 1_000,
                end = 5_000,
            )
        )
    }

    private fun assertTranslationLineSpacing(line: ISyncedLine) {
        // Reproduce the fixed theme line height inherited by the preference caller.
        val translationStyle = mutableStateOf(TextStyle(fontSize = 18.sp, lineHeight = 24.sp))
        val fontScale = mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                TranslationTestContent(line, translationStyle.value)
            }
        }

        for ((size, scale) in listOf(18 to 1f, 32 to 1f, 32 to 1.5f, 18 to 1f)) {
            compose.runOnIdle {
                translationStyle.value = translationStyle.value.copy(
                    fontSize = size.sp,
                    fontWeight = if (size == 32) FontWeight.Bold else FontWeight.Normal,
                )
                fontScale.value = scale
            }
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(multilineTranslation, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val result = results.single()
            assertTrue("Translation must exercise both wrapping and explicit newlines", result.lineCount >= 3)
            val fontSizePx = with(result.layoutInput.density) { size.sp.toPx() }
            for (index in 1 until result.lineCount) {
                val baselineDistance = result.getLineBaseline(index) - result.getLineBaseline(index - 1)
                assertTrue(
                    "Translation baselines must fit ${size}sp glyphs at font scale $scale",
                    baselineDistance >= fontSizePx,
                )
            }
        }
    }

    private val multilineTranslation =
        "大字号翻译歌词需要自动换行，前后两行文字不应重叠。\n第二段翻译歌词也应保留足够的行距。"

    private fun assertTypography(text: String, sizeSp: Int, weight: FontWeight) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(1, results.size)
        assertEquals(sizeSp.sp, results.single().layoutInput.style.fontSize)
        assertEquals(weight, results.single().layoutInput.style.fontWeight)
    }

    @Test
    fun zeroEdgeFadesRemoveTheMaskFromTheFirstVisibleLine() {
        val fade = mutableStateOf(70.dp)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(260.dp).height(240.dp).background(Color.Black).testTag("edge-fade")) {
                    PlayerLyricsView(
                        listState = rememberLazyListState(),
                        lyrics = SyncedLyrics(lines = listOf(SyncedLine("MMMM", null, 1_000, 5_000))),
                        currentPosition = { 3_500 },
                        onLineClicked = {}, onLinePressed = {}, isManualScrolling = false,
                        normalLineTextStyle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
                        useBlurEffect = false, showPhonetic = false,
                        offset = 0.dp, keepAliveZone = 0.dp,
                        topFadeHeight = fade.value, bottomFadeHeight = fade.value,
                    )
                }
            }
        }
        fun brightestPixel(): Float {
            val pixels = compose.onNodeWithTag("edge-fade").captureToImage().toPixelMap()
            var result = 0f
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                result = maxOf(result, pixels[x, y].luminance())
            }
            return result
        }
        val masked = brightestPixel()
        compose.runOnIdle { fade.value = 0.dp }
        val unmasked = brightestPixel()
        assertTrue("Zero fades must bypass the edge mask", unmasked > masked + 0.15f)
        assertTrue("Unmasked focused text must retain its full brightness", unmasked > 0.95f)
    }

    @Test
    fun lyricEntryTranslationIsAppliedOnceToTheContent() {
        val offset = mutableStateOf(0.dp)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(260.dp).height(300.dp)) {
                    PlayerLyricsView(
                        listState = rememberLazyListState(),
                        lyrics = SyncedLyrics(lines = listOf(SyncedLine("Entry lyric", null, 1_000, 5_000))),
                        currentPosition = { 3_500 },
                        onLineClicked = {}, onLinePressed = {}, isManualScrolling = false,
                        useBlurEffect = false, showPhonetic = false,
                        offset = 0.dp, keepAliveZone = 0.dp,
                        topFadeHeight = 0.dp, bottomFadeHeight = 0.dp,
                        contentModifier = Modifier.graphicsLayer { translationY = offset.value.toPx() },
                    )
                }
            }
        }
        val initial = compose.onNodeWithText("Entry lyric", useUnmergedTree = true).getUnclippedBoundsInRoot().top
        compose.runOnIdle { offset.value = 24.dp }
        val translated = compose.onNodeWithText("Entry lyric", useUnmergedTree = true).getUnclippedBoundsInRoot().top
        assertEquals(24f, (translated - initial).value, 0.5f)
    }

    @Test
    fun aTopOnlyFadeKeepsLyricsNearTheBottomFullyVisible() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(260.dp).height(240.dp).background(Color.Black).testTag("top-only-fade")) {
                    PlayerLyricsView(
                        listState = rememberLazyListState(),
                        lyrics = SyncedLyrics(lines = listOf(SyncedLine("MMMM", null, 1_000, 5_000))),
                        currentPosition = { 3_500 },
                        onLineClicked = {}, onLinePressed = {}, isManualScrolling = false,
                        normalLineTextStyle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
                        useBlurEffect = false, showPhonetic = false,
                        offset = 180.dp, keepAliveZone = 0.dp,
                        topFadeHeight = 100.dp, bottomFadeHeight = 0.dp,
                    )
                }
            }
        }
        compose.onNodeWithText("MMMM", useUnmergedTree = true).assertIsDisplayed()
        val pixels = compose.onNodeWithTag("top-only-fade").captureToImage().toPixelMap()
        var brightest = 0f
        for (y in pixels.height / 2 until pixels.height) for (x in 0 until pixels.width) {
            brightest = maxOf(brightest, pixels[x, y].luminance())
        }
        assertTrue("A zero bottom fade must not dim the lower lyric line", brightest > 0.95f)
    }

    @Test
    fun controlsGradientExposesTheBackgroundAndRestoresLyricsAfterDismissal() {
        val controlsVisible = mutableStateOf(true)
        val background = Color(0.10f, 0.25f, 0.40f)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(260.dp).height(260.dp).background(background).testTag("controls-background")) {
                    PlayerLyricsView(
                        listState = rememberLazyListState(),
                        lyrics = SyncedLyrics(lines = listOf(SyncedLine("MMMM", null, 1_000, 5_000))),
                        currentPosition = { 3_500 },
                        onLineClicked = {}, onLinePressed = {}, isManualScrolling = false,
                        normalLineTextStyle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
                        useBlurEffect = false, showPhonetic = false,
                        offset = 180.dp, keepAliveZone = 0.dp,
                        topFadeHeight = 0.dp, bottomFadeHeight = 0.dp,
                        controlsFadeRange = { height ->
                            val shift = if (controlsVisible.value) 0f else 0.6f
                            ((0.4f + shift) * height)..((0.55f + shift) * height)
                        },
                    )
                }
            }
        }
        fun brightestRed(): Float {
            val pixels = compose.onNodeWithTag("controls-background").captureToImage().toPixelMap()
            var result = 0f
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                result = maxOf(result, pixels[x, y].red)
                assertTrue("The mask must not clear the player's background", pixels[x, y].blue >= background.blue - 0.02f)
            }
            return result
        }
        assertEquals(background.red, brightestRed(), 0.02f)
        compose.runOnIdle { controlsVisible.value = false }
        assertTrue("Lyrics must reappear when controls leave", brightestRed() > background.red + 0.6f)
    }
}
