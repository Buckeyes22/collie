package com.lateapex.collie.ui.terminal

internal data class TerminalTableRun(val start: Int, val endExclusive: Int)

/** Port of the web mirror's strict markdown/ASCII/box-table run grammar. */
internal object TerminalTableRuns {
    private val markdownDelimiter = Regex("^\\|?\\s*:?-+:?\\s*(?:\\|\\s*:?-+:?\\s*)+\\|?$")
    private val asciiDelimiter = Regex("^\\+(?:-+\\+)+$")
    private val verticals = "│┃┆┇┊┋╎╏║"

    fun find(text: CharSequence): List<TerminalTableRun> {
        if (text.isEmpty()) return emptyList()
        val rows = rows(text.toString())
        val runs = mutableListOf<TerminalTableRun>()
        var floor = 0
        for (index in rows.indices) {
            if (index < floor) continue
            val grid = rows[index].text.trimEnd()
            if (grid.isBlank()) continue
            val trimmed = grid.trimStart()
            val member: ((String) -> Boolean)? = when {
                markdownDelimiter.matches(trimmed) -> run {
                    val pipes = trimmed.filter { char -> char == '|' }.length
                    val predicate: (String) -> Boolean = { row ->
                        row.isNotBlank() && row.trimStart().filter { char -> char == '|' }.length == pipes
                    }
                    predicate
                }
                asciiDelimiter.matches(trimmed) -> offsetMember(grid, '+') { it == '|' || it == '+' }
                grid.all { it.isWhitespace() || it == '-' || it in '\u2500'..'\u257f' } &&
                    grid.any(::isBoxCross) -> offsetMember(grid, null) { it in verticals || isBoxJunction(it) }
                else -> null
            }
            if (member == null) continue
            var start = index
            while (start > floor && member(rows[start - 1].text.trimEnd())) start--
            var end = index
            while (end + 1 < rows.size && member(rows[end + 1].text.trimEnd())) end++
            floor = end + 1
            if (start == end) continue
            runs += TerminalTableRun(rows[start].start, rows[end].endExclusive)
        }
        return runs
    }

    private fun offsetMember(
        anchor: String,
        ascii: Char?,
        separator: (Char) -> Boolean,
    ): (String) -> Boolean {
        val offsets = anchor.indices.filter { at ->
            if (ascii != null) anchor[at] == ascii else isBoxJunction(anchor[at])
        }
        return { row -> row.isNotBlank() && offsets.all { it < row.length && separator(row[it]) } }
    }

    private fun isBoxCross(char: Char): Boolean = char in '\u253c'..'\u254b' || char in '\u256a'..'\u256c'
    private fun isBoxJunction(char: Char): Boolean = char in '\u252c'..'\u254b' || char in '\u2564'..'\u256c'

    private fun rows(text: String): List<Row> {
        val result = mutableListOf<Row>()
        var start = 0
        while (start < text.length) {
            val newline = text.indexOf('\n', start)
            val end = if (newline < 0) text.length else newline
            result += Row(start, if (newline < 0) end else end + 1, text.substring(start, end))
            if (newline < 0) break
            start = newline + 1
        }
        return result
    }

    private data class Row(val start: Int, val endExclusive: Int, val text: String)
}
