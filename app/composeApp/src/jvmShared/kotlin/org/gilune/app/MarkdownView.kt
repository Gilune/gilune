// Rendering the Markdown model. Reading only: nothing here can change a
// document, which is what keeps the buffer authoritative (ADR 0012).

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

@Composable
fun MarkdownBody(blocks: List<MdBlock>, modifier: Modifier = Modifier) {
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
                is MdBlock.Code -> Text(
                    block.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(colours.surfaceVariant)
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
                )
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
