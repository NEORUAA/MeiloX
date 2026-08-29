package com.ljyh.mei.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R

private val SfProFamily = FontFamily(Font(R.font.sf_pro, FontWeight.Normal))

private val AppShapes = Shapes(
    extraSmall = ContinuousRoundedRectangle(2.dp),
    small = ContinuousRoundedRectangle(4.dp),
    medium = ContinuousRoundedRectangle(8.dp),
    large = ContinuousRoundedRectangle(16.dp),
    extraLarge = ContinuousRoundedRectangle(32.dp)
)


private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = SfProFamily, letterSpacing = 1.sp),
    displayMedium = TextStyle(fontFamily = SfProFamily, letterSpacing = 1.sp),
    displaySmall = TextStyle(fontFamily = SfProFamily, letterSpacing = 1.sp),
    headlineLarge = TextStyle(fontFamily = SfProFamily, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = 1.sp),
    headlineMedium = TextStyle(fontFamily = SfProFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = 1.sp),
    headlineSmall = TextStyle(fontFamily = SfProFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 1.sp),
    titleLarge = TextStyle(fontFamily = SfProFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = 1.sp),
    titleMedium = TextStyle(fontFamily = SfProFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 1.sp),
    titleSmall = TextStyle(fontFamily = SfProFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 1.sp),
    bodyLarge = TextStyle(fontFamily = SfProFamily, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 1.sp),
    bodyMedium = TextStyle(
        fontFamily = SfProFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 1.sp,
    ),
    bodySmall = TextStyle(fontFamily = SfProFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 1.sp),
    labelLarge = TextStyle(fontFamily = SfProFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 1.sp),
    labelMedium = TextStyle(fontFamily = SfProFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.sp),
    labelSmall = TextStyle(fontFamily = SfProFamily, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 1.sp),
)
@Composable
fun MusicTheme(
    @Suppress("UNUSED_PARAMETER")
    seedColor: Color,
    isDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // MeloX uses the system red tint globally. Keep it independent from artwork-derived
    // colors so navigation, tabs, popup buttons and prominent controls never drift blue.
    val targetAccent = if (isDark) Color(0xFFFF4245) else Color(0xFFFF3B30)
    val accent by animateColorAsState(targetAccent, tween(600), label = "iOS accent")
    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = accent,
            onPrimary = Color.White,
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
            onPrimary = Color.White,
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
        typography = AppTypography,
        shapes = AppShapes,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides colorScheme.onBackground,
            // Global default for Text() without an explicit style. Must stay a full
            // typography style: a bare TextStyle would fall back to the 14sp default
            // font size. bodyLarge carries the 1.sp tracking from AppTypography.
            LocalTextStyle provides AppTypography.bodyLarge,
            content = content,
        )
    }
}
