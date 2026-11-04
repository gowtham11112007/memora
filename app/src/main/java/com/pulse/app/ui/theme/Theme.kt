package com.pulse.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.app.R

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Lavender,
    onPrimaryContainer = Ink,
    secondaryContainer = Periwinkle,
    onSecondaryContainer = Ink,
    tertiaryContainer = Mint,
    onTertiaryContainer = Ink,
    errorContainer = Pink,
    onErrorContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = Muted,
    outline = OutlineLight,
    inverseSurface = NavDark,
    inverseOnSurface = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = IndigoSoft,
    onPrimary = Ink,
    primaryContainer = LavenderDeep,
    onPrimaryContainer = InkDark,
    secondaryContainer = PeriwinkleDeep,
    onSecondaryContainer = InkDark,
    tertiaryContainer = MintDeep,
    onTertiaryContainer = InkDark,
    errorContainer = PinkDeep,
    onErrorContainer = InkDark,
    background = PaperDark,
    onBackground = InkDark,
    surface = SurfaceDark,
    onSurface = InkDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = MutedDark,
    outline = OutlineDark,
    inverseSurface = PanelDark,
    inverseOnSurface = Color.White,
)

@OptIn(ExperimentalTextApi::class)
private fun outfit(weight: Int) = Font(
    R.font.outfit,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private val Outfit = FontFamily(outfit(400), outfit(500), outfit(600))

private fun TextStyle.pulse(size: Int, line: Int, weight: FontWeight, spacing: Float = 0f) = copy(
    fontFamily = Outfit,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = spacing.sp,
)

private val base = Typography()
private val PulseTypography = base.copy(
    displayMedium = base.displayMedium.pulse(56, 62, FontWeight.Medium, -1.5f),
    displaySmall = base.displaySmall.pulse(46, 54, FontWeight.Medium, -1f),
    headlineLarge = base.headlineLarge.pulse(38, 44, FontWeight.SemiBold, -1f),
    headlineMedium = base.headlineMedium.pulse(32, 38, FontWeight.Medium, -0.5f),
    headlineSmall = base.headlineSmall.pulse(26, 30, FontWeight.Medium),
    titleLarge = base.titleLarge.pulse(20, 26, FontWeight.Medium),
    titleMedium = base.titleMedium.pulse(16, 22, FontWeight.Medium),
    bodyLarge = base.bodyLarge.pulse(16, 22, FontWeight.Normal),
    bodyMedium = base.bodyMedium.pulse(14, 20, FontWeight.Normal),
    bodySmall = base.bodySmall.pulse(12, 16, FontWeight.Normal),
    labelLarge = base.labelLarge.pulse(14, 20, FontWeight.Medium),
    labelMedium = base.labelMedium.pulse(13, 18, FontWeight.Normal),
    labelSmall = base.labelSmall.pulse(11, 16, FontWeight.Normal),
)

private val PulseShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(40.dp),
)

@Composable
fun PulseTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = PulseTypography,
        shapes = PulseShapes,
        content = content,
    )
}
