package com.lateapex.collie.ui.terminal

/** Typed, phone-oriented projection of a detected terminal dialog. */
sealed interface NativeSemanticModel {
    val title: String

    data class Prompt(
        override val title: String,
        val family: String,
        val options: List<Option>,
        val feedback: Feedback? = null,
    ) : NativeSemanticModel

    data class Wizard(
        override val title: String,
        val steps: List<Step>,
        val options: List<Option> = emptyList(),
        val answers: List<Answer> = emptyList(),
        val review: Boolean = false,
        val incomplete: Boolean = false,
    ) : NativeSemanticModel

    data class Preview(
        override val title: String,
        val steps: List<Step>,
        val options: List<Option>,
        val preview: List<String>,
        val note: Note,
    ) : NativeSemanticModel

    data class Multi(
        override val title: String,
        val steps: List<Step>,
        val options: List<Option> = emptyList(),
        val advanceLabel: String? = null,
        val escape: Option? = null,
        val pointer: MultiPointer? = null,
        val review: Boolean = false,
        val incomplete: Boolean = false,
    ) : NativeSemanticModel

    data class Menu(
        override val title: String,
        val rawLines: List<String>,
        val actions: List<SemanticAction>,
        val upDown: Boolean,
        val leftRight: Boolean,
        val currentValue: String? = null,
    ) : NativeSemanticModel

    data class Autocomplete(
        override val title: String,
        val entries: List<Entry>,
    ) : NativeSemanticModel
}

data class Option(
    val number: Int?,
    val label: String,
    val description: String? = null,
    val actionId: String? = null,
    val pointed: Boolean = false,
    val chosen: Boolean = false,
    val checked: Boolean? = null,
)

data class Step(val label: String, val answered: Boolean, val current: Boolean)
data class Answer(val question: String, val answer: String)
data class Entry(val name: String, val description: String)
data class Feedback(val key: String, val focused: Boolean, val text: String, val offered: Boolean)
data class Note(val state: NoteState, val text: String)
enum class NoteState { NONE, EDITING, ATTACHED }
enum class MultiPointer { OPTION, ADVANCE, CHAT, OTHER }

sealed interface SemanticIntent {
    data class Static(val action: SemanticAction) : SemanticIntent
    data class PromptFeedback(val key: String, val text: String) : SemanticIntent
    data class PreviewOption(val number: Int) : SemanticIntent
    data class PreviewNote(val text: String, val replacing: Boolean) : SemanticIntent
    data object MultiAdvance : SemanticIntent
}

/**
 * The safety parser remains the authority over whether a region is interactive. This projection
 * only enriches that already-recognised, bounded literal region for native presentation.
 */
object NativeSemanticDeriver {
    private val numbered = Regex("^(?:[❯›>]\\s*)?(\\d+)\\.\\s*(.+)$")
    private val grokNumbered = Regex("^┃\\s*(\\d+)\\s+\\(([○●])\\)\\s*(.+)$")
    private val checkbox = Regex("^\\[([ xX✔✓])\\]\\s*(.+)$")

    fun derive(surface: SemanticSurface): NativeSemanticModel {
        val lines = surface.regionSignature.split('\n')
        return when (surface.kind) {
            SemanticKind.PROMPT_SELECT -> prompt(surface, lines)
            SemanticKind.WIZARD -> wizard(surface, lines)
            SemanticKind.PREVIEW_SELECT -> preview(surface, lines)
            SemanticKind.MULTI_SELECT -> multi(surface, lines)
            SemanticKind.MENU -> menu(surface, lines)
            SemanticKind.AUTOCOMPLETE -> autocomplete(surface)
        }
    }

    fun identity(model: NativeSemanticModel): String = when (model) {
        is NativeSemanticModel.Prompt -> listOf(
            model.title, model.family, model.options.joinToString("|") { it.label },
            model.feedback?.key.orEmpty(),
        ).joinToString("\n")
        is NativeSemanticModel.Wizard -> listOf(
            model.title, model.review.toString(), stepsIdentity(model.steps),
            model.options.joinToString("|") { "${it.label}:${it.chosen}" },
            model.answers.joinToString("|") { "${it.question}:${it.answer}" }, model.incomplete.toString(),
        ).joinToString("\n")
        is NativeSemanticModel.Preview -> listOf(
            model.title, stepsIdentity(model.steps), model.options.joinToString("|") { "${it.number}:${it.label}:${it.chosen}" },
        ).joinToString("\n")
        is NativeSemanticModel.Multi -> listOf(
            model.title, model.review.toString(), stepsIdentity(model.steps), model.advanceLabel.orEmpty(),
            model.options.joinToString("|") { "${it.number}:${it.label}:${it.checked}" }, model.escape?.label.orEmpty(),
        ).joinToString("\n")
        is NativeSemanticModel.Menu -> listOf(
            model.title, model.actions.joinToString("|") { "${it.id}:${it.keys}" },
            model.upDown.toString(), model.leftRight.toString(),
        ).joinToString("\n")
        is NativeSemanticModel.Autocomplete -> model.entries.joinToString("|") { "${it.name}:${it.description}" }
    }

    private fun prompt(surface: SemanticSurface, lines: List<String>): NativeSemanticModel.Prompt {
        val family = when {
            surface.detail.equals("trust", true) || surface.title.contains("do you trust", true) -> "Trust"
            surface.detail.equals("permission", true) || surface.title.contains("run the following command", true) ||
                surface.actions.any { it.label.contains("always-approve", true) || it.label.contains("don't ask again", true) } -> "Permission"
            surface.detail.equals("plan", true) -> "Plan approval"
            surface.detail.equals("select", true) -> "Choose an option"
            lines.lastOrNull()?.contains("confirm", true) == true -> "Trust"
            lines.lastOrNull()?.contains("amend", true) == true -> "Permission"
            lines.lastOrNull()?.contains("ctrl+r review", true) == true || lines.any { it.contains("shift+tab to approve", true) } -> "Plan approval"
            else -> surface.detail ?: "Choose an option"
        }
        val rows = rows(lines)
        val options = surface.actions.map { action ->
            val number = action.id.substringAfterLast('-').toIntOrNull() ?: action.keys.lastOrNull()?.toIntOrNull()
            val row = rows.firstOrNull { it.number == number }
            val inlineDescription = row?.label?.removePrefix(action.label)?.trim()?.takeIf(String::isNotBlank)
            Option(number, action.label, inlineDescription ?: description(lines, row), action.id,
                row?.pointed == true, row?.chosen == true)
        }
        val feedbackRow = rows.mapIndexedNotNull { index, row ->
            val nextRow = rows.getOrNull(index + 1)?.line ?: lines.size
            row.takeIf {
                (row.line + 1 until nextRow).any { lines[it].contains("shift+tab to approve", true) }
            }
        }.singleOrNull()
        val feedback = feedbackRow?.let {
            val placeholder = it.label.startsWith("Tell Claude what to change", true)
            val continuation = description(lines, it).orEmpty()
            val text = listOf(it.label, continuation).filter(String::isNotBlank).joinToString(" ")
            Feedback(it.number.toString(), it.pointed, if (placeholder) "" else text, placeholder)
        }
        return NativeSemanticModel.Prompt(surface.title, family, options, feedback)
    }

    private fun wizard(surface: SemanticSurface, lines: List<String>): NativeSemanticModel.Wizard {
        val steps = steps(lines)
        val review = surface.actions.any { it.id == "submit" }
        if (review) {
            val answers = answers(lines)
            val incomplete = steps.any { !it.answered }
            return NativeSemanticModel.Wizard(surface.title, steps, answers = answers, review = true, incomplete = incomplete)
        }
        val rows = rows(lines)
        val options = surface.actions.filter { it.id.startsWith("option-") }.map { action ->
            val n = action.id.substringAfterLast('-').toIntOrNull()
            val row = rows.firstOrNull { it.number == n }
            Option(n, action.label, description(lines, row), action.id, row?.pointed == true, row?.chosen == true)
        }
        return NativeSemanticModel.Wizard(surface.title, steps, options)
    }

    private fun preview(surface: SemanticSurface, lines: List<String>): NativeSemanticModel.Preview {
        val notesAt = lines.indexOfLast { it.contains("Notes:") }
        val noteColumn = lines.getOrNull(notesAt)?.indexOf("Notes:")?.takeIf { it > 0 } ?: 0
        val parsedRows = rows(lines.map { if (noteColumn > 0) it.take(noteColumn) else it })
        val options = parsedRows.map { row ->
            Option(row.number, row.label.removeSuffix(" ✔"), actionId = "preview-${row.number}",
                pointed = row.pointed, chosen = row.chosen)
        }
        val preview = if (notesAt > 0 && noteColumn > 0) {
            lines.subList(parsedRows.firstOrNull()?.line ?: 0, notesAt)
                .map { it.drop(noteColumn).trimEnd() }.filter { it.isNotBlank() }
        } else emptyList()
        val noteText = lines.getOrNull(notesAt)?.substringAfter("Notes:")?.trim().orEmpty()
        val editing = lines.lastOrNull()?.contains("ctrl+g to edit", true) == true
        val note = when {
            editing -> Note(NoteState.EDITING, noteText.takeUnless { it.startsWith("press n", true) }.orEmpty())
            noteText.isNotBlank() && !noteText.startsWith("press n", true) -> Note(NoteState.ATTACHED, noteText)
            else -> Note(NoteState.NONE, "")
        }
        return NativeSemanticModel.Preview(surface.title, steps(lines), options, preview, note)
    }

    private fun multi(surface: SemanticSurface, lines: List<String>): NativeSemanticModel.Multi {
        val review = surface.actions.any { it.id == "submit" || it.id == "cancel" }
        if (review) {
            val parsedSteps = steps(lines)
            return NativeSemanticModel.Multi(surface.title, parsedSteps.takeIf { it.size > 1 }.orEmpty(), review = true,
                incomplete = parsedSteps.any { !it.answered })
        }
        val parsedRows = rows(lines)
        val toggleIds = surface.actions.map(SemanticAction::id).filter { it.startsWith("toggle-") }.toSet()
        val options = parsedRows.mapNotNull { row -> checkbox.matchEntire(row.label)?.takeIf {
            "toggle-${row.number}" in toggleIds
        }?.let { match ->
            Option(row.number, match.groupValues[2], description(lines, row), "toggle-${row.number}",
                row.pointed, checked = match.groupValues[1].trim().isNotEmpty())
        } }
        val escapeRow = parsedRows.firstOrNull { it.label.startsWith("Chat about this", true) }
        val advanceLine = lines.firstOrNull { it.trim().removePrefix("❯").trim() in setOf("Submit", "Next") }
        val pointer = when {
            advanceLine?.trimStart()?.startsWith("❯") == true -> MultiPointer.ADVANCE
            escapeRow?.pointed == true -> MultiPointer.CHAT
            parsedRows.any { it.pointed && checkbox.matches(it.label) } -> MultiPointer.OPTION
            else -> MultiPointer.OTHER
        }
        return NativeSemanticModel.Multi(
            surface.title, steps(lines).takeIf { it.size > 1 }.orEmpty(), options,
            advanceLine?.trim()?.removePrefix("❯")?.trim(),
            escapeRow?.let { Option(it.number, it.label, actionId = "escape", pointed = it.pointed) }, pointer,
        )
    }

    private fun menu(surface: SemanticSurface, lines: List<String>): NativeSemanticModel.Menu {
        val upDown = surface.actions.any { it.id == "up" || it.id == "down" }
        val leftRight = surface.actions.any { it.id == "left" || it.id == "right" }
        val current = lines.firstOrNull { it.contains("←/→") }?.substringBefore("←/→")?.trim()
        return NativeSemanticModel.Menu(surface.title, lines, surface.actions, upDown, leftRight, current)
    }

    private fun autocomplete(surface: SemanticSurface): NativeSemanticModel.Autocomplete =
        NativeSemanticModel.Autocomplete(surface.title, surface.items.map {
            Entry(it.substringBefore(" — "), it.substringAfter(" — ", ""))
        })

    private data class Row(
        val line: Int,
        val number: Int,
        val label: String,
        val pointed: Boolean,
        val chosen: Boolean,
    )

    private fun rows(lines: List<String>): List<Row> {
        val all = lines.mapIndexedNotNull { index, source ->
            val trimmed = source.trim()
            numbered.matchEntire(trimmed)?.let {
                return@mapIndexedNotNull Row(index, it.groupValues[1].toInt(), it.groupValues[2].trim(),
                    trimmed.startsWith("❯") || trimmed.startsWith("›"), trimmed.endsWith("✔"))
            }
            grokNumbered.matchEntire(trimmed)?.let {
                Row(index, it.groupValues[1].toInt(), it.groupValues[3].trim(),
                    it.groupValues[2] == "●", false)
            }
        }
        if (all.isEmpty()) return emptyList()
        var start = all.lastIndex
        while (start > 0 && all[start - 1].number == all[start].number - 1) start--
        return all.subList(start, all.size).takeIf { it.first().number == 1 } ?: emptyList()
    }

    private fun description(lines: List<String>, row: Row?): String? {
        row ?: return null
        val end = (row.line + 1 until lines.size).firstOrNull { numbered.matches(lines[it].trim()) } ?: lines.size
        return lines.subList(row.line + 1, end).asSequence().map(String::trim)
            .takeWhile { it.isNotEmpty() && !it.contains("shift+tab to approve", true) }
            .filterNot { it.startsWith("─") || it in setOf("Submit", "Next") }
            .joinToString(" ").takeIf(String::isNotBlank)
    }

    private fun steps(lines: List<String>): List<Step> {
        val source = lines.firstOrNull { it.contains('←') && it.contains('→') && it.contains("Submit") } ?: return emptyList()
        val matches = Regex("([☐☒☑])\\s+(.+?)(?=\\s{2,}[☐☒☑✔✅]\\s+|\\s{2,}✔\\s+Submit|$)").findAll(source)
        return matches.map {
            Step(it.groupValues[2].trim(), it.groupValues[1] != "☐", false)
        }.toList()
    }

    private fun answers(lines: List<String>): List<Answer> {
        val answers = mutableListOf<Answer>()
        lines.forEachIndexed { index, line ->
            val question = line.trim().removePrefix("●").trim().takeIf { line.trim().startsWith("●") } ?: return@forEachIndexed
            val answer = lines.getOrNull(index + 1)?.trim()?.removePrefix("→")?.trim().orEmpty()
            if (question.isNotBlank() && answer.isNotBlank()) answers += Answer(question, answer)
        }
        return answers
    }

    private fun stepsIdentity(steps: List<Step>) = steps.joinToString("|") { "${it.label}:${it.answered}" }
}
