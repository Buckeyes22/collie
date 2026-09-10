package com.lateapex.collie.ui.terminal

/**
 * Joins the soft wraps Herdr's grid imposed on a paragraph so the phone can wrap it once.
 * The grid width comes from the read itself: the longest visible row. A row is a wrap
 * candidate only when it fills that width exactly; anything Claude prints as structure
 * (tables, rules, fences, bullets, tree markers) is never joined in either direction.
 */
object SoftWrapReflow {
    private val structural = Regex("^\\s*(?:[│┃|┌└├┬┴┼─━═╭╮╰╯]|```|[•\\-*☐☒⎿]\\s|\\d+\\.\\s)")

    fun gridWidth(lines: List<String>): Int = lines.maxOfOrNull { it.trimEnd().length } ?: 0

    fun reflow(lines: List<String>, gridWidth: Int): List<String> {
        if (gridWidth <= 0 || lines.size < 2) return lines
        val out = ArrayList<String>(lines.size)
        var index = 0
        while (index < lines.size) {
            var current = lines[index]
            var next = index + 1
            while (next < lines.size && canJoin(current, lines[next], gridWidth)) {
                current = current.trimEnd() + " " + lines[next].trim()
                next++
            }
            out.add(current)
            index = next
        }
        return out
    }

    private fun canJoin(current: String, next: String, gridWidth: Int): Boolean {
        val trimmed = current.trimEnd()
        if (trimmed.length < gridWidth) return false
        if (next.isBlank()) return false
        if (structural.containsMatchIn(trimmed) || structural.containsMatchIn(next)) return false
        val indent = next.length - next.trimStart().length
        return indent >= 2
    }
}
