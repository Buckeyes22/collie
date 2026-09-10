package com.lateapex.collie.ui.terminal

/**
 * Bounds the live Android text layout without weakening prompt/write verification, which continues
 * to use the complete pane response. History remains the unabridged reader for session-backed panes.
 */
internal object TerminalRenderWindow {
    const val MAX_RENDER_CHARS = 16 * 1_024
    const val MAX_LINE_CHARS = 1 * 1_024
    private const val INPUT_TAIL_CHARS = MAX_RENDER_CHARS * 2

    fun limit(raw: String, omittedMarker: String): String {
        var omitted = raw.length > INPUT_TAIL_CHARS
        val input = if (omitted) raw.takeLast(INPUT_TAIL_CHARS) else raw
        val rows = input.split('\n').map { row ->
            if (row.length <= MAX_LINE_CHARS) {
                row
            } else {
                omitted = true
                val side = (MAX_LINE_CHARS - omittedMarker.length - 2).coerceAtLeast(0) / 2
                row.take(side) + " $omittedMarker " + row.takeLast(side)
            }
        }
        var bounded = rows.joinToString("\n")
        if (bounded.length > MAX_RENDER_CHARS) {
            omitted = true
            bounded = bounded.takeLast(MAX_RENDER_CHARS)
            val nextLine = bounded.indexOf('\n')
            if (nextLine >= 0) bounded = bounded.substring(nextLine + 1)
        }
        return if (omitted) "$omittedMarker\n$bounded" else bounded
    }
}
