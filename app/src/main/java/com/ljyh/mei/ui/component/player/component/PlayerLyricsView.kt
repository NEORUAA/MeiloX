/*
 * Copyright 2025 6xingyv
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Adapted from KaraokeLyricsView in com.mocharealm.accompanist:lyrics-ui:1.0.19.
 * Upstream: https://github.com/6xingyv/accompanist-lyrics-ui
 * Modified for Mei: scrolling is owned by the player, which supplies manual
 * browsing state; upstream line rendering and keep-alive layout are retained.
 */

package com.ljyh.mei.ui.component.player.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.ljyh.mei.utils.lyric.karaokeRenderTimeWindow
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeBreathingDots
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeBreathingDotsDefaults
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeLineText
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.LyricsLineItem
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.SyllableLayout
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.SyncedLineText
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.measureSyllablesAndDetermineAnimation
import com.mocharealm.accompanist.lyrics.ui.utils.isRtl
import com.mocharealm.accompanist.lyrics.ui.utils.modifier.springPlacement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private data class FocusState(
    val firstIndex: Int,
    val allIndices: List<Int>,
    val activeInterludeIndex: Int?,
    val activeIntro: Boolean
)

/** Renders lyrics while the caller owns automatic scrolling and manual browsing state. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun PlayerLyricsView(
    listState: LazyListState,
    lyrics: SyncedLyrics,
    currentPosition: () -> Int,
    onLineClicked: (ISyncedLine) -> Unit,
    onLinePressed: (ISyncedLine) -> Unit,
    isManualScrolling: Boolean,
    modifier: Modifier = Modifier,
    listModifier: Modifier = modifier,
    normalLineTextStyle: TextStyle = LocalTextStyle.current.copy(
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        textMotion = TextMotion.Animated,
    ),
    accompanimentLineTextStyle: TextStyle = LocalTextStyle.current.copy(
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        textMotion = TextMotion.Animated,
    ),
    textColor: Color = Color.White,
    breathingDotsDefaults: KaraokeBreathingDotsDefaults = KaraokeBreathingDotsDefaults(),
    phoneticTextStyle: TextStyle = normalLineTextStyle.copy(
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
    ),
    blendMode: BlendMode = BlendMode.Plus,
    useBlurEffect: Boolean = true,
    showTranslation: Boolean = true,
    showPhonetic: Boolean = true,
    isVerbatim: Boolean = true,
    offset: Dp = 32.dp,
    keepAliveZone: Dp = 100.dp,
    blurDelta: Float = 3f,
    showDebugRectangles: Boolean = false,
    placementGeneration: Int = 0,
    translationLineTextStyle: TextStyle = LocalTextStyle.current,
    topFadeHeight: Dp = 20.dp,
    bottomFadeHeight: Dp = 100.dp,
    contentModifier: Modifier = Modifier,
    controlsFadeRange: (Float) -> ClosedFloatingPointRange<Float>? = { null },
) {
    val stableNormalTextStyle = remember(normalLineTextStyle) { normalLineTextStyle }
    val stableAccompanimentTextStyle =
        remember(accompanimentLineTextStyle) { accompanimentLineTextStyle }
    val stablePhoneticTextStyle = remember(phoneticTextStyle) { phoneticTextStyle }
    // Theme line heights are fixed in sp and can be smaller than the selected font.
    val translationTextStyle = translationLineTextStyle.copy(lineHeight = 1.2.em)
    val stableOffset = remember(offset) { offset }
    val stableBlendMode = remember(blendMode) { blendMode }

    val textMeasurer = rememberTextMeasurer()
    val layoutCache = remember(
        lyrics,
        isVerbatim,
        stableNormalTextStyle,
        stableAccompanimentTextStyle,
        stablePhoneticTextStyle,
        textMeasurer
    ) { mutableStateMapOf<Int, List<SyllableLayout>>() }

    LaunchedEffect(
        lyrics,
        isVerbatim,
        stableNormalTextStyle,
        stableAccompanimentTextStyle,
        stablePhoneticTextStyle,
        textMeasurer
    ) {
        if (!isVerbatim) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            val normalStyle = stableNormalTextStyle.copy(textDirection = TextDirection.Content)
            val accompanimentStyle =
                stableAccompanimentTextStyle.copy(textDirection = TextDirection.Content)
            val phoneticStyle = stablePhoneticTextStyle.copy(textDirection = TextDirection.Content)

            val normalSpaceWidth = textMeasurer.measure(" ", normalStyle).size.width.toFloat()
            val accompanimentSpaceWidth =
                textMeasurer.measure(" ", accompanimentStyle).size.width.toFloat()

            lyrics.lines.forEachIndexed { index, line ->
                if (!isActive) return@forEachIndexed
                if (line is KaraokeLine) {
                    val style =
                        if (line is KaraokeLine.AccompanimentKaraokeLine) accompanimentStyle else normalStyle
                    val spaceWidth =
                        if (line is KaraokeLine.AccompanimentKaraokeLine) accompanimentSpaceWidth else normalSpaceWidth

                    val processedSyllables = if (line.alignment == KaraokeAlignment.End) {
                        line.syllables.dropLastWhile { it.content.isBlank() }
                    } else {
                        line.syllables
                    }

                    val layout = measureSyllablesAndDetermineAnimation(
                        syllables = processedSyllables,
                        textMeasurer = textMeasurer,
                        style = style,
                        phoneticStyle = phoneticStyle,
                        isAccompanimentLine = line is KaraokeLine.AccompanimentKaraokeLine,
                        spaceWidth = spaceWidth
                    )

                    withContext(Dispatchers.Main) {
                        layoutCache[index] = layout
                    }
                }
            }
        }
    }

    val currentTimeMs: () -> Int = currentPosition

    val timeProvider = remember { currentPosition }

    val accompanimentToMainMap = remember(lyrics.lines) {
        lyricAccompanimentOwners(lyrics.lines)
    }

    val firstLine = lyrics.lines.firstOrNull()

    val haveDotsIntro by remember(firstLine) {
        derivedStateOf {
            if (firstLine == null) false
            else (firstLine.start > 5000)
        }
    }

    val lyricsFocusState by remember(lyrics, accompanimentToMainMap, haveDotsIntro) {
        derivedStateOf {
            val time = currentTimeMs()
            val activeIndices = activeMainLyricLineIndices(lyrics.lines, time, accompanimentToMainMap)
            val first = lyricFocusLineIndex(lyrics.lines, time, accompanimentToMainMap)

            val activeInterludeIndex = lyrics.lines.indices.find { index ->
                val line = lyrics.lines[index]
                val previousLine = lyrics.lines.getOrNull(index - 1)
                previousLine != null && (line.start - previousLine.end > 5000) && time in previousLine.end..line.start
            }
            val activeIntro = haveDotsIntro && time in 0 until (firstLine?.start ?: 0)

            FocusState(first, activeIndices, activeInterludeIndex, activeIntro)
        }
    }

    val currentLyrics = lyrics
    LookaheadScope {
        Crossfade(lyrics to isVerbatim) { (lyrics, isVerbatim) ->
            Box(modifier = modifier.clipToBounds()) {
                LazyColumn(
                    state = listState,
                    modifier = listModifier
                        .fillMaxSize()
                        .then(contentModifier)
                        .graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawWithCache {
                            onDrawWithContent {
                                drawContent()
                                if (topFadeHeight > 0.dp || bottomFadeHeight > 0.dp) {
                                    val topFade = topFadeHeight.toPx() / size.height
                                    val bottomFade = bottomFadeHeight.toPx() / size.height
                                    drawRect(
                                        brush = Brush.verticalGradient(
                                            0f to if (topFade > 0f) Color.Transparent else Color.Black,
                                            topFade to Color.Black,
                                            1f - bottomFade to Color.Black,
                                            1f to if (bottomFade > 0f) Color.Transparent else Color.Black,
                                        ),
                                        blendMode = BlendMode.DstIn
                                    )
                                }
                                controlsFadeRange(size.height)?.let { range ->
                                    if (range.start < size.height && range.endInclusive > range.start) {
                                        // Fade lyric pixels while preserving the live player background.
                                        drawRect(
                                            brush = Brush.verticalGradient(
                                                colors = listOf(Color.Black, Color.Transparent),
                                                startY = range.start,
                                                endY = range.endInclusive,
                                            ),
                                            blendMode = BlendMode.DstIn,
                                        )
                                    }
                                }
                            }
                        }
                        .layout { measurable, constraints ->
                            val extraHeightPx = (keepAliveZone * 2).roundToPx()

                            val placeable = measurable.measure(
                                constraints.copy(
                                    maxHeight = constraints.maxHeight + extraHeightPx
                                )
                            )

                            layout(constraints.maxWidth, constraints.maxHeight) {
                                placeable.place(0, -(keepAliveZone.roundToPx()))
                            }
                        },
                    contentPadding = PaddingValues(vertical = stableOffset + keepAliveZone)
                ) {
                    itemsIndexed(
                        items = lyrics.lines,
                        key = { index, line -> "${line.start}-${line.end}-$index" }
                    ) { index, line ->
                        val isCurrentFocusLine = index in lyricsFocusState.allIndices
                        val isLineRtl =
                            when (line) {
                                is KaraokeLine -> {
                                    remember(line.syllables) { line.syllables.any { it.content.isRtl() } }
                                }

                                else -> false
                            }
                        val isLineRightAligned = when (line) {
                            is KaraokeLine -> {
                                remember { line.alignment == KaraokeAlignment.End }
                            }

                            else -> false
                        }
                        val isVisualRightAligned = remember(isLineRightAligned, isLineRtl) {
                            if (isLineRightAligned) !isLineRtl
                            else isLineRtl
                        }

                        val distanceWeightState = remember(useBlurEffect, lyricsFocusState) {
                            derivedStateOf {
                                val start = lyricsFocusState.allIndices.firstOrNull() ?: lyricsFocusState.firstIndex
                                val end = lyricsFocusState.allIndices.lastOrNull() ?: lyricsFocusState.firstIndex
                                maxOf(0, start - index, index - end)
                            }
                        }

                        val dynamicStiffness by remember(distanceWeightState.value) {
                            derivedStateOf {
                                (120f - (distanceWeightState.value * 20f)).coerceAtLeast(20f)
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .springPlacement(
                                    this@LookaheadScope,
                                    // A new key resets this node's spring without recreating the renderer.
                                    "${line.start}-${line.end}-$index-$placementGeneration",
                                    isManualScrolling,
                                    stiffness = dynamicStiffness
                                ),
                            horizontalAlignment = if (isVisualRightAligned) Alignment.End else Alignment.Start
                        ) {
                            val previousLine = lyrics.lines.getOrNull(index - 1)

                            val showDotsInterlude = lyricsFocusState.activeInterludeIndex == index
                            val showDotsIntro = lyricsFocusState.activeIntro && index == 0

                            AnimatedVisibility(showDotsInterlude || showDotsIntro) {
                                KaraokeBreathingDots(
                                    alignment = when (val line = previousLine ?: firstLine) {
                                        is KaraokeLine -> line.alignment
                                        is SyncedLine -> if (line.content.isRtl()) KaraokeAlignment.End else KaraokeAlignment.Start
                                        else -> KaraokeAlignment.Start
                                    },
                                    startTimeMs = previousLine?.end ?: 0,
                                    endTimeMs = if (showDotsIntro) firstLine!!.start else line.start,
                                    currentTimeProvider = timeProvider,
                                    defaults = breathingDotsDefaults,
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            }


                            val blurRadiusState = animateFloatAsState(
                                targetValue = (
                                        if (!useBlurEffect) 0f
                                        else if (distanceWeightState.value > 0 && !isManualScrolling) {
                                            distanceWeightState.value * blurDelta
                                        } else 0f),
                                animationSpec = tween(300),
                            )

                            when (line) {
                                is KaraokeLine -> {
                                    if (line is KaraokeLine.MainKaraokeLine) {
                                        LyricsLineItem(
                                            isFocused = isCurrentFocusLine,
                                            isRightAligned = isVisualRightAligned,
                                            onLineClicked = { onLineClicked(line) },
                                            onLinePressed = { onLinePressed(line) },
                                            blurRadius = { blurRadiusState.value },
                                            blendMode = stableBlendMode,
                                        ) {
                                            if (!isVerbatim) {
                                                // Pronunciation wraps LRC in KaraokeLine without adding word timing.
                                                LineSyncedPhoneticText(
                                                    line = line,
                                                    isRightAligned = isVisualRightAligned,
                                                    textStyle = stableNormalTextStyle,
                                                    translationTextStyle = translationTextStyle,
                                                    phoneticTextStyle = stablePhoneticTextStyle,
                                                    textColor = textColor,
                                                    blendMode = stableBlendMode,
                                                    showTranslation = showTranslation,
                                                    showPhonetic = showPhonetic,
                                                )
                                            } else {
                                                val renderTimeProvider = remember(line, timeProvider) {
                                                    val window = karaokeRenderTimeWindow(line)
                                                    if (window == null) timeProvider else {
                                                        // Reuse static row pixels; every active animation
                                                        // still receives the original playback clock.
                                                        val renderTime = derivedStateOf { window.sample(timeProvider()) }
                                                        val provider: () -> Int = { renderTime.value }
                                                        provider
                                                    }
                                                }
                                                // Outgoing content must not use the incoming lyric's syllable measurements.
                                                val precalculatedLayouts = if (lyrics == currentLyrics) layoutCache[index] else null
                                                // The upstream renderer remembers styles and wrapping without all inputs.
                                                key(
                                                    stableNormalTextStyle,
                                                    stableAccompanimentTextStyle,
                                                    stablePhoneticTextStyle,
                                                    textMeasurer,
                                                    precalculatedLayouts
                                                ) {
                                                    // Translation Text inherits this style; primary and phonetic text use explicit styles.
                                                    CompositionLocalProvider(LocalTextStyle provides translationTextStyle) {
                                                        KaraokeLineText(
                                                            line = line,
                                                            currentTimeProvider = renderTimeProvider,
                                                            normalLineTextStyle = stableNormalTextStyle,
                                                            accompanimentLineTextStyle = stableAccompanimentTextStyle,
                                                            phoneticTextStyle = stablePhoneticTextStyle,
                                                            activeColor = textColor,
                                                            blendMode = stableBlendMode,
                                                            showDebugRectangles = showDebugRectangles,
                                                            showTranslation = showTranslation,
                                                            showPhonetic = showPhonetic,
                                                            precalculatedLayouts = precalculatedLayouts,
                                                            textMeasurer = textMeasurer
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                is SyncedLine -> {
                                    val isLineRtl = remember(line.content) { line.content.isRtl() }
                                    LyricsLineItem(
                                        isFocused = isCurrentFocusLine,
                                        isRightAligned = isLineRtl,
                                        onLineClicked = { onLineClicked(line) },
                                        onLinePressed = { onLinePressed(line) },
                                        blurRadius = { blurRadiusState.value },
                                        blendMode = stableBlendMode,
                                    ) {
                                        // Translation Text inherits this style; primary and phonetic text use explicit styles.
                                        CompositionLocalProvider(LocalTextStyle provides translationTextStyle) {
                                            SyncedLineText(
                                                line = line,
                                                isLineRtl = isLineRtl,
                                                textStyle = stableNormalTextStyle.copy(lineHeight = 1.2.em),
                                                textColor = textColor,
                                                showTranslation = showTranslation,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item("BottomSpacing") {
                        Spacer(
                            modifier = Modifier.fillMaxWidth().height(2000.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LineSyncedPhoneticText(
    line: KaraokeLine.MainKaraokeLine,
    isRightAligned: Boolean,
    textStyle: TextStyle,
    translationTextStyle: TextStyle,
    phoneticTextStyle: TextStyle,
    textColor: Color,
    blendMode: BlendMode,
    showTranslation: Boolean,
    showPhonetic: Boolean,
) {
    val content = remember(line.syllables) { line.syllables.joinToString("") { it.content } }
    val textAlign = if (isRightAligned) TextAlign.End else TextAlign.Start
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = if (isRightAligned) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = content,
            style = textStyle.copy(lineHeight = 1.2.em),
            color = textColor,
            textAlign = textAlign,
        )
        if (showTranslation) {
            line.translation?.let {
                Text(
                    text = it,
                    style = translationTextStyle,
                    color = textColor.copy(alpha = 0.4f),
                    textAlign = textAlign,
                    modifier = Modifier.graphicsLayer { this.blendMode = blendMode },
                )
            }
        }
        if (showPhonetic) {
            line.phonetic?.let {
                Text(
                    text = it,
                    style = phoneticTextStyle.copy(lineHeight = 1.2.em),
                    color = textColor.copy(alpha = 0.6f),
                    textAlign = textAlign,
                    modifier = Modifier.graphicsLayer { this.blendMode = blendMode },
                )
            }
        }
    }
}
