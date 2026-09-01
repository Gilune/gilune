// The desktop's density. Material's defaults are tuned for a phone:
// bigger type and taller rows. The colours are no longer here — they are
// the product's, shared with every shell in `Palette.kt` (ADR 0017 § 2)
// — so what remains is the one thing that is genuinely desktop, the
// Compact type scale.

package org.gilune.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

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
        colorScheme = if (dark) GiluneDark else GiluneLight,
        typography = Compact,
        content = content,
    )
}
