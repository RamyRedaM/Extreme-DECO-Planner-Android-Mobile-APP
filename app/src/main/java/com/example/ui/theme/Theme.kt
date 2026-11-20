package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BrightMarineLightColorScheme = lightColorScheme(
    primary = OceanLightBlue,
    onPrimary = Color.White,
    primaryContainer = OceanLightBlueContainer,
    onPrimaryContainer = DeepNavyText,
    secondary = DecoGreen,
    onSecondary = Color.White,
    secondaryContainer = DecoGreenLight,
    onSecondaryContainer = DecoGreen,
    tertiary = DiveOrange,
    onTertiary = Color.White,
    tertiaryContainer = DiveOrangeLight,
    onTertiaryContainer = DiveOrange,
    error = ErrorRed,
    onError = Color.White,
    errorContainer = ErrorRedLight,
    onErrorContainer = ErrorRed,
    background = PureWhite,
    onBackground = DeepNavyText,
    surface = PureWhite,
    onSurface = DeepNavyText,
    surfaceVariant = LightSkySurface,
    onSurfaceVariant = NavyMutedText,
    surfaceContainerLow = SoftIceBackground,
    surfaceContainer = LightSkySurface,
    surfaceContainerHigh = LightSkyContainer,
    outline = SkyBorder,
    outlineVariant = Color(0xFFD9E9F4)
)

private val AbyssDarkColorScheme = darkColorScheme(
    primary = BrightSkyBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF003B5C),
    onPrimaryContainer = IceWhite,
    secondary = Color(0xFF2ED573),
    onSecondary = Color(0xFF002110),
    secondaryContainer = Color(0xFF0B3B22),
    onSecondaryContainer = Color(0xFF69F0AE),
    tertiary = Color(0xFFFF9800),
    onTertiary = Color(0xFF261400),
    tertiaryContainer = Color(0xFF422400),
    onTertiaryContainer = Color(0xFFFFB74D),
    error = Color(0xFFFF5252),
    onError = Color.White,
    errorContainer = Color(0xFF4F1016),
    onErrorContainer = Color(0xFFFF8A80),
    background = AbyssBackground,
    onBackground = IceWhite,
    surface = AbyssSurface,
    onSurface = IceWhite,
    surfaceVariant = AbyssSurfaceHigh,
    onSurfaceVariant = SlateMuted,
    surfaceContainerLow = AbyssSurface,
    surfaceContainer = AbyssSurfaceHigh,
    surfaceContainerHigh = Color(0xFF1F3854),
    outline = AbyssOutline,
    outlineVariant = Color(0xFF1A314A)
)

@Composable
fun ExtremeDecoTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) AbyssDarkColorScheme else BrightMarineLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
