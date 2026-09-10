package com.lateapex.collie.ui.terminal

/**
 * Recognizes Codex's status row from either its Context-bearing text grammar or the renderer paint
 * used by newer configurable status lines. The painted path intentionally refuses an unstyled
 * lookalike: submitted transcript text can contain the same model/path words.
 */
internal object CodexStatusRow {
    private const val SEPARATOR = " · "
    private const val MIN_FIELDS = 2
    private const val MAX_FIELDS = 12
    private const val MAX_FIELD_CHARS = 160
    private const val MAX_RIGHT_STATUS_CHARS = 96
    private val contextShape = Regex(
        "^ {2}\\S.* · (?:(?:.* · )+Context \\d+% (?:left|used)\\b|Context \\d+% (?:left|used)\\b · \\S).*$",
    )

    fun matches(rawLine: String): Boolean {
        val plain = TerminalPlainText.strip(rawLine).trimEnd()
        if (contextShape.matches(plain)) return true
        val segments = segments(rawLine)
        if (segments.size < 4 || segments.first().text != "  " || !segments.first().style.isPlain) return false

        var cursor = 1
        var fields = 0
        while (cursor < segments.size) {
            val field = segments[cursor]
            if (!field.style.isField || !validField(field.text)) break
            fields++
            cursor++
            if (cursor >= segments.size) break

            val separator = segments[cursor]
            if (separator.style.isDimPlain && separator.text == SEPARATOR) {
                cursor++
                continue
            }
            if (separator.style.isDimPlain && separator.text.startsWith(SEPARATOR)) {
                if (fields < MIN_FIELDS || !validField(separator.text.removePrefix(SEPARATOR))) return false
                fields++
                cursor++
                break
            }
            break
        }
        if (fields !in MIN_FIELDS..MAX_FIELDS) return false
        return validRightStatusTail(segments.drop(cursor))
    }

    private fun validRightStatusTail(tail: List<Segment>): Boolean {
        if (tail.isEmpty()) return true
        var cursor = 0
        val padding = tail[cursor]
        if (!padding.style.isPlain || padding.text.length < 2 || padding.text.any { !it.isWhitespace() }) return false
        cursor++
        if (cursor == tail.size) return true
        val status = tail[cursor]
        if (!status.style.hasForeground || status.style.hasBackground || status.style.bold || status.style.dim ||
            status.text.isBlank() || status.text != status.text.trim() ||
            status.text.codePointCount(0, status.text.length) > MAX_RIGHT_STATUS_CHARS
        ) return false
        cursor++
        if (cursor == tail.size) return true
        return tail.drop(cursor).all { it.style.isPlain && it.text.all(Char::isWhitespace) }
    }

    private fun validField(value: String): Boolean =
        value.isNotEmpty() && value == value.trim() && value.none(::isControl) &&
            value.codePointCount(0, value.length) <= MAX_FIELD_CHARS

    private fun isControl(char: Char): Boolean = char != '\t' && (char.code < 0x20 || char.code == 0x7f)

    private fun segments(raw: String): List<Segment> {
        val result = mutableListOf<Segment>()
        val text = StringBuilder()
        var style = Style()
        fun flush() {
            if (text.isEmpty()) return
            val value = text.toString()
            val previous = result.lastOrNull()
            if (previous?.style == style) result[result.lastIndex] = previous.copy(text = previous.text + value)
            else result += Segment(value, style)
            text.clear()
        }

        var index = 0
        while (index < raw.length) {
            if (raw[index] == ESC && raw.getOrNull(index + 1) == '[') {
                flush()
                var end = index + 2
                while (end < raw.length && raw[end].code !in 0x40..0x7e) end++
                if (end >= raw.length) break
                if (raw[end] == 'm') style = applySgr(style, raw.substring(index + 2, end))
                index = end + 1
            } else {
                val char = raw[index++]
                if (char == '\r') continue
                if (char == '\t' || char.code >= 0x20) text.append(char)
            }
        }
        flush()
        return result.filter { it.text.isNotEmpty() }
    }

    private fun applySgr(initial: Style, body: String): Style {
        val codes = if (body.isBlank()) listOf(0) else body.split(';', ':').mapNotNull(String::toIntOrNull)
        var result = initial
        var cursor = 0
        while (cursor < codes.size) {
            when (codes[cursor]) {
                0 -> result = Style()
                1 -> result = result.copy(bold = true)
                2 -> result = result.copy(dim = true)
                22 -> result = result.copy(bold = false, dim = false)
                in 30..37, in 90..97 -> result = result.copy(hasForeground = true)
                39 -> result = result.copy(hasForeground = false)
                in 40..47, in 100..107 -> result = result.copy(hasBackground = true)
                49 -> result = result.copy(hasBackground = false)
                38 -> {
                    result = result.copy(hasForeground = true)
                    cursor += extendedColourArgs(codes, cursor + 1)
                }
                48 -> {
                    result = result.copy(hasBackground = true)
                    cursor += extendedColourArgs(codes, cursor + 1)
                }
            }
            cursor++
        }
        return result
    }

    private fun extendedColourArgs(codes: List<Int>, start: Int): Int = when (codes.getOrNull(start)) {
        5 -> 2
        2 -> 4
        else -> 0
    }

    private data class Segment(val text: String, val style: Style)

    private data class Style(
        val hasForeground: Boolean = false,
        val hasBackground: Boolean = false,
        val bold: Boolean = false,
        val dim: Boolean = false,
    ) {
        val isPlain: Boolean get() = !hasForeground && !hasBackground && !bold && !dim
        val isField: Boolean get() = hasForeground && !hasBackground && !bold && !dim
        val isDimPlain: Boolean get() = !hasForeground && !hasBackground && !bold && dim
    }

    private const val ESC = '\u001b'
}
