// Rendering the Markdown model. Reading only: nothing here can change a
// document, which is what keeps the buffer authoritative (ADR 0012).
//
// ADR 0015 § 5 adds `editor.blocks` here: a fenced block already carries
// its info string, so the seam is a **renderer table** consulted in the
// `Code` branch, and an info string nobody registered falls through to
// today's monospace box. `MdBlock` stays sealed and the `when` stays
// exhaustive — no new block type, and no parser change.
//
// The table is shared; the rows a registration draws are not. The shell's
// own `filter` renderer is desktop code beside the agenda panel it shares
// its row shape with, exactly as every other list in this shell is, and a
// mobile reader would register its own over the same `runFilterBlock`
// (ADR 0015 § 7: what jvmShared owes the phone is the evaluation).

package org.gilune.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import org.gilune.plugin.sdk.DocumentReference
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/// One `editor.blocks` registration (ADR 0015 § 5): the fence info string
/// it claims, and what draws that block. `render` never sees the buffer
/// and cannot change a byte — the shell owns composition, exactly as it
/// does for `editor.decorations`.
class BlockRegistration(val info: String, val render: @Composable (MdBlock.Code) -> Unit)

internal val LocalDocumentTextStyle = staticCompositionLocalOf<TextStyle?> { null }

/// The registration list, and in v1 it **is** the registry: a table keyed
/// by info string rather than a branch in the `when`, so a contributed
/// block is an entry away. Info strings are matched case-insensitively and
/// a later registration replaces an earlier one of the same name, on the
/// `SearchOperators` precedent.
///
/// Empty — the default — is today's shell exactly: every fence is a code
/// box. That is also the removal story, since the `filter` registration is
/// the shell's own and not the tasks plugin's (§ 4).
class BlockRenderers(registrations: List<BlockRegistration> = emptyList()) {
    private val byInfo: Map<String, BlockRegistration> =
        registrations.associateBy { it.info.trim().lowercase() }

    operator fun get(info: String?): BlockRegistration? =
        info?.trim()?.lowercase()?.let(byInfo::get)
}

/// `onToggleTask` is the Read view's checkbox tap (ADR 0017 § 8): it is
/// handed the **source line** of the item, and the surface that owns the
/// bytes does the guarded write through `activateLine`. Null draws the
/// same glyph, inert: reading a note never changes it unless a person
/// asks, and a click on the box is asking (ADR 0012 amendment: the
/// desktop's presentation mode ticks).
///
/// `links` makes Markdown links, `[[wiki links]]` and plugin references
/// clickable; the default draws them as text, as before.
///
/// `checkboxTarget` is the tap target the glyph sits in the middle of.
/// Android's floor is 48dp; a pointer needs no such thing, so the default
/// is the marker column the bullet has always had.
///
/// `pills` are the editor's pills over the same parsed text ([pillsIn]),
/// drawn here as the editor draws them; `pillTones` colours them, as
/// [rememberPillTones] answers for the vault.
@Composable
fun MarkdownBody(
    blocks: List<MdBlock>,
    modifier: Modifier = Modifier,
    renderers: BlockRenderers = BlockRenderers(),
    onToggleTask: ((line: Int) -> Unit)? = null,
    checkboxTarget: Dp = 22.dp,
    textStyle: TextStyle = TextStyle.Default,
    blockSpacing: Dp = 10.dp,
    links: ReadLinks = ReadLinks(),
    pills: List<ReadPill> = emptyList(),
    pillTones: PillToneMap = PillToneMap(ready = true),
) {
    val colours = MaterialTheme.colorScheme
    val palette = pillPalette(pillTones)
    val scale = if (textStyle.fontSize.isSpecified) textStyle.fontSize.value / 14f else 1f
    val lineHeight = if (textStyle.lineHeight.isSpecified) textStyle.lineHeight else 22.sp
    CompositionLocalProvider(LocalDocumentTextStyle provides textStyle.takeUnless { it == TextStyle.Default }) {
        ProvideTextStyle(textStyle) {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(blockSpacing)) {
                for (block in blocks) {
                    when (block) {
                        is MdBlock.Heading -> PillText(
                            annotate(block.spans, colours.primary, links, pills, colours.onPrimaryContainer),
                            fontSize = headingSize(block.level) * scale,
                            lineHeight = if (textStyle.lineHeight.isSpecified) (headingSize(block.level).value * lineHeight.value / 14f).sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                            fontWeight = FontWeight.SemiBold,
                            palette = palette,
                        )
                        is MdBlock.Paragraph -> PillText(
                            annotate(block.spans, colours.primary, links, pills, colours.onPrimaryContainer),
                            lineHeight = lineHeight,
                            palette = palette,
                        )
                        is MdBlock.ListItem -> Row(verticalAlignment = Alignment.CenterVertically) {
                            if (block.task == null) {
                                Text(
                                    when (block.marker) {
                                        "-", "*", "+" -> "•"
                                        else -> block.marker.ifEmpty { " " }
                                    },
                                    modifier = Modifier.width(22.dp * scale),
                                    color = colours.onSurfaceVariant,
                                )
                            } else {
                                TaskGlyph(block.task, block.line, onToggleTask, maxOf(checkboxTarget, 22.dp * scale))
                            }
                            PillText(annotate(block.spans, colours.primary, links, pills, colours.onPrimaryContainer), lineHeight = lineHeight, palette = palette)
                        }
                        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                            Box(Modifier.width(2.dp).fillMaxHeight().background(colours.outline))
                            PillText(
                                annotate(block.spans, colours.primary, links, pills, colours.onPrimaryContainer),
                                color = colours.onSurfaceVariant,
                                lineHeight = lineHeight,
                                modifier = Modifier.padding(start = 10.dp),
                                palette = palette,
                            )
                        }
                        // The seam (§ 5). A registered info string draws itself;
                        // every other one — and every unfenced code block, which
                        // carries none — is the box it has always been.
                        is MdBlock.Code -> {
                            val registration = renderers[block.language]
                            if (registration == null) CodeBox(block.text, scale, textStyle.lineHeight) else registration.render(block)
                        }
                        is MdBlock.Embed -> Text(
                            "▸ ${block.target}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp * scale,
                            color = colours.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(colours.surfaceVariant)
                                .padding(10.dp),
                        )
                        is MdBlock.Rule -> HorizontalDivider()
                    }
                }
            }
        }
    }
}

/// The checkbox as a reader sees it. The glyph is drawn whether or not it
/// can be tapped, because ☐ is what `- [ ]` *means* and a reading surface
/// should say so; only the tap is conditional.
///
/// Confirmation is haptic. There is no hover and no cursor on a phone,
/// and the desktop's implementation of the same call is a no-op, so this
/// costs the pointer shell nothing.
@Composable
private fun TaskGlyph(
    checked: Boolean,
    line: Int,
    onToggle: ((line: Int) -> Unit)?,
    target: Dp,
) {
    val colours = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val description = if (checked) "task done" else "task not done"
    Box(
        Modifier
            .size(target)
            .let { base ->
                if (onToggle == null) {
                    base
                } else {
                    base.clickable(onClickLabel = "toggle task") {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onToggle(line)
                    }
                }
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (checked) CHECKED_GLYPH else UNCHECKED_GLYPH,
            color = if (checked) colours.primary else colours.onSurfaceVariant,
        )
    }
}

private const val UNCHECKED_GLYPH = "☐" // BALLOT BOX
private const val CHECKED_GLYPH = "☑" // BALLOT BOX WITH CHECK

/// The monospace box, unchanged and moved only so the `Code` branch can
/// fall through to it. **This is the removal story for a block renderer**
/// (§ 4): an info string nobody registered — including one whose plugin
/// has gone — is a legible code block again, byte for byte.
@Composable
private fun CodeBox(text: String, scale: Float, lineHeight: androidx.compose.ui.unit.TextUnit) {
    val colours = MaterialTheme.colorScheme
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp * scale,
        lineHeight = lineHeight,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(colours.surfaceVariant)
            .horizontalScroll(rememberScrollState())
            .padding(10.dp),
    )
}

private fun headingSize(level: Int) = when (level) {
    1 -> 24.sp
    2 -> 20.sp
    3 -> 17.sp
    else -> 15.sp
}

/// What a click on drawn words asks for. The surface that owns navigation
/// answers it; this file only says which words were clicked.
sealed interface ReadLink {
    /// A Markdown link's destination, verbatim: a URL or a vault path.
    data class Address(val target: String) : ReadLink

    /// `[[target]]` or `[[target|shown]]`: a note named in the vault.
    data class Wiki(val target: String) : ReadLink

    /// A plugin's reference, clicked where the plugin said it is written.
    data class Reference(val reference: DocumentReference) : ReadLink
}

/// The clickable words of one rendering. `references` pair a range of the
/// parsed **body** with what it refers to — a plugin's recognition, so the
/// renderer knows where a mention is without knowing what one looks like.
/// With no `open`, nothing is clickable and the text draws exactly as it
/// always has: wiki links stay their literal brackets.
class ReadLinks(
    val references: List<Pair<IntRange, DocumentReference>> = emptyList(),
    val open: ((ReadLink) -> Unit)? = null,
)

/// `[[target]]` and `[[target|shown]]`, not the `![[…]]` of an embed.
private val WIKI_LINK = Regex("""(?<!!)\[\[([^\]|\n]+)(?:\|([^\]\n]*))?]]""")

private fun annotate(
    spans: List<Span>,
    linkColour: androidx.compose.ui.graphics.Color,
    links: ReadLinks,
    pills: List<ReadPill> = emptyList(),
    pillTagColour: androidx.compose.ui.graphics.Color = linkColour,
): AnnotatedString =
    buildAnnotatedString {
        val open = links.open
        val linkStyle = SpanStyle(color = linkColour, textDecoration = TextDecoration.Underline)
        fun clickable(target: ReadLink, start: Int, end: Int) {
            if (open == null || start >= end) return
            addStyle(linkStyle, start, end)
            addLink(LinkAnnotation.Clickable("read-link") { open(target) }, start, end)
        }
        for (span in spans) {
            withStyle(
                SpanStyle(
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    fontFamily = if (span.code) FontFamily.Monospace else null,
                    color = if (span.link != null) linkColour else androidx.compose.ui.graphics.Color.Unspecified,
                    textDecoration = when {
                        span.strike -> TextDecoration.LineThrough
                        span.link != null -> TextDecoration.Underline
                        else -> null
                    },
                ),
            ) {
                // A verbatim run of the span's text, its references laid
                // over it through the source offsets the parser kept.
                fun verbatim(from: Int, to: Int) {
                    if (from >= to) return
                    val at = length
                    append(span.text, from, to)
                    if (span.link != null) clickable(ReadLink.Address(span.link), at, length)
                    if (span.source < 0 || span.code) return
                    val source = span.source + from until span.source + to
                    for ((range, reference) in links.references) {
                        val first = maxOf(range.first, source.first)
                        val last = minOf(range.last, source.last)
                        if (first > last) continue
                        clickable(ReadLink.Reference(reference), at + (first - source.first), at + (last - source.first) + 1)
                    }
                    // A pill may meet a span boundary, so each part is
                    // marked for the stretch of it this run holds.
                    for ((range, tag, item) in pills) {
                        val tagEnd = range.first + tag
                        for ((part, isTag) in listOf((range.first until tagEnd) to true, (tagEnd..range.last) to false)) {
                            val first = maxOf(part.first, source.first)
                            val last = minOf(part.last, source.last)
                            if (first > last) continue
                            val from = at + (first - source.first)
                            val to = at + (last - source.first) + 1
                            addStyle(SpanStyle(color = if (isTag) pillTagColour else linkColour), from, to)
                            addStringAnnotation(if (isTag) PILL_TAG else PILL_BODY, item, from, to)
                        }
                    }
                }
                var next = 0
                if (open != null && !span.code && span.link == null) {
                    for (match in WIKI_LINK.findAll(span.text)) {
                        verbatim(next, match.range.first)
                        val target = match.groupValues[1].trim()
                        val shown = match.groupValues[2].trim().ifEmpty { target }
                        val at = length
                        append(shown)
                        clickable(ReadLink.Wiki(target), at, length)
                        next = match.range.last + 1
                    }
                }
                verbatim(next, span.text.length)
            }
        }
    }
