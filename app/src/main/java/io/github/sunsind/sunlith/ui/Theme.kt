package io.github.sunsind.sunlith.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp

@Immutable
data class SunlithPalette(
    val background: Color,
    val surface: Color,
    val raised: Color,
    val line: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val onAccent: Color,
    val danger: Color,
    val dark: Boolean,
)

val DarkPalette = SunlithPalette(
    background = Color(0xFF0B0B0B),
    surface = Color(0xFF151515),
    raised = Color(0xFF212121),
    line = Color(0xFF2A2A2A),
    ink = Color(0xFFEDEDED),
    muted = Color(0xFF9C9C9C),
    faint = Color(0xFF8A8A8A),
    accent = Color(0xFFF2F2F2),
    onAccent = Color(0xFF0B0B0B),
    danger = Color(0xFFE0918A),
    dark = true,
)

val LightPalette = SunlithPalette(
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
    raised = Color(0xFFEDEDED),
    line = Color(0xFFE0E0E0),
    ink = Color(0xFF121212),
    muted = Color(0xFF5F5F5F),
    faint = Color(0xFF6B6B6B),
    accent = Color(0xFF121212),
    onAccent = Color(0xFFFFFFFF),
    danger = Color(0xFFB0433B),
    dark = false,
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

// Text follows its content even when the surrounding controls are mirrored.
private val SunlithTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(textDirection = TextDirection.ContentOrLtr),
        displayMedium = displayMedium.copy(textDirection = TextDirection.ContentOrLtr),
        displaySmall = displaySmall.copy(textDirection = TextDirection.ContentOrLtr),
        headlineLarge = headlineLarge.copy(textDirection = TextDirection.ContentOrLtr),
        headlineMedium = headlineMedium.copy(textDirection = TextDirection.ContentOrLtr),
        headlineSmall = headlineSmall.copy(textDirection = TextDirection.ContentOrLtr,
            fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = titleLarge.copy(textDirection = TextDirection.ContentOrLtr, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(textDirection = TextDirection.ContentOrLtr, fontWeight = FontWeight.Medium),
        titleSmall = titleSmall.copy(textDirection = TextDirection.ContentOrLtr),
        bodyLarge = bodyLarge.copy(textDirection = TextDirection.ContentOrLtr),
        bodyMedium = bodyMedium.copy(textDirection = TextDirection.ContentOrLtr),
        bodySmall = bodySmall.copy(textDirection = TextDirection.ContentOrLtr),
        labelLarge = labelLarge.copy(textDirection = TextDirection.ContentOrLtr, fontWeight = FontWeight.Medium),
        labelMedium = labelMedium.copy(textDirection = TextDirection.ContentOrLtr),
        labelSmall = labelSmall.copy(textDirection = TextDirection.ContentOrLtr),
    )
}

@Composable
fun isDarkTheme(themeMode: String): Boolean = when (themeMode) {
    "light" -> false
    "system" -> isSystemInDarkTheme()
    else -> true
}

@Composable
fun SunlithTheme(themeMode: String, materialYou: Boolean = true, content: @Composable () -> Unit) {
    val dark = isDarkTheme(themeMode)
    val p = if (dark) DarkPalette else LightPalette
    val neutralScheme = if (dark) {
        darkColorScheme(
            primary = p.accent, onPrimary = p.onAccent,
            primaryContainer = p.raised, onPrimaryContainer = p.ink,
            secondary = p.muted, onSecondary = p.onAccent,
            secondaryContainer = p.raised, onSecondaryContainer = p.ink,
            tertiary = p.muted, onTertiary = p.onAccent,
            background = p.background, onBackground = p.ink,
            surface = p.background, onSurface = p.ink,
            surfaceVariant = p.raised, onSurfaceVariant = p.muted,
            surfaceTint = Color.Transparent,
            outline = p.faint, outlineVariant = p.line,
            error = p.danger, onError = p.onAccent,
            surfaceContainerLowest = p.background, surfaceContainerLow = p.surface,
            surfaceContainer = p.surface, surfaceContainerHigh = Color(0xFF1B1B1B),
            surfaceContainerHighest = p.raised, surfaceBright = p.raised, surfaceDim = p.background,
            inverseSurface = p.ink, inverseOnSurface = p.background, inversePrimary = p.onAccent,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = p.onAccent,
            primaryContainer = p.raised, onPrimaryContainer = p.ink,
            secondary = p.muted, onSecondary = p.onAccent,
            secondaryContainer = p.raised, onSecondaryContainer = p.ink,
            tertiary = p.muted, onTertiary = p.onAccent,
            background = p.background, onBackground = p.ink,
            surface = p.background, onSurface = p.ink,
            surfaceVariant = p.raised, onSurfaceVariant = p.muted,
            surfaceTint = Color.Transparent,
            outline = p.faint, outlineVariant = p.line,
            error = p.danger, onError = p.onAccent,
            surfaceContainerLowest = p.surface, surfaceContainerLow = p.surface,
            surfaceContainer = p.surface, surfaceContainerHigh = p.surface,
            surfaceContainerHighest = p.raised, surfaceBright = p.surface, surfaceDim = p.raised,
            inverseSurface = p.ink, inverseOnSurface = p.background, inversePrimary = p.onAccent,
        )
    }
    val scheme = if (materialYou && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else neutralScheme
    val palette = if (materialYou && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        SunlithPalette(
            background = scheme.background,
            surface = scheme.surfaceContainerLow,
            raised = scheme.surfaceContainerHigh,
            line = scheme.outlineVariant,
            ink = scheme.onSurface,
            muted = scheme.onSurfaceVariant,
            faint = scheme.outline,
            accent = scheme.primary,
            onAccent = scheme.onPrimary,
            danger = scheme.error,
            dark = dark,
        )
    } else p
    CompositionLocalProvider(LocalPalette provides palette, LocalContentColor provides palette.ink) {
        MaterialTheme(colorScheme = scheme, typography = SunlithTypography, content = content)
    }
}

val SectionLabel = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp,
    textDirection = TextDirection.ContentOrLtr)

fun eventTint(argb: Int, palette: SunlithPalette): Color {
    if (argb == 0) return palette.muted
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb or 0xFF000000.toInt(), hsv)
    hsv[1] = hsv[1] * 0.55f
    hsv[2] = if (palette.dark) hsv[2].coerceIn(0.55f, 0.80f) else hsv[2].coerceIn(0.40f, 0.68f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}
