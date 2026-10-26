// Pills (owner, 2026-10-26): a plugin's `DecorationRender.Pill` — the
// People connection `assign: @phil.jackson` — drawn as a two-part pill, the
// tag tinted on its own and the body beside it in the link's tint.
//
// The words are never changed. Styling marks the two parts with string
// annotations inside the styled text; whichever surface lays that text out
// — the desktop field, the phone's painted overlay, a Read view paragraph —
// paints the rounded backgrounds behind its own layout with [drawPills].
// One mark, one painter, three surfaces.
//
// Colour (owner, same day): each mark carries `<registration>|<key>`, and a
// registration that declares `PillTones` colours its pills from the index —
// for People, the `colour` on a connection's page. [rememberPillTones] runs
// those lookups off the UI thread as the vault changes; [pillPalette] turns
// the named tones into colours for the theme. A key the source does not
// answer is grey; a registration with no source, or an index still being
// built, draws the accent.

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
import org.gilune.plugin.sdk.EditorDecoration
import org.gilune.plugin.sdk.PillTone
import uniffi.gilune_ffi.Gilune

const val PILL_TAG = "gilune.pill.tag"
const val PILL_BODY = "gilune.pill.body"

/// Marks `[tagStart, bodyStart)` as a pill's tag and `[bodyStart, end)` as
/// its body, colouring the words of each. `item` is the pill's
/// `<registration>|<key>`, which its colour is looked up by.
fun AnnotatedString.Builder.markPill(
    tagStart: Int,
    bodyStart: Int,
    end: Int,
    tagText: Color,
    bodyText: Color,
    item: String = "",
) {
    if (tagStart >= bodyStart || bodyStart >= end) return
    addStyle(SpanStyle(color = tagText), tagStart, bodyStart)
    addStyle(SpanStyle(color = bodyText), bodyStart, end)
    addStringAnnotation(PILL_TAG, item, tagStart, bodyStart)
    addStringAnnotation(PILL_BODY, item, bodyStart, end)
}

/// The mark's item for a pill: which registration drew it, and its key.
fun pillItem(registration: String, key: String?): String = "$registration|${key.orEmpty()}"

/// The two backgrounds: the tag's own tint and, beside it, a lighter one.
data class PillColours(val tag: Color, val body: Color)

/// The tones the registrations' sources answered. `sources` names every
/// registration that has one; `ready` is false until its lookup has run
/// against a complete index, and until then every pill draws the accent
/// rather than flashing grey.
data class PillToneMap(
    val sources: Set<String> = emptySet(),
    val tones: Map<String, PillTone> = emptyMap(),
    val ready: Boolean = false,
) {
    fun toneOf(item: String): PillTone {
        val registration = item.substringBefore('|')
        if (registration !in sources || !ready) return PillTone.ACCENT
        return tones[item] ?: PillTone.GREY
    }
}

/// Runs every pill source among `decorations`. Paged, and bounded: a vault
/// with more connections than this draws the rest grey, which is a
/// statement about the bound, not about them.
internal fun loadPillTones(kernel: Gilune?, decorations: List<EditorDecoration>): PillToneMap {
    val sourced = decorations.filter { it.pillTones != null }
    if (sourced.isEmpty()) return PillToneMap(ready = true)
    if (kernel == null) return PillToneMap(sourced.map { it.id }.toSet())
    val tones = mutableMapOf<String, PillTone>()
    for (decoration in sourced) {
        val source = decoration.pillTones ?: continue
        var cursor: String? = null
        var read = 0
        do {
            val page = kernel.lookup(source.lookup.copy(pageSize = 200u, cursor = cursor))
            if (!page.indexComplete) return PillToneMap(sourced.map { it.id }.toSet())
            for (hit in page.hits) source.tone(hit)?.let { (key, tone) -> tones[pillItem(decoration.id, key)] = tone }
            read += page.hits.size
            cursor = page.nextCursor
        } while (cursor != null && read < 2_000)
    }
    return PillToneMap(sourced.map { it.id }.toSet(), tones, ready = true)
}

/// The tones for this vault as it stands, re-read whenever it moves.
@Composable
fun rememberPillTones(state: GiluneState, decorations: List<EditorDecoration>): PillToneMap {
    val kernel = state.gilune.value
    val ids = decorations.filter { it.pillTones != null }.map { it.id }
    if (ids.isEmpty()) return PillToneMap(ready = true)
    val work by rememberQueryWork(kernel, ids, state.vaultCursor.value) { loadPillTones(kernel, decorations) }
    return work.value ?: PillToneMap(ids.toSet())
}

/// The hue each named tone is drawn from. Mid-tones, so a light tint of one
/// over a light surface and a dark tint over a dark surface both read.
private fun PillTone.hue(accent: Color): Color = when (this) {
    PillTone.ACCENT -> accent
    PillTone.BLUE -> Color(0xFF378ADD)
    PillTone.GREEN -> Color(0xFF639922)
    PillTone.AMBER -> Color(0xFFBA7517)
    PillTone.RED -> Color(0xFFE24B4A)
    PillTone.PURPLE -> Color(0xFF7F77DD)
    PillTone.GREY -> Color(0xFF888780)
}

/// Colours for a pill's mark. Both are tints of its tone's hue, the tag's
/// the deeper, so the pill reads as one thing in two parts in either theme;
/// made opaque over the surface so a tag is one exact colour wherever it is
/// drawn.
@Composable
fun pillPalette(tones: PillToneMap = PillToneMap(ready = true)): (String) -> PillColours {
    val colours = MaterialTheme.colorScheme
    val byTone = remember(colours) {
        PillTone.entries.associateWith { tone ->
            val hue = tone.hue(colours.primary)
            PillColours(
                tag = hue.copy(alpha = 0.24f).compositeOver(colours.surface),
                body = hue.copy(alpha = 0.09f).compositeOver(colours.surface),
            )
        }
    }
    return remember(byTone, tones) { { item: String -> byTone.getValue(tones.toneOf(item)) } }
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
fun DrawScope.drawPills(layout: TextLayoutResult, palette: (String) -> PillColours, origin: Offset = Offset.Zero) {
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
        val colours = palette(part.item)
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
    palette: (String) -> PillColours = pillPalette(),
) {
    if (!text.hasPills()) {
        Text(text, modifier, color = color, fontSize = fontSize, fontWeight = fontWeight, lineHeight = lineHeight)
        return
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text,
        modifier.drawBehind { layout?.let { drawPills(it, palette) } },
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        onTextLayout = { layout = it },
    )
}
