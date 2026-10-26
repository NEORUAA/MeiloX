package com.ljyh.mei.ui.component.player.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.constants.LyricAutoFollowEnabledKey
import com.ljyh.mei.constants.LyricGlowEnabledKey
import com.ljyh.mei.constants.LyricLiftEnabledKey
import com.ljyh.mei.constants.LyricLongToneEnabledKey
import com.ljyh.mei.constants.LyricRomanizationEnabledKey
import com.ljyh.mei.constants.LyricTranslationEnabledKey
import com.ljyh.mei.constants.LyricVisualStyle
import io.github.neoruaa.glasmio.glass.GlassSurface
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.utils.rememberPreference
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.delay

@Composable
fun LiquidLyricsView(
    style: LyricVisualStyle,
    lyricData: LyricData,
    positionMs: () -> Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    onLineLongPress: (ISyncedLine) -> Unit,
    modifier: Modifier = Modifier,
) {
    var position by remember { mutableIntStateOf(positionMs().toInt()) }
    val (translationEnabled) = rememberPreference(LyricTranslationEnabledKey, true)
    val (romanizationEnabled) = rememberPreference(LyricRomanizationEnabledKey, true)
    val (glowEnabled) = rememberPreference(LyricGlowEnabledKey, true)
    val (liftEnabled) = rememberPreference(LyricLiftEnabledKey, true)
    val (longToneEnabled) = rememberPreference(LyricLongToneEnabledKey, true)
    val (autoFollowEnabled) = rememberPreference(LyricAutoFollowEnabledKey, true)
    LaunchedEffect(isPlaying) {
        while (true) {
            position = positionMs().toInt()
            delay(if (isPlaying) 33 else 100)
        }
    }
    val lines = lyricData.lyricLine.lines
    when (style) {
        LyricVisualStyle.AppleMusic -> AppleLiquidLyrics(
            lines, position, onSeek, onLineLongPress, translationEnabled, romanizationEnabled,
            glowEnabled, liftEnabled, longToneEnabled, autoFollowEnabled, modifier,
        )
        LyricVisualStyle.EVA -> EvaLiquidLyrics(lines, position, onSeek, onLineLongPress, translationEnabled, romanizationEnabled, modifier)
        LyricVisualStyle.TextPV -> TextPvLiquidLyrics(lines, position, onSeek, onLineLongPress, translationEnabled, romanizationEnabled, modifier)
        LyricVisualStyle.Skyline -> SkylineLiquidLyrics(lines, position, onSeek, onLineLongPress, translationEnabled, romanizationEnabled, modifier)
    }
}

@Composable
private fun AppleLiquidLyrics(
    lines: List<ISyncedLine>,
    position: Int,
    onSeek: (Long) -> Unit,
    onLineLongPress: (ISyncedLine) -> Unit,
    showTranslation: Boolean,
    showRomanization: Boolean,
    glow: Boolean,
    lift: Boolean,
    longTone: Boolean,
    autoFollow: Boolean,
    modifier: Modifier,
) {
    val activeIndex = activeLineIndex(lines, position)
    val state = rememberLazyListState()
    LaunchedEffect(activeIndex, autoFollow) {
        if (autoFollow && activeIndex >= 0) state.animateScrollToItem(activeIndex)
    }
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 120.dp, horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        itemsIndexed(lines, key = { index, line -> "${line.start}-$index" }) { index, line ->
            val active = index == activeIndex
            val focusedScale by animateFloatAsState(if (active && lift) 1.04f else 1f, label = "lyricLift")
            Column(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { scaleX = focusedScale; scaleY = focusedScale }
                    .alpha(if (active) 1f else 0.42f)
                    .then(if (active && glow) Modifier.lyricGlow() else Modifier)
                    .combinedClickable(
                        onClick = { onSeek(line.start.toLong()) },
                        onLongClick = { onLineLongPress(line) },
                    ),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (line is KaraokeLine) {
                    KaraokeRevealText(
                        line = line,
                        position = position,
                        active = active,
                        longTone = longTone,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp),
                    )
                } else {
                    Text(line.content(), fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 31.sp)
                }
                if (showRomanization) line.phonetic()?.takeIf(String::isNotBlank)?.let {
                    Text(it, color = Color.White.copy(alpha = 0.72f), fontSize = 14.sp)
                }
                if (showTranslation) line.translation()?.takeIf(String::isNotBlank)?.let {
                    Text(it, color = Color.White.copy(alpha = 0.72f), fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun EvaLiquidLyrics(
    lines: List<ISyncedLine>,
    position: Int,
    onSeek: (Long) -> Unit,
    onLineLongPress: (ISyncedLine) -> Unit,
    showTranslation: Boolean,
    showRomanization: Boolean,
    modifier: Modifier,
) {
    val index = activeLineIndex(lines, position)
    val active = lines.getOrNull(index)
    val next = lines.getOrNull(index + 1)
    val previous = lines.getOrNull(index - 1)
    Box(modifier.fillMaxSize().padding(20.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val green = Color(0xFF8BFFB0)
            drawLine(green.copy(alpha = 0.55f), start = androidx.compose.ui.geometry.Offset(0f, size.height * .33f), end = androidx.compose.ui.geometry.Offset(size.width, size.height * .33f), strokeWidth = 1.dp.toPx())
            drawLine(green.copy(alpha = 0.35f), start = androidx.compose.ui.geometry.Offset(size.width * .16f, 0f), end = androidx.compose.ui.geometry.Offset(size.width * .16f, size.height), strokeWidth = 1.dp.toPx())
        }
        Text("EVA // LYRIC SYSTEM", color = Color(0xFF8BFFB0), fontSize = 11.sp, modifier = Modifier.align(Alignment.TopStart))
        previous?.let { Text(it.content(), color = Color.White.copy(.25f), fontSize = 16.sp, maxLines = 2, modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(.65f), textAlign = TextAlign.End) }
        active?.let { line ->
            Column(
                Modifier.align(Alignment.CenterStart).fillMaxWidth().combinedClickable(
                    onClick = { onSeek(line.start.toLong()) },
                    onLongClick = { onLineLongPress(line) },
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(line.content(), color = Color(0xFF8BFFB0), fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.Black)
                if (showRomanization) line.phonetic()?.let { Text(it.uppercase(), color = Color(0xFFFFB1D6), fontSize = 13.sp) }
                if (showTranslation) line.translation()?.let { Text(it, color = Color.White.copy(.7f), fontSize = 15.sp) }
            }
        }
        next?.let { Text("NEXT  ${it.content()}", color = Color.White.copy(.45f), fontSize = 15.sp, maxLines = 2, modifier = Modifier.align(Alignment.BottomEnd).fillMaxWidth(.74f), textAlign = TextAlign.End) }
    }
}

@Composable
private fun TextPvLiquidLyrics(
    lines: List<ISyncedLine>,
    position: Int,
    onSeek: (Long) -> Unit,
    onLineLongPress: (ISyncedLine) -> Unit,
    showTranslation: Boolean,
    showRomanization: Boolean,
    modifier: Modifier,
) {
    val index = activeLineIndex(lines, position)
    val line = lines.getOrNull(index)
    val phase = if (line == null || line.duration <= 0) 0f else ((position - line.start).toFloat() / line.duration).coerceIn(0f, 1f)
    Box(
        modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0xFF120C2C), Color(0xFF43296B), Color(0xFFEA6688))),
        ).padding(22.dp),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            repeat(16) { index ->
                val x = size.width * ((index * .173f + phase * .3f) % 1f)
                val y = size.height * ((index * .287f + phase * .18f) % 1f)
                drawCircle(Color.White.copy(alpha = .08f + (index % 4) * .03f), radius = (5 + index % 5).dp.toPx(), center = androidx.compose.ui.geometry.Offset(x, y))
            }
        }
        Text("TEXT PV  /  %02d".format((index + 1).coerceAtLeast(0)), fontSize = 11.sp, color = Color.White.copy(.6f), modifier = Modifier.align(Alignment.TopEnd))
        line?.let {
            GlassSurface(
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().combinedClickable(
                    onClick = { onSeek(it.start.toLong()) },
                    onLongClick = { onLineLongPress(it) },
                ),
                shape = ContinuousRoundedRectangle(30.dp),
            ) {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(it.content(), fontSize = 33.sp, lineHeight = 39.sp, fontWeight = FontWeight.Black)
                    if (showRomanization) it.phonetic()?.let { text -> Text(text, color = Color(0xFFFFD4E2), fontSize = 14.sp) }
                    if (showTranslation) it.translation()?.let { text -> Text(text, color = Color.White.copy(.72f), fontSize = 15.sp) }
                }
            }
        }
        Row(
            modifier = Modifier.align(Alignment.BottomStart),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            listOf("◆", "◇", "◆").forEach { symbol ->
                Text(symbol, color = Color.White.copy(.45f))
            }
        }
    }
}

@Composable
private fun SkylineLiquidLyrics(
    lines: List<ISyncedLine>,
    position: Int,
    onSeek: (Long) -> Unit,
    onLineLongPress: (ISyncedLine) -> Unit,
    showTranslation: Boolean,
    showRomanization: Boolean,
    modifier: Modifier,
) {
    val index = activeLineIndex(lines, position)
    val active = lines.getOrNull(index)
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF04111C), Color(0xFF0D2630), Color(0xFF171526))))) {
        Canvas(Modifier.fillMaxSize()) {
            val horizon = size.height * .58f
            val glow = Color(0xFF70E4FF)
            repeat(5) { layer ->
                val alpha = .18f - layer * .025f
                val points = 90
                var previous = androidx.compose.ui.geometry.Offset(0f, horizon)
                repeat(points) { point ->
                    val x = size.width * point / (points - 1)
                    val wave = sin((point * .17f + position / 1400f + layer) * PI).toFloat()
                    val y = horizon + wave * (18 + layer * 9).dp.toPx()
                    val next = androidx.compose.ui.geometry.Offset(x, y)
                    drawLine(glow.copy(alpha = alpha), previous, next, strokeWidth = (2 - layer * .25f).dp.toPx(), cap = StrokeCap.Round)
                    previous = next
                }
            }
        }
        active?.let {
            Column(
                Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 28.dp).combinedClickable(
                    onClick = { onSeek(it.start.toLong()) },
                    onLongClick = { onLineLongPress(it) },
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(it.content(), textAlign = TextAlign.Center, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
                if (showRomanization) it.phonetic()?.let { text -> Text(text, color = Color(0xFF87E8FF), fontSize = 14.sp, textAlign = TextAlign.Center) }
                if (showTranslation) it.translation()?.let { text -> Text(text, color = Color.White.copy(.68f), fontSize = 15.sp, textAlign = TextAlign.Center) }
            }
        }
        lines.getOrNull(index + 1)?.let {
            Text(it.content(), color = Color.White.copy(.28f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp))
        }
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun KaraokeRevealText(
    line: KaraokeLine,
    position: Int,
    active: Boolean,
    longTone: Boolean,
    style: androidx.compose.ui.text.TextStyle,
) {
    val textMeasurer = rememberTextMeasurer()
    val text = line.syllables.joinToString("") { it.content }
    val progress = if (!active) if (position > line.end) 1f else 0f else line.progress(position)
    val measured = remember(text, style) { textMeasurer.measure(AnnotatedString(text), style = style) }
    val heldSyllable = if (longTone && active) line.syllables.firstOrNull { position in it.start..it.end && it.duration > 600 } else null
    Canvas(Modifier.fillMaxWidth().heightIn(min = 40.dp)) {
        drawText(measured, color = Color.White.copy(alpha = .32f))
        clipRect(right = measured.size.width * progress) {
            drawText(measured, color = Color.White)
        }
        heldSyllable?.let {
            drawCircle(Color.White.copy(.2f), radius = (10 + 7 * it.progress(position)).dp.toPx(), center = androidx.compose.ui.geometry.Offset(measured.size.width * progress, measured.size.height * .5f))
        }
    }
}

private fun activeLineIndex(lines: List<ISyncedLine>, position: Int): Int {
    val direct = lines.indexOfLast { position >= it.start }
    return direct.coerceAtLeast(0).coerceAtMost((lines.size - 1).coerceAtLeast(0))
}

private fun ISyncedLine.content(): String = when (this) {
    is KaraokeLine -> syllables.joinToString("") { it.content }
    is SyncedLine -> content
    else -> ""
}

private fun ISyncedLine.translation(): String? = when (this) {
    is KaraokeLine -> translation
    is SyncedLine -> translation
    else -> null
}

private fun ISyncedLine.phonetic(): String? = when (this) {
    is KaraokeLine -> phonetic ?: syllables.mapNotNull(KaraokeSyllable::phonetic).takeIf(List<String>::isNotEmpty)?.joinToString("")
    else -> null
}

private fun Modifier.lyricGlow(): Modifier = drawWithContent {
    drawContent()
    drawRect(
        brush = Brush.radialGradient(listOf(Color.White.copy(.10f), Color.Transparent), radius = size.maxDimension * .62f),
        blendMode = androidx.compose.ui.graphics.BlendMode.Plus,
    )
}
