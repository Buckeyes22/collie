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
            // The web OMP adapter intentionally remains Tier 1/raw and claims no dialog grammar.
            "omp" -> null
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
        for (i in firstOption - 1 downTo maxOf(0, firstOption - 12)) {
            val trimmed = lines[i].trim()
            if (trimmed.contains('?') || trimmed.startsWith("Do you", true) ||
                trimmed.startsWith("Question ", true) || trimmed.startsWith("Requesting permission", true)) return i
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
