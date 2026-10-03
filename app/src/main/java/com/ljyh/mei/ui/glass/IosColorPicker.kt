package com.ljyh.mei.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import kotlin.math.floor
import kotlin.math.roundToInt

private val IosColorGrid = listOf(
    listOf(0xFFFEFFFE, 0xFFEBEBEB, 0xFFD6D6D6, 0xFFC2C2C2, 0xFFADADAD, 0xFF999999, 0xFF858585, 0xFF707070, 0xFF5C5C5C, 0xFF474747, 0xFF333333, 0xFF000000),
    listOf(0xFF00374A, 0xFF011D57, 0xFF11053B, 0xFF2E063D, 0xFF3C071B, 0xFF5C0701, 0xFF5A1C00, 0xFF583300, 0xFF563D00, 0xFF666100, 0xFF4F5504, 0xFF263E0F),
    listOf(0xFF004D65, 0xFF012F7B, 0xFF1A0A52, 0xFF450D59, 0xFF551029, 0xFF831100, 0xFF7B2900, 0xFF7A4A00, 0xFF785800, 0xFF8D8602, 0xFF6F760A, 0xFF38571A),
    listOf(0xFF016E8F, 0xFF0042A9, 0xFF2C0977, 0xFF61187C, 0xFF791A3D, 0xFFB51A00, 0xFFAD3E00, 0xFFA96800, 0xFFA67B01, 0xFFC4BC00, 0xFF9BA50E, 0xFF4E7A27),
    listOf(0xFF008CB4, 0xFF0056D6, 0xFF371A94, 0xFF7A219E, 0xFF99244F, 0xFFE22400, 0xFFDA5100, 0xFFD38301, 0xFFD19D01, 0xFFF5EC00, 0xFFC3D117, 0xFF669D34),
    listOf(0xFF00A1D8, 0xFF0061FD, 0xFF4D22B2, 0xFF982ABC, 0xFFB92D5D, 0xFFFF4015, 0xFFFF6A00, 0xFFFFAB01, 0xFFFCC700, 0xFFFEFB41, 0xFFD9EC37, 0xFF76BB40),
    listOf(0xFF01C7FC, 0xFF3A87FD, 0xFF5E30EB, 0xFFBE38F3, 0xFFE63B7A, 0xFFFE6250, 0xFFFE8648, 0xFFFEB43F, 0xFFFECB3E, 0xFFFFF76B, 0xFFE4EF65, 0xFF96D35F),
    listOf(0xFF52D6FC, 0xFF74A7FF, 0xFF864FFD, 0xFFD357FE, 0xFFEE719E, 0xFFFF8C82, 0xFFFEA57D, 0xFFFEC777, 0xFFFED977, 0xFFFFF994, 0xFFEAF28F, 0xFFB1DD8B),
    listOf(0xFF93E3FC, 0xFFA7C6FF, 0xFFB18CFE, 0xFFE292FE, 0xFFF4A4C0, 0xFFFFB5AF, 0xFFFFC5AB, 0xFFFED9A8, 0xFFFDE4A8, 0xFFFFFBB9, 0xFFF1F7B7, 0xFFCDE8B5),
    listOf(0xFFCBF0FF, 0xFFD2E2FE, 0xFFD8C9FE, 0xFFEFCafe, 0xFFF9D3E0, 0xFFFFDAD8, 0xFFFFE2D6, 0xFFFEecd4, 0xFFFEF1D5, 0xFFFDFBDD, 0xFFF6FADB, 0xFFDEEED4),
).map { row -> row.map(::Color) }

/** Figma nodes 3147:15989 and 3147:15999. */
@Composable
fun IosColorPicker(
    visible: Boolean,
    selectedColor: Color,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit,
    title: String,
) {
    if (!visible) return
    var mode by remember { mutableIntStateOf(0) }
    var opacity by remember { mutableFloatStateOf(selectedColor.alpha) }
    var color by remember { mutableStateOf(selectedColor) }
    val previewColor = color.copy(alpha = 1f)
    val quickColors = listOf(0xFF34C759, 0xFFFFCC00, 0xFFFF8D28, 0xFFFF383C, 0xFFCB30E0, 0xFF0088FF, 0xFF6155F5, 0xFFFF2D55, 0xFFAC7F5E).map(::Color)

    IosModalSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
                Box(Modifier.fillMaxWidth().height(44.dp)) {
                    SfIcon("eyedropper", null, Modifier.align(Alignment.CenterStart), size = 22.dp, tint = LocalGlassColors.current.secondaryContent)
                    Text(title, style = IosTypography.headline, modifier = Modifier.align(Alignment.Center))
                    Box(Modifier.align(Alignment.CenterEnd).size(44.dp).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                        SfIcon("xmark", null, size = 20.dp, tint = LocalGlassColors.current.secondaryContent)
                    }
                }
                GlassSegmentedControl(
                    items = listOf(
                        0 to stringResource(R.string.color_picker_grid),
                        1 to stringResource(R.string.color_picker_spectrum),
                        2 to stringResource(R.string.color_picker_sliders),
                    ),
                    selected = mode,
                    onSelected = { mode = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                when (mode) {
                    0 -> Column(Modifier.fillMaxWidth().height(296.dp).clip(ContinuousRoundedRectangle(10.dp))) {
                        IosColorGrid.forEach { row ->
                            Row(Modifier.fillMaxWidth().weight(1f)) {
                                row.forEach { swatch ->
                                    Box(
                                        Modifier.weight(1f).fillMaxSize().background(swatch).clickable {
                                            color = swatch.copy(alpha = opacity)
                                            onColorSelected(color)
                                        },
                                    ) {
                                        if (swatch.toArgb() == color.copy(alpha = 1f).toArgb()) {
                                            Box(Modifier.fillMaxSize().border(3.dp, Color.White))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    1 -> SpectrumPicker(color) { next -> color = next.copy(alpha = opacity); onColorSelected(color) }
                    else -> SliderPicker(color) { next -> color = next.copy(alpha = opacity); onColorSelected(color) }
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.color_picker_opacity).uppercase(), style = IosTypography.caption)
                Row(
                    Modifier.padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    IosOpacitySlider(
                        value = opacity,
                        color = previewColor,
                        onValueChange = { next ->
                            opacity = next
                            color = color.copy(alpha = next)
                            onColorSelected(color)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier
                            .background(LocalGlassColors.current.elevatedBackground, ContinuousRoundedRectangle(8.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text("${(opacity * 100).toInt()}%", style = IosTypography.headline)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(LocalGlassColors.current.separator))
                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(30.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(Modifier.size(75.dp).clip(ContinuousRoundedRectangle(10.dp)).background(previewColor))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                        quickColors.chunked(5).forEachIndexed { rowIndex, swatches ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                swatches.forEach { swatch ->
                                    ColorDot(swatch, color, opacity) { next ->
                                        color = next
                                        onColorSelected(next)
                                    }
                                }
                                if (rowIndex == 1) {
                                    Box(
                                        Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (LocalGlassColors.current.isDark) Color(0xFF2C2C2E)
                                                else Color(0xFFE5E5EA),
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) { SfIcon("plus", null, size = 17.dp) }
                                }
                            }
                        }
                    }
                }
        }
    }
}

@Composable
private fun IosOpacitySlider(
    value: Float,
    color: Color,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier.height(34.dp),
    ) {
        val widthPx = constraints.maxWidth
        Box(
            Modifier
            .fillMaxSize()
            .clip(CircleShape)
            .drawBehind {
                val cell = 8.dp.toPx()
                val columns = (size.width / cell).toInt() + 1
                val rows = (size.height / cell).toInt() + 1
                for (row in 0 until rows) {
                    for (column in 0 until columns) {
                        drawRect(
                            if ((row + column) % 2 == 0) Color.White else Color.Black,
                            topLeft = androidx.compose.ui.geometry.Offset(column * cell, row * cell),
                            size = androidx.compose.ui.geometry.Size(cell, cell),
                        )
                    }
                }
            }
            .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0f), color)))
            .pointerInput(widthPx) {
                fun update(x: Float) = onValueChange((x / size.width).coerceIn(0f, 1f))
                detectTapGestures { update(it.x) }
            }
            .pointerInput(widthPx) {
                fun update(x: Float) = onValueChange((x / size.width).coerceIn(0f, 1f))
                detectHorizontalDragGestures(
                    onDragStart = { update(it.x) },
                    onHorizontalDrag = { change, _ -> update(change.position.x) },
                )
            },
        )
        val knobPx = with(density) { 34.dp.roundToPx() }
        Box(
            Modifier
                .offset { IntOffset(((constraints.maxWidth - knobPx) * value.coerceIn(0f, 1f)).roundToInt(), 0) }
                .size(34.dp)
                .graphicsLayer { shadowElevation = with(density) { 2.dp.toPx() }; shape = CircleShape }
                .background(Color.White, CircleShape)
                .border(3.dp, Color.White, CircleShape),
        )
    }
}

@Composable
private fun ColorDot(
    swatch: Color,
    selectedColor: Color,
    opacity: Float,
    onSelected: (Color) -> Unit,
) {
    val selected = swatch.toArgb() == selectedColor.copy(alpha = 1f).toArgb()
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(swatch).clickable {
            onSelected(swatch.copy(alpha = opacity))
        },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(22.dp).border(2.dp, Color.White, CircleShape))
    }
}

@Composable
private fun SpectrumPicker(color: Color, onColor: (Color) -> Unit) {
    val spectrum = listOf(0xFFFF3B30, 0xFFFF9500, 0xFFFFCC00, 0xFF34C759, 0xFF00C7BE, 0xFF0088FF, 0xFF5856D6, 0xFFAF52DE, 0xFFFF2D55).map(::Color)
    Row(Modifier.fillMaxWidth().height(224.dp).clip(ContinuousRoundedRectangle(10.dp))) {
        spectrum.forEach { swatch -> Box(Modifier.weight(1f).fillMaxSize().background(swatch).clickable { onColor(swatch) }) }
    }
}

@Composable
private fun SliderPicker(color: Color, onColor: (Color) -> Unit) {
    Column(Modifier.fillMaxWidth().height(224.dp), verticalArrangement = Arrangement.SpaceEvenly) {
        listOf("R" to color.red, "G" to color.green, "B" to color.blue).forEachIndexed { index, (label, value) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = IosTypography.headline, modifier = Modifier.width(24.dp))
                GlassSlider(value, { next ->
                    onColor(when (index) { 0 -> color.copy(red = next); 1 -> color.copy(green = next); else -> color.copy(blue = next) })
                }, Modifier.weight(1f))
            }
        }
    }
}
