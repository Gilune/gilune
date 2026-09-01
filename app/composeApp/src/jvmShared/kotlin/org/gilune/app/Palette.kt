// The product's colours, shared by every shell (ADR 0017 § 2: "the
// palette is inherited whole"). These schemes are pure Material 3 and
// carry no density, no type and no platform: neutral surfaces with one
// restrained accent, both defined in full so dark mode is never a
// half-applied afterthought.
//
// Typography deliberately does NOT live here. The desktop's Compact
// scale is desktop density and the phone's 16sp body is a legibility and
// touch-target floor; each shell supplies its own `Typography` and wraps
// these schemes in its own `GiluneTheme`. That split is the honest form
// of "the desktop look-and-feel on the phone".

package org.gilune.app

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/// Which theme a device is set to. A device-local fact, like the vault
/// path: a vault opened on two machines is themed twice. Shared because
/// both shells store it (desktop in `Prefs`, mobile in DataStore) and
/// both settings screens render the same three choices.
enum class ThemeChoice { System, Light, Dark }

val GiluneLight = lightColorScheme(
    primary = Color(0xFF3F5B8B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE2E9F6),
    onPrimaryContainer = Color(0xFF1B2C47),
    secondary = Color(0xFF5B6472),
    onSecondary = Color(0xFFFFFFFF),
    // Material fills a navigation bar's selected indicator from the
    // secondary container. Left undefined it arrives stock purple —
    // exactly the leak that made every desktop dialog purple until the
    // `surfaceContainer` family was written out (2026-08-31).
    secondaryContainer = Color(0xFFDDE4F0),
    onSecondaryContainer = Color(0xFF1B2C47),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1C20),
    surface = Color(0xFFFDFDFE),
    onSurface = Color(0xFF1A1C20),
    surfaceVariant = Color(0xFFF1F2F5),
    onSurfaceVariant = Color(0xFF5C626D),
    // Dialogs, menus and sheets take their container from this family;
    // left at Material's defaults they arrive tinted purple.
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFBFC),
    surfaceContainer = Color(0xFFF6F7F9),
    surfaceContainerHigh = Color(0xFFF1F2F5),
    surfaceContainerHighest = Color(0xFFEAECF0),
    outline = Color(0xFFD5D8DF),
    outlineVariant = Color(0xFFE7E9ED),
    error = Color(0xFFB3261E),
)

val GiluneDark = darkColorScheme(
    primary = Color(0xFFA6BCE4),
    onPrimary = Color(0xFF14243D),
    primaryContainer = Color(0xFF283A57),
    onPrimaryContainer = Color(0xFFD9E3F5),
    secondary = Color(0xFF9BA4B2),
    onSecondary = Color(0xFF1B2330),
    secondaryContainer = Color(0xFF2B3648),
    onSecondaryContainer = Color(0xFFD9E3F5),
    background = Color(0xFF101215),
    onBackground = Color(0xFFE5E7EC),
    surface = Color(0xFF15171B),
    onSurface = Color(0xFFE5E7EC),
    surfaceVariant = Color(0xFF21252B),
    onSurfaceVariant = Color(0xFFA3AAB6),
    surfaceContainerLowest = Color(0xFF0B0D10),
    surfaceContainerLow = Color(0xFF15171B),
    surfaceContainer = Color(0xFF1A1D22),
    surfaceContainerHigh = Color(0xFF21252B),
    surfaceContainerHighest = Color(0xFF272C33),
    outline = Color(0xFF343A43),
    outlineVariant = Color(0xFF272C33),
    error = Color(0xFFF2B8B5),
)
