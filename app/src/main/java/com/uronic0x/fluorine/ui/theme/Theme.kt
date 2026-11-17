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

enum class AppThemeMode(val id: String) {
    DARK("dark"),
    LIGHT("light"),
    SYSTEM("system");

    companion object {
        fun fromId(id: String?): AppThemeMode =
            entries.firstOrNull { it.id == id } ?: DARK
    }
}

// Pure Carbon Dark & Neutral White Palette (no green tint)
private val LightBg = Color(0xFFFFFFFF)
private val LightSurface = Color(0xFFF8F9FB)
private val LightSurfaceVariant = Color(0xFFF1F3F6)
private val LightPrimary = Color(0xFF2563EB)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFFDBEAFE)
private val LightOnPrimaryContainer = Color(0xFF1E3A8A)
private val LightSecondary = Color(0xFF475569)
private val LightOnSecondary = Color(0xFFFFFFFF)
private val LightSecondaryContainer = Color(0xFFE2E8F0)
private val LightOnSecondaryContainer = Color(0xFF1E293B)
private val LightTertiary = Color(0xFF0284C7)
private val LightOnBg = Color(0xFF0F172A)
private val LightOnSurface = Color(0xFF0F172A)
private val LightOnSurfaceVariant = Color(0xFF64748B)
private val LightOutline = Color(0xFFCBD5E1)
private val LightOutlineVariant = Color(0xFFE2E8F0)
private val LightError = Color(0xFFDC2626)

private val DarkBg = Color(0xFF0D0F12)
private val DarkSurface = Color(0xFF15181E)
private val DarkSurfaceVariant = Color(0xFF1E222B)
private val DarkPrimary = Color(0xFF3B82F6)
private val DarkOnPrimary = Color(0xFFFFFFFF)
private val DarkPrimaryContainer = Color(0xFF1E3A8A)
private val DarkOnPrimaryContainer = Color(0xFFDBEAFE)
private val DarkSecondary = Color(0xFF94A3B8)
private val DarkOnSecondary = Color(0xFF0F172A)
private val DarkSecondaryContainer = Color(0xFF262C36)
private val DarkOnSecondaryContainer = Color(0xFFE2E8F0)
private val DarkTertiary = Color(0xFF38BDF8)
private val DarkOnBg = Color(0xFFF1F5F9)
private val DarkOnSurface = Color(0xFFF1F5F9)
private val DarkOnSurfaceVariant = Color(0xFF94A3B8)
private val DarkOutline = Color(0xFF333946)
private val DarkOutlineVariant = Color(0xFF252A34)
private val DarkError = Color(0xFFF87171)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    background = LightBg,
    onBackground = LightOnBg,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = LightError
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    background = DarkBg,
    onBackground = DarkOnBg,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DarkError
)

val SansFontFamily = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans, FontWeight.Bold)
)

val MonoFontFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Medium)
)

val FluorineTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = SansFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 33.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = SansFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontFamily = SansFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = SansFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = SansFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = SansFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = SansFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp
    ),
    bodySmall = TextStyle(
        fontFamily = SansFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = SansFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = MonoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = MonoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp
    )
)

@Composable
fun FluorineTheme(
    themeMode: AppThemeMode = AppThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (isDark) DarkColorScheme else LightColorScheme,
        typography = FluorineTypography,
        content = content
    )
}
