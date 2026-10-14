package com.lateapex.collie.ui.terminal

import android.icu.text.BreakIterator
import com.lateapex.collie.network.PromptColumns
import java.util.Locale

/** Display columns match the fixed official text-width.ts Unicode17 ranges and cluster-base rule. */
internal object PromptColumnText {
    fun crop(raw: String, columns: PromptColumns): String = TerminalPlainText.strip(cropRaw(raw, columns))

    fun cropRaw(raw: String, columns: PromptColumns): String {
        if (!columns.valid()) return ""
        return raw.split('\n').joinToString("\n") { row ->
            val out = StringBuilder()
            var cell = 0
            var cursor = 0
            while (cursor < row.length) {
                val escape = escapeLength(row, cursor)
                if (escape > 0) { out.append(row, cursor, cursor + escape); cursor += escape; continue }
                var end = cursor
                while (end < row.length && escapeLength(row, end) == 0) end++
                val run = row.substring(cursor, end)
                val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
                iterator.setText(run)
                var from = iterator.first()
                var to = iterator.next()
                while (to != BreakIterator.DONE) {
                    val cp = run.codePointAt(from)
                    val width = when {
                        combining.any { cp in it } -> 0
                        wide.any { cp in it } -> 2
                        else -> 1
                    }
                    if (cp == 13 || cell >= columns.start && cell + width <= columns.end) out.append(run, from, to)
                    if (cp != 13) cell += width
                    from = to
                    to = iterator.next()
                }
                cursor = end
            }
            out.toString()
        }
    }
    private fun escapeLength(text: String, at: Int): Int {
        val char = text[at]
        if (char != '\u001b' && char != '\u009b') return 0
        if (char == '\u009b' || text.getOrNull(at + 1) == '[') {
            var end = at + if (char == '\u009b') 1 else 2
            while (end < text.length && text[end].code !in 0x40..0x7e) end++
            return (end + 1).coerceAtMost(text.length) - at
        }
        if (text.getOrNull(at + 1) == ']') {
            var end = at + 2
            while (end < text.length && text[end] != '\u0007' && !(text[end] == '\u001b' && text.getOrNull(end + 1) == '\\')) end++
            if (text.getOrNull(end) == '\u001b') end++
            return (end + 1).coerceAtMost(text.length) - at
        }
        return (at + 2).coerceAtMost(text.length) - at
    }
    private val combining = listOf(
        0x0300..0x036f,
        0x1ab0..0x1aff,
        0x1dc0..0x1dff,
        0x200b..0x200f,
        0x2060..0x2064,
        0x20d0..0x20ff,
        0x3099..0x309a,
        0xfe00..0xfe0f,
        0xfe20..0xfe2f,
        0xfeff..0xfeff
    )
    private val wide = listOf(
        0x1100..0x115f,
        0x231a..0x231b,
        0x2329..0x232a,
        0x23e9..0x23ec,
        0x23f0..0x23f0,
        0x23f3..0x23f3,
        0x25fd..0x25fe,
        0x2614..0x2615,
        0x2630..0x2637,
        0x2648..0x2653,
        0x267f..0x267f,
        0x268a..0x268f,
        0x2693..0x2693,
        0x26a1..0x26a1,
        0x26aa..0x26ab,
        0x26bd..0x26be,
        0x26c4..0x26c5,
        0x26ce..0x26ce,
        0x26d4..0x26d4,
        0x26ea..0x26ea,
        0x26f2..0x26f3,
        0x26f5..0x26f5,
        0x26fa..0x26fa,
        0x26fd..0x26fd,
        0x2705..0x2705,
        0x270a..0x270b,
        0x2728..0x2728,
        0x274c..0x274c,
        0x274e..0x274e,
        0x2753..0x2755,
        0x2757..0x2757,
        0x2795..0x2797,
        0x27b0..0x27b0,
        0x27bf..0x27bf,
        0x2b1b..0x2b1c,
        0x2b50..0x2b50,
        0x2b55..0x2b55,
        0x2e80..0x303e,
        0x3041..0x33ff,
        0x3400..0x4dbf,
        0x4e00..0x9fff,
        0xa000..0xa4cf,
        0xa960..0xa97c,
        0xac00..0xd7a3,
        0xf900..0xfaff,
        0xfe10..0xfe19,
        0xfe30..0xfe4f,
        0xfe50..0xfe52,
        0xfe54..0xfe66,
        0xfe68..0xfe6b,
        0xff00..0xff60,
        0xffe0..0xffe6,
        0x1b000..0x1b122,
        0x1b132..0x1b132,
        0x1b150..0x1b152,
        0x1b155..0x1b155,
        0x1b164..0x1b167,
        0x1b170..0x1b2fb,
        0x1f004..0x1f004,
        0x1f0cf..0x1f0cf,
        0x1f18e..0x1f18e,
        0x1f191..0x1f19a,
        0x1f200..0x1f202,
        0x1f210..0x1f23b,
        0x1f240..0x1f248,
        0x1f250..0x1f251,
        0x1f260..0x1f265,
        0x1f300..0x1f320,
        0x1f32d..0x1f335,
        0x1f337..0x1f37c,
        0x1f37e..0x1f393,
        0x1f3a0..0x1f3ca,
        0x1f3cf..0x1f3d3,
        0x1f3e0..0x1f3f0,
        0x1f3f4..0x1f3f4,
        0x1f3f8..0x1f43e,
        0x1f440..0x1f440,
        0x1f442..0x1f4fc,
        0x1f4ff..0x1f53d,
        0x1f54b..0x1f54e,
        0x1f550..0x1f567,
        0x1f57a..0x1f57a,
        0x1f595..0x1f596,
        0x1f5a4..0x1f5a4,
        0x1f5fb..0x1f64f,
        0x1f680..0x1f6c5,
        0x1f6cc..0x1f6cc,
        0x1f6d0..0x1f6d2,
        0x1f6d5..0x1f6d8,
        0x1f6dc..0x1f6df,
        0x1f6eb..0x1f6ec,
        0x1f6f4..0x1f6fc,
        0x1f7e0..0x1f7eb,
        0x1f7f0..0x1f7f0,
        0x1f90c..0x1f93a,
        0x1f93c..0x1f945,
        0x1f947..0x1f9ff,
        0x1fa70..0x1fa7c,
        0x1fa80..0x1fa8a,
        0x1fa8e..0x1fac6,
        0x1fac8..0x1fac8,
        0x1facd..0x1fadc,
        0x1fadf..0x1faea,
        0x1faef..0x1faf8,
        0x20000..0x2fffd,
        0x30000..0x3fffd
    )
}
