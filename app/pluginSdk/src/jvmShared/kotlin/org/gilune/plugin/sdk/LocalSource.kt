package org.gilune.plugin.sdk

import uniffi.gilune_ffi.*

/** Restrict recognition to selected locations while retaining exact source and
 * the full location map for attribution. Metadata remains available as context. */
fun SourceTextSnapshot.forLocations(locations: Set<SourceLocation>): SourceTextSnapshot {
    val ranges = buildList {
        items.filter { SourceLocation.MarkdownItem(it.key) in locations }.forEach { addAll(it.ownRanges) }
        lines.filter { SourceLocation.BodyLine(it.number) in locations }.forEach { add(it.range) }
    }.sortedBy { it.start }
    val merged = mutableListOf<SourceSpan>()
    ranges.forEach { range ->
        val last = merged.lastOrNull()
        if (last != null && range.start <= last.end) merged[merged.lastIndex] = SourceSpan(last.start, maxOf(last.end, range.end))
        else merged += range
    }
    var at = 0
    val allowed = buildList {
        textRanges.forEach { text ->
            while (at < merged.size && merged[at].end <= text.start) at++
            var i = at
            while (i < merged.size && merged[i].start < text.end) {
                val range = merged[i++]
                add(SourceSpan(maxOf(text.start, range.start), minOf(text.end, range.end)))
            }
        }
    }
    return copy(textRanges = allowed)
}
