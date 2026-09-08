package org.gilune.app

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified

/** Filter results inherit the containing note's typography; sidebar rows keep
 * their own sizes when no document style is provided. */
@Composable
internal fun NoteResultText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontFamily: FontFamily? = null,
    fontStyle: FontStyle? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val document = LocalDocumentTextStyle.current
    val size = if (document != null && document.fontSize.isSpecified && fontSize.isSpecified) {
        fontSize * (document.fontSize.value / 13f)
    } else fontSize
    Text(text, modifier = modifier, color = color, fontSize = size,
        fontFamily = fontFamily ?: document?.fontFamily, fontStyle = fontStyle,
        lineHeight = document?.lineHeight ?: TextUnit.Unspecified, maxLines = maxLines, overflow = overflow)
}
