package com.lateapex.collie.ui.terminal

/** One tail-anchored grammar for both reply binding and stranded-draft detection. */
internal object CodexComposer {
    const val PLACEHOLDER = "Ask Codex to do anything"
    private const val MAX_ROWS = 100

    data class Frame(val raw: List<String>, val plain: List<String>, val prompt: Int, val status: Int) {
        fun draft(): String? {
            val head = plain[prompt].removePrefix("›").trim()
            val continuation = plain.subList(prompt + 1, status).map(String::trim).filter(String::isNotEmpty)
            if (continuation.isEmpty() && (head.isEmpty() ||
                    head == PLACEHOLDER && CodexStatusRow.isDimPlaceholder(raw[prompt]))) return null
            return (listOf(head) + continuation).filter(String::isNotEmpty).joinToString(" ").takeIf(String::isNotEmpty)
        }

        /** Keep animation glyphs in wire evidence: the bridge compares literal rendered rows. */
        fun region(): String = raw.subList(prompt, status)
            .joinToString("\n") { TerminalPlainText.strip(it).trimEnd() }.trimEnd()
    }

    fun locate(text: String): Frame? {
        val raw = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val plain = raw.map(CodexStatusRow::composerText)
        val last = plain.indexOfLast(String::isNotBlank)
        if (last < 0) return null
        val status = when {
            CodexStatusRow.matches(raw[last]) -> last
            last > 0 && isHint(plain[last]) && CodexStatusRow.matches(raw[last - 1]) -> last - 1
            else -> return null
        }
        for (row in status - 1 downTo maxOf(0, status - MAX_ROWS)) {
            val value = plain[row]
            if (value == "›" || value.startsWith("› ")) return Frame(raw, plain, row, status)
            // A transcript bullet/rule or another status row ends this composer band.
            if (value.firstOrNull()?.isWhitespace() == false || CodexStatusRow.matches(raw[row])) return null
        }
        return null
    }

    private fun isHint(row: String): Boolean = row.startsWith("  ") && row.isNotBlank() &&
        row.codePointCount(0, row.length) <= 512 && row.none { it.code < 0x20 || it.code == 0x7f }
}
