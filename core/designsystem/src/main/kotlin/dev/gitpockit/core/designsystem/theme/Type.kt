package dev.gitpockit.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * UI text uses the system sans font for now; bundled Inter (OFL) replaces it in Phase 5.
 * Bundled fonts avoid the Google Play Services font provider, which F-Droid builds can't rely on.
 */
val UiFont: FontFamily = FontFamily.SansSerif

/** Code, hashes and diffs. Bundled JetBrains Mono (OFL) replaces it in Phase 5. */
val CodeFont: FontFamily = FontFamily.Monospace

internal val GitPockitTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontFamily = UiFont, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontFamily = UiFont, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        headlineSmall = headlineSmall.copy(fontFamily = UiFont, fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontFamily = UiFont, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontFamily = UiFont, fontWeight = FontWeight.Medium),
        titleSmall = titleSmall.copy(fontFamily = UiFont, fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.copy(fontFamily = UiFont),
        bodyMedium = bodyMedium.copy(fontFamily = UiFont),
        bodySmall = bodySmall.copy(fontFamily = UiFont),
        labelLarge = labelLarge.copy(fontFamily = UiFont, fontWeight = FontWeight.Medium),
        labelMedium = labelMedium.copy(fontFamily = UiFont, fontWeight = FontWeight.Medium),
        labelSmall = labelSmall.copy(fontFamily = UiFont),
    )
}

/** Monospace style for commit hashes, file paths and diff lines. */
val CodeTextStyle = TextStyle(fontFamily = CodeFont, fontSize = 13.sp, lineHeight = 19.sp)
