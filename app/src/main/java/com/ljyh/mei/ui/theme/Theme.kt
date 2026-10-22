package com.ljyh.mei.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.constants.DefaultInterfaceLetterSpacing
import com.ljyh.mei.constants.InterfaceLetterSpacingKey
import com.ljyh.mei.utils.color.ColorExtractionUtils
import com.ljyh.mei.utils.rememberPreference

/** Raw custom or dynamic accent; null selects the current mode's default accent. */
val LocalAccentSeedColor = staticCompositionLocalOf<Color?> { null }
private val LocalDynamicAccent = staticCompositionLocalOf { false }
val LocalInterfaceLetterSpacing = compositionLocalOf { DefaultInterfaceLetterSpacing.sp }

private val AppShapes = Shapes(
    extraSmall = ContinuousRoundedRectangle(2.dp),
    small = ContinuousRoundedRectangle(4.dp),
    medium = ContinuousRoundedRectangle(8.dp),
    large = ContinuousRoundedRectangle(16.dp),
    extraLarge = ContinuousRoundedRectangle(32.dp)
)


@Composable
fun MusicTheme(
    seedColor: Color,
    isDark: Boolean = isSystemInDarkTheme(),
    dynamicAccent: Boolean = LocalDynamicAccent.current,
    content: @Composable () -> Unit,
) {
    val savedLetterSpacing by rememberPreference(InterfaceLetterSpacingKey, LocalInterfaceLetterSpacing.current.value)
    val letterSpacing = (savedLetterSpacing.takeIf { it.isFinite() }
        ?: DefaultInterfaceLetterSpacing).coerceIn(0f, 3f).sp
    val typography = remember(letterSpacing) { appTypography(letterSpacing) }
    // A source change must not expose an uncorrected frame of the previous cover color.
    val animatedSeed by key(dynamicAccent) {
        animateColorAsState(seedColor, tween(600), label = "iOS accent")
    }
    // Only artwork-derived colors need correction. Preserve default and user-selected colors.
    val accent = remember(animatedSeed, isDark, dynamicAccent) {
        if (dynamicAccent) ColorExtractionUtils.themeAccentColor(animatedSeed, isDark) else animatedSeed
    }
    val onAccent = if (dynamicAccent) ColorExtractionUtils.accentContentColor(accent) else Color.White
    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accent.copy(alpha = 0.26f),
            background = Color.Black,
            onBackground = Color.White,
            surface = Color.Black,
            onSurface = Color.White,
            surfaceVariant = Color(0xFF1C1C1E),
            onSurfaceVariant = Color(0xB2EBEBF5),
            surfaceDim = Color.Black,
            surfaceBright = Color(0xFF2C2C2E),
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF1C1C1E),
            surfaceContainer = Color(0xFF1C1C1E),
            surfaceContainerHigh = Color(0xFF2C2C2E),
            surfaceContainerHighest = Color(0xFF3A3A3C),
            outline = Color(0xFF8E8E93),
            outlineVariant = Color(0x2BFFFFFF),
            error = Color(0xFFFF4245),
        )
    } else {
        val groupedBackground = Color(0xFFF2F2F7)
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accent.copy(alpha = 0.16f),
            background = groupedBackground,
            onBackground = Color.Black,
            surface = Color.White,
            onSurface = Color.Black,
            surfaceVariant = Color(0xFFF2F2F7),
            onSurfaceVariant = Color(0x993C3C43),
            outlineVariant = Color(0xFFE6E6E6),
            error = Color(0xFFFF383C),
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = AppShapes,
    ) {
        CompositionLocalProvider(
            LocalDynamicAccent provides dynamicAccent,
            LocalInterfaceLetterSpacing provides letterSpacing,
            LocalContentColor provides colorScheme.onBackground,
            LocalTextStyle provides typography.bodyLarge,
            content = content,
        )
    }
}
