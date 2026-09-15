package com.rubidiumclient.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Pastel dark purple palette ────────────────────────────────────────────
// Values only — every val name is unchanged, so all ~300 call sites in
// DashboardActivity and OverlayService keep working untouched.
//
// Semantic contract preserved from the previous light theme:
//   Background / Surface   = large dark fills
//   OnBackground / OnSurface = text drawn on top of those fills
//   Outline*               = hairlines and borders
// So inverting light -> dark needs no call-site changes.

val RubidiumBackground     = Color(0xFF171223)
val RubidiumSurface        = Color(0xFF1F1830)
val RubidiumSurfaceVar     = Color(0xFF292040)
val RubidiumSurfaceRaised  = Color(0xFF332849)

val RubidiumAccent         = Color(0xFFC6A9FF)
val RubidiumAccentLight    = Color(0xFFDFCCFF)
val RubidiumAccentDark     = Color(0xFF7E5FD1)

val RubidiumOnBackground   = Color(0xFFEDE7FA)
val RubidiumOnSurface      = Color(0xFFD8CEEA)
val RubidiumOnSurfaceDim   = Color(0xFF9C8EB8)

val RubidiumOutline        = Color(0xFF443761)
val RubidiumOutlineStrong  = Color(0xFF5B4A80)

val RubidiumError          = Color(0xFFFF92A8)
val RubidiumSuccess        = Color(0xFF9FE7BA)
val RubidiumWarning        = Color(0xFFFFD6A1)

val RubidiumConnectIdle    = Color(0xFF6E5F8C)

// Alt gezinme çubuğu için açık ten rengi
val RubidiumSkinTone       = Color(0xFFF1D7B8)

// Aktif/açık modül kartları için pastel mor tonlar
val RubidiumModuleActive       = Color(0xFFB79BFF)
val RubidiumModuleActiveBorder = Color(0xFF9A7BE0)
val RubidiumModuleExpanded     = Color(0xFF7E63C0)
val RubidiumModuleActiveText   = Color(0xFF171223)

val RubidiumPurple      = RubidiumAccent
val RubidiumPurpleLight = RubidiumAccentLight
val RubidiumPurpleDark  = RubidiumAccentDark

private val Scheme = darkColorScheme(
    primary          = RubidiumAccent,
    // Primary is a light pastel now, so text on it must be dark.
    onPrimary        = Color(0xFF171223),
    primaryContainer = RubidiumAccentDark,
    onPrimaryContainer = RubidiumOnBackground,
    secondary        = RubidiumAccentLight,
    onSecondary      = Color(0xFF171223),
    background       = RubidiumBackground,
    onBackground     = RubidiumOnBackground,
    surface          = RubidiumSurface,
    onSurface        = RubidiumOnSurface,
    surfaceVariant   = RubidiumSurfaceVar,
    onSurfaceVariant = RubidiumOnSurfaceDim,
    outline          = RubidiumOutline,
    outlineVariant   = RubidiumOutline,
    error            = RubidiumError,
    onError          = Color(0xFF2A0D14)
)

@Composable
fun RubidiumClientTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
