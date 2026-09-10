package com.lateapex.collie.ui.terminal

/** Strips inert terminal control sequences without allocating paint spans for grammar-only scans. */
internal object TerminalPlainText {
    fun strip(input: String): String {
        val out = StringBuilder(input.length)
        var index = 0
        while (index < input.length) {
            val char = input[index]
            when {
                char == ESC && input.getOrNull(index + 1) == '[' -> {
                    index = csiEnd(input, index + 2).let { if (it < 0) input.length else it + 1 }
                }
                char == ESC && input.getOrNull(index + 1) == ']' -> {
                    index = oscEnd(input, index + 2)
                }
                char == '\r' -> {
                    if (input.getOrNull(index + 1) != '\n' && index + 1 < input.length) {
                        val lineStart = out.lastIndexOf("\n") + 1
                        out.delete(lineStart, out.length)
                    }
                    index++
                }
                char == '\n' || char == '\t' || char.code >= 0x20 -> {
                    out.append(char)
                    index++
                }
                else -> index++
            }
        }
        return out.toString()
    }

    private fun csiEnd(input: String, start: Int): Int {
        var cursor = start
        while (cursor < input.length) {
            if (input[cursor].code in 0x40..0x7e) return cursor
            cursor++
        }
        return -1
    }

    private fun oscEnd(input: String, start: Int): Int {
        var cursor = start
        while (cursor < input.length) {
            if (input[cursor] == '\u0007') return cursor + 1
            if (input[cursor] == ESC && input.getOrNull(cursor + 1) == '\\') return cursor + 2
            cursor++
        }
        return input.length
    }

    private const val ESC = '\u001b'
}
