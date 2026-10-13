package com.lateapex.collie.ui.terminal

import com.lateapex.collie.domain.MuxKeyGrammar

enum class SemanticKind {
    PROMPT_SELECT,
    WIZARD,
    PREVIEW_SELECT,
    MULTI_SELECT,
    MENU,
    AUTOCOMPLETE,
}

data class SemanticAction(
    val id: String,
    val label: String,
    val keys: List<String>,
)

data class SemanticSurface(
    val kind: SemanticKind,
    val title: String,
    val detail: String? = null,
    val items: List<String> = emptyList(),
    val actions: List<SemanticAction> = emptyList(),
    val regionSignature: String,
    val revision: Long,
    val startLine: Int,
    val endLine: Int,
    val ownsKeyboard: Boolean = true,
    val interactionLocked: Boolean = false,
    val nativeModel: NativeSemanticModel? = null,
)

/**
 * Conservative, tail-anchored Android port of the web harness' interactive block boundary.
 * It emits only key spellings printed by a known adapter fixture, or the adapter's probed numeric
 * option recipe. Unknown, torn, oversized, and agent-mismatched screens stay in the raw terminal.
 */
object AgentSemanticParser {
    private const val MAX_BINDING_CHARS = 8_192
    private const val ANALYSIS_LINES = 160
    private const val ANALYSIS_CHARS = 65_536
    private const val OPTION_WINDOW = 28
    private const val CLAUDE_PROMPT_SIGNATURE_LOOKBACK = 40
    private const val CLAUDE_PROMPT_FOOTER_GAP = 3
    private const val CLAUDE_PLAN_FEEDBACK_WRAP_ALLOWANCE = 4
    private val rule = Regex("^[\\s─━═—-]{3,}$")
    private val claudeOption = Regex("^(?:❯\\s*)?([1-9])\\.\\s+(.+)$")
    private val codexOption = Regex("^(?:\\s*›\\s+|\\s{2,})([1-9])\\.\\s+(.+)$")
    private val agyOption = Regex(
        "^(?:[❯›>•*○●]\\s*|\\([ xX*•]\\)\\s*|\\[[ xX*•✔✓]\\]\\s*)?" +
            "(?:(\\d+)[.):\\]]|\\((\\d+)\\)|\\[(\\d+)\\])\\s+(.+)$",
    )
    private val stepGlyph = Regex("[☐☒☑✔✅]")
    private val freeText = Regex(
        "^(?:type something|type a custom|write-in|tell (?:claude|agy)|custom response)",
        RegexOption.IGNORE_CASE,
    )
    private val MUSE_RULE = Regex("^[─━═-]{8,}$")
    private val MUSE_VOICE = Regex("^─{2,} Voice input \\(.*\\) ─{8,}$")
    private val MUSE_STATUS = Regex("^  \\S.*·.*$")
    private const val OMP_SWITCH_FOOTER = "↑/↓ models · ⏎ use for this session · type to search · @ quick roles · ⎋ close · Alt+P task model"
    private const val OMP_SWITCH_TITLE_WINDOW = 72
    private val OMP_SWITCH_SEARCH = Regex("^🔍 >(?: (.*?))?$")
    private val OMP_SWITCH_ROW_HEAD = Regex("^([❯ ]) (\\S[\\s\\S]*)$")
    private val OMP_SWITCH_MODEL = Regex("^(\\S+/\\S+)( ●)?( ⦸ context>\\S+)?(?: +(\\S[\\s\\S]*))?$")

    fun detect(agent: String?, rawText: String, revision: Long): SemanticSurface? {
        val exactAgent = agent?.trim()?.lowercase() ?: return null
        val window = analysisWindow(rawText)
        val lines = TerminalPlainText.strip(window.text).split('\n').map(String::trimEnd)
        if (lines.none { it.isNotBlank() }) return null
        val detected = when (exactAgent) {
            "claude" -> detectClaude(lines, revision)
            "codex" -> detectCodex(lines, revision)
            "grok" -> detectGrok(lines, revision)
            "agy", "antigravity" -> detectAgy(lines, revision)
            "omp" -> detectOmp(lines, revision)
            "muse" -> detectMuse(lines, revision)
            "opencode" -> detectOpenCodeQuestion(lines, revision, openCodeFocusedRows(window.text))
            else -> null
        }
        return detected?.copy(
            startLine = detected.startLine + window.lineOffset,
            endLine = detected.endLine + window.lineOffset,
        )
    }

    /** Every supported dialog is tail-anchored; bound parsing without changing full-buffer offsets. */
    private fun analysisWindow(rawText: String): AnalysisWindow {
        var start = (rawText.length - ANALYSIS_CHARS).coerceAtLeast(0)
        var cursor = rawText.length
        var lines = 0
        while (cursor > start && lines <= ANALYSIS_LINES) {
            cursor--
            if (rawText[cursor] == '\n') lines++
        }
        if (lines > ANALYSIS_LINES) start = cursor + 1
        if (start > 0) {
            val nextLine = rawText.indexOf('\n', start)
            start = if (nextLine >= 0) nextLine + 1 else rawText.length
        }
        return AnalysisWindow(
            text = rawText.substring(start),
            lineOffset = rawText.substring(0, start).count { it == '\n' },
        )
    }

    private data class AnalysisWindow(val text: String, val lineOffset: Int)

    private fun detectOmp(lines: List<String>, revision: Long): SemanticSurface? {
        detectOmpNoteEditor(lines, revision)?.let { return it }
        detectOmpSwitch(lines, revision)?.let { return it }
        detectOmpResume(lines, revision)?.let { return it }
        detectOmpMulti(lines, revision)?.let { return it }
        val askFooter = lines.indexOfLast { boxContent(it) == "⏎ select · n note · ↑/↓ move · ⎋ cancel" }
        if (askFooter >= 0) {
            val footer = askFooter
            val titleLine = (maxOf(0, footer - OPTION_WINDOW)..footer).firstOrNull {
                lines[it].trim().startsWith("╭─ Ask") && lines[it].trim().endsWith("╮")
            } ?: return null
            val rowPattern = Regex("^\\s*(❯\\s*)?○\\s+(.+?)\\s*$")
            val rows = mutableListOf<Pair<Int, MatchResult>>()
            for (i in titleLine + 1 until footer) rowPattern.matchEntire(boxContent(lines[i]))?.let { rows += i to it }
            if (rows.size !in 2..9 || rows.zipWithNext().any { (a, b) -> b.first - a.first > 2 }) return null
            val pointed = rows.filter { it.second.groupValues[1].isNotEmpty() }
            if (pointed.size != 1 || rows.any { it.second.groupValues[2].isBlank() }) return null
            // OMP inserts a horizontal separator between the question and choices. It is visual
            // chrome, not the question text (and can be the last nonblank line before the rows).
            val askLines = (titleLine + 1 until rows.first().first)
                .map { boxContent(lines[it]) }
                .filter(String::isNotBlank)
                .filterNot(::isOmpHorizontalRule)
            if (askLines.size != 1) return null
            val ask = askLines.single()
            if (hasOmpHiddenCharacter(ask)) return null
            if (rows.any { hasOmpHiddenCharacter(it.second.groupValues[2]) }) return null
            val pointIndex = rows.indexOf(pointed.single())
            val actions = rows.mapIndexed { index, (_, match) ->
                pointerAction("option-${index + 1}", match.groupValues[2], pointIndex, index)
            }
            return surface(SemanticKind.PROMPT_SELECT, ask, "omp-select",
                items = listOf("omp-note:${rows[pointIndex].second.groupValues[2]}|$ask"),
                actions = actions, lines = lines,
                start = titleLine, end = footer, signatureStart = titleLine, revision = revision)
        }
        val footer = lastNonBlank(lines).takeIf { it >= 0 } ?: return null
        if ((footer + 1 until lines.size).any { lines[it].trim().startsWith("Command:") }) return null
        val titleLine = (maxOf(0, footer - 80)..footer).firstOrNull {
            lines[it].trim().startsWith("╭─ Allow tool: ") && lines[it].trim().endsWith("╮")
        } ?: return null
        if (titleLine == footer) return null
        val choicePattern = Regex("^\\s*(❯\\s*)?(Approve|Deny)\\s*$", RegexOption.IGNORE_CASE)
        val choices = (titleLine + 1 until footer).mapNotNull { index ->
            choicePattern.matchEntire(boxContent(lines[index]))?.let { index to it }
        }
        if (choices.map { it.second.groupValues[2].lowercase() } != listOf("approve", "deny") ||
            choices.count { it.second.groupValues[1].isNotEmpty() } != 1 ||
            (titleLine + 1 until footer).none { boxContent(lines[it]).contains("navigate", true) && boxContent(lines[it]).contains("cancel", true) }
        ) return null
        val rawBody = (titleLine + 1 until choices.first().first).map { boxContent(lines[it]) }
        val bodyStart = rawBody.indexOfFirst(String::isNotBlank)
        if (bodyStart < 0) return null
        val bodyAndPadding = rawBody.drop(bodyStart)
        val paddingRows = bodyAndPadding.takeLastWhile(String::isBlank).size
        if (paddingRows > 1) return null
        val body = if (paddingRows == 1) bodyAndPadding.dropLast(1) else bodyAndPadding
        if (!validOmpApprovalBody(lines[titleLine].substringAfter("Allow tool: ").substringBefore(" ─"), body)) return null
        val pointed = choices.indexOfFirst { it.second.groupValues[1].isNotEmpty() }
        val actions = choices.mapIndexed { index, (_, row) ->
            pointerAction("option-${index + 1}", row.groupValues[2], pointed, index)
        } + action("cancel", "Cancel", listOf("Escape"))
        return surface(SemanticKind.PROMPT_SELECT, lines[titleLine].trim().removePrefix("╭─ ").substringBefore(" ─"),
            "omp-approval", items = listOf(body.joinToString("\n")), actions = actions, lines = lines, start = titleLine, end = footer,
            signatureStart = titleLine, revision = revision)
    }

    /** OMP 18.4 note editor opened by the captured `n note` footer on a single Ask option. */
    private fun detectOmpNoteEditor(lines: List<String>, revision: Long): SemanticSurface? {
        val end = lastNonBlank(lines).takeIf { it >= 5 } ?: return null
        if (!Regex("^╰─+╯$").matches(lines[end].trim()) ||
            !Regex("^│\\s*│$").matches(lines[end - 1].trim()) ||
            boxContent(lines[end - 2]) != "⏎ or Ctrl+Q submit  ⎋ cancel  Ctrl+G external editor"
        ) return null
        val titleAt = (maxOf(0, end - 8)..end).firstOrNull {
            Regex("^╭─ Note for ([^:]+): (.+) ─+╮$").matches(lines[it].trim())
        } ?: return null
        if (!Regex("^│\\s*│$").matches(lines[titleAt + 1].trim())) return null
        val promptLine = lines[titleAt + 2].trim().removePrefix("│").removeSuffix("│").trimStart()
        if (!promptLine.startsWith(">")) return null
        val targetAndQuestion = Regex("^╭─ Note for ([^:]+): (.+) ─+╮$").matchEntire(lines[titleAt].trim()) ?: return null
        val target = targetAndQuestion.groupValues[1]
        val question = targetAndQuestion.groupValues[2]
        if (target.isBlank() || question.isBlank() || hasOmpHiddenCharacter(target) || hasOmpHiddenCharacter(question)) return null
        val textRows = mutableListOf(promptLine.removePrefix(">").trim())
        for (index in titleAt + 3 until end - 2) {
            val line = boxContent(lines[index])
            if (line.isBlank()) break
            textRows += line
        }
        val text = textRows.joinToString(" ").trim()
        if (text.length > 300 || text.any { it.isISOControl() }) return null
        return surface(
            SemanticKind.PROMPT_SELECT,
            "Note for $target: $question",
            "omp-note-editor",
            items = listOf("omp-note:$target|$question"),
            actions = listOf(action("cancel", "Cancel", listOf("Escape"))),
            lines = lines,
            start = titleAt,
            end = end,
            signatureStart = titleAt,
            revision = revision,
        )
    }

    /** OMP 18.4.10's captured multi-question Ask surface. Submit is an explicit Enter action; each
     * option toggle is a pointer walk plus Space and is committed only after checked-state read-back. */
    private fun detectOmpMulti(lines: List<String>, revision: Long): SemanticSurface? {
        val end = lastNonBlank(lines).takeIf { it >= 5 } ?: return null
        if (!Regex("^╰─+╯$").matches(lines[end].trim()) ||
            boxContent(lines[end - 1]) != "␣ toggle · ⏎ submit · ↑/↓ move · ⇥/←/→ · ⎋ cancel"
        ) return null
        // The shipped capture has the footer immediately above the bottom border. The one empty
        // boxed row is between the options and lower divider, not between footer and bottom.
        val lowerDivider = end - 2
        if (!Regex("^├─+┤$").matches(lines[lowerDivider].trim())) return null
        val titleAt = (maxOf(0, end - 20)..lowerDivider).firstOrNull { lines[it].trim() == "╭─ Ask ─" + "─".repeat(10) + "╮" ||
            Regex("^╭─ Ask ─+╮$").matches(lines[it].trim()) } ?: return null
        if (titleAt + 3 >= lowerDivider) return null
        if (!Regex("^│\\s*[A-Za-z0-9_-]+\\s{2,}Submit\\s*│$").matches(lines[titleAt + 1].trim())) return null
        val question = boxContent(lines[titleAt + 2])
        if (question.isBlank() || hasOmpHiddenCharacter(question) || !Regex("^├─+┤$").matches(lines[titleAt + 3].trim())) return null
        val rows = mutableListOf<OmpMultiRow>()
        var blanks = 0
        val rowPattern = Regex("^(❯\\s*)?([☐☑])\\s+(.+?)\\s*$")
        for (index in titleAt + 4 until lowerDivider) {
            val content = boxContent(lines[index])
            if (content.isBlank()) {
                if (rows.isEmpty() || ++blanks > 1) return null
                continue
            }
            if (blanks > 0) return null
            val match = rowPattern.matchEntire(content) ?: return null
            val label = match.groupValues[3]
            if (label.isBlank() || hasOmpHiddenCharacter(label)) return null
            rows += OmpMultiRow(match.groupValues[1].isNotEmpty(), match.groupValues[2] == "☑", label, index)
        }
        if (blanks > 1 || rows.size !in 2..9 || rows.count { it.pointed } != 1 || rows.any { it.label.contains('…') }) return null
        if (rows.count { it.label == "Other (type your own)" } > 1) return null
        if (rows.any { it.label == "Other (type your own)" } && rows.last().label != "Other (type your own)") return null
        if (rows.any { it.label == "Other (type your own)" && it.checked }) return null
        val pointedAt = rows.indexOfFirst { it.pointed }
        val actions = rows.mapIndexedNotNull { index, row ->
            if (row.label == "Other (type your own)") return@mapIndexedNotNull null
            val delta = index - pointedAt
            val arrows = List(kotlin.math.abs(delta)) { if (delta > 0) "Down" else "Up" }
            action("toggle-${index + 1}", row.label, arrows + "Space")
        } + action("submit", "Submit selected answers", listOf("Enter")) +
            action("cancel", "Cancel", listOf("Escape"))
        return surface(
            SemanticKind.MULTI_SELECT,
            "Ask: $question",
            detail = "omp-multi",
            items = rows.map { "${it.label}\t${it.checked}\t${it.pointed}" },
            actions = actions,
            lines = lines,
            start = titleAt,
            end = end,
            signatureStart = titleAt,
            revision = revision,
        )
    }

    private data class OmpMultiRow(val pointed: Boolean, val checked: Boolean, val label: String, val line: Int)

    private fun validOmpApprovalBody(tool: String, body: List<String>): Boolean {
        if (body.isEmpty() || body.any {
                it.contains("…") || it.contains("Provider safety checks:") || hasOmpHiddenCharacter(it)
            }
        ) return false
        return when (tool) {
            "bash" -> {
                val command = body.indexOfFirst { it.startsWith("Command: ") }
                command >= 0 && body.drop(command + 1).all(String::isBlank) &&
                    body.take(command).all { it.startsWith("Reason: ") }
            }
            "write" -> {
                val path = body.indexOfFirst { it.startsWith("Path: /") }
                path == 0 && body.getOrNull(1) == "Content:" && body.size in 3..32 &&
                    body.drop(2).all(String::isNotBlank)
            }
            else -> false
        }
    }

    private fun hasOmpHiddenCharacter(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val code = value.codePointAt(index)
            if (code < 0x20 || code in 0x7f..0x9f || code == 0x00ad || code == 0x061c || code == 0x180e ||
                code in 0x200b..0x200f || code == 0x2028 || code == 0x2029 || code in 0x202a..0x202e ||
                code in 0x2060..0x206f || code in 0xfe00..0xfe0f || code == 0xfeff || code in 0xe0000..0xe007f
            ) return true
            index += Character.charCount(code)
        }
        return false
    }

    /** Exact OMP 18.4.10 session-only compact model picker, including its hidden walk rows. */
    private fun detectOmpSwitch(lines: List<String>, revision: Long): SemanticSurface? {
        val end = lastNonBlank(lines).takeIf { it >= 5 } ?: return null
        if (!Regex("^╰─+╯$").matches(lines[end].trim()) ||
            boxContent(lines[end - 1]) != OMP_SWITCH_FOOTER
        ) return null
        val detailFacts = lines[end - 3]
        val detailChips = lines[end - 2]
        if (!Regex("^│ {3}\\S.*│$").matches(detailFacts.trim()) ||
            !Regex("^│\\s*│$").matches(lines[end - 4].trim())
        ) return null

        val titleAt = (maxOf(0, end - OMP_SWITCH_TITLE_WINDOW)..(end - 5)).firstOrNull {
            Regex("^╭─ Switch Model ─+╮$").matches(lines[it].trim())
        } ?: return null
        if (boxContent(lines[titleAt + 1]) != "Session-only switch — role models stay unchanged") return null
        val searchMatch = OMP_SWITCH_SEARCH.matchEntire(boxContent(lines[titleAt + 2])) ?: return null
        if (!Regex("^│\\s*│$").matches(lines[titleAt + 3].trim())) return null
        val search = searchMatch.groupValues.getOrElse(1) { "" }.trim()

        val windowStart = titleAt + 4
        val windowEnd = end - 5
        val windowCount = windowEnd - windowStart + 1
        if (windowCount !in 5..60) return null
        val modelRows = mutableListOf<OmpSwitchRow>()
        var hasScrollBar: Boolean? = null
        var padding = false
        var sawScrollThumb = false
        var rules = 0
        for (lineAt in windowStart..windowEnd) {
            val row = lines[lineAt].trim()
            if (Regex("^│\\s*│$").matches(row)) {
                padding = true
                continue
            }
            if (padding || !row.startsWith("│ ") || !row.endsWith(" │") || row.length < 6) return null
            val inner = row.substring(2, row.length - 2)
            val cell = inner.lastOrNull() ?: return null
            if (cell !in setOf('█', '│', ' ')) return null
            val rowHasScrollBar = cell in setOf('█', '│')
            if (hasScrollBar != null && hasScrollBar != rowHasScrollBar) return null
            hasScrollBar = rowHasScrollBar
            if (cell == '█') sawScrollThumb = true
            val content = inner.dropLast(1).trimEnd()
            if (Regex("^ {2}─+$").matches(content)) {
                if (++rules > 1) return null
                continue
            }
            val head = OMP_SWITCH_ROW_HEAD.matchEntire(content) ?: return null
            val pointed = head.groupValues[1] == "❯"
            val body = head.groupValues[2]
            if (body.contains('…')) {
                modelRows += OmpSwitchRow(pointed, null, false, false, emptyList())
                continue
            }
            val match = OMP_SWITCH_MODEL.matchEntire(body) ?: return null
            val badges = match.groupValues.getOrNull(4).orEmpty().trim().let {
                if (it.isEmpty()) emptyList() else it.split(Regex("\\s{2,}"))
            }
            if (!badges.all(::isKnownOmpSwitchBadge)) return null
            modelRows += OmpSwitchRow(
                pointed = pointed,
                id = match.groupValues[1],
                current = match.groupValues[2].isNotEmpty(),
                overContext = match.groupValues[3].isNotEmpty(),
                badges = badges,
            )
        }
        if (modelRows.size < 2 || modelRows.count { it.pointed } != 1 ||
            modelRows.count { it.current } > 1 ||
            (hasScrollBar == true && padding) ||
            (hasScrollBar == true && !sawScrollThumb)
        ) return null
        val pointedAt = modelRows.indexOfFirst { it.pointed }
        val pointed = modelRows[pointedAt]
        val chipsText = boxContent(detailChips)
        val chipState = readOmpSwitchDetailChips(chipsText) ?: return null
        if (pointed.id != null && (chipState.second != pointed.overContext ||
                (!chipState.second && chipState.first != pointed.current))
        ) return null

        val actions = mutableListOf<SemanticAction>()
        val identityItems = mutableListOf("search:$search")
        identityItems += "current:${modelRows.firstOrNull { it.current }?.id.orEmpty()}"
        modelRows.forEachIndexed { index, row ->
            val rowId = row.id ?: "<truncated>"
            val rowDescription = row.badges.joinToString(" · ")
            var actionId = ""
            if (row.id != null && !row.overContext && !row.current) {
                actionId = "omp-model-${index + 1}"
                actions += pointerAction(actionId, row.id, pointedAt, index)
            }
            identityItems += "row:$rowId\t$rowDescription\t$actionId\t${row.current}\t${row.overContext}"
        }
        if (actions.isEmpty()) return null
        actions += action("cancel", if (search.isEmpty()) "Close" else "Clear search", listOf("Escape"))

        val accessibleTitle = listOf(
            "Switch Model",
            "Session-only switch — role models stay unchanged",
            "🔍 >${search.takeIf(String::isNotEmpty)?.let { " $it" }.orEmpty()}",
            modelRows.firstOrNull { it.current }?.id?.let { "● current: $it" },
        ).filterNotNull().joinToString("\n")
        return surface(
            SemanticKind.PROMPT_SELECT,
            accessibleTitle,
            detail = "omp-switch",
            items = identityItems,
            actions = actions,
            lines = lines,
            start = titleAt,
            end = end,
            signatureStart = titleAt,
            revision = revision,
        )
    }

    private data class OmpSwitchRow(
        val pointed: Boolean,
        val id: String?,
        val current: Boolean,
        val overContext: Boolean,
        val badges: List<String>,
    )

    private fun isKnownOmpSwitchBadge(value: String): Boolean = listOf(
        Regex("^🧠 \\d+$"),
        Regex("^(?:\\d+(?:\\.\\d+)?s )?~?\\d+(?:\\.\\d+)?t/s$"),
        Regex("^\\d+(?:\\.\\d+)?[kmbt]? ◫$"),
        Regex("^(?:\\$[\\d.?]+/[\\d.?]+(?: [\\d.?]+×)?|[\\d.?]+×|free|included|varies|unknown)$"),
    ).any { it.matches(value) }

    private fun readOmpSwitchDetailChips(value: String): Pair<Boolean, Boolean>? {
        if (Regex("^⦸ context \\S+ exceeds \\S+ limit(?:.*)$").matches(value)) return false to true
        if (value.isBlank()) return false to false
        val chips = value.split(" · ")
        if (chips.any { !Regex("^[●○] \\S(?:[^·]*\\S)?$").matches(it) }) return null
        return (chips.firstOrNull() == "● current") to false
    }

    /** Exact OMP 17.2/18.4 resume-picker rows. Unknown rows and no-match states stay raw. */
    private fun detectOmpResume(lines: List<String>, revision: Long): SemanticSurface? {
        val end = lastNonBlank(lines).takeIf { it >= 2 } ?: return null
        val boxed = lines[end].trim().matches(Regex("^╰─+╯$"))
        val bare = !boxed && lines[end].trim().matches(Regex("^─{3,}$"))
        if (!boxed && !bare) return null
        fun blank(line: String) = if (boxed) Regex("^│\\s*│$").matches(line.trim()) else line.isBlank()
        if (!blank(lines[end - 1])) return null
        val footerAt = end - 2
        val footerLine = if (boxed) lines[footerAt].trim() else lines[footerAt]
        val footer = if (boxed) Regex("^│ \\[(.*)]\\s*│$").matchEntire(footerLine)?.groupValues?.get(1)?.trim()
        else Regex("^ {2}\\[(.*)]$").matchEntire(footerLine)?.groupValues?.get(1)
        val knownFooters = if (boxed) setOf(
            "⌦/⌫ delete · ⏎ select · ⇥ all projects · ⎋ cancel",
            "⌦/⌫ delete · ⏎ select · ⇥ current folder · ⎋ cancel",
        ) else setOf("Del/⌫ delete · Enter select · Tab all projects · Esc cancel")
        if (footer !in knownFooters) return null
        val titlePattern = if (boxed) Regex("^╭─ (Resume Session \\((?:current folder|all projects)\\)) ─+╮$")
        else Regex("^ (Resume Session \\(current folder\\))$")
        val titleAt = (maxOf(0, footerAt - 120)..footerAt).firstOrNull { index ->
            titlePattern.matches(if (boxed) lines[index].trim() else lines[index])
        } ?: return null
        val titleLine = if (boxed) lines[titleAt].trim() else lines[titleAt]
        val title = titlePattern.matchEntire(titleLine)?.groupValues?.get(1) ?: return null
        var cursor = titleAt + 1
        cursor = if (boxed) {
            if (cursor + 2 >= footerAt || !blank(lines[cursor]) ||
                !Regex("^│ >(?: .*)?│$").matches(lines[cursor + 1].trim()) || !blank(lines[cursor + 2])
            ) return null
            cursor + 3
        } else {
            if (cursor + 4 >= footerAt || !blank(lines[cursor]) ||
                !Regex("^─+$").matches(lines[cursor + 1].trim()) || !blank(lines[cursor + 2]) ||
                !Regex("^>(?: .*)?$").matches(lines[cursor + 3].trim()) || !blank(lines[cursor + 4])
            ) return null
            cursor + 5
        }
        val sessions = mutableListOf<Pair<String, String>>()
        var pointedIndex = -1
        while (cursor < footerAt) {
            if (blank(lines[cursor])) {
                cursor++
                continue
            }
            val group = mutableListOf<String>()
            while (cursor < footerAt && !blank(lines[cursor])) {
                val value = if (boxed) {
                    val line = lines[cursor].trim()
                    if (!line.startsWith("│ ") || !line.endsWith("│")) return null
                    line.substring(2).removeSuffix("│").trimEnd()
                } else lines[cursor]
                group += value
                cursor++
            }
            if (group.size !in 2..3) return null
            val meta = group.last().trim()
            val segments = meta.split(Regex("\\s+·\\s+"))
            if (segments.size < 2 || !isOmpResumeAge(segments[0]) ||
                !segments[1].matches(Regex("\\d+(?:\\.\\d+)?[KMG]?B"))
            ) return null
            val head = Regex("^([❯ ]) (\\S.*)$").matchEntire(group.first()) ?: return null
            val pointed = head.groupValues[1] == "❯"
            val label = head.groupValues[2].trim()
            if (label.isBlank()) return null
            if (group.size == 3 && !Regex("^ {2}\\S.*$").matches(group[1])) return null
            if (pointed) {
                if (pointedIndex >= 0) return null
                pointedIndex = sessions.size
            }
            sessions += label to meta
        }
        if (sessions.isEmpty() || pointedIndex < 0) return null
        val actions = sessions.mapIndexed { index, (label, _) ->
            pointerAction("option-${index + 1}", label, pointedIndex, index)
        } + action("cancel", "Cancel", listOf("Escape"))
        return surface(
            SemanticKind.PROMPT_SELECT,
            title,
            "omp-resume",
            items = sessions.map { "${it.first}\n${it.second}" },
            actions = actions,
            lines = lines,
            start = titleAt,
            end = end,
            signatureStart = titleAt,
            revision = revision,
        )
    }

    private fun isOmpResumeAge(value: String): Boolean = value.matches(
        Regex("(?:just now|now|\\d+ \\w+ ago|\\d{1,4}[./-]\\d{1,2}[./-]\\d{1,4})", RegexOption.IGNORE_CASE),
    )

    private fun detectMuse(lines: List<String>, revision: Long): SemanticSurface? {
        detectMuseCheckbox(lines, revision)?.let { return it }
        val footer = lastNonBlank(lines).takeIf { it >= 0 } ?: return null
        val trustFooter = lines.indexOfLast { it.trim() == "Use Up/Down or 1/2, then Enter. Esc quits." }
        if (trustFooter >= 0) {
            val title = lines.indexOfLast { it.trim() == "Do you trust this workspace?" }
            if (title < 0) return null
            val rows = trailingNumberedRows(lines, trustFooter, ::parseMuseRow)
            if (rows.size != 2 || rows.map { it.number } != listOf(1, 2) || rows.count { it.pointed } != 1 ||
                !rows[0].label.startsWith("Trust and continue", true) || !rows[1].label.equals("Quit", true)
            ) return null
            val actions = rows.map { action("option-${it.number}", it.label, listOf(it.number.toString())) } +
                action("cancel", "Cancel", listOf("Escape"))
            return surface(SemanticKind.PROMPT_SELECT, lines[title].trim(), "trust", actions = actions,
                lines = lines, start = title, end = trustFooter, revision = revision)
        }
        val questionFooter = lines.indexOfLast {
            it.trim() == "Enter to select · ↑/↓ to move · Tab for an optional note · Esc to interrupt"
        }
        if (questionFooter >= 0) {
            val rows = trailingNumberedRows(lines, questionFooter, ::parseMuseRow)
            if (rows.size !in 2..9 || rows.count { it.pointed } != 1 || rows.any { it.label.isBlank() }) return null
            val voice = (questionFooter + 1 until minOf(lines.size, questionFooter + 8)).firstOrNull {
                lines[it].trim().startsWith("── Voice input")
            } ?: return null
            val composer = (voice + 1 until minOf(lines.size, voice + 5)).firstOrNull { lines[it].trim() == "❯" }
                ?: return null
            if ((composer + 1 until lines.size).any { lines[it].trim().startsWith("❯") && lines[it].trim() != "❯" }) return null
            val questionAt = (maxOf(0, rows.first().line - 8) until rows.first().line)
                .lastOrNull { lines[it].trim().endsWith("?") } ?: return null
            val question = lines[questionAt].trim()
            val pointIndex = rows.indexOfFirst { it.pointed }
            val actions = rows.mapIndexed { index, row -> pointerAction("option-${row.number}", row.label, pointIndex, index) }
            return surface(SemanticKind.PROMPT_SELECT, question, "select", actions = actions,
                lines = lines, start = questionAt, end = questionFooter, signatureStart = questionAt,
                revision = revision)
        }
        // Muse's approval is a digit-only, immediate action. Include the full command subject in
        // the signed surface so a changed command cannot reuse the old approval card.
        val question = lines.indexOfLast { it.trim() == "Would you like to run the following command?" }
        if (question >= 0) {
            val rows = trailingNumberedRows(lines, footer, ::parseMuseRow)
            if (rows.size != 3 || rows.map { it.number } != listOf(1, 2, 3) || rows.count { it.pointed } != 1 ||
                lines.subList(question + 1, rows.first().line).none { it.trim().startsWith("Current argv:") }
            ) return null
            val actions = rows.map { action("option-${it.number}", it.label, listOf(it.number.toString())) }
            return surface(SemanticKind.PROMPT_SELECT, lines[question].trim(), "permission", actions = actions,
                lines = lines, start = question, end = rows.last().line, signatureStart = question, revision = revision)
        }
        return null
    }

    /** Exact Muse 1.3.0 checkbox phase from the measured toppings/drinks capture corpus. */
    private fun detectMuseCheckbox(lines: List<String>, revision: Long): SemanticSurface? {
        detectMuseCheckboxReview(lines, revision)?.let { return it }
        val footer = lines.indexOfLast { it.trim().startsWith("Enter to toggle") }
        if (footer < 0) return null
        val footerText = (footer..minOf(lines.lastIndex, footer + 2)).joinToString(" ") { lines[it].trim() }
        if (!footerText.startsWith("Enter to toggle") ||
            !footerText.contains("Submit row to continue") ||
            !footerText.contains("↑/↓ to move") ||
            !footerText.contains("Tab for an optional note") ||
            !footerText.contains("Esc to interrupt")
        ) return null
        val footerEnd = if (lines.getOrNull(footer + 1)?.trim() == "interrupt") footer + 1 else footer
        // Muse marks its focused checkbox row with the measured `›` glyph (the other
        // numbered-dialog grammars use `❯`). Keep the glyph allowlist exact to these captures.
        val rowPattern = Regex("^\\s*([❯›]\\s*)?(\\d+)\\.\\s+(.+?)\\s*$")
        val found = (maxOf(0, footer - OPTION_WINDOW)..footer).mapNotNull { index ->
            rowPattern.matchEntire(lines[index])?.let { match ->
                NumberedRow(index, match.groupValues[2].toInt(), match.groupValues[3].trim(), match.groupValues[1].isNotEmpty())
            }
        }
        if (found.isEmpty()) return null
        var first = found.lastIndex
        while (first > 0 && found[first - 1].number == found[first].number - 1) first--
        val rows = found.subList(first, found.size)
        if (rows.first().number != 1 || rows.size !in 2..10) return null
        val optionRows = rows.dropLast(1)
        if (optionRows.size !in 1..9 || optionRows.map { it.number } != (1..optionRows.size).toList()) return null
        val submit = rows.last()
        val checkedCount = Regex("^Submit answer \\((\\d+) checked\\)$").matchEntire(submit.label)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val parsedOptions = optionRows.map { row ->
            val state = checkbox(row.label) ?: return null
            row to state
        }
        if (parsedOptions.count { it.second.first } != checkedCount) return null
        val pointedRows = rows.filter(NumberedRow::pointed)
        if (pointedRows.size != 1) return null
        val questionAt = (maxOf(0, optionRows.first().line - 12) until optionRows.first().line)
            .lastOrNull { lines[it].trim().endsWith("?") } ?: return null
        val headerAt = (maxOf(0, questionAt - 8) until questionAt)
            .lastOrNull { lines[it].contains("Request user input") } ?: return null
        if (headerAt >= questionAt) return null
        val noteOpen = (optionRows.first().line..footerEnd).any { lines[it].trim().startsWith("Note (optional):") }
        val actions = if (noteOpen) emptyList() else parsedOptions.map { (row, state) ->
            val cleanLabel = state.second.removeSuffix("  Optionally, add details in notes (tab).").trim()
            if (cleanLabel.isBlank()) return null
            action("toggle-${row.number}", cleanLabel,
                if (row.pointed) listOf("Enter") else listOf(row.number.toString(), "Enter"))
        }
        return surface(
            SemanticKind.MULTI_SELECT,
            lines[questionAt].trim(),
            detail = "muse-pointer-checkbox",
            actions = actions,
            lines = lines,
            start = questionAt,
            end = footerEnd,
            signatureStart = questionAt,
            revision = revision,
            locked = noteOpen,
        )
    }

    /** The captured Muse review phase has an unnumbered, already-pointed Submit row. */
    private fun detectMuseCheckboxReview(lines: List<String>, revision: Long): SemanticSurface? {
        val status = lastNonBlank(lines).takeIf { it >= 0 } ?: return null
        if (!MUSE_STATUS.matches(lines[status])) return null
        val bottom = status - 1
        if (bottom < 3 || !MUSE_RULE.matches(lines[bottom])) return null
        val prompt = bottom - 1
        if (lines[prompt].trim() != "❯" || !MUSE_VOICE.matches(lines[prompt - 1])) return null
        val voice = prompt - 1
        val interruptAt = previousNonBlank(lines, voice - 1)
        if (interruptAt < 0) return null
        val interruptRow = lines[interruptAt].trim()
        val interruptPointed = interruptRow.startsWith(">")
        if (interruptRow.removePrefix(">").trim() != "Interrupt turn" ||
            lines[interruptAt].trimStart() == lines[interruptAt]
        ) return null
        val submitAt = previousNonBlank(lines, interruptAt - 1)
        if (submitAt < 0) return null
        val submitRow = lines[submitAt].trim()
        val submitPointed = submitRow.startsWith(">")
        if (submitRow.removePrefix(">").trim() != "Submit answers" ||
            submitPointed == interruptPointed || lines[submitAt].trimStart() == lines[submitAt]
        ) return null
        var cursor = submitAt - 1
        val summariesReversed = mutableListOf<String>()
        var leadAt = -1
        while (cursor >= 0) {
            cursor = previousNonBlank(lines, cursor)
            if (cursor < 0) break
            val row = lines[cursor].trim()
            if (row.startsWith("Review answers before submit")) {
                leadAt = cursor
                break
            }
            if (": " !in row || row.length > 512) return null
            summariesReversed += row
            cursor--
        }
        if (leadAt < 0) return null
        val lead = lines[leadAt].trim()
        if (!lead.startsWith("Review answers before submit") ||
            !lead.contains("Enter to edit or submit") || !lead.contains("↑/↓ to move") ||
            !lead.contains("Esc to go back")
        ) return null
        val summaries = summariesReversed.asReversed()
        if (summaries.isEmpty()) return null
        val pointerIndex = if (submitPointed) 0 else 1
        val actions = listOf(
            pointerAction("review-submit", "Submit answers", pointerIndex, 0),
            pointerAction("review-interrupt", "Interrupt turn", pointerIndex, 1),
            action("review-back", "Edit answers", listOf("Escape")),
        )
        return surface(
            SemanticKind.MULTI_SELECT,
            "Review answers before submit",
            detail = "muse-checkbox-review",
            items = summaries,
            actions = actions,
            lines = lines,
            start = leadAt,
            end = status,
            signatureStart = leadAt,
            revision = revision,
        )
    }

    private fun detectOpenCodeQuestion(lines: List<String>, revision: Long, focusedRows: Set<Int>): SemanticSurface? {
        val footer = lines.indexOfLast { openCodeBoxContent(it) == "↑↓ select  enter submit  esc dismiss" }
        if (footer < 0) return null
        val rows = trailingNumberedRows(lines, footer) { parseOpenCodeRow(it) }
            .map { it.copy(pointed = it.line in focusedRows) }
        if (rows.size !in 2..9 || rows.count { it.pointed } != 1 ||
            rows.zipWithNext().any { (a, b) -> b.line - a.line > 3 } ||
            lines.getOrNull(footer - 1)?.trim() != "┃"
        ) return null
        val questionAt = (maxOf(0, rows.first().line - 8) until rows.first().line)
            .lastOrNull { lines[it].trim().removePrefix("┃").trim().endsWith("?") } ?: return null
        val question = lines[questionAt].trim().removePrefix("┃").trim()
        val pointIndex = rows.indexOfFirst { it.pointed }
        val actions = rows.mapIndexed { index, row -> pointerAction("option-${row.number}", row.label, pointIndex, index) }
        return surface(SemanticKind.PROMPT_SELECT, question, "select", actions = actions,
            lines = lines, start = questionAt, end = footer, signatureStart = questionAt, revision = revision)
    }

    /** OpenCode encodes its pointer only in the measured SGR chip style, not a printable glyph. */
    private fun openCodeFocusedRows(raw: String): Set<Int> = raw.split('\n').mapIndexedNotNull { index, line ->
        index.takeIf { OPENCODE_POINTER_STYLE.containsMatchIn(line) }
    }.toSet()

    private fun parseMuseRow(text: String): NumberedRow? {
        val match = Regex("^\\s*([›❯>]\\s*)?([1-9])\\.?\\s+(.+?)\\s*$").matchEntire(text) ?: return null
        return NumberedRow(number = match.groupValues[2].toInt(), label = match.groupValues[3],
            pointed = match.groupValues[1].isNotEmpty())
    }

    private fun parseOpenCodeRow(text: String): NumberedRow? {
        val match = Regex("^\\s*(?:┃\\s*)?([›❯>]\\s*)?([1-9])\\.\\s+(.+?)\\s*$").matchEntire(text) ?: return null
        return NumberedRow(number = match.groupValues[2].toInt(), label = match.groupValues[3],
            pointed = match.groupValues[1].isNotEmpty())
    }

    private fun pointerAction(id: String, label: String, pointed: Int, target: Int): SemanticAction {
        val delta = target - pointed
        val arrows = List(kotlin.math.abs(delta)) { if (delta > 0) "Down" else "Up" }
        return action(id, label, arrows + "Enter")
    }

    private fun boxContent(line: String): String = line.trim().removePrefix("│").removeSuffix("│").trim()

    private fun openCodeBoxContent(line: String): String = line.trim().removePrefix("┃").trim()

    private fun isOmpHorizontalRule(line: String): Boolean {
        val content = line.trim().removePrefix("├").removeSuffix("┤").trim()
        return content.isNotEmpty() && content.all { it in "─━═- " }
    }

    private val OPENCODE_POINTER_STYLE = Regex("\u001b\\[38;2;106;145;198m\u001b\\[48;2;30;30;30m[1-9]\\.")

    private fun detectClaude(lines: List<String>, revision: Long): SemanticSurface? =
        detectClaudePreview(lines, revision)
            ?: detectClaudeMulti(lines, revision)
            ?: detectClaudeWizard(lines, revision)
            ?: detectClaudePrompt(lines, revision)
            ?: detectClaudeMenu(lines, revision)
            ?: detectClaudeAutocomplete(lines, revision)

    private fun detectClaudePrompt(lines: List<String>, revision: Long): SemanticSurface? {
        val footer = lastNonBlank(lines)
        if (footer < 0) return null
        val family = claudeFamily(lines[footer]) ?: return null
        if (stepGlyph.findAll(lines.subList(maxOf(0, footer - 18), footer).joinToString(" ")).count() >= 2) return null
        val rows = trailingNumberedRows(lines, footer, ::parseClaudeOption)
        if (rows.size !in 2..9) return null
        val hasPlanFeedbackTail = family == "plan" && lines
            .subList(rows.last().line + 1, footer)
            .any { it.trim().startsWith("shift+tab to approve with this feedback", ignoreCase = true) }
        val footerGap = CLAUDE_PROMPT_FOOTER_GAP +
            if (hasPlanFeedbackTail) CLAUDE_PLAN_FEEDBACK_WRAP_ALLOWANCE else 0
        if (footer - rows.last().line > footerGap) return null
        val questionAt = findQuestion(lines, rows.first().line) ?: return null
        val planInputLines = if (family == "plan") {
            rows.mapIndexedNotNull { index, row ->
                val next = rows.getOrNull(index + 1)?.line ?: footer
                row.line.takeIf {
                    lines.subList(row.line + 1, next).any { continuation ->
                        continuation.trim().startsWith("shift+tab to approve with this feedback", ignoreCase = true)
                    }
                }
            }.toSet()
        } else {
            emptySet()
        }
        val locked = rows.any { it.pointed && (freeText.containsMatchIn(it.label) || it.line in planInputLines) }
        val keysWithEnter = family == "select"
        val actions = rows.filterNot { freeText.containsMatchIn(it.label) || it.line in planInputLines }.map {
            action("option-${it.number}", it.label, listOf(it.number.toString()) + if (keysWithEnter) listOf("Enter") else emptyList())
        }
        return surface(
            kind = SemanticKind.PROMPT_SELECT,
            title = lines[questionAt].trim(),
            actions = actions,
            lines = lines,
            start = questionAt,
            end = footer,
            signatureStart = maxOf(0, rows.first().line - CLAUDE_PROMPT_SIGNATURE_LOOKBACK),
            revision = revision,
            locked = locked,
        )
    }

    private fun detectClaudeWizard(lines: List<String>, revision: Long): SemanticSurface? {
        val footer = lastNonBlank(lines)
        if (footer < 0) return null
        detectClaudeReview(lines, footer, revision, requireMultiQuestion = true)?.let { return it }
        if (claudeFamily(lines[footer]) != "select") return null
        val rows = trailingNumberedRows(lines, footer, ::parseClaudeOption)
        if (rows.size !in 2..9 || rows.any { checkbox(it.label) != null } || footer - rows.last().line > 3) return null
        val stepper = findStepper(lines, rows.first().line, requireMultiQuestion = true) ?: return null
        val question = lines.subList(stepper + 1, rows.first().line).filter(String::isNotBlank).joinToString(" ") { it.trim() }
        if (question.isBlank()) return null
        val locked = rows.any { it.pointed && freeText.containsMatchIn(it.label) }
        val actions = buildList {
            add(action("previous", "Previous question", listOf("Left")))
            rows.filterNot { freeText.containsMatchIn(it.label) }.forEach {
                add(action("option-${it.number}", it.label.removeSuffix(" ✔"), listOf(it.number.toString())))
            }
            add(action("next", "Next question", listOf("Right")))
        }
        return surface(SemanticKind.WIZARD, question, actions = actions,
            lines = lines, start = stepper, end = footer, revision = revision, locked = locked)
    }

    private fun detectClaudeReview(
        lines: List<String>,
        footer: Int,
        revision: Long,
        requireMultiQuestion: Boolean,
    ): SemanticSurface? {
        val cancel = parseClaudeOption(lines[footer]) ?: return null
        val submitAt = previousNonBlank(lines, footer - 1)
        val submit = submitAt.takeIf { it >= 0 }?.let { parseClaudeOption(lines[it]) } ?: return null
        if (cancel.number != 2 || cancel.label != "Cancel" || submit.number != 1 || submit.label != "Submit answers") return null
        val readyAt = previousNonBlank(lines, submitAt - 1)
        if (readyAt < 0 || !lines[readyAt].trim().startsWith("Ready to submit your answers?", ignoreCase = true)) return null
        val stepper = findStepper(lines, readyAt, requireMultiQuestion) ?: return null
        val actions = listOf(
            action("submit", "Submit answers", listOf("1")),
            action("cancel", "Cancel", listOf("2")),
        )
        return surface(
            if (requireMultiQuestion) SemanticKind.WIZARD else SemanticKind.MULTI_SELECT,
            "Ready to submit your answers?",
            actions = actions,
            lines = lines,
            start = stepper,
            end = footer,
            revision = revision,
        )
    }

    private fun detectClaudePreview(lines: List<String>, revision: Long): SemanticSurface? {
        val footer = lastNonBlank(lines)
        if (footer < 0 || !lines[footer].contains("n to add notes", ignoreCase = true) || claudeFamily(lines[footer]) != "select") return null
        val notesAt = (maxOf(0, footer - 8) until footer).lastOrNull { lines[it].contains("Notes:") } ?: return null
        val noteColumn = lines[notesAt].indexOf("Notes:").takeIf { it > 0 } ?: return null
        val rows = mutableListOf<NumberedRow>()
        for (i in maxOf(0, notesAt - OPTION_WINDOW) until notesAt) {
            parseClaudeOption(lines[i].take(noteColumn))?.let { rows += it.copy(line = i) }
        }
        if (rows.size !in 2..9 || rows.map { it.number } != (1..rows.size).toList()) return null
        val questionAt = findQuestion(lines, rows.first().line) ?: return null
        val locked = lines[footer].contains("ctrl+g to edit", ignoreCase = true)
        val previewStepper = findStepper(lines, rows.first().line, requireMultiQuestion = true)
        val actions = buildList {
            rows.forEach { add(action("preview-${it.number}", "Preview ${it.label.removeSuffix(" ✔")}", listOf(it.number.toString()))) }
            rows.firstOrNull(NumberedRow::pointed)?.let {
                add(action("choose-${it.number}", "Choose highlighted · ${it.label.removeSuffix(" ✔")}", listOf("Enter")))
            }
            if (previewStepper != null) {
                add(action("previous", "Previous question", listOf("Left")))
                add(action("next", "Next question", listOf("Right")))
            }
        }
        return surface(SemanticKind.PREVIEW_SELECT, lines[questionAt].trim(),
            items = rows.map { it.label.removeSuffix(" ✔") }, actions = actions,
            lines = lines, start = previewStepper ?: questionAt, end = footer, revision = revision, locked = locked)
    }

    private fun detectClaudeMulti(lines: List<String>, revision: Long): SemanticSurface? {
        val footer = lastNonBlank(lines)
        if (footer < 0) return null
        detectClaudeReview(lines, footer, revision, requireMultiQuestion = false)?.let { return it }
        if (claudeFamily(lines[footer]) != "select") return null
        val rows = trailingNumberedRows(lines, footer, ::parseClaudeOption)
        if (rows.size !in 2..9) return null
        val checkboxRows = rows.mapNotNull { row -> checkbox(row.label)?.let { Triple(row, it.first, it.second) } }
        if (checkboxRows.size < 2) return null
        val stepper = findCheckboxStepper(lines, rows.first().line) ?: return null
        val ruleAt = (rows.first().line + 1 until footer).lastOrNull { isRule(lines[it]) } ?: return null
        val advanceAt = previousNonBlank(lines, ruleAt - 1)
        if (advanceAt <= rows.first().line || !lines[advanceAt].trim().removePrefix("❯").trim().matches(Regex("Submit|Next"))) return null
        val question = lines.subList(stepper + 1, rows.first().line).filter(String::isNotBlank).joinToString(" ") { it.trim() }
        if (question.isBlank()) return null
        val actions = buildList {
            // A checkbox question can be one step of the same multi-question wizard. A standalone
            // one-question multi-select still prints a decorative one-chip header, but the web UI
            // intentionally does not invent navigation for it.
            if (stepGlyph.findAll(lines[stepper]).count() >= 3) {
                add(action("previous", "Previous question", listOf("Left")))
            }
            checkboxRows.filterNot { freeText.containsMatchIn(it.third) }.forEach { (row, checked, label) ->
                add(action("toggle-${row.number}", "${if (checked) "☑" else "☐"} $label", listOf(row.number.toString())))
            }
            rows.firstOrNull { it.label.startsWith("Chat about this", ignoreCase = true) }?.let {
                add(action("escape", it.label, listOf(it.number.toString())))
            }
            add(action("up", "Move highlight up", listOf("Up")))
            add(action("down", "Move highlight down", listOf("Down")))
            if (lines[advanceAt].trimStart().startsWith("❯")) {
                add(action("advance", lines[advanceAt].trim().removePrefix("❯").trim(), listOf("Enter")))
            }
            if (stepGlyph.findAll(lines[stepper]).count() >= 3) {
                add(action("next", "Next question", listOf("Right")))
            }
        }
        return surface(SemanticKind.MULTI_SELECT, question, actions = actions,
            lines = lines, start = stepper, end = footer, revision = revision)
    }

    private fun detectClaudeMenu(lines: List<String>, revision: Long): SemanticSurface? {
        val footer = lastNonBlank(lines)
        if (footer < 0 || claudeFamily(lines[footer]) != null) return null
        val named = parseToFooter(lines[footer])
        if (named.size < 2) return null
        val start = (maxOf(0, footer - 30) until footer).lastOrNull { isRule(lines[it]) } ?: return null
        val titleAt = (start + 1 until footer).firstOrNull { lines[it].isNotBlank() } ?: return null
        val actions = buildList {
            addAll(named)
            if ((start until footer).any { lines[it].trimStart().startsWith("❯") }) {
                add(action("up", "Move up", listOf("Up")))
                add(action("down", "Move down", listOf("Down")))
            }
            if ((start until footer).any { it > start && Regex("←/→\\s+to").containsMatchIn(lines[it]) }) {
                add(action("left", "Previous value", listOf("Left")))
                add(action("right", "Next value", listOf("Right")))
            }
        }.distinctBy(SemanticAction::id)
        return surface(SemanticKind.MENU, lines[titleAt].trim(), actions = actions,
            lines = lines, start = start, end = footer, revision = revision)
    }

    private fun detectClaudeAutocomplete(lines: List<String>, revision: Long): SemanticSurface? {
        val footer = lastNonBlank(lines)
        if (footer < 0) return null
        val entry = Regex("^  (/[A-Za-z0-9][A-Za-z0-9:_-]*)(?: {2,}(\\S.*))?$")
        var start = footer
        while (start >= 0 && (entry.matches(lines[start]) || (lines[start].startsWith("    ") && lines[start].trim().isNotEmpty()))) start--
        start++
        if (start > footer || !entry.matches(lines[start])) return null
        val border = previousNonBlank(lines, start - 1)
        if (border < 0 || !isRule(lines[border])) return null
        val items = (start..footer).mapNotNull { entry.matchEntire(lines[it])?.let { match ->
            listOfNotNull(match.groupValues[1], match.groupValues.getOrNull(2)?.takeIf(String::isNotBlank)).joinToString(" — ")
        } }
        if (items.isEmpty()) return null
        return surface(SemanticKind.AUTOCOMPLETE, "Suggestions", items = items,
            lines = lines, start = border, end = footer, revision = revision, ownsKeyboard = false)
    }

    private fun detectCodex(lines: List<String>, revision: Long): SemanticSurface? {
        val footer = lastNonBlank(lines)
        if (footer < 0) return null
        val footerText = lines[footer]
        if (footerText.trim() == "Press enter to continue") {
            val rows = numberedRowsImmediatelyAbove(lines, footer, ::parseCodexOption)
            if (rows.size != 2 || rows[0].label != "Yes, continue" || rows[1].label != "No, quit") return null
            val questionAt = (maxOf(0, rows.first().line - 12) until rows.first().line).lastOrNull {
                lines[it].contains("Do you trust the contents of this directory?")
            } ?: return null
            return surface(SemanticKind.PROMPT_SELECT, lines[questionAt].trim(),
                actions = rows.map { action("option-${it.number}", it.label, listOf(it.number.toString())) },
                lines = lines, start = questionAt, end = footer, revision = revision)
        }
        if (footerText.trim() == "Press enter to confirm or esc to cancel") {
            val rows = trailingNumberedRows(lines, footer, ::parseCodexOption)
            if (rows.size < 2 || rows.first().number != 1 || rows.first().label.substringBefore(" (") != "Yes, proceed" ||
                rows.last().label.substringBefore(" (") != "No, and tell Codex what to do differently" ||
                rows.drop(1).dropLast(1).any { !it.label.contains("don't ask again", true) && !it.label.contains("don’t ask again", true) }
            ) return null
            val header = (maxOf(0, rows.first().line - 24) until rows.first().line).lastOrNull {
                lines[it].trim() == "Would you like to run the following command?"
            } ?: return null
            val actions = listOf(
                action("yes", "Yes, proceed", listOf("1")),
                action("no", "No, and tell Codex what to do differently", listOf(rows.last().number.toString())),
            )
            return surface(SemanticKind.PROMPT_SELECT, lines[header].trim(), actions = actions,
                lines = lines, start = header, end = footer, revision = revision)
        }
        if (footerText.trim().lowercase().startsWith("tab to add notes | enter to submit") ||
            footerText.trim().lowercase().startsWith("tab or esc to clear notes")
        ) {
            val rows = trailingNumberedRows(lines, footer, ::parseCodexOption)
            if (rows.size < 2 || rows.map { it.number } != (1..rows.size).toList()) return null
            val headerAt = (maxOf(0, rows.first().line - 8) until rows.first().line).lastOrNull {
                Regex("^\\s*Question \\d+/\\d+ \\(\\d+ unanswered\\)$").matches(lines[it])
            } ?: return null
            val questionAt = previousNonBlank(lines, rows.first().line - 1)
            if (questionAt <= headerAt) return null
            val locked = footerText.contains("clear notes", true)
            return surface(SemanticKind.PROMPT_SELECT, lines[questionAt].trim(),
                actions = rows.map { action("option-${it.number}", it.label.substringBefore("  "), listOf(it.number.toString())) },
                lines = lines, start = headerAt, end = footer, revision = revision, locked = locked)
        }
        return null
    }

    private fun detectGrok(lines: List<String>, revision: Long): SemanticSurface? =
        detectGrokPermission(lines, revision)
            ?: detectGrokAsk(lines, revision)
            ?: detectGrokUnsupportedCheckbox(lines, revision)
            ?: detectGrokPlan(lines, revision)

    private fun detectGrokPermission(lines: List<String>, revision: Long): SemanticSurface? {
        val footer = lastNonBlank(lines)
        if (footer < 0) return null
        val count = Regex("(?:^|\\s)1/([1-9]):select").find(lines[footer])?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (!lines[footer].contains("Tab:next option", true) || !lines[footer].contains("Ctrl+o:always-approve", true) ||
            !lines[footer].contains("Ctrl+c:cancel", true)) return null
        val rows = grokRows(lines, footer)
        if (rows.size != count || rows.size < 3 || rows.map { it.number } != (1..rows.size).toList()) return null
        val yes = rows[rows.lastIndex - 1]
        val no = rows.last()
        if (!yes.label.startsWith("Yes", true) || yes.label.contains("always", true) || !no.label.startsWith("No, reject", true) ||
            rows.dropLast(2).any {
                !Regex("always-approve|don't ask again|don’t ask again|this session", RegexOption.IGNORE_CASE)
                    .containsMatchIn(it.label)
            }
        ) return null
        val start = grokCardStart(lines, rows.first().line) ?: return null
        val title = grokQuestion(lines, start, rows.first().line) ?: return null
        return surface(SemanticKind.PROMPT_SELECT, title,
            actions = listOf(
                action("yes", yes.label.substringBefore(" ("), listOf(yes.number.toString())),
                action("no", no.label.substringBefore(" ("), listOf(no.number.toString())),
            ), lines = lines, start = start, end = footer, revision = revision)
    }

    private fun detectGrokAsk(lines: List<String>, revision: Long): SemanticSurface? {
        val footer = lastNonBlank(lines)
        if (footer < 0 || !(lines[footer].contains("Tab:next answer", true) ||
                lines[footer].contains("Tab/Space:question", true) || lines[footer].contains("Esc:back", true))) return null
        val rows = grokRows(lines, footer)
        if (rows.size < 2 || rows.map { it.number } != (1..rows.size).toList()) return null
        val first = rows.first().line
        val start = grokCardStart(lines, first) ?: return null
        val title = grokQuestion(lines, start, first) ?: return null
        val window = lines.subList(start, footer + 1)
        if (window.any { Regex("^\\s*┃\\s+[1-9]\\s+\\[[ x✔✓]\\]", RegexOption.IGNORE_CASE).containsMatchIn(it) }) return null
        if (window.none { it.contains("↑/↓ navigate", true) && Regex("Enter:(?:select|submit|edit)", RegexOption.IGNORE_CASE).containsMatchIn(it) }) return null
        val locked = window.any { Regex("^\\s*┃\\s+z\\s+\\(●\\)").containsMatchIn(it) } ||
            window.any { it.contains("Enter:edit", true) }
        val parked = lines[footer].contains("Tab/Space:question", true)
        return surface(SemanticKind.PROMPT_SELECT, title,
            actions = rows.map {
                val keys = if (parked) listOf("Tab", it.number.toString()) else listOf(it.number.toString())
                action("option-${it.number}", it.label.substringBefore("  "), keys)
            }, lines = lines, start = start, end = footer, revision = revision, locked = locked)
    }

    private fun detectGrokPlan(lines: List<String>, revision: Long): SemanticSurface? {
        val footer = lastNonBlank(lines)
        if (footer < 0 || !Regex("(?:^|\\s)a:approve(?:\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(lines[footer]) ||
            !(lines[footer].contains("q:quit plan", true) || lines[footer].contains("Tab:plan", true) || lines[footer].contains("Esc:back", true))) return null
        val status = (0 until footer).lastOrNull { lines[it].contains("plan approval", true) } ?: return null
        val start = (0 until status).lastOrNull { lines[it].trimStart().startsWith("╭─") } ?: return null
        val actions = parseColonFooter(lines[footer])
        if (actions.isEmpty()) return null
        return surface(SemanticKind.MENU, "Plan approval", actions = actions,
            lines = lines, start = start, end = footer, revision = revision)
    }

    /** Grok checkbox asks have no probed safe digit recipe; claim only enough to lock free text. */
    private fun detectGrokUnsupportedCheckbox(lines: List<String>, revision: Long): SemanticSurface? {
        val footer = lastNonBlank(lines)
        if (footer < 0 || !(lines[footer].contains("Tab:next answer", true) ||
                lines[footer].contains("Tab/Space:question", true))) return null
        val checkboxRow = (maxOf(0, footer - 40) until footer).firstOrNull {
            Regex("^\\s*┃\\s+[1-9]\\s+\\[[ x✔✓]\\]", RegexOption.IGNORE_CASE).containsMatchIn(lines[it])
        } ?: return null
        val start = grokCardStart(lines, checkboxRow) ?: return null
        val title = grokQuestion(lines, start, checkboxRow) ?: return null
        return surface(
            SemanticKind.MULTI_SELECT,
            title,
            lines = lines,
            start = start,
            end = footer,
            revision = revision,
            locked = true,
        )
    }

    private fun detectAgy(lines: List<String>, revision: Long): SemanticSurface? {
        val alien = Regex("Claude Code|\\.claude/|╭─ Ask ─╮|oh-my-pi|codex|grok", RegexOption.IGNORE_CASE)
        if (lines.any(alien::containsMatchIn)) return null
        var footer = lastNonBlank(lines)
        if (footer < 0) return null
        var family = agyFamily(lines[footer])
        if (family == null && footer > 0) {
            family = agyFamily(lines[footer - 1])
            if (family != null) footer--
        }
        family ?: return null
        val rows = trailingNumberedRows(lines, footer) { parseAgyOption(it, family == "trust") }
        if (rows.size !in 2..9 || footer - rows.last().line > 3) return null
        val questionAt = findQuestion(lines, rows.first().line) ?: return null
        val actions = rows.filterNot { freeText.containsMatchIn(it.label) }.map {
            val keys = listOf(it.number.toString()) + if (family == "select" || family == "plan") listOf("Enter") else emptyList()
            action("option-${it.number}", it.label, keys)
        }
        return surface(SemanticKind.PROMPT_SELECT, lines[questionAt].trim(), family.replaceFirstChar { it.uppercase() },
            actions = actions, lines = lines, start = questionAt, end = footer, revision = revision)
    }

    private fun surface(
        kind: SemanticKind,
        title: String,
        detail: String? = null,
        items: List<String> = emptyList(),
        actions: List<SemanticAction> = emptyList(),
        lines: List<String>,
        start: Int,
        end: Int,
        signatureStart: Int = start,
        revision: Long,
        ownsKeyboard: Boolean = true,
        locked: Boolean = false,
    ): SemanticSurface? {
        if (title.isBlank() || start !in lines.indices || end !in lines.indices || start > end ||
            signatureStart !in 0..start
        ) return null
        val signature = lines.subList(signatureStart, end + 1).joinToString("\n")
        if (signature.isBlank() || signature.length > MAX_BINDING_CHARS) return null
        if (actions.any { it.keys.isEmpty() || it.keys.any { key -> !MuxKeyGrammar.isValid(key) } }) return null
        val surface = SemanticSurface(
            kind = kind,
            title = title,
            detail = detail,
            items = items,
            actions = actions,
            regionSignature = signature,
            revision = revision,
            startLine = start,
            endLine = end,
            ownsKeyboard = ownsKeyboard,
            interactionLocked = locked,
        )
        return surface.copy(nativeModel = NativeSemanticDeriver.derive(surface))
    }

    private fun action(id: String, label: String, keys: List<String>) = SemanticAction(id, label.trim(), keys)

    private data class NumberedRow(val line: Int = -1, val number: Int, val label: String, val pointed: Boolean)

    private fun parseClaudeOption(text: String): NumberedRow? = claudeOption.matchEntire(text.trim())?.let {
        NumberedRow(number = it.groupValues[1].toInt(), label = it.groupValues[2].trim(), pointed = text.trimStart().startsWith("❯"))
    }

    private fun parseCodexOption(text: String): NumberedRow? = codexOption.matchEntire(text)?.let {
        NumberedRow(
            number = it.groupValues[1].toInt(),
            label = it.groupValues[2].trim(),
            pointed = text.trimStart().startsWith("›"),
        )
    }

    private fun parseAgyOption(text: String, trust: Boolean): NumberedRow? {
        val trimmed = text.trim()
        agyOption.matchEntire(trimmed)?.let {
            val number = listOf(it.groupValues[1], it.groupValues[2], it.groupValues[3]).first(String::isNotEmpty).toInt()
            return NumberedRow(number = number, label = it.groupValues[4].trim(), pointed = trimmed.firstOrNull() in setOf('❯', '›', '>'))
        }
        if (trust) {
            Regex("^(?:[❯›>•*○●]\\s*)?((?:Yes|No)[^.]*)$", RegexOption.IGNORE_CASE).matchEntire(trimmed)?.let {
                val label = it.groupValues[1].trim()
                return NumberedRow(number = if (label.startsWith("Yes", true)) 1 else 2, label = label, pointed = trimmed.firstOrNull() in setOf('❯', '›', '>'))
            }
        }
        return null
    }

    private fun trailingNumberedRows(
        lines: List<String>,
        footer: Int,
        parser: (String) -> NumberedRow?,
    ): List<NumberedRow> {
        val all = mutableListOf<NumberedRow>()
        for (i in maxOf(0, footer - OPTION_WINDOW) until footer) parser(lines[i])?.let { all += it.copy(line = i) }
        if (all.isEmpty()) return emptyList()
        var start = all.lastIndex
        while (start > 0 && all[start - 1].number == all[start].number - 1) start--
        val tail = all.subList(start, all.size)
        return tail.takeIf { it.first().number == 1 } ?: emptyList()
    }

    private fun numberedRowsImmediatelyAbove(
        lines: List<String>,
        footer: Int,
        parser: (String) -> NumberedRow?,
    ): List<NumberedRow> {
        var at = previousNonBlank(lines, footer - 1)
        val reversed = mutableListOf<NumberedRow>()
        while (at >= 0) {
            val parsed = parser(lines[at]) ?: break
            reversed += parsed.copy(line = at)
            at--
        }
        return reversed.asReversed().takeIf { rows -> rows.map { it.number } == (1..rows.size).toList() } ?: emptyList()
    }

    private fun grokRows(lines: List<String>, footer: Int): List<NumberedRow> {
        val regex = Regex("^\\s*┃\\s+([1-9])\\s+\\(([●○])\\)\\s+(.+?)\\s*$")
        return (maxOf(0, footer - 40) until footer).mapNotNull { i -> regex.matchEntire(lines[i])?.let {
            NumberedRow(i, it.groupValues[1].toInt(), it.groupValues[3].trim().removeSuffix(" █"), it.groupValues[2] == "●")
        } }
    }

    private fun grokCardStart(lines: List<String>, firstOption: Int): Int? {
        var start = firstOption
        while (start > maxOf(0, firstOption - 20) && lines[start - 1].trimStart().startsWith("┃")) start--
        return start.takeIf { it < firstOption }
    }

    private fun grokQuestion(lines: List<String>, start: Int, firstOption: Int): String? =
        (start until firstOption).map { lines[it].replace(Regex("^\\s*┃\\s*"), "").trim() }.lastOrNull(String::isNotBlank)

    private fun checkbox(label: String): Pair<Boolean, String>? =
        Regex("^\\[([ xX✔✓])\\]\\s*(.+)$").matchEntire(label)?.let {
            (it.groupValues[1].trim().isNotEmpty()) to it.groupValues[2].trim()
        }

    private fun findQuestion(lines: List<String>, firstOption: Int): Int? {
        val floor = maxOf(0, firstOption - 12)
        for (i in firstOption - 1 downTo floor) {
            val trimmed = lines[i].trim()
            if (trimmed.contains('?') || trimmed.startsWith("Do you", true) ||
                trimmed.startsWith("Question ", true) || trimmed.startsWith("Requesting permission", true)) return i
        }
        // An AskUserQuestion need not be phrased as a question ("Pick a colour:"). Claude prints
        // its "☐ Header" tab above the text either way, so the first non-blank line under that
        // header is the question (S25 Ultra, 2026-09-10; the web grammar accepts the same shape).
        for (i in firstOption - 1 downTo floor) {
            if (Regex("^\\s*☐\\s+\\S").containsMatchIn(lines[i])) {
                return (i + 1 until firstOption).firstOrNull { lines[it].isNotBlank() }
            }
        }
        return null
    }

    private fun findStepper(lines: List<String>, before: Int, requireMultiQuestion: Boolean): Int? {
        for (i in before - 1 downTo maxOf(0, before - 48)) {
            if (isRule(lines[i])) return null
            val glyphs = stepGlyph.findAll(lines[i]).count()
            val match = if (requireMultiQuestion) glyphs >= 3 else glyphs == 2 &&
                Regex("✔\\s*Submit|✅\\s*Submit", RegexOption.IGNORE_CASE).containsMatchIn(lines[i])
            if (match && lines[i].contains('←') && lines[i].contains('→')) return i
        }
        return null
    }

    private fun findCheckboxStepper(lines: List<String>, before: Int): Int? {
        for (i in before - 1 downTo maxOf(0, before - 10)) {
            if (isRule(lines[i])) return null
            val glyphs = stepGlyph.findAll(lines[i]).count()
            if (glyphs >= 2 && Regex("✔\\s*Submit|✅\\s*Submit", RegexOption.IGNORE_CASE).containsMatchIn(lines[i]) &&
                lines[i].contains('←') && lines[i].contains('→')) return i
        }
        return null
    }

    private fun claudeFamily(text: String): String? = when {
        Regex("\\benter to confirm\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "trust"
        Regex("\\btab to amend\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "permission"
        Regex("\\benter to select\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "select"
        text.contains("ctrl+r review", true) && !text.contains("tab to amend", true) -> "plan"
        text.contains("ctrl+g to edit", true) || text.contains(".claude/plans/", true) -> "plan"
        else -> null
    }

    private fun agyFamily(text: String): String? = when {
        Regex("\\b(?:enter confirm|enter to confirm)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "trust"
        Regex("\\b(?:tab amend|tab to amend)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "permission"
        text.contains("ctrl+r review", true) && !text.contains("tab amend", true) -> "plan"
        text.contains("ctrl+g to edit", true) || text.contains(".antigravity/plans/", true) || text.contains(".agy/plans/", true) -> "plan"
        Regex("\\b(?:enter select|enter to select)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "select"
        else -> null
    }

    private fun parseToFooter(text: String): List<SemanticAction> {
        val key = Regex("^(Enter|Esc|Escape|Tab|shift\\+tab|[a-z]|(?:Up|Down|Left|Right)|ctrl\\+[a-z]) to (.+)$", RegexOption.IGNORE_CASE)
        return text.split(Regex("\\s+·\\s+")).mapNotNull { part -> key.matchEntire(part.trim())?.let {
            val normalized = normalizeNamedKey(it.groupValues[1]) ?: return@let null
            action("key-${normalized.lowercase()}", it.groupValues[2].replaceFirstChar { char -> char.uppercase() }, listOf(normalized))
        } }
    }

    private fun parseColonFooter(text: String): List<SemanticAction> = text.split(Regex("\\s+│\\s+")).mapNotNull { part ->
        val match = Regex("^(.+?):(.+)$").matchEntire(part.trim()) ?: return@mapNotNull null
        val key = normalizeNamedKey(match.groupValues[1]) ?: return@mapNotNull null
        val verb = match.groupValues[2].trim()
        if (verb.contains("always-approve", true)) return@mapNotNull null
        action("key-${key.lowercase()}", verb.replaceFirstChar { it.uppercase() }, listOf(key))
    }

    private fun normalizeNamedKey(value: String): String? = when (value.trim().lowercase()) {
        "enter" -> "Enter"
        "esc", "escape" -> "Escape"
        "tab" -> "Tab"
        "shift+tab" -> "shift+Tab"
        "up", "↑" -> "Up"
        "down", "↓" -> "Down"
        "left", "←" -> "Left"
        "right", "→" -> "Right"
        else -> value.trim().lowercase().takeIf { Regex("[a-z]|ctrl\\+[a-z]").matches(it) }
    }

    private fun isRule(text: String): Boolean {
        val trimmed = text.trim()
        if (rule.matches(trimmed) || Regex("^[▔▁]{8,}$").matches(trimmed)) return true
        return Regex("^[─━═-]{2,}\\s+\\S(?:.*\\S)?\\s+[─━═-]{2,}$").matches(trimmed)
    }
    private fun lastNonBlank(lines: List<String>): Int = lines.indexOfLast(String::isNotBlank)
    private fun previousNonBlank(lines: List<String>, start: Int): Int {
        for (i in minOf(start, lines.lastIndex) downTo 0) if (lines[i].isNotBlank()) return i
        return -1
    }
}

object SemanticActionGuard {
    fun verify(
        agent: String?,
        displayed: SemanticSurface,
        action: SemanticAction,
        freshText: String,
        freshRevision: Long,
    ): SemanticAction? {
        if (freshRevision != displayed.revision || displayed.interactionLocked || action !in displayed.actions) return null
        val fresh = AgentSemanticParser.detect(agent, freshText, freshRevision) ?: return null
        if (fresh.kind != displayed.kind || fresh.regionSignature != displayed.regionSignature || fresh.interactionLocked) return null
        return fresh.actions.singleOrNull { it.id == action.id && it.keys == action.keys }
    }
}
