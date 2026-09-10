package com.lateapex.collie.ui.terminal

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SemanticInteractionEngineTest {
    @Test
    fun previewOptionMovesPointerVerifiesThenEnters() = runTest {
        val initial = previewFixture(pointed = 1)
        val moved = previewFixture(pointed = 2)
        val transport = FakeTransport(listOf(pane(initial, 8), pane(moved, 9)))
        val surface = detect(initial, 8)

        val result = SemanticInteractionEngine(transport) {}.execute(
            "claude", surface, SemanticIntent.PreviewOption(2),
        )

        assertEquals(SemanticInteractionResult.SENT, result)
        assertEquals(listOf(listOf("2"), listOf("Enter")), transport.keys.map { it.first })
        assertEquals(surface.regionSignature, transport.keys.first().second)
        assertEquals(detect(moved, 9).regionSignature, transport.keys.last().second)
    }

    @Test
    fun planFeedbackFocusesTypesWithoutSubmitThenVerifiesAndEnters() = runTest {
        val initial = planFixture("Tell Claude what to change", focused = false)
        val focused = planFixture("Tell Claude what to change", focused = true)
        val typed = planFixture("Use guard clauses", focused = true)
        val transport = FakeTransport(listOf(pane(initial, 4), pane(focused, 5), pane(typed, 6)))
        val surface = detect(initial, 4)

        val result = SemanticInteractionEngine(transport) {}.execute(
            "claude", surface, SemanticIntent.PromptFeedback("3", "Use guard clauses"),
        )

        assertEquals(SemanticInteractionResult.SENT, result)
        assertEquals(listOf(listOf("3"), listOf("Enter")), transport.keys.map { it.first })
        assertEquals(listOf(Triple("Use guard clauses", false, detect(focused, 5).regionSignature)), transport.replies)
    }

    @Test
    fun multiAdvanceWalksToVerifiedAdvanceBeforeEnter() = runTest {
        val initial = multiFixture(pointerOnAdvance = false)
        val advanced = multiFixture(pointerOnAdvance = true)
        val transport = FakeTransport(listOf(pane(initial, 12), pane(advanced, 13)))
        val surface = detect(initial, 12)

        val result = SemanticInteractionEngine(transport) {}.execute(
            "claude", surface, SemanticIntent.MultiAdvance,
        )

        assertEquals(SemanticInteractionResult.SENT, result)
        assertEquals(listOf(listOf("Down"), listOf("Enter")), transport.keys.map { it.first })
        assertEquals(detect(advanced, 13).regionSignature, transport.keys.last().second)
    }

    @Test
    fun replacingPreviewNoteOpensClearsTypesWithoutSubmitAndBlurs() = runTest {
        val initial = previewNoteFixture("old note", editing = false)
        val opened = previewNoteFixture("old note", editing = true)
        val cleared = previewNoteFixture("", editing = true)
        val typed = previewNoteFixture("new note", editing = true)
        val blurred = previewNoteFixture("new note", editing = false)
        val transport = FakeTransport(listOf(
            pane(initial, 20), pane(opened, 21), pane(cleared, 22), pane(typed, 23), pane(blurred, 24),
        ))
        val surface = detect(initial, 20)

        val result = SemanticInteractionEngine(transport) {}.execute(
            "claude", surface, SemanticIntent.PreviewNote("new note", replacing = true),
        )

        assertEquals(SemanticInteractionResult.SENT, result)
        assertEquals("n", transport.keys[0].first.single())
        assertEquals("ctrl+k", transport.keys[1].first.first())
        assertEquals("Escape", transport.keys[2].first.single())
        assertEquals(listOf(Triple("new note", false, detect(cleared, 22).regionSignature)), transport.replies)
    }

    @Test
    fun entryRevisionOrSignatureDriftSendsNothing() = runTest {
        val initial = previewFixture(1)
        val changed = previewFixture(1).replace("Rounded", "Round")
        val transport = FakeTransport(listOf(pane(changed, 7)))

        val result = SemanticInteractionEngine(transport) {}.execute(
            "claude", detect(initial, 6), SemanticIntent.PreviewOption(2),
        )

        assertEquals(SemanticInteractionResult.CHANGED, result)
        assertEquals(emptyList<List<String>>(), transport.keys.map { it.first })
    }

    @Test
    fun planFeedbackNeverAppendsToTextThatAppearsAfterFocus() = runTest {
        val initial = planFixture("Tell Claude what to change", focused = false)
        val externallyTyped = planFixture("someone else is typing", focused = true)
        val transport = FakeTransport(listOf(pane(initial, 30), pane(externallyTyped, 31)))

        val result = SemanticInteractionEngine(transport) {}.execute(
            "claude", detect(initial, 30), SemanticIntent.PromptFeedback("3", "my feedback"),
        )

        assertEquals(SemanticInteractionResult.CHANGED, result)
        assertEquals(listOf(listOf("3")), transport.keys.map { it.first })
        assertEquals(emptyList<Triple<String, Boolean, String?>>(), transport.replies)
    }

    @Test
    fun serverSidePromptChangeIsNotFlattenedIntoGenericFailure() = runTest {
        val initial = previewFixture(1)
        val transport = FakeTransport(
            listOf(pane(initial, 40)),
            keyOutcomes = listOf(SemanticWriteOutcome.CHANGED),
        )

        val result = SemanticInteractionEngine(transport) {}.execute(
            "claude", detect(initial, 40), SemanticIntent.PreviewOption(2),
        )

        assertEquals(SemanticInteractionResult.CHANGED, result)
    }

    private fun detect(text: String, revision: Long) =
        AgentSemanticParser.detect("claude", text.trimIndent(), revision) ?: error("fixture not detected")

    private fun pane(text: String, revision: Long) = SemanticPane(text.trimIndent(), revision)

    private fun previewFixture(pointed: Int) = """
        ☐ Design
        Which widget design should we use?
        ${if (pointed == 1) "❯" else " "} 1. Boxy             ┌───────────────┐
        ${if (pointed == 2) "❯" else " "} 2. Rounded          │ rounded       │
          3. Minimal          └───────────────┘
                              Notes: press n to add notes
        ─────────────────────────
          Chat about this
        Enter to select · ↑/↓ to navigate · n to add notes · Esc to cancel
    """

    private fun previewNoteFixture(note: String, editing: Boolean): String {
        val noteValue = note.ifBlank { "" }
        val footer = "Enter to select · ↑/↓ to navigate · n to add notes" +
            if (editing) " · ctrl+g to edit · Esc to cancel" else " · Esc to cancel"
        return """
            ☐ Design
            Which widget design should we use?
            ❯ 1. Boxy             ┌───────────────┐
              2. Rounded          │ rounded       │
              3. Minimal          └───────────────┘
                                  Notes: $noteValue
            ─────────────────────────
              Chat about this
            $footer
        """
    }

    private fun planFixture(feedback: String, focused: Boolean) = """
        Ready to code?
        Would you like to proceed?
          1. Yes, use auto mode
          2. Yes, approve manually
        ${if (focused) "❯" else " "} 3. $feedback
             shift+tab to approve with this feedback
        ctrl+g to edit in nano · .claude/plans/example.md
    """

    private fun multiFixture(pointerOnAdvance: Boolean) = """
        ←  ☐ Toppings  ✔ Submit  →
        Which pizza toppings do you want?
        ❯ 1. [ ] Cheese
          2. [✔] Mushrooms
          3. [ ] Type something
        ${if (pointerOnAdvance) "❯" else " "}    Submit
        ─────────────────────────
          4. Chat about this
        Enter to select · ↑/↓ to navigate · Esc to cancel
    """

    private class FakeTransport(
        reads: List<SemanticPane>,
        keyOutcomes: List<SemanticWriteOutcome> = emptyList(),
    ) : SemanticInteractionTransport {
        private val reads = ArrayDeque(reads)
        private val keyOutcomes = ArrayDeque(keyOutcomes)
        val keys = mutableListOf<Pair<List<String>, String?>>()
        val replies = mutableListOf<Triple<String, Boolean, String?>>()

        override suspend fun read(): SemanticPane? = reads.removeFirstOrNull()
        override suspend fun keys(keys: List<String>, expectedPrompt: String?): SemanticWriteOutcome {
            this.keys += keys to expectedPrompt
            return keyOutcomes.removeFirstOrNull() ?: SemanticWriteOutcome.OK
        }
        override suspend fun reply(text: String, submit: Boolean, expectedPrompt: String?): SemanticWriteOutcome {
            replies += Triple(text, submit, expectedPrompt)
            return SemanticWriteOutcome.OK
        }
    }
}
