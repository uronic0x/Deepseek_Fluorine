package com.uronic0x.fluorine.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.uronic0x.fluorine.R

// Warm Editorial Paper & Forest Ink palette matching Fluorine's design identity
val PaperLight = Color(0xFFF9F8F4)
val SurfaceLight = Color(0xFFFFFFFF)
val InkLight = Color(0xFF243C38)
val MutedLight = Color(0xFF75817A)
val LineLight = Color(0xFFE2E7DF)
val AccentLight = Color(0xFF476A58)
val SoftLight = Color(0xFFEDF2EB)
val WarnLight = Color(0xFFA45C34)
val TaskPanelLight = Color(0xFFF1F3ED)
val ThinkingBgLight = Color(0xFFF3F5F0)
val ToolBadgeBgLight = Color(0xFFEEF3F1)
val ToolBadgeFgLight = Color(0xFF1F4E45)
val TabTrackLight = Color(0xFFEEEFE9)

// Adaptive Forest Dark palette
val PaperDark = Color(0xFF121816)
val SurfaceDark = Color(0xFF1A221F)
val InkDark = Color(0xFFE5ECE8)
val MutedDark = Color(0xFF94A39B)
val LineDark = Color(0xFF2B3732)
val AccentDark = Color(0xFF78A88F)
val SoftDark = Color(0xFF22302A)
val WarnDark = Color(0xFFD98254)
val TaskPanelDark = Color(0xFF17201C)
val ThinkingBgDark = Color(0xFF1E2824)
val ToolBadgeBgDark = Color(0xFF1E332C)
val ToolBadgeFgDark = Color(0xFF9AD4C0)
val TabTrackDark = Color(0xFF1E2623)

val PlusJakartaSans = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans, FontWeight.Bold)
)

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Medium)
)

private val LightColorScheme = lightColorScheme(
    primary = AccentLight,
    onPrimary = Color.White,
    primaryContainer = SoftLight,
    onPrimaryContainer = InkLight,
    secondary = ToolBadgeFgLight,
    onSecondary = Color.White,
    secondaryContainer = ToolBadgeBgLight,
    onSecondaryContainer = ToolBadgeFgLight,
    tertiary = WarnLight,
    onTertiary = Color.White,
    background = PaperLight,
    onBackground = InkLight,
    surface = SurfaceLight,
    onSurface = InkLight,
    surfaceVariant = TaskPanelLight,
    onSurfaceVariant = MutedLight,
    outline = LineLight,
    outlineVariant = LineLight,
    error = WarnLight,
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF0E1F17),
    primaryContainer = SoftDark,
    onPrimaryContainer = InkDark,
    secondary = ToolBadgeFgDark,
    onSecondary = Color(0xFF0E1F17),
    secondaryContainer = ToolBadgeBgDark,
    onSecondaryContainer = ToolBadgeFgDark,
    tertiary = WarnDark,
    onTertiary = Color.Black,
    background = PaperDark,
    onBackground = InkDark,
    surface = SurfaceDark,
    onSurface = InkDark,
    surfaceVariant = TaskPanelDark,
    onSurfaceVariant = MutedDark,
    outline = LineDark,
    outlineVariant = LineDark,
    error = WarnDark,
    onError = Color.Black
)

val FluorineTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 46.sp,
        letterSpacing = 0.4.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.5.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 26.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelSmall = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.8.sp
    )
)

@Composable
fun FluorineTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = FluorineTypography,
        content = content
    )
}
