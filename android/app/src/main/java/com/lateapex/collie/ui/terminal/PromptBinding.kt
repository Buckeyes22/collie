package com.lateapex.collie.ui.terminal

/**
 * Produces conservative optimistic-concurrency evidence from the exact pane the operator saw.
 * The bridge re-reads immediately before a write and requires this region to remain near the tail.
 */
object PromptBinding {
    private const val MAX_LINES = 3
    private const val MAX_CHARS = 8_192
    private const val MAX_DRAFT_LINES = 100
    private val sgr = Regex("(?:\u001B\\[|\u009B)[0-?]*[ -/]*m")

    /** Bound evidence for explicit named keys on whatever screen the operator can currently see. */
    fun tailRegion(text: String): String? = normalizedLines(text)
        .takeLast(MAX_LINES)
        .joinToString("\n")
        .takeIf { it.isNotEmpty() && it.length <= MAX_CHARS }

    /**
     * A one-shot text reply is stricter than a named key: it is enabled only for a positively
     * recognized empty free-text composer when Collie has an exact harness adapter. Agents without
     * a web harness adapter (including OpenCode and shell panes) retain the web client's fail-open
     * message path, while still binding the write to the exact visible tail.
     */
    fun composerRegion(agent: String?, text: String): String? {
        val key = agent?.trim()?.lowercase().orEmpty()
        return when (key) {
            "codex" -> codexComposerRegion(text)
            "claude" -> claudeComposerRegion(text)
            "grok" -> grokComposerRegion(text)
            "omp" -> ompComposerRegion(text)
            "agy", "antigravity" -> agyComposerRegion(text)
            else -> tailRegion(text)
        }
    }

    private fun codexComposerRegion(text: String): String? {
        val framed = frame(text)
        val lines = framed.plain
        var status = lines.indexOfLast { it.trim().isNotEmpty() }
        if (status < 0 || !CodexStatusRow.matches(framed.raw[status])) return null

        var cursor = status - 1
        var gap = 0
        while (cursor >= 0 && lines[cursor].trim().isEmpty()) {
            cursor--
            if (++gap > 2) return null
        }
        if (cursor < 0) return null
        val draftEnd = cursor
        var scanned = 0
        while (cursor >= 0 && scanned++ < 100) {
            val row = lines[cursor].trimEnd()
            if (row == "›" || row.startsWith("› ")) {
                if (draftEnd != cursor) return null
                if (!isEmptyCodexPrompt(text, cursor, row)) return null
                val candidate = lines.subList(cursor, draftEnd + 1)
                    .joinToString("\n") { it.trimEnd() }
                return candidate.takeIf {
                    it.length in 1..MAX_CHARS && verifyNearTail(text, it)
                }
            }
            if (row.isEmpty() || !CODEX_CONTINUATION.matches(row)) return null
            cursor--
        }
        return null
    }

    /** Claude's `rule / ❯ / rule` input box, followed by at most the bridge's tail allowance. */
    private fun claudeComposerRegion(text: String): String? {
        val frame = frame(text)
        val last = frame.plain.indexOfLast { it.isNotBlank() }
        if (last < 0) return null
        for (bottom in last downTo 0) {
            if (!isClaudeBottom(frame.plain[bottom])) continue
            if (frame.plain.subList(bottom + 1, frame.plain.size).count(String::isNotBlank) > 5) return null
            val prompt = bottom - 1
            val top = prompt - 1
            if (top < 0 || !isClaudeTop(frame.plain[top])) return null
            val promptText = frame.plain[prompt].trimStart().trimEnd()
            if (!promptText.startsWith("❯")) return null
            val body = promptText.removePrefix("❯").trim()
            if (body.isNotEmpty() && body != CLAUDE_EMPTY_PLACEHOLDER &&
                !bodyAfterMarkerIsDim(frame.raw[prompt], '❯')
            ) return null
            return region(frame.plain, prompt, bottom, text)
        }
        return null
    }

    /** Grok's rounded box. A status-bearing bottom border and empty `│ ❯ │` row are mandatory. */
    private fun grokComposerRegion(text: String): String? {
        val lines = plainLines(text).map(String::trimEnd)
        val last = lines.indexOfLast { it.isNotBlank() }
        if (last < 0) return null
        var bottom = -1
        var status = ""
        for (candidate in last downTo (last - 7).coerceAtLeast(0)) {
            val match = GROK_BOTTOM.matchEntire(lines[candidate]) ?: continue
            bottom = candidate
            status = match.groupValues[1]
            break
        }
        if (bottom < 0) return null
        for (row in bottom + 1..last) {
            val value = lines[row]
            if (value.isBlank()) continue
            if (!isGrokHint(value) && value.trimStart() != "[stable]") return null
        }
        if (status.contains("plan approval", ignoreCase = true) ||
            lines.subList(bottom, last + 1).any(::isGrokPlanHint)
        ) return null

        var cursor = bottom - 1
        var walked = 0
        while (cursor >= 0 && walked++ < MAX_DRAFT_LINES && GROK_INNER.matches(lines[cursor])) cursor--
        if (cursor < 0 || !GROK_TOP.matches(lines[cursor])) return null
        val prompt = cursor + 1
        if (prompt >= bottom) return null
        val promptMatch = GROK_PROMPT.matchEntire(lines[prompt]) ?: return null
        if (promptMatch.groupValues[1].isNotBlank()) return null
        for (row in prompt + 1 until bottom) {
            val inner = GROK_INNER.matchEntire(lines[row]) ?: return null
            if (inner.groupValues[1].isNotBlank()) return null
        }
        var above = cursor - 1
        while (above >= 0 && lines[above].isBlank()) above--
        if (above >= 0) {
            val prior = lines[above].trimStart()
            val priorCode = prior.firstOrNull()?.code
            if (priorCode != null && priorCode in BOX_DRAWING_RANGE &&
                !prior.startsWith("┌") && !prior.startsWith("└")
            ) {
                return null
            }
        }
        return region(lines, prompt, prompt, text)
    }

    /** omp 17's closed composer and 18's clipped open prompt; both must be empty and at the tail. */
    private fun ompComposerRegion(text: String): String? {
        val lines = plainLines(text).map(String::trimEnd)
        val bottom = lines.indexOfLast { it.isNotBlank() }
        if (bottom < 0) return null
        val row = lines[bottom]
        val closed = OMP_BOTTOM.matchEntire(row)
        if (closed != null) {
            if (closed.groupValues[1].isNotBlank()) return null
            val top = bottom - 1
            if (top < 0 || !OMP_TOP.matches(lines[top])) return null
            return region(lines, bottom, bottom, text)
        }
        val open = OMP_OPEN_BOTTOM.matchEntire(row) ?: return null
        if (open.groupValues.getOrElse(1) { "" }.isNotBlank()) return null
        val status = bottom - 1
        val statusCode = lines.getOrNull(status)?.trimStart()?.firstOrNull()?.code
        if (status < 0 || lines[status].isBlank() || statusCode != null && statusCode in BOX_DRAWING_RANGE) {
            return null
        }
        return region(lines, bottom, bottom, text)
    }

    /** Agy/Antigravity's always-boxed empty `>` composer. */
    private fun agyComposerRegion(text: String): String? {
        val lines = plainLines(text).map(String::trimEnd)
        val last = lines.indexOfLast { it.isNotBlank() }
        if (last < 0) return null
        for (bottom in last downTo 0) {
            if (!AGY_BORDER.matches(lines[bottom].trim())) continue
            if (lines.subList(bottom + 1, lines.size).count(String::isNotBlank) > 4) return null
            val prompt = bottom - 1
            val top = prompt - 1
            if (top < 0 || !AGY_BORDER.matches(lines[top].trim())) return null
            if (lines[prompt].trim() !in AGY_PROMPTS) return null
            return region(lines, prompt, bottom, text)
        }
        return null
    }

    private fun region(lines: List<String>, from: Int, to: Int, fullText: String): String? {
        val candidate = lines.subList(from, to + 1).joinToString("\n", transform = String::trimEnd)
        return candidate.takeIf { it.length in 1..MAX_CHARS && verifyNearTail(fullText, it) }
    }

    private data class Frame(val raw: List<String>, val plain: List<String>)

    private fun frame(text: String): Frame {
        val raw = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        return Frame(raw, raw.map { it.replace(sgr, "") })
    }

    /** Claude ghost suggestions are arbitrary text; only the TUI's all-dim paint makes them empty. */
    private fun bodyAfterMarkerIsDim(rawRow: String, marker: Char): Boolean {
        var index = 0
        var dim = false
        var markerSeen = false
        var sawBody = false
        while (index < rawRow.length) {
            val csiStart = when {
                rawRow[index] == '\u001b' && index + 1 < rawRow.length && rawRow[index + 1] == '[' -> index + 2
                rawRow[index] == '\u009b' -> index + 1
                else -> -1
            }
            if (csiStart >= 0) {
                var end = csiStart
                while (end < rawRow.length && rawRow[end].code !in 0x40..0x7e) end++
                if (end >= rawRow.length) return false
                if (rawRow[end] == 'm') {
                    val body = rawRow.substring(csiStart, end)
                    val codes = if (body.isBlank()) listOf(0) else body.split(';').mapNotNull(String::toIntOrNull)
                    codes.forEach { code ->
                        when (code) {
                            0, 22 -> dim = false
                            2 -> dim = true
                        }
                    }
                }
                index = end + 1
                continue
            }
            val char = rawRow[index++]
            if (!markerSeen) {
                if (char == marker) markerSeen = true
                continue
            }
            if (char.isWhitespace()) continue
            sawBody = true
            if (!dim) return false
        }
        return markerSeen && sawBody
    }

    private fun isClaudeBottom(line: String): Boolean = CLAUDE_BOTTOM.matches(line.trim())

    private fun isClaudeTop(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < 8) return false
        if (CLAUDE_BOTTOM.matches(trimmed)) return true
        val label = CLAUDE_LABELLED.matchEntire(trimmed)?.groupValues?.get(1) ?: return false
        return label.any { !it.isWhitespace() && it != '─' }
    }

    private fun isGrokHint(line: String): Boolean = line.trim().split(Regex("\\s+│\\s+")).all {
        GROK_HINT_SEGMENT.containsMatchIn(it)
    }

    private fun isGrokPlanHint(line: String): Boolean {
        val lower = line.lowercase()
        return "a:approve" in lower || "request changes" in lower || "tab:prompt" in lower
    }

    /** Mirrors bridge/prompt-binding.ts and is kept public for contract tests. */
    fun verifyNearTail(freshText: String, expected: String, tailLines: Int = 6): Boolean {
        val fresh = normalizedLines(freshText)
        val wanted = normalizedLines(expected)
        if (wanted.isEmpty()) return false
        var lastMatch = -1
        for (start in 0..(fresh.size - wanted.size).coerceAtLeast(-1)) {
            if (start >= 0 && wanted.indices.all { fresh[start + it] == wanted[it] }) lastMatch = start
        }
        if (lastMatch < 0) return false
        val tailStart = (fresh.size - tailLines.coerceAtLeast(0)).coerceAtLeast(0)
        return lastMatch + wanted.size - 1 >= tailStart
    }

    private fun normalizedLines(text: String): List<String> = plainLines(text)
        .map(String::trimEnd)
        .filter(String::isNotEmpty)

    private fun plainLines(text: String): List<String> = text
            .replace(sgr, "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')

    private fun isEmptyCodexPrompt(fullText: String, rowIndex: Int, plainRow: String): Boolean {
        if (plainRow == "›") return true
        val body = plainRow.removePrefix("› ").trimEnd()
        if (body.isEmpty()) return true
        if (body != CODEX_PLACEHOLDER) return false
        val rawRow = fullText.replace("\r\n", "\n").replace('\r', '\n')
            .split('\n')
            .getOrNull(rowIndex)
            ?: return false
        return placeholderBodyIsDim(rawRow)
    }

    /** Codex distinguishes its empty placeholder from identical operator text with dim paint. */
    private fun placeholderBodyIsDim(rawRow: String): Boolean {
        var index = 0
        var plainOffset = 0
        var dim = false
        var sawBody = false
        while (index < rawRow.length) {
            if (rawRow[index] == '\u001b' && index + 1 < rawRow.length && rawRow[index + 1] == '[') {
                var end = index + 2
                while (end < rawRow.length && rawRow[end].code !in 0x40..0x7e) end++
                if (end >= rawRow.length) return false
                if (rawRow[end] == 'm') {
                    val body = rawRow.substring(index + 2, end)
                    val codes = if (body.isBlank()) listOf(0) else body.split(';').mapNotNull(String::toIntOrNull)
                    for (code in codes) when (code) {
                        0, 22 -> dim = false
                        2 -> dim = true
                    }
                }
                index = end + 1
                continue
            }
            val char = rawRow[index++]
            if (plainOffset >= 2 && !char.isWhitespace()) {
                sawBody = true
                if (!dim) return false
            }
            plainOffset++
        }
        return sawBody
    }

    private val CODEX_CONTINUATION = Regex("^ {2}\\s*\\S.*$")
    private const val CODEX_PLACEHOLDER = "Ask Codex to do anything"
    private val CLAUDE_BOTTOM = Regex("^─{8,}$")
    private val CLAUDE_LABELLED = Regex("^─+\\s+(.+)\\s+─+$")
    private const val CLAUDE_EMPTY_PLACEHOLDER = "Press up to edit queued messages"
    private val GROK_TOP = Regex("^\\s*╭─+╮$")
    private val GROK_BOTTOM = Regex("^\\s*╰─+\\s+([\\s\\S]+?)\\s+─╯$")
    private val GROK_INNER = Regex("^\\s*│ ([\\s\\S]*)│$")
    private val GROK_PROMPT = Regex("^\\s*│ ❯([\\s\\S]*)│$")
    private val GROK_HINT_SEGMENT = Regex(
        "^(?:(?:Shift|Ctrl)\\+(?:Tab|Space|Esc|Enter|Up|Down|[A-Za-z.])|Tab/Space|Tab|Space|Esc|Enter|Up|Down|[A-Za-z]):\\S",
    )
    private val OMP_TOP = Regex("^╭─+[\\s\\S]*╮$")
    private val OMP_BOTTOM = Regex("^╰─ ([\\s\\S]*) ─╯$")
    private val OMP_OPEN_BOTTOM = Regex("^╰─(?: ([\\s\\S]*))?$")
    private val AGY_BORDER = Regex("^[─━═-]{8,}$")
    private val AGY_PROMPTS = setOf(">", "❯", "›")
    private val BOX_DRAWING_RANGE = 0x2500..0x257f
}
