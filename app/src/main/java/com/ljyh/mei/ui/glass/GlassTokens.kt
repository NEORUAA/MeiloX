package com.ljyh.mei.ui.glass

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ljyh.mei.R

@Immutable
data class GlassColors(
    val isDark: Boolean,
    val accent: Color,
    val content: Color,
    val secondaryContent: Color,
    val container: Color,
    val prominentContainer: Color,
    val subtleStroke: Color,
    val groupedBackground: Color,
    val elevatedBackground: Color,
    val separator: Color,
    val tertiaryContent: Color,
    val destructive: Color,
)

@Immutable
data class GlassDimensions(
    val compactCornerRadius: Dp = 16.dp,
    val regularCornerRadius: Dp = 26.dp,
    val sheetCornerRadius: Dp = 38.dp,
    val controlHeight: Dp = 48.dp,
    val iconButtonSize: Dp = 44.dp,
    val bottomBarHeight: Dp = 64.dp,
)

/** Exact iOS/iPadOS 27 typography tokens exposed by the referenced Figma library. */
object IosTypography {
    val fontFamily = FontFamily(Font(R.font.sf_pro, FontWeight.Normal))
    val largeTitle = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 41.sp,
        letterSpacing = 1.sp,
//        letterSpacing = 0.4.sp,
    )
    val title2 = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 1.sp,
    )
    val headline = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 1.sp,
//        letterSpacing = (-0.43).sp,
    )
    val body = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 1.sp,
//        letterSpacing = (-0.43).sp,
    )
    val subheadline = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 1.sp,
//        letterSpacing = (-0.23).sp,
    )
    val caption = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.sp,
    )
}

val LocalGlassColors = staticCompositionLocalOf {
    GlassColors(
        isDark = false,
        accent = Color(0xFFFF3B30),
        content = Color.Black,
        secondaryContent = Color.Black.copy(alpha = 0.62f),
        container = Color.White,
        prominentContainer = Color(0xFFFF3B30).copy(alpha = 0.82f),
        subtleStroke = Color.White.copy(alpha = 0.55f),
        groupedBackground = Color(0xFFF2F2F7),
        elevatedBackground = Color.White,
        separator = Color(0xFFE6E6E6),
        tertiaryContent = Color(0x4D3C3C43),
        destructive = Color(0xFFFF383C),
    )
}

/** Content color inherited by symbols and text placed inside a glass surface. */
val LocalGlassContentColor = staticCompositionLocalOf<Color?> { null }
val LocalGroupedListIconColor = staticCompositionLocalOf<Color?> { null }
val LocalGroupedListBackgroundAlpha = staticCompositionLocalOf { 1f }

/** Shared grouped-list opacity used by every iOS sheet, including the player queue. */
const val SheetGroupedListBackgroundAlpha = 0.55f

val LocalGlassDimensions = staticCompositionLocalOf { GlassDimensions() }

fun defaultGlassColors(
    isDark: Boolean,
    accent: Color = if (isDark) Color(0xFFFF4245) else Color(0xFFFF3B30),
): GlassColors {
    return GlassColors(
        isDark = isDark,
        accent = accent,
        content = if (isDark) Color.White else Color.Black,
        secondaryContent = if (isDark) {
            Color(0xB2EBEBF5)
        } else {
            Color.Black.copy(alpha = 0.58f)
        },
        container = if (isDark) {
            Color(0xFF1C1C1E).copy(alpha = 0.54f)
        } else {
            Color(0xFFF8F8FA).copy(alpha = 0.64f)
        },
        prominentContainer = (if (isDark) Color(0xFFFF4245) else Color(0xFFFF3B30))
            .copy(alpha = if (isDark) 0.76f else 0.84f),
        subtleStroke = if (isDark) {
            Color(0x2BFFFFFF)
        } else {
            Color.White.copy(alpha = 0.64f)
        },
        groupedBackground = if (isDark) Color.Black else Color(0xFFF2F2F7),
        elevatedBackground = if (isDark) Color(0xFF1C1C1E) else Color.White,
        separator = if (isDark) Color(0x2BFFFFFF) else Color(0xFFE6E6E6),
        tertiaryContent = if (isDark) Color(0x4DEBEBF5) else Color(0x4D3C3C43),
        destructive = if (isDark) Color(0xFFFF4245) else Color(0xFFFF383C),
    )
}
