package com.rubidiumclient.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val RubidiumBackground     = Color(0xFFEAE7E0)
val RubidiumSurface        = Color(0xFFE9E6DF)
val RubidiumSurfaceVar     = Color(0xFFDDD9D0)
val RubidiumSurfaceRaised  = Color(0xFFF8F6F1)

val RubidiumAccent         = Color(0xFF1C1C1E)
val RubidiumAccentLight    = Color(0xFF48484A)
val RubidiumAccentDark     = Color(0xFFEAEAEC)

val RubidiumOnBackground   = Color(0xFF1C1C1E)
val RubidiumOnSurface      = Color(0xFF3A3A3C)
val RubidiumOnSurfaceDim   = Color(0xFF8A8A8E)

val RubidiumOutline        = Color(0xFFD5D5D9)
val RubidiumOutlineStrong  = Color(0xFFB8B8BD)

val RubidiumError          = Color(0xFF1C1C1E)
val RubidiumSuccess        = Color(0xFF6E6E73)
val RubidiumWarning        = Color(0xFF8A8A8E)

val RubidiumConnectIdle    = Color(0xFFC7C7CC)

// Alt gezinme çubuğu için açık ten rengi
val RubidiumSkinTone       = Color(0xFFF1D7B8)

// Aktif/açık modül kartları için grimsi tonlar (yeşil yerine)
val RubidiumModuleActive       = Color(0xFFAEAEB4)
val RubidiumModuleActiveBorder = Color(0xFF7D7D83)
val RubidiumModuleExpanded     = Color(0xFF9C9CA3)
val RubidiumModuleActiveText   = Color(0xFF1C1C1E)

val RubidiumPurple      = RubidiumAccent
val RubidiumPurpleLight = RubidiumAccentLight
val RubidiumPurpleDark  = RubidiumAccentDark

private val Scheme = lightColorScheme(
    primary          = RubidiumAccent,
    onPrimary        = Color.White,
    primaryContainer = RubidiumAccentDark,
    secondary        = RubidiumAccentLight,
    background       = RubidiumBackground,
    surface          = RubidiumSurface,
    surfaceVariant   = RubidiumSurfaceVar,
    onBackground     = RubidiumOnBackground,
    onSurface        = RubidiumOnSurface,
    outline          = RubidiumOutline,
    error            = RubidiumError
)

@Composable
fun RubidiumClientTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
