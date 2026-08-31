// A desktop palette and density. Material's defaults are tuned for a
// phone: bigger type, taller rows, and a purple accent that reads as
// "stock Android" on a Windows desktop. The colours here are neutral
// with one restrained accent, and both schemes are defined in full so
// dark mode is never a half-applied afterthought.

package org.gilune.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF3F5B8B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE2E9F6),
    onPrimaryContainer = Color(0xFF1B2C47),
    secondary = Color(0xFF5B6472),
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

private val Dark = darkColorScheme(
    primary = Color(0xFFA6BCE4),
    onPrimary = Color(0xFF14243D),
    primaryContainer = Color(0xFF283A57),
    onPrimaryContainer = Color(0xFFD9E3F5),
    secondary = Color(0xFF9BA4B2),
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

private val Compact = Typography().run {
    copy(
        bodyLarge = bodyLarge.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodyMedium = bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
        bodySmall = bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        titleMedium = titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
        titleSmall = titleSmall.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun GiluneTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = Compact,
        content = content,
    )
}
