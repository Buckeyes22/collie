package com.lateapex.collie.ui.terminal

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan

/** Converts terminal SGR sequences to inert Android text spans and drops every other control. */
class AnsiParser {
    fun parse(input: String, colourTransform: (Int) -> Int = { it }): CharSequence {
        val out = SpannableStringBuilder()
        var style = Style()
        var runStart = 0
        var index = 0
        while (index < input.length) {
            val char = input[index]
            when {
                char == ESC && index + 1 < input.length && input[index + 1] == '[' -> {
                    applyStyle(out, runStart, out.length, style, colourTransform)
                    val end = csiEnd(input, index + 2)
                    if (end == -1) break
                    if (input[end] == 'm') style = applySgr(style, input.substring(index + 2, end))
                    index = end + 1
                    runStart = out.length
                }
                char == ESC && index + 1 < input.length && input[index + 1] == ']' -> {
                    applyStyle(out, runStart, out.length, style, colourTransform)
                    index = oscEnd(input, index + 2)
                    runStart = out.length
                }
                char == '\r' -> {
                    // CRLF is a line ending, while a bare CR followed by more text is a terminal
                    // redraw. Only the latter replaces the current visual line.
                    if (input.getOrNull(index + 1) == '\n' || index + 1 == input.length) {
                        index++
                        continue
                    }
                    applyStyle(out, runStart, out.length, style, colourTransform)
                    val lineStart = out.lastIndexOf("\n") + 1
                    out.delete(lineStart, out.length)
                    runStart = out.length
                    index++
                }
                char == '\n' || char == '\t' || char.code >= 0x20 -> {
                    out.append(char)
                    index++
                }
                else -> index++
            }
        }
        applyStyle(out, runStart, out.length, style, colourTransform)
        return out
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
            if (input[cursor] == ESC && cursor + 1 < input.length && input[cursor + 1] == '\\') {
                return cursor + 2
            }
            cursor++
        }
        return input.length
    }

    private fun applySgr(initial: Style, body: String): Style {
        val codes = if (body.isBlank()) {
            listOf(0)
        } else {
            body.split(';', ':').filter(String::isNotEmpty).mapNotNull(String::toIntOrNull)
        }
        var style = initial
        var index = 0
        while (index < codes.size) {
            val code = codes[index]
            style = when (code) {
                0 -> Style()
                1 -> style.copy(bold = true)
                2 -> style.copy(dim = true)
                3 -> style.copy(italic = true)
                4 -> style.copy(underline = true)
                7 -> style.copy(inverse = true)
                9 -> style.copy(strike = true)
                22 -> style.copy(bold = false, dim = false)
                23 -> style.copy(italic = false)
                24 -> style.copy(underline = false)
                27 -> style.copy(inverse = false)
                29 -> style.copy(strike = false)
                in 30..37 -> style.copy(foreground = ANSI[code - 30])
                39 -> style.copy(foreground = null)
                in 40..47 -> style.copy(background = ANSI[code - 40])
                49 -> style.copy(background = null)
                in 90..97 -> style.copy(foreground = BRIGHT[code - 90])
                in 100..107 -> style.copy(background = BRIGHT[code - 100])
                38, 48 -> {
                    val parsed = extendedColour(codes, index + 1)
                    if (parsed == null) style else if (code == 38) style.copy(foreground = parsed.first) else style.copy(background = parsed.first)
                }
                else -> style
            }
            if ((code == 38 || code == 48) && index + 1 < codes.size) {
                index += if (codes[index + 1] == 5) 2 else if (codes[index + 1] == 2) 4 else 0
            }
            index++
        }
        return style
    }

    private fun extendedColour(codes: List<Int>, start: Int): Pair<Int, Int>? {
        if (start >= codes.size) return null
        return when (codes[start]) {
            5 -> codes.getOrNull(start + 1)?.takeIf { it in 0..255 }?.let { indexedColour(it) to 2 }
            2 -> {
                val r = codes.getOrNull(start + 1)
                val g = codes.getOrNull(start + 2)
                val b = codes.getOrNull(start + 3)
                if (r != null && g != null && b != null && r in 0..255 && g in 0..255 && b in 0..255) {
                    Color.rgb(r, g, b) to 4
                } else null
            }
            else -> null
        }
    }

    private fun indexedColour(index: Int): Int = when {
        index < 8 -> ANSI[index]
        index < 16 -> BRIGHT[index - 8]
        index < 232 -> {
            val value = index - 16
            val r = value / 36
            val g = (value % 36) / 6
            val b = value % 6
            fun channel(v: Int) = if (v == 0) 0 else 55 + v * 40
            Color.rgb(channel(r), channel(g), channel(b))
        }
        else -> {
            val grey = 8 + (index - 232) * 10
            Color.rgb(grey, grey, grey)
        }
    }

    private fun applyStyle(
        out: SpannableStringBuilder,
        start: Int,
        end: Int,
        raw: Style,
        colourTransform: (Int) -> Int,
    ) {
        if (start >= end) return
        val foreground = if (raw.inverse) raw.background ?: DEFAULT_BACKGROUND else raw.foreground
        val background = if (raw.inverse) raw.foreground ?: DEFAULT_FOREGROUND else raw.background
        val paintedForeground = if (raw.dim) dim(foreground ?: DEFAULT_FOREGROUND) else foreground
        val paintedBackground = background?.let { if (raw.dim) dim(it) else it }
        paintedForeground?.let {
            out.setSpan(ForegroundColorSpan(colourTransform(it)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        paintedBackground?.let {
            out.setSpan(BackgroundColorSpan(colourTransform(it)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val typeface = when {
            raw.bold && raw.italic -> Typeface.BOLD_ITALIC
            raw.bold -> Typeface.BOLD
            raw.italic -> Typeface.ITALIC
            else -> null
        }
        typeface?.let { out.setSpan(StyleSpan(it), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        if (raw.underline) out.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (raw.strike) out.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun dim(colour: Int): Int = Color.argb(
        (Color.alpha(colour) * DIM_OPACITY).toInt(),
        Color.red(colour),
        Color.green(colour),
        Color.blue(colour),
    )

    private data class Style(
        val foreground: Int? = null,
        val background: Int? = null,
        val bold: Boolean = false,
        val dim: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val inverse: Boolean = false,
        val strike: Boolean = false,
    )

    companion object {
        private const val ESC = '\u001b'
        private const val DIM_OPACITY = 0.6f
        private const val DEFAULT_FOREGROUND = 0xfffafafa.toInt()
        private const val DEFAULT_BACKGROUND = 0xff0a0a0a.toInt()
        private val ANSI = intArrayOf(
            0xff000000.toInt(), 0xffcd3131.toInt(), 0xff0dbc79.toInt(), 0xffe5e510.toInt(),
            0xff2472c8.toInt(), 0xffbc3fbc.toInt(), 0xff11a8cd.toInt(), 0xffe5e5e5.toInt(),
        )
        private val BRIGHT = intArrayOf(
            0xff666666.toInt(), 0xfff14c4c.toInt(), 0xff23d18b.toInt(), 0xfff5f543.toInt(),
            0xff3b8eea.toInt(), 0xffd670d6.toInt(), 0xff29b8db.toInt(), 0xffffffff.toInt(),
        )
    }
}
