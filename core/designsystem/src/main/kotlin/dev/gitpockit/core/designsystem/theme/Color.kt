package dev.gitpockit.core.designsystem.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Raw palette. UI code should read colors from MaterialTheme or [GitPockitTheme.extendedColors]. */
internal object Palette {
    // Premium dark
    val Ink950 = Color(0xFF07080C) // AMOLED-ish backdrop
    val Ink900 = Color(0xFF0B0D12) // background
    val Ink850 = Color(0xFF12151C) // surface
    val Ink800 = Color(0xFF181C25) // raised surface
    val Ink750 = Color(0xFF1F2430) // container high
    val Ink700 = Color(0xFF232836) // hairline border
    val Ink500 = Color(0xFF3A4152)
    val Mist300 = Color(0xFF9AA3B5) // secondary text on dark
    val Mist100 = Color(0xFFE6E9F0) // primary text on dark

    // Brand accent: emerald -> teal
    val Emerald400 = Color(0xFF34D399)
    val Emerald600 = Color(0xFF059669)
    val Emerald900 = Color(0xFF053D2C)
    val Teal400 = Color(0xFF22D3EE)
    val Teal700 = Color(0xFF0E7490)
    val Violet400 = Color(0xFFA78BFA)

    // Light
    val Paper = Color(0xFFFFFFFF)
    val Paper50 = Color(0xFFF7F8FA)
    val Paper100 = Color(0xFFEFF1F5)
    val Paper200 = Color(0xFFE2E5EC)
    val Slate600 = Color(0xFF4B5565)
    val Slate900 = Color(0xFF111827)

    // Semantic (diff / status)
    val AddedDark = Color(0xFF3FB950)
    val RemovedDark = Color(0xFFF85149)
    val ModifiedDark = Color(0xFFD29922)
    val AddedLight = Color(0xFF1A7F37)
    val RemovedLight = Color(0xFFCF222E)
    val ModifiedLight = Color(0xFF9A6700)
    val ErrorDark = Color(0xFFFF6B6B)
    val ErrorLight = Color(0xFFBA1A1A)
}

/** Colors Material 3 does not model: diff states, hairlines, brand gradient. */
data class ExtendedColors(
    val added: Color,
    val removed: Color,
    val modified: Color,
    val addedContainer: Color,
    val removedContainer: Color,
    val hairline: Color,
    val textSecondary: Color,
    val brandGradient: Brush,
)

internal val DarkExtendedColors = ExtendedColors(
    added = Palette.AddedDark,
    removed = Palette.RemovedDark,
    modified = Palette.ModifiedDark,
    addedContainer = Palette.AddedDark.copy(alpha = 0.14f),
    removedContainer = Palette.RemovedDark.copy(alpha = 0.14f),
    hairline = Palette.Ink700,
    textSecondary = Palette.Mist300,
    brandGradient = Brush.linearGradient(listOf(Palette.Emerald400, Palette.Teal400)),
)

internal val LightExtendedColors = ExtendedColors(
    added = Palette.AddedLight,
    removed = Palette.RemovedLight,
    modified = Palette.ModifiedLight,
    addedContainer = Palette.AddedLight.copy(alpha = 0.10f),
    removedContainer = Palette.RemovedLight.copy(alpha = 0.10f),
    hairline = Palette.Paper200,
    textSecondary = Palette.Slate600,
    brandGradient = Brush.linearGradient(listOf(Palette.Emerald600, Palette.Teal700)),
)
