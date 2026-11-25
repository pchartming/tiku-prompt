package io.github.pchartming.tikuprompt.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private val BrandTeal = Color(0xFF00695C)
private val BrandTealLight = Color(0xFF80D5C8)

val LightColors = lightColorScheme(
    primary = BrandTeal,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB2F0E6),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE8E1),
    onSecondaryContainer = Color(0xFF06201C),
    tertiary = Color(0xFF1D5FA6),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD5E3FF),
    onTertiaryContainer = Color(0xFF001C3B),
    background = Color(0xFFF7FAF9),
    onBackground = Color(0xFF171D1B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFE3ECE9),
    onSurfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF6F7976),
    outlineVariant = Color(0xFFBEC9C5),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

val DarkColors = darkColorScheme(
    primary = BrandTealLight,
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFF9CF2E4),
    secondary = Color(0xFFB1CCC6),
    onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF324B47),
    onSecondaryContainer = Color(0xFFCDE8E1),
    tertiary = Color(0xFFA7C8FF),
    onTertiary = Color(0xFF00325B),
    tertiaryContainer = Color(0xFF00497F),
    onTertiaryContainer = Color(0xFFD5E3FF),
    background = Color(0xFF0F1513),
    onBackground = Color(0xFFDFE4E1),
    surface = Color(0xFF131A18),
    onSurface = Color(0xFFDFE4E1),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBFC9C5),
    outline = Color(0xFF89938F),
    outlineVariant = Color(0xFF3F4946),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)