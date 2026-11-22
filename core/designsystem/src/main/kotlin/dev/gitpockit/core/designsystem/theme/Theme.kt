package dev.gitpockit.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** User-selectable appearance. */
enum class ThemeMode { System, Dark, Light }

data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.System,
    /** Pure black backgrounds for OLED screens (dark only). */
    val amoled: Boolean = false,
    /** Material You wallpaper colors (Android 12+). Off by default to keep the brand look. */
    val dynamicColor: Boolean = false,
)

private val PremiumDark: ColorScheme = darkColorScheme(
    primary = Palette.Emerald400,
    onPrimary = Palette.Ink900,
    primaryContainer = Palette.Emerald900,
    onPrimaryContainer = Palette.Emerald400,
    secondary = Palette.Teal400,
    onSecondary = Palette.Ink900,
    secondaryContainer = Palette.Ink750,
    onSecondaryContainer = Palette.Mist100,
    tertiary = Palette.Violet400,
    background = Palette.Ink900,
    onBackground = Palette.Mist100,
    surface = Palette.Ink900,
    onSurface = Palette.Mist100,
    surfaceVariant = Palette.Ink800,
    onSurfaceVariant = Palette.Mist300,
    surfaceContainerLowest = Palette.Ink950,
    surfaceContainerLow = Palette.Ink850,
    surfaceContainer = Palette.Ink850,
    surfaceContainerHigh = Palette.Ink800,
    surfaceContainerHighest = Palette.Ink750,
    outline = Palette.Ink500,
    outlineVariant = Palette.Ink700,
    error = Palette.ErrorDark,
    onError = Palette.Ink900,
)

private val SimpleLight: ColorScheme = lightColorScheme(
    primary = Palette.Emerald600,
    onPrimary = Palette.Paper,
    primaryContainer = Palette.Emerald400.copy(alpha = 0.18f).compositeOverWhite(),
    onPrimaryContainer = Palette.Emerald900,
    secondary = Palette.Teal700,
    onSecondary = Palette.Paper,
    secondaryContainer = Palette.Paper100,
    onSecondaryContainer = Palette.Slate900,
    background = Palette.Paper50,
    onBackground = Palette.Slate900,
    surface = Palette.Paper50,
    onSurface = Palette.Slate900,
    surfaceVariant = Palette.Paper100,
    onSurfaceVariant = Palette.Slate600,
    surfaceContainerLowest = Palette.Paper,
    surfaceContainerLow = Palette.Paper,
    surfaceContainer = Palette.Paper,
    surfaceContainerHigh = Palette.Paper100,
    surfaceContainerHighest = Palette.Paper200,
    outline = Palette.Slate600.copy(alpha = 0.5f).compositeOverWhite(),
    outlineVariant = Palette.Paper200,
    error = Palette.ErrorLight,
)

private fun ColorScheme.amoled(): ColorScheme = copy(
    background = androidx.compose.ui.graphics.Color.Black,
    surface = androidx.compose.ui.graphics.Color.Black,
    surfaceContainerLowest = androidx.compose.ui.graphics.Color.Black,
    surfaceContainerLow = Palette.Ink950,
    surfaceContainer = Palette.Ink950,
)

private fun androidx.compose.ui.graphics.Color.compositeOverWhite() =
    androidx.compose.ui.graphics.Color(
        red = red * alpha + (1 - alpha),
        green = green * alpha + (1 - alpha),
        blue = blue * alpha + (1 - alpha),
    )

private val GitPockitShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val LocalExtendedColors = staticCompositionLocalOf { DarkExtendedColors }

@Composable
fun GitPockitTheme(
    settings: ThemeSettings = ThemeSettings(),
    content: @Composable () -> Unit,
) {
    val dark = when (settings.mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }
    val context = LocalContext.current
    val base = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> PremiumDark
        else -> SimpleLight
    }
    val scheme = if (dark && settings.amoled) base.amoled() else base

    CompositionLocalProvider(LocalExtendedColors provides if (dark) DarkExtendedColors else LightExtendedColors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = GitPockitTypography,
            shapes = GitPockitShapes,
            content = content,
        )
    }
}

object GitPockitTheme {
    val extendedColors: ExtendedColors
        @Composable @ReadOnlyComposable
        get() = LocalExtendedColors.current
}
