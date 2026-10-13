package com.lateapex.collie.ui.terminal

import kotlinx.coroutines.delay

data class SemanticPane(val text: String, val revision: Long)

enum class SemanticWriteOutcome { OK, CHANGED, FAILED }
enum class SemanticInteractionResult { SENT, CHANGED, FAILED }

interface SemanticInteractionTransport {
    suspend fun read(): SemanticPane?
    suspend fun keys(keys: List<String>, expectedPrompt: String?): SemanticWriteOutcome
    suspend fun reply(text: String, submit: Boolean, expectedPrompt: String?): SemanticWriteOutcome
}

/** Guarded multi-step recipes measured by the web client against the same terminal dialogs. */
class SemanticInteractionEngine(
    private val transport: SemanticInteractionTransport,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun execute(agent: String?, displayed: SemanticSurface, intent: SemanticIntent): SemanticInteractionResult {
        val entry = strictFresh(agent, displayed) ?: return SemanticInteractionResult.CHANGED
        return when (intent) {
            is SemanticIntent.Static -> static(displayed, intent.action, entry)
            is SemanticIntent.PointerSelect -> pointerSelect(agent, displayed, intent.action, entry)
            is SemanticIntent.PromptFeedback -> promptFeedback(agent, displayed, intent, entry)
            is SemanticIntent.MultiToggle -> multiToggle(agent, displayed, intent.action, entry)
            is SemanticIntent.OmpMultiToggle -> ompMultiToggle(agent, displayed, intent.action, entry)
            is SemanticIntent.OmpNoteSubmit -> ompNoteSubmit(agent, displayed, intent.text, entry)
            is SemanticIntent.MultiReviewSelect -> multiReviewSelect(agent, displayed, intent.action, entry)
            is SemanticIntent.PreviewOption -> previewOption(agent, displayed, intent.number, entry)
            is SemanticIntent.PreviewNote -> previewNote(agent, displayed, intent, entry)
            SemanticIntent.MultiAdvance -> multiAdvance(agent, displayed, entry)
        }
    }

    private suspend fun static(
        displayed: SemanticSurface,
        action: SemanticAction,
        fresh: SemanticSurface,
    ): SemanticInteractionResult {
        if (action !in displayed.actions) return SemanticInteractionResult.CHANGED
        val verified = fresh.actions.singleOrNull { it.id == action.id && it.keys == action.keys }
            ?: return SemanticInteractionResult.CHANGED
        return transport.keys(verified.keys, fresh.regionSignature).asResult()
    }

    private suspend fun pointerSelect(
        agent: String?,
        displayed: SemanticSurface,
        action: SemanticAction,
        fresh: SemanticSurface,
    ): SemanticInteractionResult {
        if (action !in displayed.actions) return SemanticInteractionResult.CHANGED
        val prompt = fresh.nativeModel as? NativeSemanticModel.Prompt ?: return SemanticInteractionResult.CHANGED
        val verified = fresh.actions.singleOrNull { it.id == action.id && it.keys == action.keys }
            ?: return SemanticInteractionResult.CHANGED
        if (verified.keys.lastOrNull() != "Enter" || verified.keys.dropLast(1).any { it !in setOf("Up", "Down") }) {
            return SemanticInteractionResult.CHANGED
        }
        val target = prompt.options.indexOfFirst { it.actionId == verified.id }
        if (target < 0) return SemanticInteractionResult.CHANGED
        val expectedArrows = verified.keys.dropLast(1)
        if (fresh.detail == "omp-switch") {
            // omp's switch list has rows that cannot be offered (current, over-context or visually
            // shortened ids). They still occupy pointer positions, so a filtered phone option index
            // cannot reproduce the captured walk. The exact parser binds all those rows in items.
            val modelRowCount = fresh.items.count { it.startsWith("row:") }
            if (modelRowCount !in 2..60 || expectedArrows.size >= modelRowCount) {
                return SemanticInteractionResult.CHANGED
            }
        } else {
            val pointed = prompt.options.indices.filter { prompt.options[it].pointed }
            if (pointed.size != 1) return SemanticInteractionResult.CHANGED
            val delta = target - pointed.single()
            val measuredArrows = List(kotlin.math.abs(delta)) { if (delta > 0) "Down" else "Up" }
            if (expectedArrows != measuredArrows || expectedArrows.size > prompt.options.size) {
                return SemanticInteractionResult.CHANGED
            }
        }
        if (expectedArrows.isNotEmpty()) {
            transport.keys(expectedArrows, fresh.regionSignature).takeUnless { it == SemanticWriteOutcome.OK }
                ?.let { return it.asResult() }
        }
        val selected = if (expectedArrows.isEmpty()) fresh else poll(agent, prompt) { model ->
            (model as? NativeSemanticModel.Prompt)?.options?.singleOrNull { it.actionId == verified.id }?.pointed == true
        } ?: return SemanticInteractionResult.CHANGED
        val selectedModel = selected.nativeModel as? NativeSemanticModel.Prompt ?: return SemanticInteractionResult.CHANGED
        if (selectedModel.options.singleOrNull { it.actionId == verified.id }?.pointed != true) {
            return SemanticInteractionResult.CHANGED
        }
        // Once read-back proves the row is pointed, the parser's fresh recipe for that row is
        // Enter only. Its old recipe necessarily contained the arrows that moved the pointer.
        if (selected.actions.none { it.id == verified.id && it.keys == listOf("Enter") }) {
            return SemanticInteractionResult.CHANGED
        }
        return transport.keys(listOf("Enter"), selected.regionSignature).asResult()
    }

    private suspend fun promptFeedback(
        agent: String?,
        displayed: SemanticSurface,
        intent: SemanticIntent.PromptFeedback,
        fresh: SemanticSurface,
    ): SemanticInteractionResult {
        val original = displayed.nativeModel as? NativeSemanticModel.Prompt ?: return SemanticInteractionResult.CHANGED
        val feedback = original.feedback ?: return SemanticInteractionResult.CHANGED
        val text = intent.text.trim().take(MAX_FEEDBACK_LENGTH)
        if (!feedback.offered || feedback.focused || text.isBlank() || feedback.key != intent.key) {
            return SemanticInteractionResult.CHANGED
        }
        if (original.family == "omp-select") {
            if (text.any { it == '\n' || it == '\r' }) return SemanticInteractionResult.CHANGED
            transport.keys(listOf("n"), fresh.regionSignature).takeUnless { it == SemanticWriteOutcome.OK }
                ?.let { return it.asResult() }
            val editor = pollOmpNote(original, requireText = null) ?: return SemanticInteractionResult.CHANGED
            val editorModel = editor.nativeModel as? NativeSemanticModel.Prompt ?: return SemanticInteractionResult.CHANGED
            if (editorModel.feedback?.text.orEmpty().isNotEmpty()) return SemanticInteractionResult.CHANGED
            transport.reply(text, submit = false, expectedPrompt = editor.regionSignature)
                .takeUnless { it == SemanticWriteOutcome.OK }?.let { return it.asResult() }
            val typed = pollOmpNote(original, requireText = text) ?: return SemanticInteractionResult.CHANGED
            val current = typed.nativeModel as? NativeSemanticModel.Prompt ?: return SemanticInteractionResult.CHANGED
            if (current.feedback?.text != text) return SemanticInteractionResult.CHANGED
            // The captured editor explicitly advertises Enter (as well as Ctrl+Q) for submit.
            return transport.keys(listOf("Enter"), typed.regionSignature).asResult()
        }
        transport.keys(listOf(intent.key), fresh.regionSignature).takeUnless { it == SemanticWriteOutcome.OK }
            ?.let { return it.asResult() }
        // Focus is not sufficient: another terminal user may have typed into the field during the
        // focus round-trip. Claude restores this input at position zero, so appending our reply and
        // submitting would combine two operators' text. Match the web guard and type only after a
        // fresh render proves that the focused field is still empty.
        val focused = poll(agent, original) {
            (it as? NativeSemanticModel.Prompt)?.feedback?.let { row -> row.focused && row.text.isEmpty() } == true
        }
            ?: return SemanticInteractionResult.CHANGED
        transport.reply(text, submit = false, expectedPrompt = focused.regionSignature)
            .takeUnless { it == SemanticWriteOutcome.OK }?.let { return it.asResult() }
        val typed = poll(agent, original) {
            val value = (it as? NativeSemanticModel.Prompt)?.feedback
            // Unlike preview notes, this row reflows without windowing: require the complete value
            // before the irreversible Enter rather than accepting a visible suffix.
            value?.focused == true && value.text == text
        } ?: return SemanticInteractionResult.CHANGED
        return transport.keys(listOf("Enter"), typed.regionSignature).asResult()
    }

    private suspend fun pollOmpNote(original: NativeSemanticModel.Prompt, requireText: String?): SemanticSurface? {
        repeat(POLL_ATTEMPTS) {
            sleep(POLL_DELAY_MS)
            val pane = transport.read() ?: return@repeat
            val observed = AgentSemanticParser.detect("omp", pane.text, pane.revision) ?: return@repeat
            if (observed.detail != "omp-note-editor") return@repeat
            val model = observed.nativeModel as? NativeSemanticModel.Prompt ?: return null
            if (model.identityDetails != original.identityDetails || model.feedback?.focused != true) return null
            val value = model.feedback.text
            if ((requireText == null && value.isEmpty()) || (requireText != null && value == requireText)) return observed
            if (requireText != null && value.isNotEmpty() && !requireText.endsWith(value)) return null
        }
        return null
    }

    private suspend fun ompNoteSubmit(
        agent: String?, displayed: SemanticSurface, requestedText: String, fresh: SemanticSurface,
    ): SemanticInteractionResult {
        val original = displayed.nativeModel as? NativeSemanticModel.Prompt ?: return SemanticInteractionResult.CHANGED
        val feedback = original.feedback ?: return SemanticInteractionResult.CHANGED
        val text = requestedText.trim().take(MAX_FEEDBACK_LENGTH)
        if (original.family != "omp-note-editor" || !feedback.focused || feedback.text.isNotEmpty() ||
            text.isBlank() || text.any { it == '\n' || it == '\r' }
        ) return SemanticInteractionResult.CHANGED
        transport.reply(text, submit = false, expectedPrompt = fresh.regionSignature)
            .takeUnless { it == SemanticWriteOutcome.OK }?.let { return it.asResult() }
        val typed = pollOmpNote(original, requireText = text) ?: return SemanticInteractionResult.CHANGED
        val current = typed.nativeModel as? NativeSemanticModel.Prompt ?: return SemanticInteractionResult.CHANGED
        if (current.feedback?.text != text) return SemanticInteractionResult.CHANGED
        return transport.keys(listOf("Enter"), typed.regionSignature).asResult()
    }

    /** Muse digits move the checkbox pointer; require a same-question read-back before Enter. */
    private suspend fun multiToggle(
        agent: String?,
        displayed: SemanticSurface,
        action: SemanticAction,
        fresh: SemanticSurface,
    ): SemanticInteractionResult {
        if (displayed.detail != "muse-pointer-checkbox" || action !in displayed.actions) {
            return SemanticInteractionResult.CHANGED
        }
        val original = displayed.nativeModel as? NativeSemanticModel.Multi ?: return SemanticInteractionResult.CHANGED
        val current = fresh.nativeModel as? NativeSemanticModel.Multi ?: return SemanticInteractionResult.CHANGED
        val number = action.id.removePrefix("toggle-").toIntOrNull() ?: return SemanticInteractionResult.CHANGED
        if (current.review || current.pointerOptionNumber == null || current.options.none { it.number == number }) {
            return SemanticInteractionResult.CHANGED
        }
        val checkedBefore = current.options.singleOrNull { it.number == number }?.checked ?: return SemanticInteractionResult.CHANGED
        val verifiedAction = fresh.actions.singleOrNull { it.id == action.id && it.keys == action.keys }
            ?: return SemanticInteractionResult.CHANGED
        if (verifiedAction.keys != listOf(number.toString(), "Enter")) return SemanticInteractionResult.CHANGED
        if (current.pointerOptionNumber != number) {
            transport.keys(listOf(number.toString()), fresh.regionSignature)
                .takeUnless { it == SemanticWriteOutcome.OK }?.let { return it.asResult() }
        }
        val pointed = if (current.pointerOptionNumber == number) fresh else
            poll(agent, original) { model ->
                (model as? NativeSemanticModel.Multi)?.let {
                    it.pointerOptionNumber == number && it.options.singleOrNull { option -> option.number == number }?.checked == checkedBefore
                } == true
            } ?: return SemanticInteractionResult.CHANGED
        val pointedModel = pointed.nativeModel as? NativeSemanticModel.Multi ?: return SemanticInteractionResult.CHANGED
        if (pointedModel.pointerOptionNumber != number ||
            pointedModel.options.singleOrNull { it.number == number }?.checked != checkedBefore ||
            pointed.actions.none { it.id == action.id && it.keys == listOf("Enter") }
        ) return SemanticInteractionResult.CHANGED
        transport.keys(listOf("Enter"), pointed.regionSignature)
            .takeUnless { it == SemanticWriteOutcome.OK }?.let { return it.asResult() }

        // The toggle is reversible but an uncertain response must not become an automatic second
        // toggle. Verify that this exact option changed before reporting success.
        repeat(POLL_ATTEMPTS) {
            sleep(POLL_DELAY_MS)
            val pane = transport.read() ?: return@repeat
            val observed = AgentSemanticParser.detect(agent, pane.text, pane.revision) ?: return@repeat
            val model = observed.nativeModel as? NativeSemanticModel.Multi ?: return SemanticInteractionResult.CHANGED
            if (!sameMultiStructure(original, model, number)) return SemanticInteractionResult.CHANGED
            if (model.pointerOptionNumber != number) return SemanticInteractionResult.CHANGED
            val checkedAfter = model.options.singleOrNull { it.number == number }?.checked
                ?: return SemanticInteractionResult.CHANGED
            if (checkedAfter != checkedBefore) return SemanticInteractionResult.SENT
        }
        return SemanticInteractionResult.CHANGED
    }

    private fun sameMultiStructure(a: NativeSemanticModel.Multi, b: NativeSemanticModel.Multi, toggledNumber: Int): Boolean =
        a.title == b.title && a.options.map { it.number to it.label } == b.options.map { it.number to it.label } &&
            Regex("^Submit answer \\((\\d+) checked\\)$").matches(a.advanceLabel.orEmpty()) &&
            Regex("^Submit answer \\((\\d+) checked\\)$").matches(b.advanceLabel.orEmpty()) &&
            a.options.filter { it.number != toggledNumber }.map { it.number to it.checked } ==
                b.options.filter { it.number != toggledNumber }.map { it.number to it.checked }

    /** Move between Muse review rows only with a same-answer pointer read-back before Enter. */
    /** OMP Ask: arrows select a row, Space toggles it. Each step is bound to the exact screen and
     * the toggle is reported only after a fresh frame proves exactly the requested checkbox changed. */
    private suspend fun ompMultiToggle(
        agent: String?, displayed: SemanticSurface, action: SemanticAction, fresh: SemanticSurface,
    ): SemanticInteractionResult {
        if (displayed.detail != "omp-multi" || action !in displayed.actions) return SemanticInteractionResult.CHANGED
        val original = displayed.nativeModel as? NativeSemanticModel.Multi ?: return SemanticInteractionResult.CHANGED
        val current = fresh.nativeModel as? NativeSemanticModel.Multi ?: return SemanticInteractionResult.CHANGED
        val number = action.id.removePrefix("toggle-").toIntOrNull() ?: return SemanticInteractionResult.CHANGED
        if (current.review || current.pointerOptionNumber == null || current.options.size !in 2..9) return SemanticInteractionResult.CHANGED
        val before = current.options.map { it.checked ?: return SemanticInteractionResult.CHANGED }
        val from = current.options.indexOfFirst { it.pointed }
        val target = current.options.indexOfFirst { it.number == number }
        if (from < 0 || target < 0) return SemanticInteractionResult.CHANGED
        val arrows = List(kotlin.math.abs(target - from)) { if (target > from) "Down" else "Up" }
        val verified = fresh.actions.singleOrNull { it.id == action.id } ?: return SemanticInteractionResult.CHANGED
        if (verified.keys != arrows + "Space") return SemanticInteractionResult.CHANGED
        if (arrows.isNotEmpty()) {
            transport.keys(arrows, fresh.regionSignature).takeUnless { it == SemanticWriteOutcome.OK }
                ?.let { return it.asResult() }
        }
        val pointed = if (arrows.isEmpty()) fresh else pollOmpMulti(agent, original, number, before, expectChanged = false)
            ?: return SemanticInteractionResult.CHANGED
        val pointedModel = pointed.nativeModel as? NativeSemanticModel.Multi ?: return SemanticInteractionResult.CHANGED
        if (pointedModel.pointerOptionNumber != number ||
            pointedModel.options.map { it.checked } != before.map { it as Boolean? } ||
            pointed.actions.singleOrNull { it.id == action.id }?.keys != listOf("Space")
        ) return SemanticInteractionResult.CHANGED
        transport.keys(listOf("Space"), pointed.regionSignature).takeUnless { it == SemanticWriteOutcome.OK }
            ?.let { return it.asResult() }
        return if (pollOmpMulti(agent, original, number, before, expectChanged = true) != null) {
            SemanticInteractionResult.SENT
        } else SemanticInteractionResult.CHANGED
    }

    private suspend fun pollOmpMulti(
        agent: String?, original: NativeSemanticModel.Multi, target: Int, before: List<Boolean>, expectChanged: Boolean,
    ): SemanticSurface? {
        repeat(POLL_ATTEMPTS) {
            sleep(POLL_DELAY_MS)
            val pane = transport.read() ?: return@repeat
            val observed = AgentSemanticParser.detect(agent, pane.text, pane.revision) ?: return@repeat
            if (observed.detail != "omp-multi") return null
            val model = observed.nativeModel as? NativeSemanticModel.Multi ?: return null
            if (model.title != original.title || model.options.map { it.number to it.label } != original.options.map { it.number to it.label } ||
                model.options.size != before.size
            ) return null
            val checks = model.options.map { it.checked ?: return null }
            if (checks.indices.any { it != target - 1 && checks[it] != before[it] }) return null
            val changed = checks[target - 1] != before[target - 1]
            if (model.pointerOptionNumber == target && changed == expectChanged) return observed
            if (changed) return null
        }
        return null
    }

    private suspend fun multiReviewSelect(
        agent: String?,
        displayed: SemanticSurface,
        action: SemanticAction,
        fresh: SemanticSurface,
    ): SemanticInteractionResult {
        if (displayed.detail != "muse-checkbox-review" || action !in displayed.actions ||
            action.id !in setOf("review-submit", "review-interrupt")
        ) return SemanticInteractionResult.CHANGED
        val original = displayed.nativeModel as? NativeSemanticModel.Multi ?: return SemanticInteractionResult.CHANGED
        val current = fresh.nativeModel as? NativeSemanticModel.Multi ?: return SemanticInteractionResult.CHANGED
        if (!original.review || !current.review || current.reviewPointerId == null) return SemanticInteractionResult.CHANGED
        val verified = fresh.actions.singleOrNull { it.id == action.id && it.keys == action.keys }
            ?: return SemanticInteractionResult.CHANGED
        if (verified.keys.lastOrNull() != "Enter" || verified.keys.dropLast(1).any { it !in setOf("Up", "Down") } ||
            verified.keys.size > 2
        ) return SemanticInteractionResult.CHANGED

        val pointed = if (current.reviewPointerId == action.id) fresh else {
            val direction = verified.keys.dropLast(1).singleOrNull() ?: return SemanticInteractionResult.CHANGED
            transport.keys(listOf(direction), fresh.regionSignature)
                .takeUnless { it == SemanticWriteOutcome.OK }?.let { return it.asResult() }
            poll(agent, original) { model ->
                (model as? NativeSemanticModel.Multi)?.let { it.review && it.reviewPointerId == action.id } == true
            } ?: return SemanticInteractionResult.CHANGED
        }
        val pointedModel = pointed.nativeModel as? NativeSemanticModel.Multi ?: return SemanticInteractionResult.CHANGED
        if (!pointedModel.review || pointedModel.reviewPointerId != action.id ||
            pointed.actions.none { it.id == action.id && it.keys == listOf("Enter") }
        ) return SemanticInteractionResult.CHANGED
        return transport.keys(listOf("Enter"), pointed.regionSignature).asResult()
    }

    private suspend fun previewOption(
        agent: String?,
        displayed: SemanticSurface,
        number: Int,
        fresh: SemanticSurface,
    ): SemanticInteractionResult {
        val original = displayed.nativeModel as? NativeSemanticModel.Preview ?: return SemanticInteractionResult.CHANGED
        if (original.note.state == NoteState.EDITING || original.options.none { it.number == number }) {
            return SemanticInteractionResult.CHANGED
        }
        transport.keys(listOf(number.toString()), fresh.regionSignature).takeUnless { it == SemanticWriteOutcome.OK }
            ?.let { return it.asResult() }
        val pointed = poll(agent, original) { model ->
            (model as? NativeSemanticModel.Preview)?.options?.any { it.number == number && it.pointed } == true
        } ?: return SemanticInteractionResult.CHANGED
        return transport.keys(listOf("Enter"), pointed.regionSignature).asResult()
    }

    private suspend fun previewNote(
        agent: String?,
        displayed: SemanticSurface,
        intent: SemanticIntent.PreviewNote,
        fresh: SemanticSurface,
    ): SemanticInteractionResult {
        val original = displayed.nativeModel as? NativeSemanticModel.Preview ?: return SemanticInteractionResult.CHANGED
        if (original.note.state == NoteState.EDITING || intent.replacing != (original.note.state == NoteState.ATTACHED)) {
            return SemanticInteractionResult.CHANGED
        }
        val text = intent.text.trim().take(MAX_NOTE_LENGTH)
        transport.keys(listOf("n"), fresh.regionSignature).takeUnless { it == SemanticWriteOutcome.OK }
            ?.let { return it.asResult() }
        var editing = poll(agent, original) { (it as? NativeSemanticModel.Preview)?.note?.state == NoteState.EDITING }
            ?: return SemanticInteractionResult.CHANGED
        if (intent.replacing) {
            val clear = listOf("ctrl+k") + List(MAX_NOTE_LENGTH + 20) { "Backspace" }
            transport.keys(clear, editing.regionSignature).takeUnless { it == SemanticWriteOutcome.OK }
                ?.let { return it.asResult() }
            editing = poll(agent, original) {
                val note = (it as? NativeSemanticModel.Preview)?.note
                note?.state == NoteState.EDITING && note.text.isEmpty()
            } ?: return SemanticInteractionResult.CHANGED
        }
        if (text.isNotEmpty()) {
            transport.reply(text, submit = false, expectedPrompt = editing.regionSignature)
                .takeUnless { it == SemanticWriteOutcome.OK }?.let { return it.asResult() }
            editing = poll(agent, original) {
                val note = (it as? NativeSemanticModel.Preview)?.note
                note?.state == NoteState.EDITING && note.text.isNotBlank() && text.endsWith(note.text)
            } ?: return SemanticInteractionResult.CHANGED
        }
        repeat(2) {
            transport.keys(listOf("Escape"), editing.regionSignature).takeUnless { it == SemanticWriteOutcome.OK }
                ?.let { return it.asResult() }
            val blurred = poll(agent, original, attempts = 4) {
                (it as? NativeSemanticModel.Preview)?.note?.state != NoteState.EDITING
            }
            if (blurred != null) return SemanticInteractionResult.SENT
            val current = transport.read()?.let { AgentSemanticParser.detect(agent, it.text, it.revision) }
            val model = current?.nativeModel
            if (model == null || NativeSemanticDeriver.identity(model) != NativeSemanticDeriver.identity(original) ||
                (model as? NativeSemanticModel.Preview)?.note?.state != NoteState.EDITING
            ) return SemanticInteractionResult.CHANGED
            editing = current
        }
        return SemanticInteractionResult.FAILED
    }

    private suspend fun multiAdvance(
        agent: String?,
        displayed: SemanticSurface,
        fresh: SemanticSurface,
    ): SemanticInteractionResult {
        val original = displayed.nativeModel as? NativeSemanticModel.Multi ?: return SemanticInteractionResult.CHANGED
        if (original.review || original.advanceLabel == null) return SemanticInteractionResult.CHANGED
        if (displayed.detail == "omp-multi") {
            val freshModel = fresh.nativeModel as? NativeSemanticModel.Multi ?: return SemanticInteractionResult.CHANGED
            if (displayed.actions.none { it.id == "submit" && it.keys == listOf("Enter") } ||
                fresh.detail != "omp-multi" || freshModel.options.map { it.number to (it.label to it.checked) } !=
                original.options.map { it.number to (it.label to it.checked) }
            ) return SemanticInteractionResult.CHANGED
            return transport.keys(listOf("Enter"), fresh.regionSignature).asResult()
        }
        var current: SemanticSurface? = fresh
        repeat(original.options.size + 6) {
            if (current == null) {
                sleep(NAV_SETTLE_MS)
                val pane = transport.read() ?: return@repeat
                current = AgentSemanticParser.detect(agent, pane.text, pane.revision)
                return@repeat
            }
            val observed = current ?: return@repeat
            val model = observed.nativeModel as? NativeSemanticModel.Multi ?: return SemanticInteractionResult.CHANGED
            if (!sameIdentity(original, model)) return SemanticInteractionResult.CHANGED
            val keys = if (model.pointer == MultiPointer.ADVANCE) listOf("Enter")
            else listOf(if (model.pointer == MultiPointer.CHAT) "Up" else "Down")
            val outcome = transport.keys(keys, observed.regionSignature)
            if (outcome != SemanticWriteOutcome.OK) return outcome.asResult()
            if (keys.single() == "Enter") return SemanticInteractionResult.SENT
            current = null
            sleep(NAV_SETTLE_MS)
            val pane = transport.read() ?: return@repeat
            current = AgentSemanticParser.detect(agent, pane.text, pane.revision)
        }
        return SemanticInteractionResult.CHANGED
    }

    private suspend fun strictFresh(agent: String?, displayed: SemanticSurface): SemanticSurface? {
        val pane = transport.read() ?: return null
        if (pane.revision != displayed.revision) return null
        val fresh = AgentSemanticParser.detect(agent, pane.text, pane.revision) ?: return null
        if (fresh.kind != displayed.kind || fresh.regionSignature != displayed.regionSignature || fresh.interactionLocked) return null
        return fresh
    }

    private suspend fun poll(
        agent: String?,
        original: NativeSemanticModel,
        attempts: Int = POLL_ATTEMPTS,
        predicate: (NativeSemanticModel) -> Boolean,
    ): SemanticSurface? {
        repeat(attempts) {
            sleep(POLL_DELAY_MS)
            val pane = transport.read() ?: return@repeat
            val fresh = AgentSemanticParser.detect(agent, pane.text, pane.revision) ?: return@repeat
            val model = fresh.nativeModel ?: return@repeat
            if (!sameIdentity(original, model)) return null
            if (predicate(model)) return fresh
        }
        return null
    }

    private fun sameIdentity(a: NativeSemanticModel, b: NativeSemanticModel): Boolean =
        a::class == b::class && NativeSemanticDeriver.identity(a) == NativeSemanticDeriver.identity(b)

    private fun SemanticWriteOutcome.asResult() = when (this) {
        SemanticWriteOutcome.OK -> SemanticInteractionResult.SENT
        SemanticWriteOutcome.CHANGED -> SemanticInteractionResult.CHANGED
        SemanticWriteOutcome.FAILED -> SemanticInteractionResult.FAILED
    }

    private companion object {
        const val MAX_FEEDBACK_LENGTH = 240
        const val MAX_NOTE_LENGTH = 300
        const val POLL_ATTEMPTS = 8
        const val POLL_DELAY_MS = 250L
        const val NAV_SETTLE_MS = 250L
    }
}
