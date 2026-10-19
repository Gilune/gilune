// Markdown source to a render model. Pure over a string, so it is
// testable without a screen, and it never rewrites the source: the buffer
// is the document and this is only a reading of it (ADR 0012).
//
// Front matter is split off before parsing. It is machine contract, not
// prose, and it must survive editing untouched — the editor writes it
// back verbatim, because dropping it would break a note's identity and
// re-import it as a new object.

package org.gilune.app

import org.gilune.plugin.sdk.FrontMatterEntry

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/// A note split into its machine header and its prose. `body` starts at
/// the first line after the closing fence, so offsets into it are stable.
data class Note(val frontMatter: List<FrontMatterEntry>, val body: String)

private const val FENCE = "---"

fun splitNote(text: String): Note {
    val lines = text.lines()
    if (lines.firstOrNull()?.trim() != FENCE) return Note(emptyList(), text)
    val close = lines.drop(1).indexOfFirst { it.trim() == FENCE }
    if (close < 0) return Note(emptyList(), text)
    val entries = lines.subList(1, close + 1).mapNotNull { line ->
        val at = line.indexOf(':')
        if (at <= 0) null
        else FrontMatterEntry(line.take(at).trim(), line.drop(at + 1).trim())
    }
    return Note(entries, lines.drop(close + 2).joinToString("\n"))
}

/// Emphasis carried on a span of text. Kept as a set of flags rather than
/// a style object so the model stays free of Compose.
data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false,
                val code: Boolean = false, val strike: Boolean = false, val link: String? = null) {
    /// Where `text` begins in the parsed body, when it is a verbatim slice
    /// of it — character `i` of the span is body offset `source + i` — and
    /// -1 when it is not (a code span stripped of its ticks, a run joined
    /// across a gap). This is what lets a reading surface hand a click on
    /// drawn words back to the source a plugin recognised them in.
    ///
    /// Outside the constructor on purpose: a span's *meaning* is its text
    /// and emphasis, so equality ignores where it came from.
    var source: Int = -1
        internal set
}

internal fun Span.at(offset: Int): Span = also { it.source = offset }

/// Every block carries the **0-based line of `body` it starts on**
/// (ADR 0017 § 8: ticking a box from Read ships before the editor does).
///
/// A rendered surface has no caret and no offset table, so the only
/// honest way back from something drawn to the bytes behind it is a line
/// index the parser knew and kept. The alternative — matching rendered
/// text against source lines — guesses, and a guess that misses writes
/// to the wrong line. `line` is -1 only for a block built by hand.
sealed interface MdBlock {
    val line: Int

    data class Heading(val level: Int, val spans: List<Span>, override val line: Int = -1) : MdBlock
    data class Paragraph(val spans: List<Span>, override val line: Int = -1) : MdBlock

    /// `task` is null on an ordinary bullet, false on `[ ]` and true on
    /// `[x]`. It is read off the **source line** with the same recogniser
    /// `activateLine` uses, not off the parse tree, so what a reader taps
    /// and what the toggle rewrites cannot disagree.
    data class ListItem(
        val marker: String,
        val spans: List<Span>,
        override val line: Int = -1,
        val task: Boolean? = null,
    ) : MdBlock

    data class Quote(val spans: List<Span>, override val line: Int = -1) : MdBlock
    data class Code(val text: String, val language: String?, override val line: Int = -1) : MdBlock
    data class Rule(override val line: Int = -1) : MdBlock

    /// An Obsidian-style embed, which is how a capture points at its
    /// original bytes. Rendered as a reference, never as a rewrite.
    data class Embed(val target: String, override val line: Int = -1) : MdBlock
}

private val EMBED = Regex("""^!\[\[(.+?)]]$""")

/// The `[ ]` a GFM parser may or may not have handed back as text. It is
/// stripped from the rendered spans because the glyph replaces it; the
/// bytes are untouched, which is the whole point of rendering.
private val LEADING_TASK_MARKER = Regex("""^\[[ xX]]\s*""")

/// A task marker directly after a bullet, optionally indented — the same
/// shape `MarkdownEditing.kt` and the tasks plugin recognise.
private val TASK_LINE = Regex("""^([ \t]*)([-*+])([ \t]+)\[([ xX])]""")

fun parseMarkdown(body: String): List<MdBlock> {
    val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(body)
    val blocks = mutableListOf<MdBlock>()
    val lines = SourceLines(body)
    for (node in tree.children) collectBlock(node, body, blocks, marker = null, lines = lines)
    return blocks
}

/// Offset-to-line for one parse, computed once. Counting newlines per
/// block would be quadratic on a long note for a number every block
/// needs.
private class SourceLines(source: String) {
    private val starts: IntArray = buildList {
        add(0)
        source.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
    }.toIntArray()

    private val text = source

    fun lineAt(offset: Int): Int {
        val bounded = offset.coerceIn(0, text.length)
        var low = 0
        var high = starts.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (starts[mid] <= bounded) low = mid else high = mid - 1
        }
        return low
    }

    fun textOf(line: Int): String {
        if (line < 0 || line >= starts.size) return ""
        val start = starts[line]
        val end = if (line + 1 < starts.size) starts[line + 1] - 1 else text.length
        return text.substring(start, end.coerceAtLeast(start))
    }
}

private fun collectBlock(
    node: ASTNode,
    src: String,
    out: MutableList<MdBlock>,
    marker: String?,
    lines: SourceLines,
) {
    val line = lines.lineAt(node.startOffset)
    when (node.type) {
        MarkdownElementTypes.ATX_1 -> out.add(MdBlock.Heading(1, headingSpans(node, src), line))
        MarkdownElementTypes.ATX_2 -> out.add(MdBlock.Heading(2, headingSpans(node, src), line))
        MarkdownElementTypes.ATX_3 -> out.add(MdBlock.Heading(3, headingSpans(node, src), line))
        MarkdownElementTypes.ATX_4 -> out.add(MdBlock.Heading(4, headingSpans(node, src), line))
        MarkdownElementTypes.ATX_5 -> out.add(MdBlock.Heading(5, headingSpans(node, src), line))
        MarkdownElementTypes.ATX_6 -> out.add(MdBlock.Heading(6, headingSpans(node, src), line))
        MarkdownElementTypes.SETEXT_1 -> out.add(MdBlock.Heading(1, inlineSpans(node, src), line))
        MarkdownElementTypes.SETEXT_2 -> out.add(MdBlock.Heading(2, inlineSpans(node, src), line))
        MarkdownElementTypes.PARAGRAPH -> {
            val raw = node.getTextInNode(src).toString().trim()
            val embed = EMBED.find(raw)
            when {
                embed != null -> out.add(MdBlock.Embed(embed.groupValues[1], line))
                marker != null -> out.add(listItem(marker, inlineSpans(node, src), line, lines))
                else -> out.add(MdBlock.Paragraph(inlineSpans(node, src), line))
            }
        }
        MarkdownElementTypes.BLOCK_QUOTE ->
            out.add(MdBlock.Quote(inlineSpans(node, src), line))
        MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST ->
            for (item in node.children) collectBlock(item, src, out, marker, lines)
        MarkdownElementTypes.LIST_ITEM -> {
            val bullet = node.children
                .firstOrNull {
                    it.type == MarkdownTokenTypes.LIST_BULLET ||
                        it.type == MarkdownTokenTypes.LIST_NUMBER
                }
                ?.getTextInNode(src)?.toString()?.trim() ?: "•"
            var emitted = false
            for (child in node.children) {
                val before = out.size
                collectBlock(child, src, out, marker = if (emitted) "" else bullet, lines = lines)
                if (out.size > before) emitted = true
            }
        }
        MarkdownElementTypes.CODE_FENCE -> out.add(codeFence(node, src, line))
        MarkdownElementTypes.CODE_BLOCK ->
            out.add(MdBlock.Code(node.getTextInNode(src).toString().trimEnd(), null, line))
        MarkdownTokenTypes.HORIZONTAL_RULE -> out.add(MdBlock.Rule(line))
        else -> Unit // whitespace, line breaks, link definitions
    }
}

/// A list item, told whether its source line is a GFM task. The marker
/// characters are dropped from the spans — the glyph stands for them —
/// while the source keeps them, exactly as emphasis markers are dropped.
private fun listItem(marker: String, spans: List<Span>, line: Int, lines: SourceLines): MdBlock.ListItem {
    val state = TASK_LINE.find(lines.textOf(line))?.groupValues?.get(4)
        ?: return MdBlock.ListItem(marker, spans, line)
    val stripped = spans.mapIndexed { index, span ->
        if (index == 0) {
            val kept = span.text.replaceFirst(LEADING_TASK_MARKER, "")
            val dropped = span.text.length - kept.length
            span.copy(text = kept).at(if (span.source >= 0) span.source + dropped else -1)
        } else span
    }.filter { it.text.isNotEmpty() }
    return MdBlock.ListItem(marker, stripped, line, task = state != " ")
}

private fun codeFence(node: ASTNode, src: String, line: Int): MdBlock.Code {
    val language = node.children
        .firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }
        ?.getTextInNode(src)?.toString()?.trim()
    val text = node.children
        .filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT || it.type == MarkdownTokenTypes.EOL }
        .joinToString("") { it.getTextInNode(src) }
    return MdBlock.Code(text.trim('\n'), language?.ifEmpty { null }, line)
}

private fun headingSpans(node: ASTNode, src: String): List<Span> {
    val content = node.children.firstOrNull { it.type == MarkdownTokenTypes.ATX_CONTENT }
    return if (content == null) inlineSpans(node, src) else inlineSpans(content, src)
}

private fun inlineSpans(node: ASTNode, src: String): List<Span> {
    val spans = mutableListOf<Span>()
    walkInline(node, src, Span(""), spans)
    return merge(spans)
}

private fun walkInline(node: ASTNode, src: String, style: Span, out: MutableList<Span>) {
    // `[[name]]` is not CommonMark: the parser sees the inner `[name]` as a
    // reference link nothing defines. It is a wiki link, so it stays text
    // and a reading surface reads it as one.
    val reference = node.type == MarkdownElementTypes.FULL_REFERENCE_LINK ||
        node.type == MarkdownElementTypes.SHORT_REFERENCE_LINK
    if (reference && insideWikiBrackets(node, src)) {
        out.add(style.copy(text = node.getTextInNode(src).toString()).at(node.startOffset))
        return
    }
    when (node.type) {
        MarkdownElementTypes.STRONG -> {
            emphasised(node, src, style.copy(bold = true), out, markers = 2)
            return
        }
        MarkdownElementTypes.EMPH -> {
            emphasised(node, src, style.copy(italic = true), out, markers = 1)
            return
        }
        MarkdownElementTypes.CODE_SPAN -> {
            out.add(style.copy(text = node.getTextInNode(src).toString().trim('`'), code = true))
            return
        }
        MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.FULL_REFERENCE_LINK,
        MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
            val destination = node.children
                .firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
                ?.getTextInNode(src)?.toString()
            val label = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
            val linked = style.copy(link = destination ?: node.getTextInNode(src).toString())
            if (label == null) {
                out.add(linked.copy(text = node.getTextInNode(src).toString()).at(node.startOffset))
            } else {
                // The label's bracket tokens are syntax, not text.
                val inner = label.children.filter {
                    it.type != MarkdownTokenTypes.LBRACKET && it.type != MarkdownTokenTypes.RBRACKET
                }
                for (child in inner) walkInline(child, src, linked, out)
            }
            return
        }
        // A bare `https://…` (GFM) or `<https://…>` is its own address.
        GFMTokenTypes.GFM_AUTOLINK -> {
            val address = node.getTextInNode(src).toString()
            out.add(style.copy(text = address, link = address).at(node.startOffset))
            return
        }
        MarkdownElementTypes.AUTOLINK -> {
            val address = node.getTextInNode(src).toString().removePrefix("<").removeSuffix(">")
            out.add(style.copy(text = address, link = address).at(node.startOffset + 1))
            return
        }
        MarkdownTokenTypes.EOL -> {
            // One character for one: the newline it stands for keeps the
            // offsets after it honest.
            out.add(style.copy(text = " ").at(node.startOffset))
            return
        }
        MarkdownTokenTypes.BLOCK_QUOTE, MarkdownTokenTypes.ATX_HEADER -> return
    }
    if (node.children.isEmpty()) {
        out.add(style.copy(text = node.getTextInNode(src).toString()).at(node.startOffset))
    } else {
        for (child in node.children) walkInline(child, src, style, out)
    }
}

/// A reference link written as the inside of `[[…]]`: one more bracket
/// either side of it in the source.
private fun insideWikiBrackets(node: ASTNode, src: String): Boolean {
    val before = node.startOffset - 1
    val after = node.endOffset
    return before >= 0 && after < src.length && src[before] == '[' && src[after] == ']'
}

/// Emphasis nodes carry their own `*`/`_` delimiters as child tokens.
/// Rich reading drops them; the source keeps them, which is the whole
/// point of rendering rather than transforming.
private fun emphasised(node: ASTNode, src: String, style: Span, out: MutableList<Span>, markers: Int) {
    val inner = node.children.drop(markers).dropLast(markers)
    for (child in inner) walkInline(child, src, style, out)
}

private fun merge(spans: List<Span>): List<Span> {
    val merged = mutableListOf<Span>()
    for (span in spans) {
        if (span.text.isEmpty()) continue
        val last = merged.lastOrNull()
        if (last != null && last.copy(text = "") == span.copy(text = "")) {
            // Still a slice only when the two runs touch in the source.
            val contiguous = last.source >= 0 && span.source == last.source + last.text.length
            merged[merged.size - 1] = last.copy(text = last.text + span.text).at(if (contiguous) last.source else -1)
        } else {
            merged.add(span)
        }
    }
    if (merged.size == 1) {
        val only = merged[0]
        val lead = only.text.length - only.text.trimStart().length
        return listOf(only.copy(text = only.text.trim()).at(if (only.source >= 0) only.source + lead else -1))
    }
    return merged
}
