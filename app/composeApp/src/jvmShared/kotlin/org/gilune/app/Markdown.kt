// Markdown source to a render model. Pure over a string, so it is
// testable without a screen, and it never rewrites the source: the buffer
// is the document and this is only a reading of it (ADR 0012).
//
// Front matter is split off before parsing. It is machine contract, not
// prose, and it must survive editing untouched — the editor writes it
// back verbatim, because dropping it would break a note's identity and
// re-import it as a new object.

package org.gilune.app

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

data class FrontMatterEntry(val key: String, val value: String)

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
                val code: Boolean = false, val strike: Boolean = false, val link: String? = null)

sealed interface MdBlock {
    data class Heading(val level: Int, val spans: List<Span>) : MdBlock
    data class Paragraph(val spans: List<Span>) : MdBlock
    data class ListItem(val marker: String, val spans: List<Span>) : MdBlock
    data class Quote(val spans: List<Span>) : MdBlock
    data class Code(val text: String, val language: String?) : MdBlock
    data object Rule : MdBlock

    /// An Obsidian-style embed, which is how a capture points at its
    /// original bytes. Rendered as a reference, never as a rewrite.
    data class Embed(val target: String) : MdBlock
}

private val EMBED = Regex("""^!\[\[(.+?)]]$""")

fun parseMarkdown(body: String): List<MdBlock> {
    val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(body)
    val blocks = mutableListOf<MdBlock>()
    for (node in tree.children) collectBlock(node, body, blocks, marker = null)
    return blocks
}

private fun collectBlock(node: ASTNode, src: String, out: MutableList<MdBlock>, marker: String?) {
    when (node.type) {
        MarkdownElementTypes.ATX_1 -> out.add(MdBlock.Heading(1, headingSpans(node, src)))
        MarkdownElementTypes.ATX_2 -> out.add(MdBlock.Heading(2, headingSpans(node, src)))
        MarkdownElementTypes.ATX_3 -> out.add(MdBlock.Heading(3, headingSpans(node, src)))
        MarkdownElementTypes.ATX_4 -> out.add(MdBlock.Heading(4, headingSpans(node, src)))
        MarkdownElementTypes.ATX_5 -> out.add(MdBlock.Heading(5, headingSpans(node, src)))
        MarkdownElementTypes.ATX_6 -> out.add(MdBlock.Heading(6, headingSpans(node, src)))
        MarkdownElementTypes.SETEXT_1 -> out.add(MdBlock.Heading(1, inlineSpans(node, src)))
        MarkdownElementTypes.SETEXT_2 -> out.add(MdBlock.Heading(2, inlineSpans(node, src)))
        MarkdownElementTypes.PARAGRAPH -> {
            val raw = node.getTextInNode(src).toString().trim()
            val embed = EMBED.find(raw)
            when {
                embed != null -> out.add(MdBlock.Embed(embed.groupValues[1]))
                marker != null -> out.add(MdBlock.ListItem(marker, inlineSpans(node, src)))
                else -> out.add(MdBlock.Paragraph(inlineSpans(node, src)))
            }
        }
        MarkdownElementTypes.BLOCK_QUOTE ->
            out.add(MdBlock.Quote(inlineSpans(node, src)))
        MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST ->
            for (item in node.children) collectBlock(item, src, out, marker)
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
                collectBlock(child, src, out, marker = if (emitted) "" else bullet)
                if (out.size > before) emitted = true
            }
        }
        MarkdownElementTypes.CODE_FENCE -> out.add(codeFence(node, src))
        MarkdownElementTypes.CODE_BLOCK ->
            out.add(MdBlock.Code(node.getTextInNode(src).toString().trimEnd(), null))
        MarkdownTokenTypes.HORIZONTAL_RULE -> out.add(MdBlock.Rule)
        else -> Unit // whitespace, line breaks, link definitions
    }
}

private fun codeFence(node: ASTNode, src: String): MdBlock.Code {
    val language = node.children
        .firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }
        ?.getTextInNode(src)?.toString()?.trim()
    val text = node.children
        .filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT || it.type == MarkdownTokenTypes.EOL }
        .joinToString("") { it.getTextInNode(src) }
    return MdBlock.Code(text.trim('\n'), language?.ifEmpty { null })
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
                out.add(linked.copy(text = node.getTextInNode(src).toString()))
            } else {
                // The label's bracket tokens are syntax, not text.
                val inner = label.children.filter {
                    it.type != MarkdownTokenTypes.LBRACKET && it.type != MarkdownTokenTypes.RBRACKET
                }
                for (child in inner) walkInline(child, src, linked, out)
            }
            return
        }
        MarkdownTokenTypes.EOL -> {
            out.add(style.copy(text = " "))
            return
        }
        MarkdownTokenTypes.BLOCK_QUOTE, MarkdownTokenTypes.ATX_HEADER -> return
    }
    if (node.children.isEmpty()) {
        out.add(style.copy(text = node.getTextInNode(src).toString()))
    } else {
        for (child in node.children) walkInline(child, src, style, out)
    }
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
            merged[merged.size - 1] = last.copy(text = last.text + span.text)
        } else {
            merged.add(span)
        }
    }
    if (merged.size == 1) {
        val only = merged[0]
        return listOf(only.copy(text = only.text.trim()))
    }
    return merged
}
