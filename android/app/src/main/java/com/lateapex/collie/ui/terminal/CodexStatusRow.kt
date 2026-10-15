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
    private const val MAX_ROW_CHARS = 512
    private val inlineQueue = Regex("^ {2}\\S.*\\bto queue(?: message)?\\s+\\d+% context (?:left|used)\\b.*$")
    private val contextShape = Regex(
        "^ {2}\\S.* · (?:(?:.* · )+Context \\d+% (?:left|used)\\b|Context \\d+% (?:left|used)\\b · \\S).*$",
    )

    fun matches(rawLine: String): Boolean {
        val plain = TerminalPlainText.strip(rawLine).trimEnd()
        if (contextShape.matches(plain) || inlineQueue.matches(plain)) return true
        if (plain.codePointCount(0, plain.length) > MAX_ROW_CHARS || plain.any(::isControl)) return false
        val segments = splitPaintedGaps(segments(rawLine.trimEnd())).toMutableList()
        while (segments.lastOrNull()?.text?.isBlank() == true) segments.removeAt(segments.lastIndex)
        if (segments.isNotEmpty()) segments[segments.lastIndex] = segments.last().copy(text = segments.last().text.trimEnd())
        if (segments.size < 4 || segments.first().text != "  " || !segments.first().style.isPlain) return false

        var cursor = 1
        var fields = 0
        var paint: String? = null
        val fieldColours = mutableSetOf<String>()
        while (cursor < segments.size) {
            val field = segments[cursor]
            if (!field.style.isField || !validField(field.text)) return false
            fieldColours += field.style.foreground ?: return false
            fields++
            cursor++
            if (cursor >= segments.size) break

            val separator = segments[cursor]
            if (separator.style.isPlain && separator.text.length >= 2 && separator.text.all { it == ' ' }) {
                if (fields < MIN_FIELDS || !validRightStatusTail(segments.drop(cursor + 1))) return false
                break
            }
            val nextPaint = separator.style.separatorPaint ?: return false
            if (paint != null && paint != nextPaint) return false
            paint = nextPaint
            if (separator.text != SEPARATOR && separator.text.startsWith(SEPARATOR)) {
                if (fields < MIN_FIELDS || !validField(separator.text.removePrefix(SEPARATOR))) return false
                if (cursor != segments.lastIndex) {
                    val gap = segments[cursor + 1]
                    if (!gap.style.isPlain || gap.text.length < 2 || gap.text.any { it != ' ' } ||
                        !validRightStatusTail(segments.drop(cursor + 2))) return false
                }
                fields++
                break
            }
            if (separator.text != SEPARATOR || cursor == segments.lastIndex) return false
            val spinner = segments.getOrNull(cursor + 1)
            if (cursor == segments.size - 2 && spinner != null && spinner.style.isField &&
                Regex("[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏]").matches(spinner.text)
            ) break
            cursor++
        }
        if (fields !in MIN_FIELDS..MAX_FIELDS) return false
        return paint == null || !paint.startsWith("fg:") || paint.removePrefix("fg:") !in fieldColours
    }

    private fun validRightStatusTail(tail: List<Segment>): Boolean {
        if (tail.isEmpty() || tail.size > 12 || tail.first().text.startsWith(' ')) return false
        return tail.sumOf { it.text.codePointCount(0, it.text.length) } <= MAX_FIELD_CHARS &&
            tail.all { !it.style.hasBackground && (it.style.foreground != null || it.style.dim) }
    }

    private fun splitPaintedGaps(input: List<Segment>): List<Segment> = input.flatMapIndexed { index, segment ->
        val match = Regex("^(\\S(?:.*\\S)?)( {2,})$").matchEntire(segment.text)
        if (match == null || index == input.lastIndex || segment.style.foreground == null) listOf(segment)
        else listOf(segment.copy(text = match.groupValues[1]), Segment(match.groupValues[2], Style()))
    }

    /** Only renderer-painted single braille glyphs are animation; an unpainted typed glyph stays. */
    fun composerText(raw: String): String = segments(raw).joinToString("") {
        if (it.style.foreground != null && it.text.length == 1 && it.text[0] in '\u2800'..'\u28ff') " "
        else it.text
    }.trimEnd()

    fun isDimPlaceholder(raw: String): Boolean {
        val parts = segments(raw)
        val bodyEnd = 2 + CodexComposer.PLACEHOLDER.length
        var offset = 0
        var sawBody = false
        for (part in parts) {
            val end = offset + part.text.length
            if (offset < bodyEnd && end > 2) {
                val body = part.text.substring((2 - offset).coerceAtLeast(0), (bodyEnd - offset).coerceAtMost(part.text.length))
                if (body.any { !it.isWhitespace() }) {
                    sawBody = true
                    if (!part.style.dim) return false
                }
            }
            offset = end
        }
        return sawBody
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
                3 -> result = result.copy(italic = true)
                4 -> result = result.copy(underline = true)
                22 -> result = result.copy(bold = false, dim = false)
                23 -> result = result.copy(italic = false)
                24 -> result = result.copy(underline = false)
                in 30..37, in 90..97 -> result = result.copy(foreground = codes[cursor].toString())
                39 -> result = result.copy(foreground = null)
                in 40..47, in 100..107 -> result = result.copy(hasBackground = true)
                49 -> result = result.copy(hasBackground = false)
                38 -> {
                    val args = extendedColourArgs(codes, cursor + 1)
                    result = result.copy(foreground = codes.subList(cursor, (cursor + args + 1).coerceAtMost(codes.size)).joinToString(";"))
                    cursor += args
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
        val foreground: String? = null,
        val hasBackground: Boolean = false,
        val bold: Boolean = false,
        val dim: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
    ) {
        val isPlain: Boolean get() = foreground == null && !hasBackground && !bold && !dim && !italic && !underline
        val isField: Boolean get() = foreground != null && !hasBackground && !bold && !dim && !italic && !underline
        val separatorPaint: String? get() = when {
            hasBackground || bold || italic || underline -> null
            dim -> if (foreground == null) "dim" else null
            foreground == null -> "plain"
            else -> "fg:$foreground"
        }
    }

    private const val ESC = '\u001b'
}
