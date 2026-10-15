package com.lateapex.collie.ui.terminal

import com.lateapex.collie.network.PromptColumns

/** Bounded port of the fixed official Pi stock/OmO editor, panel and picker observations. */
internal object PiComposer {
    data class Composer(val rows: List<String>, val top: Int, val bottom: Int, val columns: PromptColumns?, val omo: Boolean) {
        fun region(): String = rows.subList(top, bottom + 1).joinToString("\n")
        fun draft(): String? = rows.subList(top + 1, bottom).mapIndexed { index, row ->
            if (!omo) row else if (index == 0) row.removePrefix("❯").removePrefix(" ") else row.drop(2)
        }.map(String::trim).filter(String::isNotEmpty).joinToString(" ").takeIf(String::isNotEmpty)
    }

    private data class View(val raw: List<String>, val plain: List<String>, val columns: PromptColumns? = null)
    private data class Tail(val bottom: Int, val end: Int, val palette: Boolean)
    private val rule = Regex("^─{8,}$")
    private val labelled = Regex("^─{2,} \\S.*? ─{3,}$")
    private val run = Regex("^(?:─{2,} \\S.*? ─{3,}|─+)")
    private val prompt = Regex("^❯(?: ([\\s\\S]*))?$")
    private val box = Regex("^[─-╿]")
    private val separator = Regex("\\s+[•·]\\s+|\\s{2,}")
    private val atom = "(?:(?:ctrl|shift|alt|meta|cmd|super)\\+)*(?:enter|return|esc|escape|tab|space|backspace|delete|home|end|pgup|pgdn|pageup|pagedown|↑↓|↑/↓|←→|←/→|↑|↓|←|→|[a-z0-9])"
    private val hintKey = Regex("^(?:$atom)(?:/(?:$atom|↑↓|↑/↓|←→|←/→|↑|↓|←|→))*$", RegexOption.IGNORE_CASE)
    private val chord = Regex("(?:ctrl|shift|alt|meta|cmd|super)\\+|[↑↓←→]", RegexOption.IGNORE_CASE)

    fun locate(text: String): Composer? {
        if (text.length > 262_144) return null
        val raw = text.replace("\r\n", "\n").split('\n').takeLast(200)
        val original = View(raw, raw.map { TerminalPlainText.strip(it).trimEnd() })
        val view = panelView(original)
        val tail = tail(view) ?: return null
        val top = ruleAbove(view.plain, tail.bottom, view.plain[tail.bottom].length, 100)
        if (top < 0 || top + 1 == tail.bottom) return null
        if (style(view.raw[top]).firstColour != style(view.raw[tail.bottom]).firstColour) return null
        val rows = view.plain.subList(top + 1, tail.bottom)
        val width = view.plain[tail.bottom].length
        val omo = prompt.matches(rows.first())
        fun pasted(value: String) = value.all { it == '─' } && value.length < width
        if (omo) {
            val colour = style(view.raw[tail.bottom]).firstColour ?: return null
            if (style(view.raw[top + 1]).firstColour != colour) return null
            if (rows.drop(1).any { it.isNotEmpty() && (!it.startsWith("  ") || box.containsMatchIn(it.trimStart()) && !pasted(it.trimStart())) }) return null
        } else {
            val first = rows.indexOfFirst(String::isNotEmpty)
            if (rows.withIndex().any { (index, value) -> value.isNotEmpty() &&
                (index == first && value.startsWith(" ") || box.containsMatchIn(value.trimStart()) && !pasted(value.trimStart()) || prompt.matches(value.trimStart())) }) return null
            // A stock picker can occupy exactly the editor's frame. Its painted key pairs own it.
            if ((top + 1 until tail.bottom).any { style(view.raw[it]).everyPainted && hintPairs(view.plain[it]) >= 2 }) return null
        }
        val composer = Composer(view.plain, top, tail.bottom, view.columns, omo)
        if (tail.palette && composer.draft()?.startsWith("/") != true) return null
        return composer.takeIf { it.region().length in 1..32_768 }
    }

    /** Empty composer evidence. Status counters are deliberately outside this exact binding. */
    fun emptyRegion(text: String): String? = locate(text)?.takeIf { it.draft() == null }?.let { composer ->
        composer.region().takeIf { PromptBinding.verifyNearTail(text, it, expectedColumns = composer.columns) }
    }

    private fun ruleAbove(rows: List<String>, from: Int, width: Int, limit: Int): Int =
        (from - 1 downTo maxOf(0, from - limit)).firstOrNull { rows[it].length == width && (rule.matches(rows[it]) || labelled.matches(rows[it])) } ?: -1

    private fun tail(view: View): Tail? {
        val rows = view.plain
        val end = rows.indexOfLast(String::isNotEmpty) + 1
        var start = end
        while (start > 0 && end - start < 4) {
            val value = rows[start - 1]
            if (value.isEmpty() || value.startsWith(" ") || value.startsWith("→ ") || box.containsMatchIn(value.trimStart()) || prompt.matches(value)) break
            start--
        }
        if (start == end || start == 0) return null
        if ((start until end).any { !style(view.raw[it]).anyPainted && !(it == end - 1 && it > start) }) return null
        if (rule.matches(rows[start - 1])) return Tail(start - 1, end, false)
        var paletteStart = start
        while (paletteStart > 0 && start - paletteStart < 32 && Regex("^(?:→ | {2}\\S| {4}\\S)").containsMatchIn(rows[paletteStart - 1])) paletteStart--
        if (paletteStart == start || paletteStart < 1 || !rule.matches(rows[paletteStart - 1])) return null
        val palette = rows.subList(paletteStart, start)
        val stock = palette.count { it.startsWith("→ ") }
        val omo = palette.count { it.startsWith("  → ") }
        if (stock + omo != 1) return null
        val other = if (stock == 1) Regex("^ {2}(?!→ )\\S") else Regex("^ {4}\\S")
        if (palette.any { !it.startsWith("→ ") && !it.startsWith("  → ") && !Regex("^ {2}\\(\\d+/\\d+\\)$").matches(it) && !other.containsMatchIn(it) }) return null
        return Tail(paletteStart - 1, end, true)
    }

    private fun panelView(original: View): View {
        // Each candidate must be the actual tail frame's width, never a transcript/pasted rule.
        val widths = original.plain.asReversed().mapNotNull { run.find(it)?.value?.length }.filter { it in 8..4096 }.distinct()
        for (width in widths) {
            val columns = PromptColumns(0, width)
            val raw = original.raw.map { PromptColumnText.cropRaw(it, columns) }
            val cropped = View(raw, raw.map { TerminalPlainText.strip(it).trimEnd() }, columns)
            val tail = tail(cropped) ?: continue
            if (run.find(original.plain[tail.bottom])?.value?.length != width) continue
            val top = ruleAbove(cropped.plain, tail.bottom, width, 160)
            if (top < 0) continue
            val sign = (top until tail.end).any { row ->
                val left = PromptColumnText.crop(original.raw[row], columns)
                val rest = original.plain[row].drop(left.length.coerceAtMost(original.plain[row].length))
                rest.isNotBlank() && (left.endsWith("─") || left.endsWith("  "))
            }
            if (sign) return cropped
        }
        return original
    }

    /** Port of the official pair-only picker hint observation; prose is never a hint. */
    private fun hintPairs(text: String): Int {
        val segments = text.trim().split(separator).filter(String::isNotBlank)
        if (segments.size >= 2) return if (segments.all { part ->
            val key = part.substringBefore(' ')
            part.substringAfter(' ', "").isNotBlank() && (key == "1-9" || hintKey.matches(key))
        }) segments.size else 0
        var pairs = 0; var verbs = 0; var hasChord = false
        for (token in text.trim().split(' ')) {
            val key = hintKey.matches(token) && !Regex("^[a-z0-9]$", RegexOption.IGNORE_CASE).matches(token)
            val chorded = key && chord.containsMatchIn(token)
            if (chorded && pairs > 0 && verbs == 0) return 0
            if (chorded || key && (pairs == 0 || verbs > 0)) {
                pairs++; verbs = 0; hasChord = hasChord || chorded
            } else { if (pairs == 0) return 0; verbs++ }
        }
        return if (verbs > 0 && pairs >= 2 && hasChord) pairs else 0
    }

    private data class Paint(val firstColour: String?, val anyPainted: Boolean, val everyPainted: Boolean)
    private val sgr = Regex("\u001B\\[([0-9;:]*)m")
    private fun style(raw: String): Paint {
        var colour: String? = null; var dim = false; var first: String? = null
        var visible = false; var any = false; var all = true; var from = 0
        fun observe(end: Int) {
            if (TerminalPlainText.strip(raw.substring(from, end)).any { !it.isWhitespace() }) {
                if (!visible) first = colour
                visible = true; any = any || colour != null || dim; all = all && (colour != null || dim)
            }
        }
        for (match in sgr.findAll(raw)) {
            observe(match.range.first)
            val codes = match.groupValues[1].replace(':', ';').split(';').map { it.toIntOrNull() ?: 0 }
            var i = 0
            while (i < codes.size) {
                val code = codes[i]
                when {
                    code == 0 -> { colour = null; dim = false }
                    code == 2 -> dim = true
                    code == 22 -> dim = false
                    code == 39 -> colour = null
                    code in 30..37 || code in 90..97 -> colour = code.toString()
                    code == 38 || code == 48 -> {
                        val count = when (codes.getOrNull(i + 1)) { 2 -> 4; 5 -> 2; else -> 0 }
                        if (code == 38 && count > 0 && i + count < codes.size) colour = codes.subList(i, i + count + 1).joinToString(";")
                        i += count
                    }
                }
                i++
            }
            from = match.range.last + 1
        }
        observe(raw.length)
        return Paint(first, any, visible && all)
    }
}
