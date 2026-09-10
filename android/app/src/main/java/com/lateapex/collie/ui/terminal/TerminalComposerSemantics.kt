package com.lateapex.collie.ui.terminal

data class TerminalDraft(val text: String, val opaque: Boolean = false)

/** Harness-shaped, read-only observations used by native composer notices. */
object TerminalComposerSemantics {
    private const val MAX_SCAN_LINES = 100
    private const val ANALYSIS_LINES = MAX_SCAN_LINES + 16
    private const val ANALYSIS_CHARS = 65_536
    private val rule = Regex("^[─━═-]{8,}$")
    private val noEchoPatterns = listOf(
        Regex("(^|\\s)\\[sudo\\]\\s+password\\s+for\\s+\\S+\\s*:\\s*$", RegexOption.IGNORE_CASE),
        Regex("(^|\\s)password(\\s*\\([^)]*\\))?\\s*:\\s*$", RegexOption.IGNORE_CASE),
        Regex("(^|\\s)passphrase[^:]*:\\s*$", RegexOption.IGNORE_CASE),
        Regex("(^|\\s)enter\\s+(the\\s+)?(password|passphrase)[^:]*:\\s*$", RegexOption.IGNORE_CASE),
    )

    fun noEchoPrompt(rawText: String): String? {
        val lines = plainLines(rawText)
        val end = lines.indexOfLast(String::isNotBlank)
        if (end < 0) return null
        for (index in end downTo maxOf(0, end - 1)) {
            val text = lines[index].trimEnd()
            if (noEchoPatterns.any { it.containsMatchIn(text) }) return text.trim()
        }
        return null
    }

    fun terminalDraft(agent: String?, rawText: String): TerminalDraft? {
        val tail = analysisTail(rawText)
        val lines = TerminalPlainText.strip(tail).replace("\r\n", "\n").replace('\r', '\n')
            .split('\n').map(String::trimEnd)
        return when (agent?.trim()?.lowercase()) {
            "claude" -> claudeDraft(lines)
            "codex" -> codexDraft(tail, lines)
            "grok" -> grokDraft(lines)
            "agy", "antigravity" -> agyDraft(lines)
            else -> null
        }
    }

    private fun claudeDraft(lines: List<String>): TerminalDraft? {
        val last = lines.indexOfLast(String::isNotBlank)
        if (last < 0) return null
        val bottom = (last downTo maxOf(0, last - 8)).firstOrNull { rule.matches(lines[it].trim()) } ?: return null
        if (lines.subList(bottom + 1, lines.size).count(String::isNotBlank) > 5) return null
        var top = bottom - 1
        var walked = 0
        while (top >= 0 && walked++ < MAX_SCAN_LINES && !rule.matches(lines[top].trim())) top--
        if (top < 0) return null
        val prompt = (top + 1 until bottom).firstOrNull { lines[it].trimStart().startsWith("❯") } ?: return null
        if ((top + 1 until prompt).any { lines[it].isNotBlank() }) return null
        val head = lines[prompt].trimStart().removePrefix("❯").trim()
        val parts = buildList {
            if (head.isNotEmpty()) add(head)
            for (index in prompt + 1 until bottom) lines[index].trim().takeIf(String::isNotEmpty)?.let(::add)
        }
        val draft = parts.joinToString(" ").trim()
        if (draft.isEmpty() || draft == "Press up to edit queued messages") return null
        return TerminalDraft(draft, opaque = Regex("^\\[Pasted text #\\d+ \\+\\d+ lines]$").matches(draft))
    }

    private fun codexDraft(rawTail: String, lines: List<String>): TerminalDraft? {
        val status = lines.indexOfLast(String::isNotBlank)
        val rawLines = rawTail.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        if (status < 0 || !CodexStatusRow.matches(rawLines.getOrNull(status) ?: return null)) return null
        var cursor = status - 1
        while (cursor >= 0 && lines[cursor].isBlank()) cursor--
        val end = cursor
        var walked = 0
        while (cursor >= 0 && walked++ < MAX_SCAN_LINES) {
            val row = lines[cursor]
            if (row.trimStart().startsWith("›")) {
                val head = row.trimStart().removePrefix("›").trim()
                val parts = mutableListOf(head)
                for (index in cursor + 1..end) parts += lines[index].trim()
                val draft = parts.filter(String::isNotEmpty).joinToString(" ")
                return draft.takeIf(String::isNotEmpty)?.let(::TerminalDraft)
            }
            if (row.isBlank() || !row.startsWith("  ")) return null
            cursor--
        }
        return null
    }

    private fun grokDraft(lines: List<String>): TerminalDraft? {
        val last = lines.indexOfLast(String::isNotBlank)
        if (last < 0) return null
        val bottom = (last downTo maxOf(0, last - 7)).firstOrNull { GROK_BOTTOM.matches(lines[it]) } ?: return null
        if (lines[bottom].contains("plan approval", true)) return null
        val top = (bottom - 1 downTo maxOf(0, bottom - MAX_SCAN_LINES)).firstOrNull { GROK_TOP.matches(lines[it]) } ?: return null
        val rows = (top + 1 until bottom).map { lines[it] }
        val promptAt = rows.indexOfFirst { GROK_PROMPT.matches(it) }
        if (promptAt < 0) return null
        val prompt = GROK_PROMPT.matchEntire(rows[promptAt])!!.groupValues[1].trim()
        val continuation = rows.drop(promptAt + 1).mapNotNull { GROK_INNER.matchEntire(it)?.groupValues?.get(1)?.trim() }
        val draft = (listOf(prompt) + continuation).filter(String::isNotEmpty).joinToString(" ")
        return draft.takeIf(String::isNotEmpty)?.let(::TerminalDraft)
    }

    private fun agyDraft(lines: List<String>): TerminalDraft? {
        val last = lines.indexOfLast(String::isNotBlank)
        if (last < 0) return null
        val bottom = (last downTo maxOf(0, last - 4)).firstOrNull { rule.matches(lines[it].trim()) } ?: return null
        val prompt = lines.getOrNull(bottom - 1)?.trim() ?: return null
        val top = lines.getOrNull(bottom - 2)?.trim() ?: return null
        if (!rule.matches(top) || !prompt.startsWith("> ")) return null
        return prompt.removePrefix("> ").trim().takeIf(String::isNotEmpty)?.let(::TerminalDraft)
    }

    private fun plainLines(text: String): List<String> =
        TerminalPlainText.strip(analysisTail(text)).replace("\r\n", "\n").replace('\r', '\n').split('\n')

    /** Composer grammar is tail-anchored; never repaint hundreds of irrelevant scrollback rows. */
    private fun analysisTail(text: String): String {
        var start = (text.length - ANALYSIS_CHARS).coerceAtLeast(0)
        var cursor = text.length
        var lines = 0
        while (cursor > start && lines <= ANALYSIS_LINES) {
            cursor--
            if (text[cursor] == '\n') lines++
        }
        if (lines > ANALYSIS_LINES) start = cursor + 1
        return text.substring(start)
    }

    private val GROK_TOP = Regex("^\\s*╭─+╮$")
    private val GROK_BOTTOM = Regex("^\\s*╰─+\\s+[\\s\\S]+?\\s+─╯$")
    private val GROK_INNER = Regex("^\\s*│ ([\\s\\S]*)│$")
    private val GROK_PROMPT = Regex("^\\s*│ ❯([\\s\\S]*)│$")
}

class StableTerminalDraft(private val now: () -> Long) {
    private var candidateKey: String? = null
    private var candidateSince = 0L
    private var latest: TerminalDraft? = null
    private var handledKey: String? = null

    fun observe(raw: TerminalDraft?): TerminalDraft? {
        if (raw == null) {
            candidateKey = null
            latest = null
            handledKey = null
            return null
        }
        val key = normalize(raw.text)
        if (key != candidateKey) {
            candidateKey = key
            candidateSince = now()
            latest = raw
            return null
        }
        latest = raw
        if (key == handledKey || now() - candidateSince < STABLE_MIN_AGE_MS) return null
        return latest
    }

    fun markHandled(draft: TerminalDraft) {
        handledKey = normalize(draft.text)
    }

    companion object {
        const val STABLE_MIN_AGE_MS = 1_500L
        fun normalize(value: String): String = value.trim().replace(Regex("\\s+"), " ")
    }
}
