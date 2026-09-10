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
            is SemanticIntent.PromptFeedback -> promptFeedback(agent, displayed, intent, entry)
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
