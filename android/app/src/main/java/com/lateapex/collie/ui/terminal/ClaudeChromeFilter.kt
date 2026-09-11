package com.lateapex.collie.ui.terminal

import android.text.SpannableStringBuilder

/**
 * Removes the deterministic Claude Code composer box from an already parsed terminal mirror.
 *
 * This intentionally implements a conservative subset of the web Claude adapter. It requires the
 * complete top-border -> prompt -> bottom-border shape and keeps the status rows that the web client
 * re-surfaces above its composer. Unrecognised variants are returned unchanged.
 */
data class ChromeSplit(val body: CharSequence, val statusRows: List<CharSequence>)

class ClaudeChromeFilter {
    fun filter(input: CharSequence, reflow: Boolean = true): CharSequence = split(input, reflow).body

    /** The pane renders [statusRows] as one pinned chrome row under the mirror body. */
    fun split(input: CharSequence, reflow: Boolean = true): ChromeSplit {
        val lines = splitLines(input)
        var end = lines.size
        while (end > 0 && lines[end - 1].text.isBlank()) end--
        if (end == 0) return ChromeSplit(input, emptyList())
        val width = SoftWrapReflow.gridWidth(lines.map { it.text })

        // No box at the tail (a dialog is up, or the buffer is torn): still collapse the padding
        // Claude leaves under the dialog, otherwise the mirror above the native panel reads blank.
        val box = locateInputBox(lines, end) ?: run {
            val collapsed = reflowLines(collapsePadding(lines), width, reflow)
            return ChromeSplit(
                if (collapsed == lines) input else joinLines(input, collapsed),
                emptyList(),
            )
        }
        var bodyEnd = box.top
        while (bodyEnd > 0 && lines[bodyEnd - 1].text.isBlank()) bodyEnd--

        val body = joinLines(input, reflowLines(collapsePadding(lines.subList(0, bodyEnd)), width, reflow))
        val rows = (box.bottomBorder + 1 until box.statusEnd)
            .map { lines[it] }
            .filter { it.text.isNotBlank() }
            .map { input.subSequence(it.start, it.end).trim() }
        return ChromeSplit(body, rows)
    }

    /**
     * A joined row keeps the span of its first source row; the joined text is carried in
     * [Line.text] and [joinLines] re-emits it from the source rows so colour spans survive.
     */
    private fun reflowLines(body: List<Line>, width: Int, enabled: Boolean): List<Line> {
        if (!enabled) return body
        val texts = body.map { it.text }
        val groups = SoftWrapReflow.groups(texts, width)
        if (groups.size == body.size) return body
        val joined = SoftWrapReflow.reflow(texts, width)
        return groups.mapIndexed { index, range ->
            val sources = body.subList(range.first, range.last + 1)
            if (sources.size == 1) sources[0]
            else Line(sources.first().start, sources.last().end, joined[index], sources.toList())
        }
    }

    /**
     * Claude pins its input box to the bottom of the terminal and pads the rows between the last
     * transcript line and the box with blanks: a short conversation on a 67-row terminal carries
     * 40-odd empty rows, and the token-count row directly above the box stops the blank trim from
     * reaching them. On the phone that read as an empty mirror with a footer (S25 Ultra,
     * 2026-09-10). A run longer than [MAX_BLANK_RUN] collapses to that many rows; no content moves.
     */
    private fun collapsePadding(body: List<Line>): List<Line> {
        val out = ArrayList<Line>(body.size)
        var blanks = 0
        for (line in body) {
            if (line.text.isBlank()) {
                blanks++
                if (blanks > MAX_BLANK_RUN) continue
            } else {
                blanks = 0
            }
            out.add(line)
        }
        return out
    }

    private fun locateInputBox(lines: List<Line>, end: Int): InputBox? {
        var cursor = end - 1

        // A background-agent footer is accepted only with Claude's literal header and agent-row
        // markers. The web parser accepts this run positionally; the native subset stays narrower so
        // arbitrary terminal output below a blank separator can never be discarded as a footer.
        val footerStart = findFooterStart(lines, cursor)
        if (footerStart != null) {
            cursor = footerStart - 1
            while (cursor >= 0 && lines[cursor].text.isBlank()) cursor--
        }

        val statusEnd = cursor + 1
        var statusRows = 0
        while (
            cursor >= 0 &&
            !isBoxBorder(lines[cursor].text) &&
            lines[cursor].text.isNotBlank() &&
            statusRows < MAX_STATUS_LINES
        ) {
            statusRows++
            cursor--
        }

        if (cursor < 0 || !isBoxBorder(lines[cursor].text)) return null
        val bottomBorder = cursor
        cursor--

        var draftRows = 0
        while (
            cursor >= 0 &&
            !isBoxBorder(lines[cursor].text) &&
            !lines[cursor].text.trimStart().startsWith(PROMPT_MARKER) &&
            draftRows < MAX_DRAFT_LINES
        ) {
            draftRows++
            cursor--
        }
        if (cursor < 0 || !lines[cursor].text.trimStart().startsWith(PROMPT_MARKER)) return null
        cursor--

        while (cursor >= 0 && lines[cursor].text.isBlank() && draftRows < MAX_DRAFT_LINES) {
            draftRows++
            cursor--
        }
        if (cursor < 0 || !isInputBoxTopBorder(lines[cursor].text)) return null

        return InputBox(top = cursor, bottomBorder = bottomBorder, statusEnd = statusEnd)
    }

    private fun findFooterStart(lines: List<Line>, tail: Int): Int? {
        if (tail < 0) return null
        var cursor = tail
        var rows = 0
        while (cursor >= 0 && lines[cursor].text.isNotBlank() && rows < MAX_FOOTER_LINES) {
            rows++
            cursor--
        }
        if (rows == 0 || cursor < 0 || lines[cursor].text.isNotBlank()) return null

        val start = cursor + 1
        if (!FOOTER_HEADER.matches(lines[start].text.trim())) return null
        if ((start + 1..tail).any { !FOOTER_ROW.matches(lines[it].text.trim()) }) return null
        return start
    }

    private fun isBoxBorder(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < MIN_BORDER_LENGTH) return false
        if (trimmed.all { it == BORDER_GLYPH }) return true
        val match = LABELLED_BORDER.matchEntire(trimmed) ?: return false
        return match.groupValues[1].any { !it.isWhitespace() && it != BORDER_GLYPH }
    }

    private fun isInputBoxTopBorder(text: String): Boolean {
        if (isBoxBorder(text)) return true
        val trimmed = text.trim()
        if (trimmed.length < MIN_BORDER_LENGTH) return false
        val match = LOOSE_LABELLED_BORDER.matchEntire(trimmed) ?: return false
        return match.groupValues[1].any { !it.isWhitespace() && it != BORDER_GLYPH }
    }

    private fun splitLines(input: CharSequence): List<Line> {
        val text = input.toString()
        val lines = mutableListOf<Line>()
        var start = 0
        for (index in text.indices) {
            if (text[index] == '\n') {
                lines += Line(start, index, text.substring(start, index))
                start = index + 1
            }
        }
        lines += Line(start, text.length, text.substring(start))
        return lines
    }

    private fun joinLines(input: CharSequence, lines: List<Line>): CharSequence {
        val output = SpannableStringBuilder()
        lines.forEachIndexed { index, line ->
            if (index > 0) output.append('\n')
            val sources = line.sources
            if (sources.size == 1) {
                output.append(input, line.start, line.end)
            } else {
                sources.forEachIndexed { sourceIndex, source ->
                    if (sourceIndex > 0) output.append(' ')
                    val text = input.substring(source.start, source.end)
                    val leading = text.length - text.trimStart().length
                    val trailing = text.length - text.trimEnd().length
                    output.append(input, source.start + leading, source.end - trailing)
                }
            }
        }
        return output
    }

    private data class Line(val start: Int, val end: Int, val text: String, val joinedFrom: List<Line>? = null) {
        /** A row split out of the grid is re-emitted from its source rows so the spans survive. */
        val sources: List<Line> get() = joinedFrom ?: listOf(this)
    }

    private data class InputBox(val top: Int, val bottomBorder: Int, val statusEnd: Int)

    companion object {
        private const val BORDER_GLYPH = '─'
        private const val PROMPT_MARKER = "❯"
        private const val MIN_BORDER_LENGTH = 8
        private const val MAX_STATUS_LINES = 8
        private const val MAX_BLANK_RUN = 2
        private const val MAX_FOOTER_LINES = 8
        private const val MAX_DRAFT_LINES = 100
        private val LABELLED_BORDER = Regex("^─{2,}\\s+(.+)\\s+─{2,}$")
        private val LOOSE_LABELLED_BORDER = Regex("^─+\\s+(.+)\\s+─+$")
        private val FOOTER_HEADER = Regex("^●\\s+main$")
        private val FOOTER_ROW = Regex("^[○◯]\\s+\\S.*$")
    }
}
