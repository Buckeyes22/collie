package com.lateapex.collie.ui.terminal

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SemanticInteractionEngineTest {
    @Test
    fun measuredMuseCheckboxDigitJumpReadsBackTargetAndToggleBeforeSuccess() = runTest {
        val captured = File(fixtureDirectory(), "muse--ask-toppings.txt").readText().let(TerminalPlainText::strip)
        val moved = captured.replace("› 1. [ ] Cheese", "1. [ ] Cheese")
            .replace("2. [ ] Pepperoni", "› 2. [ ] Pepperoni")
        val toggled = moved.replace("[ ] Pepperoni", "[x] Pepperoni")
            .replace("Submit answer (0 checked)", "Submit answer (1 checked)")
        val transport = FakeTransport(listOf(pane("muse", captured, 80), pane("muse", moved, 81), pane("muse", toggled, 82)))
        val surface = AgentSemanticParser.detect("muse", captured, 80)!!

        val result = SemanticInteractionEngine(transport) {}.execute(
            "muse", surface, SemanticIntent.MultiToggle(surface.actions.single { it.id == "toggle-2" }),
        )

        assertEquals(SemanticInteractionResult.SENT, result)
        assertEquals(listOf(listOf("2"), listOf("Enter")), transport.keys.map { it.first })
        assertEquals(surface.regionSignature, transport.keys.first().second)
        assertEquals(AgentSemanticParser.detect("muse", moved, 81)!!.regionSignature, transport.keys.last().second)
    }

    @Test
    fun measuredMuseCheckboxNeverEntersIfPointerReadbackDidNotReachTheRequestedRow() = runTest {
        val captured = File(fixtureDirectory(), "muse--ask-toppings.txt").readText().let(TerminalPlainText::strip)
        val transport = FakeTransport(listOf(pane("muse", captured, 83), pane("muse", captured, 83)))
        val surface = AgentSemanticParser.detect("muse", captured, 83)!!

        val result = SemanticInteractionEngine(transport) {}.execute(
            "muse", surface, SemanticIntent.MultiToggle(surface.actions.single { it.id == "toggle-2" }),
        )

        assertEquals(SemanticInteractionResult.CHANGED, result)
        assertEquals(listOf(listOf("2")), transport.keys.map { it.first })
    }

    @Test
    fun measuredMuseCheckboxReportsChangedWhenAnotherOptionAlsoChangesAfterToggle() = runTest {
        val captured = File(fixtureDirectory(), "muse--ask-toppings.txt").readText().let(TerminalPlainText::strip)
        val moved = captured.replace("› 1. [ ] Cheese", "1. [ ] Cheese")
            .replace("2. [ ] Pepperoni", "› 2. [ ] Pepperoni")
        val changed = moved.replace("[ ] Pepperoni", "[x] Pepperoni")
            .replace("[ ] Mushrooms", "[x] Mushrooms")
            .replace("Submit answer (0 checked)", "Submit answer (2 checked)")
        val transport = FakeTransport(listOf(pane("muse", captured, 89), pane("muse", moved, 90), pane("muse", changed, 91)))
        val surface = AgentSemanticParser.detect("muse", captured, 89)!!
        val result = SemanticInteractionEngine(transport) {}.execute(
            "muse", surface, SemanticIntent.MultiToggle(surface.actions.single { it.id == "toggle-2" }),
        )
        assertEquals(SemanticInteractionResult.CHANGED, result)
        assertEquals(listOf(listOf("2"), listOf("Enter")), transport.keys.map { it.first })
    }

    @Test
    fun measuredMuseReviewSubmitsOnlyTheCapturedHighlightedSubmitRow() = runTest {
        val captured = File(fixtureDirectory(), "muse--ask-toppings-review.txt").readText().let(TerminalPlainText::strip)
        val surface = AgentSemanticParser.detect("muse", captured, 84)!!
        val transport = FakeTransport(listOf(pane("muse", captured, 84)))

        val result = SemanticInteractionEngine(transport) {}.execute(
            "muse", surface, SemanticIntent.Static(surface.actions.single { it.id == "review-submit" }),
        )

        assertEquals(SemanticInteractionResult.SENT, result)
        assertEquals(listOf(listOf("Enter")), transport.keys.map { it.first })
    }

    @Test
    fun measuredMuseReviewMovesToInterruptOnlyAfterPointerReadback() = runTest {
        val captured = File(fixtureDirectory(), "muse--ask-toppings-review.txt").readText().let(TerminalPlainText::strip)
        val moved = captured.replace("> Submit answers", "  Submit answers")
            .replace("    Interrupt turn", "  > Interrupt turn")
        val surface = AgentSemanticParser.detect("muse", captured, 86)!!
        val transport = FakeTransport(listOf(pane("muse", captured, 86), pane("muse", moved, 87)))

        val result = SemanticInteractionEngine(transport) {}.execute(
            "muse", surface, SemanticIntent.MultiReviewSelect(surface.actions.single { it.id == "review-interrupt" }),
        )

        assertEquals(SemanticInteractionResult.SENT, result)
        assertEquals(listOf(listOf("Down"), listOf("Enter")), transport.keys.map { it.first })
        assertEquals(surface.regionSignature, transport.keys.first().second)
        assertEquals(AgentSemanticParser.detect("muse", moved, 87)!!.regionSignature, transport.keys.last().second)
    }

    @Test
    fun measuredMuseReviewNeverEntersWhenThePointerReadbackStaysOnSubmit() = runTest {
        val captured = File(fixtureDirectory(), "muse--ask-toppings-review.txt").readText().let(TerminalPlainText::strip)
        val surface = AgentSemanticParser.detect("muse", captured, 88)!!
        val transport = FakeTransport(listOf(pane("muse", captured, 88), pane("muse", captured, 88)))

        val result = SemanticInteractionEngine(transport) {}.execute(
            "muse", surface, SemanticIntent.MultiReviewSelect(surface.actions.single { it.id == "review-interrupt" }),
        )

        assertEquals(SemanticInteractionResult.CHANGED, result)
        assertEquals(listOf(listOf("Down")), transport.keys.map { it.first })
    }

    @Test
    fun measuredMuseReviewRefusesAnswerDriftBeforeAnySubmitKey() = runTest {
        val captured = File(fixtureDirectory(), "muse--ask-toppings-review.txt").readText()
        val changed = captured.replace("Toppings: Pepperoni", "Toppings: Mushrooms")
        val surface = AgentSemanticParser.detect("muse", captured, 85)!!
        val transport = FakeTransport(listOf(pane("muse", changed, 85)))

        val result = SemanticInteractionEngine(transport) {}.execute(
            "muse", surface, SemanticIntent.Static(surface.actions.single { it.id == "review-submit" }),
        )

        assertEquals(SemanticInteractionResult.CHANGED, result)
        assertEquals(emptyList<List<String>>(), transport.keys.map { it.first })
    }

    @Test
    fun measuredOmpPointerRecipeReadsBackBeforeSubmittingEnter() = runTest {
        val directory = fixtureDirectory()
        val initial = File(directory, "omp--v18-4-ask-single.txt").readText()
        val moved = File(directory, "omp--v18-4-ask-single-moved.txt").readText()
        val transport = FakeTransport(listOf(pane("omp", initial, 70), pane("omp", moved, 71)))
        val surface = detect("omp", initial, 70)

        val result = SemanticInteractionEngine(transport) {}.execute(
            "omp", surface, SemanticIntent.PointerSelect(surface.actions[1]),
        )

        assertEquals(SemanticInteractionResult.SENT, result)
        assertEquals(listOf(listOf("Down"), listOf("Enter")), transport.keys.map { it.first })
        assertEquals(surface.regionSignature, transport.keys.first().second)
        assertEquals(detect("omp", moved, 71).regionSignature, transport.keys.last().second)
    }

    @Test
    fun ompPointerMovementKeepsQuestionIdentityButQuestionOrOptionShapeChangeRefusesEnter() = runTest {
        val directory = fixtureDirectory()
        val initial = File(directory, "omp--v18-4-ask-single.txt").readText()
        val moved = File(directory, "omp--v18-4-ask-single-moved.txt").readText()
        val original = detect("omp", initial, 711).nativeModel!!
        val movedModel = detect("omp", moved, 712).nativeModel!!
        assertEquals(NativeSemanticDeriver.identity(original), NativeSemanticDeriver.identity(movedModel))

        val changedQuestion = TerminalPlainText.strip(moved).replace("Pick a color", "Pick a fruit")
        val questionTransport = FakeTransport(listOf(pane("omp", initial, 711), pane("omp", changedQuestion, 712)))
        val questionResult = SemanticInteractionEngine(questionTransport) {}.execute(
            "omp", detect("omp", initial, 711), SemanticIntent.PointerSelect(detect("omp", initial, 711).actions[1]),
        )
        assertEquals(SemanticInteractionResult.CHANGED, questionResult)
        assertEquals(listOf(listOf("Down")), questionTransport.keys.map { it.first })

        val changedShape = TerminalPlainText.strip(moved).replace("○ Blue", "○ Violet")
        val shapeTransport = FakeTransport(listOf(pane("omp", initial, 713), pane("omp", changedShape, 714)))
        val shapeResult = SemanticInteractionEngine(shapeTransport) {}.execute(
            "omp", detect("omp", initial, 713), SemanticIntent.PointerSelect(detect("omp", initial, 713).actions[1]),
        )
        assertEquals(SemanticInteractionResult.CHANGED, shapeResult)
        assertEquals(listOf(listOf("Down")), shapeTransport.keys.map { it.first })
    }

    @Test
    fun measuredOmpApprovalProjectsTheVisibleChoiceBeforeSubmittingEnter() = runTest {
        val directory = fixtureDirectory()
        val initial = File(directory, "omp--v18-4-approval-bash.txt").readText()
        val moved = File(directory, "omp--v18-4-approval-bash-moved.txt").readText()
        val transport = FakeTransport(listOf(pane("omp", initial, 74), pane("omp", moved, 75)))
        val surface = detect("omp", initial, 74)
        val projected = surface.nativeModel as NativeSemanticModel.Prompt

        assertEquals(listOf(true, false), projected.options.map { it.pointed })
        val result = SemanticInteractionEngine(transport) {}.execute(
            "omp", surface, SemanticIntent.PointerSelect(surface.actions[1]),
        )

        assertEquals(SemanticInteractionResult.SENT, result)
        assertEquals(listOf(listOf("Down"), listOf("Enter")), transport.keys.map { it.first })
        assertEquals(detect("omp", moved, 75).regionSignature, transport.keys.last().second)
    }

    @Test
    fun measuredOmpPointerRecipeNeverEntersWhenReadBackDidNotMoveThePointer() = runTest {
        val initial = File(fixtureDirectory(), "omp--v18-4-ask-single.txt").readText()
        val transport = FakeTransport(listOf(pane("omp", initial, 76), pane("omp", initial, 76)))
        val surface = detect("omp", initial, 76)

        val result = SemanticInteractionEngine(transport) {}.execute(
            "omp", surface, SemanticIntent.PointerSelect(surface.actions[2]),
        )

        assertEquals(SemanticInteractionResult.CHANGED, result)
        assertEquals(listOf(listOf("Down", "Down")), transport.keys.map { it.first })
    }

    @Test
    fun measuredOmpMultiToggleReadsBackTheTargetAndOnlyReportsTheExactChangedCheckbox() = runTest {
        val captured = File(fixtureDirectory(), "omp--v18-4-ask-multi.txt").readText().let(TerminalPlainText::strip)
        val moved = captured.replace("❯ ☐ Cheese", "  ☐ Cheese").replace("  ☐ Olives", "❯ ☐ Olives")
        val toggled = moved.replace("❯ ☐ Olives", "❯ ☑ Olives")
        val transport = FakeTransport(listOf(pane("omp", captured, 500), pane("omp", moved, 501), pane("omp", toggled, 502)))
        val surface = AgentSemanticParser.detect("omp", captured, 500)!!

        val result = SemanticInteractionEngine(transport) {}.execute(
            "omp", surface, SemanticIntent.OmpMultiToggle(surface.actions.single { it.id == "toggle-2" }),
        )

        assertEquals(SemanticInteractionResult.SENT, result)
        assertEquals(listOf(listOf("Down"), listOf("Space")), transport.keys.map { it.first })
        assertEquals(surface.regionSignature, transport.keys.first().second)
        assertEquals(AgentSemanticParser.detect("omp", moved, 501)!!.regionSignature, transport.keys.last().second)
    }

    @Test
    fun measuredOmpMultiNeverTogglesWithoutPointerReadbackOrWhenAnotherCheckboxChanges() = runTest {
        val captured = File(fixtureDirectory(), "omp--v18-4-ask-multi.txt").readText().let(TerminalPlainText::strip)
        val unchanged = FakeTransport(listOf(pane("omp", captured, 503), pane("omp", captured, 503)))
        val surface = AgentSemanticParser.detect("omp", captured, 503)!!
        val refused = SemanticInteractionEngine(unchanged) {}.execute(
            "omp", surface, SemanticIntent.OmpMultiToggle(surface.actions.single { it.id == "toggle-2" }),
        )
        assertEquals(SemanticInteractionResult.CHANGED, refused)
        assertEquals(listOf(listOf("Down")), unchanged.keys.map { it.first })

        val moved = captured.replace("❯ ☐ Cheese", "  ☐ Cheese").replace("  ☐ Olives", "❯ ☐ Olives")
        val unexpectedlyChanged = moved.replace("❯ ☐ Olives", "❯ ☑ Olives").replace("☐ Basil", "☑ Basil")
        val raced = FakeTransport(listOf(pane("omp", captured, 504), pane("omp", moved, 505), pane("omp", unexpectedlyChanged, 506)))
        val result = SemanticInteractionEngine(raced) {}.execute(
            "omp", AgentSemanticParser.detect("omp", captured, 504)!!,
            SemanticIntent.OmpMultiToggle(AgentSemanticParser.detect("omp", captured, 504)!!.actions.single { it.id == "toggle-2" }),
        )
        assertEquals(SemanticInteractionResult.CHANGED, result)
        assertEquals(listOf(listOf("Down"), listOf("Space")), raced.keys.map { it.first })
    }

    @Test
    fun measuredOmpNoteTypesOnlyIntoTheCapturedEmptyEditorAndSubmitsAfterExactReadback() = runTest {
        val directory = fixtureDirectory()
        val ask = File(directory, "omp--v18-4-ask-single-moved.txt").readText().let(TerminalPlainText::strip)
        val editor = File(directory, "omp--v18-4-ask-note-editor.txt").readText().let(TerminalPlainText::strip)
        val typed = editor.replaceFirst(Regex("(?m)^│ >\\s*│$"), "│ > use concise wording │")
        val transport = FakeTransport(listOf(pane("omp", ask, 510), pane("omp", editor, 511), pane("omp", typed, 512)))
        val surface = AgentSemanticParser.detect("omp", ask, 510)!!

        val result = SemanticInteractionEngine(transport) {}.execute(
            "omp", surface, SemanticIntent.PromptFeedback("n", "use concise wording"),
        )

        assertEquals(SemanticInteractionResult.SENT, result)
        assertEquals(listOf(listOf("n"), listOf("Enter")), transport.keys.map { it.first })
        assertEquals(listOf(Triple("use concise wording", false, AgentSemanticParser.detect("omp", editor, 511)!!.regionSignature)), transport.replies)
    }

    @Test
    fun measuredOmpNoteRefusesAnEditorForAnotherOptionBeforeTyping() = runTest {
        val directory = fixtureDirectory()
        val ask = File(directory, "omp--v18-4-ask-single-moved.txt").readText().let(TerminalPlainText::strip)
        val wrongEditor = File(directory, "omp--v18-4-ask-note-editor.txt").readText()
            .replace("Note for Green:", "Note for Blue:").let(TerminalPlainText::strip)
        val transport = FakeTransport(listOf(pane("omp", ask, 514), pane("omp", wrongEditor, 515)))
        val surface = AgentSemanticParser.detect("omp", ask, 514)!!

        val result = SemanticInteractionEngine(transport) {}.execute(
            "omp", surface, SemanticIntent.PromptFeedback("n", "do not attach to blue"),
        )

        assertEquals(SemanticInteractionResult.CHANGED, result)
        assertEquals(listOf(listOf("n")), transport.keys.map { it.first })
        assertEquals(emptyList<Triple<String, Boolean, String?>>(), transport.replies)
    }

    @Test
    fun measuredOmpMultiSubmitUsesTheExplicitSubmitKeyAndExactCheckedSnapshot() = runTest {
        val captured = File(fixtureDirectory(), "omp--v18-4-ask-multi-checked.txt").readText().let(TerminalPlainText::strip)
        val transport = FakeTransport(listOf(pane("omp", captured, 513)))
        val surface = AgentSemanticParser.detect("omp", captured, 513)!!

        val result = SemanticInteractionEngine(transport) {}.execute("omp", surface, SemanticIntent.MultiAdvance)

        assertEquals(SemanticInteractionResult.SENT, result)
        assertEquals(listOf(listOf("Enter")), transport.keys.map { it.first })
        assertEquals(surface.regionSignature, transport.keys.single().second)
    }

    @Test
    fun measuredOmpResumeWalkVerifiesTheExactSessionBeforeEnter() = runTest {
        val directory = fixtureDirectory()
        val initial = File(directory, "omp--v18-4-resume.txt").readText()
        val moved = File(directory, "omp--v18-4-resume-moved.txt").readText()
        val transport = FakeTransport(listOf(pane("omp", initial, 180), pane("omp", moved, 181)))
        val surface = detect("omp", initial, 180)
        val model = surface.nativeModel as NativeSemanticModel.Prompt
        assertEquals("omp-resume", surface.detail)
        assertEquals("Render Fancy Content in Terminal", model.options[1].label)
        assertEquals("11 minutes ago  ·  138.0KB  ·  ✔ done", model.options[1].description)

        val result = SemanticInteractionEngine(transport) {}.execute(
            "omp", surface, SemanticIntent.PointerSelect(surface.actions[1]),
        )

        assertEquals(SemanticInteractionResult.SENT, result)
        assertEquals(listOf(listOf("Down"), listOf("Enter")), transport.keys.map { it.first })
    }

    @Test
    fun measuredOmpSessionModelWalkCountsCurrentRowsAndReadsBackTargetBeforeEnter() = runTest {
        val directory = fixtureDirectory()
        val initial = File(directory, "omp--v18-4-switch.txt").readText()
        val moved = File(directory, "omp--v18-4-switch-moved.txt").readText()
        val transport = FakeTransport(listOf(pane("omp", initial, 182), pane("omp", moved, 183)))
        val surface = detect("omp", initial, 182)
        val action = surface.actions.single { it.label == "anthropic/claude-fable-5-1" }

        val result = SemanticInteractionEngine(transport) {}.execute(
            "omp", surface, SemanticIntent.PointerSelect(action),
        )

        assertEquals(SemanticInteractionResult.SENT, result)
        assertEquals(listOf(listOf("Down"), listOf("Enter")), transport.keys.map { it.first })
        assertEquals(surface.regionSignature, transport.keys.first().second)
        assertEquals(detect("omp", moved, 183).regionSignature, transport.keys.last().second)
    }

    @Test
    fun measuredOmpSessionModelRefusesEnterAfterTheVisibleInventoryChanges() = runTest {
        val directory = fixtureDirectory()
        val initial = File(directory, "omp--v18-4-switch.txt").readText()
        val changedInventory = File(directory, "omp--v18-4-switch-ptr-fable.txt").readText()
        val transport = FakeTransport(listOf(pane("omp", initial, 185), pane("omp", changedInventory, 186)))
        val surface = detect("omp", initial, 185)

        val result = SemanticInteractionEngine(transport) {}.execute(
            "omp", surface, SemanticIntent.PointerSelect(surface.actions.single { it.label == "anthropic/claude-fable-5-1" }),
        )

        assertEquals(SemanticInteractionResult.CHANGED, result)
        assertEquals(listOf(listOf("Down")), transport.keys.map { it.first })
    }

    @Test
    fun measuredOmpSessionModelNeverEntersWithoutExactPointerReadback() = runTest {
        val initial = File(fixtureDirectory(), "omp--v18-4-switch.txt").readText()
        val transport = FakeTransport(listOf(pane("omp", initial, 184), pane("omp", initial, 184)))
        val surface = detect("omp", initial, 184)

        val result = SemanticInteractionEngine(transport) {}.execute(
            "omp", surface, SemanticIntent.PointerSelect(surface.actions.single { it.label == "anthropic/claude-fable-5-1" }),
        )

        assertEquals(SemanticInteractionResult.CHANGED, result)
        assertEquals(listOf(listOf("Down")), transport.keys.map { it.first })
    }

    @Test
    fun measuredPointerRecipeDoesNotEnterAfterReadbackChangesTheOptionIdentity() = runTest {
        val directory = fixtureDirectory()
        val initial = File(directory, "omp--v18-4-ask-single.txt").readText()
        val changed = File(directory, "omp--v18-4-ask-single-moved.txt").readText().replace("Green", "Yellow")
        val transport = FakeTransport(listOf(pane("omp", initial, 72), pane("omp", changed, 73)))
        val surface = detect("omp", initial, 72)

        val result = SemanticInteractionEngine(transport) {}.execute(
            "omp", surface, SemanticIntent.PointerSelect(surface.actions[1]),
        )

        assertEquals(SemanticInteractionResult.CHANGED, result)
        assertEquals(listOf(listOf("Down")), transport.keys.map { it.first })
    }

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

    private fun detect(text: String, revision: Long) = detect("claude", text, revision)

    private fun detect(agent: String, text: String, revision: Long) =
        AgentSemanticParser.detect(agent, text.trimIndent(), revision) ?: error("fixture not detected: $agent")

    private fun pane(text: String, revision: Long) = pane("claude", text, revision)

    private fun pane(agent: String, text: String, revision: Long) = SemanticPane(text.trimIndent(), revision)

    private fun fixtureDirectory(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "web/src/fixtures/panes") }
        .firstOrNull(File::isDirectory)
        ?: error("Could not locate web fixture corpus")

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
