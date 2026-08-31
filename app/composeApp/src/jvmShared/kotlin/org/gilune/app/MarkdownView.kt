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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/// One `editor.blocks` registration (ADR 0015 § 5): the fence info string
/// it claims, and what draws that block. `render` never sees the buffer
/// and cannot change a byte — the shell owns composition, exactly as it
/// does for `editor.decorations`.
class BlockRegistration(val info: String, val render: @Composable (MdBlock.Code) -> Unit)

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

@Composable
fun MarkdownBody(
    blocks: List<MdBlock>,
    modifier: Modifier = Modifier,
    renderers: BlockRenderers = BlockRenderers(),
) {
    val colours = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Heading -> Text(
                    annotate(block.spans, colours.primary),
                    fontSize = headingSize(block.level),
                    fontWeight = FontWeight.SemiBold,
                )
                is MdBlock.Paragraph -> Text(
                    annotate(block.spans, colours.primary),
                    lineHeight = 22.sp,
                )
                is MdBlock.ListItem -> Row {
                    Text(
                        when (block.marker) {
                            "-", "*", "+" -> "•"
                            else -> block.marker.ifEmpty { " " }
                        },
                        modifier = Modifier.width(22.dp),
                        color = colours.onSurfaceVariant,
                    )
                    Text(annotate(block.spans, colours.primary), lineHeight = 22.sp)
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(2.dp).fillMaxHeight().background(colours.outline))
                    Text(
                        annotate(block.spans, colours.primary),
                        color = colours.onSurfaceVariant,
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                // The seam (§ 5). A registered info string draws itself;
                // every other one — and every unfenced code block, which
                // carries none — is the box it has always been.
                is MdBlock.Code -> {
                    val registration = renderers[block.language]
                    if (registration == null) CodeBox(block.text) else registration.render(block)
                }
                is MdBlock.Embed -> Text(
                    "▸ ${block.target}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = colours.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(colours.surfaceVariant)
                        .padding(10.dp),
                )
                MdBlock.Rule -> HorizontalDivider()
            }
        }
    }
}

/// The monospace box, unchanged and moved only so the `Code` branch can
/// fall through to it. **This is the removal story for a block renderer**
/// (§ 4): an info string nobody registered — including one whose plugin
/// has gone — is a legible code block again, byte for byte.
@Composable
private fun CodeBox(text: String) {
    val colours = MaterialTheme.colorScheme
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
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

private fun annotate(spans: List<Span>, linkColour: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
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
            ) { append(span.text) }
        }
    }
