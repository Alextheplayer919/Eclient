package com.rubidiumclient.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Catppuccin Mocha palette (dark purple) ────────────────────────────────
// Official Mocha hexes (mauve/lavender accents over base/mantle/surface
// fills) with a few mauve tints for the darker shades.
// Values only — every val name is unchanged, so all ~300 call sites in
// DashboardActivity and OverlayService keep working untouched.
//
// Semantic contract preserved from the previous light theme:
//   Background / Surface   = large dark fills
//   OnBackground / OnSurface = text drawn on top of those fills
//   Outline*               = hairlines and borders
// So inverting light -> dark needs no call-site changes.

val RubidiumBackground     = Color(0xFF181825) // mantle
val RubidiumSurface        = Color(0xFF1E1E2E) // base
val RubidiumSurfaceVar     = Color(0xFF313244) // surface0
val RubidiumSurfaceRaised  = Color(0xFF45475A) // surface1

val RubidiumAccent         = Color(0xFFCBA6F7) // mauve
val RubidiumAccentLight    = Color(0xFFB4BEFE) // lavender
val RubidiumAccentDark     = Color(0xFF8E74AD) // mauve ~70%

val RubidiumOnBackground   = Color(0xFFCDD6F4) // text
val RubidiumOnSurface      = Color(0xFFBAC2DE) // subtext1
val RubidiumOnSurfaceDim   = Color(0xFFA6ADC8) // subtext0

val RubidiumOutline        = Color(0xFF585B70) // surface2
val RubidiumOutlineStrong  = Color(0xFF7F849C) // overlay1

val RubidiumError          = Color(0xFFF38BA8) // red
val RubidiumSuccess        = Color(0xFFA6E3A1) // green
val RubidiumWarning        = Color(0xFFF9E2AF) // yellow

val RubidiumConnectIdle    = Color(0xFF9399B2) // overlay2

// Alt gezinme çubuğu için açık ten rengi
val RubidiumSkinTone       = Color(0xFFF5E0DC) // rosewater

// Aktif/açık modül kartları için pastel mor tonlar (mauve ailesi)
val RubidiumModuleActive       = Color(0xFFCBA6F7) // mauve
val RubidiumModuleActiveBorder = Color(0xFFA285C6) // mauve ~80%
val RubidiumModuleExpanded     = Color(0xFF7A6494) // mauve ~60%
val RubidiumModuleActiveText   = Color(0xFF181825) // mantle

val RubidiumPurple      = RubidiumAccent
val RubidiumPurpleLight = RubidiumAccentLight
val RubidiumPurpleDark  = RubidiumAccentDark

private val Scheme = darkColorScheme(
    primary          = RubidiumAccent,
    // Primary is a light pastel now, so text on it must be dark.
    onPrimary        = Color(0xFF181825), // mantle
    primaryContainer = RubidiumAccentDark,
    onPrimaryContainer = RubidiumOnBackground,
    secondary        = RubidiumAccentLight,
    onSecondary      = Color(0xFF181825), // mantle
    background       = RubidiumBackground,
    onBackground     = RubidiumOnBackground,
    surface          = RubidiumSurface,
    onSurface        = RubidiumOnSurface,
    surfaceVariant   = RubidiumSurfaceVar,
    onSurfaceVariant = RubidiumOnSurfaceDim,
    outline          = RubidiumOutline,
    outlineVariant   = RubidiumOutline,
    error            = RubidiumError,
    onError          = Color(0xFF11111B) // crust
)

@Composable
fun RubidiumClientTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
