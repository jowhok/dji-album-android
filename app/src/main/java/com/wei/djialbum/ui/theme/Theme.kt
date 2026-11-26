package com.wei.djialbum.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 与桌面版 styles.css 的颜色 token 对齐 */
object DjiColors {
    val LightBg = Color(0xFFF6F5F2)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceVariant = Color(0xFFECEBE7)
    val LightText = Color(0xFF2C2C2A)
    val LightText2 = Color(0xFF5F5E5A)
    val LightText3 = Color(0xFF888780)
    val LightAccent = Color(0xFF2F7ED8)
    val LightHeart = Color(0xFFD94645)

    val DarkBg = Color(0xFF1B1B19)
    val DarkSurface = Color(0xFF212120)
    val DarkCard = Color(0xFF2A2A28)
    val DarkText = Color(0xFFD6D4CC)
    val DarkText2 = Color(0xFFA3A19A)
    val DarkText3 = Color(0xFF75736D)
    val DarkAccent = Color(0xFF378ADD)
    val DarkHeart = Color(0xFFE24B4A)
}

private val LightScheme = lightColorScheme(
    primary = DjiColors.LightAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E8FB),
    onPrimaryContainer = Color(0xFF102A45),
    secondary = Color(0xFF4A6B8A),
    background = DjiColors.LightBg,
    onBackground = DjiColors.LightText,
    surface = DjiColors.LightSurface,
    onSurface = DjiColors.LightText,
    surfaceVariant = DjiColors.LightSurfaceVariant,
    onSurfaceVariant = DjiColors.LightText2,
    surfaceContainer = Color(0xFFF1F0EC),
    surfaceContainerHigh = Color(0xFFECEBE7),
    outline = Color(0xFFD5D3CC),
    outlineVariant = Color(0xFFE4E2DD),
    error = DjiColors.LightHeart,
    onError = Color.White,
    errorContainer = Color(0xFFFBE0DF),
    onErrorContainer = Color(0xFF5C1413),
    inverseSurface = DjiColors.LightText,
    inverseOnSurface = DjiColors.LightBg
)

private val DarkScheme = darkColorScheme(
    primary = DjiColors.DarkAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E3A56),
    onPrimaryContainer = Color(0xFFD6E6FA),
    secondary = Color(0xFF8FB0CC),
    background = DjiColors.DarkBg,
    onBackground = DjiColors.DarkText,
    surface = DjiColors.DarkSurface,
    onSurface = DjiColors.DarkText,
    surfaceVariant = DjiColors.DarkCard,
    onSurfaceVariant = DjiColors.DarkText2,
    surfaceContainer = Color(0xFF242422),
    surfaceContainerHigh = Color(0xFF2A2A28),
    outline = Color(0xFF3E3E3B),
    outlineVariant = Color(0xFF333331),
    error = DjiColors.DarkHeart,
    onError = Color.White,
    errorContainer = Color(0xFF4A1D1C),
    onErrorContainer = Color(0xFFF6D5D3),
    inverseSurface = DjiColors.DarkText,
    inverseOnSurface = DjiColors.DarkBg
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp)
)

@Composable
fun isDark(mode: String): Boolean = when (mode) {
    "dark" -> true
    "light" -> false
    else -> isSystemInDarkTheme()
}

@Composable
fun DjiTheme(mode: String, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isDark(mode)) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content
    )
}
