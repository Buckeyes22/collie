package com.lateapex.collie.ui.terminal

/**
 * Joins the soft wraps Herdr's grid imposed on a paragraph so the phone can wrap it once.
 * The wrap width comes from the read itself: the longest prose row. A seam is a wrap when the
 * next row's first word would not have fit on the row above; anything Claude prints as
 * structure (tables, rules, fences, bullets, tree markers) is never joined in either direction.
 */
object SoftWrapReflow {
    private val structural = Regex("^\\s*(?:[│┃|┌└├┬┴┼─━═╭╮╰╯]|```|[•\\-*☐☒⎿]\\s|\\d+\\.\\s)")

    /**
     * The width prose wraps at. Claude draws its rules and box borders across the whole
     * terminal but wraps prose a few columns short of that, so a width taken from every row
     * never matched a prose row and nothing joined (S25 Ultra, 2026-09-10). Structural rows
     * are excluded; the longest prose row is the wrap width.
     */
    fun gridWidth(lines: List<String>): Int =
        lines.filter { !structural.containsMatchIn(it) }.maxOfOrNull { it.trimEnd().length } ?: 0

    fun reflow(lines: List<String>, gridWidth: Int): List<String> =
        groups(lines, gridWidth).map { range -> join(lines, range) }

    /**
     * The source rows each reflowed row was built from, as inclusive index ranges in order.
     * A caller that carries spans per row groups by these instead of re-deriving the join
     * from the text: an unjoined row keeps its trailing grid padding while a joined one is
     * trimmed, so text comparison cannot tell them apart (S25 Ultra crash, 2026-09-10).
     */
    fun groups(lines: List<String>, gridWidth: Int): List<IntRange> {
        if (gridWidth <= 0 || lines.size < 2) return lines.indices.map { it..it }
        val out = ArrayList<IntRange>(lines.size)
        var index = 0
        while (index < lines.size) {
            var next = index + 1
            // Each seam is judged by the row above it, never by the paragraph joined so far: a
            // joined paragraph is always wider than the grid and would swallow every indented
            // row after it.
            while (next < lines.size && canJoin(lines[next - 1], lines[next], gridWidth)) next++
            out.add(index until next)
            index = next
        }
        return out
    }

    private fun join(lines: List<String>, range: IntRange): String {
        if (range.first == range.last) return lines[range.first]
        return range.joinToString(" ") { if (it == range.first) lines[it].trimEnd() else lines[it].trim() }
    }

    /**
     * Claude wraps at word boundaries, so a soft-wrapped row ends up to one word short of the
     * width. The break was a wrap exactly when the next row's first word would not have fit on
     * this row; where it would have fit, the author broke the line (S25 Ultra, 2026-09-10).
     */
    private fun canJoin(row: String, next: String, gridWidth: Int): Boolean {
        val trimmed = row.trimEnd()
        if (trimmed.isEmpty() || next.isBlank()) return false
        if (structural.containsMatchIn(trimmed) || structural.containsMatchIn(next)) return false
        val body = next.trimStart()
        if (next.length - body.length < 2) return false
        return trimmed.length + 1 + body.substringBefore(' ').length > gridWidth
    }
}
