// The phone's type over the product's palette (ADR 0017 § 2). The
// schemes are shared and inherited whole; the scale is not. The
// desktop's Compact body is 13–14sp, which is deliberate desktop
// density — shipping it on a phone would be a legibility and
// touch-target regression, so mobile states its own floor here:
// body 16sp on a 24sp line, labels 14sp.
//
// Dynamic colour is declined (§ 2): repainting the accent from the
// user's wallpaper would dissolve the one thing that makes the phone app
// recognisably the same product.

package org.gilune.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Mobile = Typography().run {
    copy(
        bodyLarge = bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = bodyMedium.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodySmall = bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
        titleLarge = titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium),
        titleSmall = titleSmall.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
        labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
        labelMedium = labelMedium.copy(fontSize = 14.sp),
        labelSmall = labelSmall.copy(fontSize = 12.sp),
    )
}

/// Android's minimum touch target, and the size of every control this
/// shell draws that a thumb has to find.
val TouchTarget = 48.dp

/// The product's mark, drawn from the same artwork the launcher icon and
/// the desktop window wear. One composable rather than two call sites
/// each reaching for a resource, so the two places the phone shows the
/// logo cannot drift apart.
///
/// It is decoration: `contentDescription` is null deliberately, because
/// both places that draw it name the product in words directly beside it
/// and a screen reader announcing "Gilune" twice helps nobody.
@Composable
fun GiluneLogo(size: Dp = 64.dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.gilune_logo),
        contentDescription = null,
        modifier = modifier.size(size).testTag("logo"),
    )
}

@Composable
fun GiluneTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) GiluneDark else GiluneLight,
        typography = Mobile,
        content = content,
    )
}
