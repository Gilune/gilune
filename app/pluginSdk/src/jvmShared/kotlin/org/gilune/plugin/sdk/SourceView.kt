package org.gilune.plugin.sdk

import uniffi.gilune_ffi.*

/** Pure navigation of the supplied source map; never re-enters a kernel handle. */
class SourceView(val snapshot: SourceTextSnapshot) {
    private val byteOffsets = IntArray(snapshot.text.length + 1)
    private val itemRanges = snapshot.items.flatMap { item -> item.ownRanges.map { it to item.key } }.sortedBy { it.first.start }

    init {
        var char = 0
        var byte = 0
        while (char < snapshot.text.length) {
            val cp = snapshot.text.codePointAt(char)
            val width = Character.charCount(cp)
            repeat(width) { byteOffsets[char + it] = byte }
            char += width
            byte += when { cp < 0x80 -> 1; cp < 0x800 -> 2; cp < 0x10000 -> 3; else -> 4 }
        }
        byteOffsets[char] = byte
    }

    fun byteOffset(char: Int): ULong {
        require(char in byteOffsets.indices && (char == 0 || char == snapshot.text.length || !snapshot.text[char].isLowSurrogate()))
        return byteOffsets[char].toULong()
    }

    fun charOffset(byte: ULong): Int {
        require(byte <= byteOffsets.last().toULong())
        var low = 0
        var high = byteOffsets.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (byteOffsets[mid].toULong() < byte) low = mid + 1 else high = mid
        }
        require(byteOffsets[low].toULong() == byte) { "Offset is inside a UTF-8 codepoint" }
        return low
    }

    fun locationAt(char: Int): SourceLocation {
        val byte = byteOffset(char)
        require(byte < byteOffsets.last().toULong())
        if (byte < snapshot.bodyStart) return SourceLocation.DocumentRoot
        var low = 0
        var high = itemRanges.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (itemRanges[mid].first.start <= byte) low = mid + 1 else high = mid
        }
        itemRanges.getOrNull(low - 1)?.let { (range, key) ->
            if (byte < range.end) return SourceLocation.MarkdownItem(key)
        }
        low = 0
        high = snapshot.lines.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (snapshot.lines[mid].range.start <= byte) low = mid + 1 else high = mid
        }
        val line = snapshot.lines[low - 1]
        require(byte < line.range.end)
        return SourceLocation.BodyLine(line.number)
    }

    fun metadata(): SourceValue = SourceValue(snapshot.metadata, 0u)
}

/** Typed YAML values retain sequences, non-string keys, aliases and tags. */
class SourceValue(private val nodes: List<SourceValueNode>, private val id: UInt) {
    private val node get() = nodes[id.toInt()]
    val kind get() = node.kind
    val value get() = node.value
    fun children(): List<SourceValue> = node.children.map { SourceValue(nodes, it) }
    fun entries(): List<Pair<SourceValue, SourceValue>> =
        if (kind == "mapping") children().chunked(2).map { it[0] to it[1] } else emptyList()
    operator fun get(key: String): SourceValue? = entries().singleOrNull { it.first.kind == "string" && it.first.value == key }?.second
    fun string(): String? = if (kind == "string") value else null
}
