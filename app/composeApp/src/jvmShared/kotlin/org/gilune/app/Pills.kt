// Pills (owner, 2026-10-26): a plugin's `DecorationRender.Pill` — the
// People connection `assign: @phil.jackson` — drawn as a two-part pill, the
// tag tinted on its own and the body beside it in the link's tint.
//
// The words are never changed. Styling marks the two parts with string
// annotations inside the styled text; whichever surface lays that text out
// — the desktop field, the phone's painted overlay, a Read view paragraph —
// paints the rounded backgrounds behind its own layout with [drawPills].
// One mark, one painter, three surfaces.

package org.gilune.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

const val PILL_TAG = "gilune.pill.tag"
const val PILL_BODY = "gilune.pill.body"

/// Marks `[tagStart, bodyStart)` as a pill's tag and `[bodyStart, end)` as
/// its body, colouring the words of each.
fun AnnotatedString.Builder.markPill(tagStart: Int, bodyStart: Int, end: Int, tagText: Color, bodyText: Color) {
    if (tagStart >= bodyStart || bodyStart >= end) return
    addStyle(SpanStyle(color = tagText), tagStart, bodyStart)
    addStyle(SpanStyle(color = bodyText), bodyStart, end)
    addStringAnnotation(PILL_TAG, "", tagStart, bodyStart)
    addStringAnnotation(PILL_BODY, "", bodyStart, end)
}

/// The two backgrounds: the tag's own tint and, beside it, the link's.
data class PillColours(val tag: Color, val body: Color)

/// Both are tints of the accent, the tag's the deeper, so the pill reads as
/// one thing in two parts in either theme. Made opaque over the surface so
/// the tag is one exact colour wherever it is drawn.
@Composable
fun pillColours(): PillColours {
    val colours = MaterialTheme.colorScheme
    return PillColours(
        tag = colours.primary.copy(alpha = 0.22f).compositeOver(colours.surface),
        body = colours.primary.copy(alpha = 0.08f).compositeOver(colours.surface),
    )
}

/// Whether styled text carries any pill at all, so a surface with none can
/// skip the layout bookkeeping.
fun AnnotatedString.hasPills(): Boolean = getStringAnnotations(PILL_TAG, 0, length).isNotEmpty()

/// Paints every pill marked in `layout`'s text behind it. `origin` is where
/// the text's own (0, 0) sits in this draw scope.
///
/// A pill sits on the words, not the line: lines may be spaced far apart,
/// so its height follows the font around the baseline. The outer ends are
/// rounded and padded a little; where tag meets body the edge is square, so
/// the two read as one shape. A pill that wraps is drawn line by line.
fun DrawScope.drawPills(layout: TextLayoutResult, colours: PillColours, origin: Offset = Offset.Zero) {
    val text = layout.layoutInput.text
    if (text.isEmpty()) return
    val size = layout.layoutInput.style.fontSize.takeIf { it.isSpecified } ?: 14.sp
    val font = size.toPx()
    val pad = font * 0.3f
    for (part in text.getStringAnnotations(0, text.length)) {
        val isTag = part.tag == PILL_TAG
        if (!isTag && part.tag != PILL_BODY) continue
        val start = part.start.coerceIn(0, text.length)
        val end = part.end.coerceIn(start, text.length)
        if (start >= end) continue
        val firstLine = layout.getLineForOffset(start)
        val lastLine = layout.getLineForOffset(end - 1)
        for (line in firstLine..lastLine) {
            val from = maxOf(start, layout.getLineStart(line))
            val to = minOf(end, layout.getLineEnd(line))
            if (from >= to) continue
            val baseline = layout.getLineBaseline(line)
            val top = maxOf(layout.getLineTop(line), baseline - font * 0.98f)
            val bottom = minOf(layout.getLineBottom(line), baseline + font * 0.32f)
            val roundStart = isTag && line == firstLine
            val roundEnd = !isTag && line == lastLine
            val left = layout.getBoundingBox(from).left - if (roundStart) pad else 0f
            val right = layout.getBoundingBox(to - 1).right + if (roundEnd) pad else 0f
            val radius = CornerRadius((bottom - top) / 2f)
            val square = CornerRadius.Zero
            val shape = RoundRect(
                left = origin.x + left,
                top = origin.y + top,
                right = origin.x + right,
                bottom = origin.y + bottom,
                topLeftCornerRadius = if (roundStart) radius else square,
                topRightCornerRadius = if (roundEnd) radius else square,
                bottomRightCornerRadius = if (roundEnd) radius else square,
                bottomLeftCornerRadius = if (roundStart) radius else square,
            )
            drawPath(Path().apply { addRoundRect(shape) }, if (isTag) colours.tag else colours.body)
        }
    }
}

/// A `Text` that paints the pills its words carry. Text with none is an
/// ordinary `Text`, laid out exactly as before.
@Composable
fun PillText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    if (!text.hasPills()) {
        Text(text, modifier, color = color, fontSize = fontSize, fontWeight = fontWeight, lineHeight = lineHeight)
        return
    }
    val colours = pillColours()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text,
        modifier.drawBehind { layout?.let { drawPills(it, colours) } },
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        onTextLayout = { layout = it },
    )
}
