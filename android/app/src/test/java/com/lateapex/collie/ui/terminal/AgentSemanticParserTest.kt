package com.lateapex.collie.ui.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AgentSemanticParserTest {
    @Test
    fun fixtureCorpusIsExhaustivelyAndExplicitlyClassified() {
        // New slider/resume/trust recipes and clipped commands stay raw until native key recipes are proven.
        val expected = mapOf(
            "omp--select-menu-other.txt" to null,
            "omp--select-menu-noted.txt" to null,
            "claude--trust-prompt-unnumbered.txt" to null,
            "claude--menu-resume-picker--w80-second.txt" to null,
            "claude--menu-resume-picker--w60-first.txt" to null,
            "claude--menu-resume-picker--w120-third.txt" to null,
            "claude--menu-resume-picker--w120-search.txt" to null,
            "claude--menu-resume-picker--w120-first.txt" to null,
            "claude--menu-resume-picker--w120-all-sanitized.txt" to null,
            "claude--menu-effort-slider.txt" to null,
            "claude--menu-effort-slider--w80.txt" to null,
            "claude--menu-effort-slider--w80-ultracode.txt" to null,
            "claude--menu-effort-slider--w80-low.txt" to null,
            "claude--menu-effort-slider--w60.txt" to null,
            "claude--menu-effort-slider--w60-ultracode.txt" to null,
            "claude--menu-effort-slider--w60-low.txt" to null,
            "claude--menu-effort-slider--w40.txt" to null,
            "claude--menu-effort-slider--w40-ultracode.txt" to null,
            "claude--menu-effort-slider--w40-low.txt" to null,
            "claude--menu-effort-slider--w132.txt" to null,
            "claude--menu-effort-slider--w120.txt" to null,
            "claude--autocomplete-slash-clipped.txt" to null,
            "agy--permission-bash.txt" to SemanticKind.PROMPT_SELECT,
            "agy--permission-edit.txt" to SemanticKind.PROMPT_SELECT,
            "agy--plan-approval.txt" to SemanticKind.PROMPT_SELECT,
            "agy--select-menu.txt" to SemanticKind.PROMPT_SELECT,
            "agy--trust-prompt.txt" to SemanticKind.PROMPT_SELECT,
            "claude--select-ask-no-question-mark.txt" to SemanticKind.PROMPT_SELECT,
            "claude--autocomplete-slash-long.txt" to SemanticKind.AUTOCOMPLETE,
            "claude--autocomplete-slash-short.txt" to SemanticKind.AUTOCOMPLETE,
            "claude--menu-model-picker-dismissed.txt" to null,
            "claude--menu-model-picker-moved.txt" to SemanticKind.MENU,
            "claude--menu-model-picker.txt" to SemanticKind.MENU,
            "claude--permission-bash.txt" to SemanticKind.PROMPT_SELECT,
            "claude--permission-edit.txt" to SemanticKind.PROMPT_SELECT,
            "claude--plan-approval--feedback-focused.txt" to SemanticKind.PROMPT_SELECT,
            "claude--plan-approval--feedback-typed.txt" to SemanticKind.PROMPT_SELECT,
            "claude--plan-approval--feedback-wrapped.txt" to SemanticKind.PROMPT_SELECT,
            "claude--plan-approval--numbered-body.txt" to SemanticKind.PROMPT_SELECT,
            "claude--plan-approval--three-row-focused.txt" to SemanticKind.PROMPT_SELECT,
            "claude--plan-approval--three-row-typed-focused.txt" to SemanticKind.PROMPT_SELECT,
            "claude--plan-approval--three-row.txt" to SemanticKind.PROMPT_SELECT,
            "claude--plan-approval.txt" to SemanticKind.PROMPT_SELECT,
            "claude--select-menu.txt" to SemanticKind.PROMPT_SELECT,
            "claude--select-multi.txt" to SemanticKind.WIZARD,
            "claude--select-multiselect-checked.txt" to SemanticKind.MULTI_SELECT,
            "claude--select-multiselect-review.txt" to SemanticKind.MULTI_SELECT,
            "claude--select-multiselect-single.txt" to SemanticKind.MULTI_SELECT,
            "claude--select-preview-note-attached.txt" to SemanticKind.PREVIEW_SELECT,
            "claude--select-preview-note-input.txt" to SemanticKind.PREVIEW_SELECT,
            "claude--select-preview.txt" to SemanticKind.PREVIEW_SELECT,
            "claude--wizard-multiselect-checked.txt" to SemanticKind.MULTI_SELECT,
            "claude--wizard-multiselect-final.txt" to SemanticKind.MULTI_SELECT,
            "claude--wizard-multiselect-pointer-next.txt" to SemanticKind.MULTI_SELECT,
            "claude--wizard-multiselect-q1.txt" to SemanticKind.MULTI_SELECT,
            "claude--wizard-preview-note-attached.txt" to SemanticKind.PREVIEW_SELECT,
            "claude--wizard-preview-q1.txt" to SemanticKind.PREVIEW_SELECT,
            "claude--wizard-preview-wrapped-label.txt" to SemanticKind.PREVIEW_SELECT,
            "claude--wizard-q1-revisit.txt" to SemanticKind.WIZARD,
            "claude--wizard-q1.txt" to SemanticKind.WIZARD,
            "claude--wizard-q2.txt" to SemanticKind.WIZARD,
            "claude--wizard-submit-unanswered.txt" to SemanticKind.WIZARD,
            "claude--wizard-submit.txt" to SemanticKind.WIZARD,
            "claude--trust-prompt.txt" to SemanticKind.PROMPT_SELECT,
            "codex--approval-exec.txt" to SemanticKind.PROMPT_SELECT,
            "codex--ask-fruit.txt" to SemanticKind.PROMPT_SELECT,
            // Web leaves focused notes raw; native claims a locked surface solely to disable writes.
            "codex--ask-notes-focused.txt" to SemanticKind.PROMPT_SELECT,
            "codex--ask-wizard-q1.txt" to SemanticKind.PROMPT_SELECT,
            "codex--ask-wizard-q2.txt" to SemanticKind.PROMPT_SELECT,
            "codex--trust-prompt.txt" to SemanticKind.PROMPT_SELECT,
            "grok--ask-color-moved.txt" to SemanticKind.PROMPT_SELECT,
            "grok--ask-color.txt" to SemanticKind.PROMPT_SELECT,
            "grok--ask-esc-park.txt" to SemanticKind.PROMPT_SELECT,
            // No safe checkbox digit choreography has been probed; native surfaces are locked.
            "grok--ask-multi-checked.txt" to SemanticKind.MULTI_SELECT,
            "grok--ask-multi.txt" to SemanticKind.MULTI_SELECT,
            "grok--ask-size.txt" to SemanticKind.PROMPT_SELECT,
            "grok--ask-wizard-q1.txt" to SemanticKind.PROMPT_SELECT,
            "grok--ask-wizard-q2.txt" to SemanticKind.PROMPT_SELECT,
            "grok--ask-z-focused.txt" to SemanticKind.PROMPT_SELECT,
            "grok--ask-z-parked.txt" to SemanticKind.PROMPT_SELECT,
            "grok--ask-z-typed.txt" to SemanticKind.PROMPT_SELECT,
            "grok--permission-edit.txt" to SemanticKind.PROMPT_SELECT,
            "grok--permission-rm-feedback.txt" to SemanticKind.PROMPT_SELECT,
            "grok--permission-rm-moved.txt" to SemanticKind.PROMPT_SELECT,
            "grok--permission-rm.txt" to SemanticKind.PROMPT_SELECT,
            "grok--plan-approval.txt" to SemanticKind.MENU,
            "grok--plan-request-changes.txt" to SemanticKind.MENU,
            "grok--plan-tab-prompt.txt" to SemanticKind.MENU,
            "omp--menu-dismissed.txt" to null,
            "omp--menu-model-moved.txt" to null,
            "omp--menu-model.txt" to null,
            "omp--menu-resume-moved.txt" to SemanticKind.PROMPT_SELECT,
            "omp--menu-resume.txt" to SemanticKind.PROMPT_SELECT,
            "omp--menu-settings-moved.txt" to null,
            "omp--menu-settings.txt" to null,
            "omp--select-menu-moved.txt" to null,
            "omp--select-menu.txt" to null,
            "omp--select-multi-checked.txt" to null,
            "omp--select-multi-review.txt" to null,
            "omp--select-multi.txt" to null,
            "omp--v18-4-ask-single.txt" to SemanticKind.PROMPT_SELECT,
            "omp--v18-4-ask-single-moved.txt" to SemanticKind.PROMPT_SELECT,
            "omp--v18-4-ask-multi.txt" to SemanticKind.MULTI_SELECT,
            "omp--v18-4-ask-multi-checked.txt" to SemanticKind.MULTI_SELECT,
            "omp--v18-4-ask-note-editor.txt" to SemanticKind.PROMPT_SELECT,
            "omp--v18-4-approval-write.txt" to SemanticKind.PROMPT_SELECT,
            "omp--v18-4-approval-write-moved.txt" to SemanticKind.PROMPT_SELECT,
            "omp--v18-4-approval-write-long.txt" to SemanticKind.PROMPT_SELECT,
            "omp--v18-4-resume.txt" to SemanticKind.PROMPT_SELECT,
            "omp--v18-4-resume-moved.txt" to SemanticKind.PROMPT_SELECT,
            "omp--v18-4-resume-search.txt" to SemanticKind.PROMPT_SELECT,
            "omp--v18-4-resume-all-projects.txt" to SemanticKind.PROMPT_SELECT,
            "omp--v18-4-resume-untitled-dated.txt" to SemanticKind.PROMPT_SELECT,
            "omp--v18-4-resume-nomatch.txt" to null,
            "omp--v18-4-approval-bash.txt" to SemanticKind.PROMPT_SELECT,
            "omp--v18-4-approval-bash-moved.txt" to SemanticKind.PROMPT_SELECT,
            "omp--slash-palette--filtered.txt" to null,
            "omp--slash-palette.txt" to null,
            "muse--ask-color.txt" to SemanticKind.PROMPT_SELECT,
            "muse--ask-color-moved.txt" to SemanticKind.PROMPT_SELECT,
            "muse--ask-toppings.txt" to SemanticKind.MULTI_SELECT,
            "muse--ask-toppings-checked.txt" to SemanticKind.MULTI_SELECT,
            "muse--ask-toppings-notes-open.txt" to SemanticKind.MULTI_SELECT,
            "muse--ask-toppings-review.txt" to SemanticKind.MULTI_SELECT,
            "muse--trust-prompt.txt" to SemanticKind.PROMPT_SELECT,
            "muse--approval-ls.txt" to SemanticKind.PROMPT_SELECT,
            "muse--approval-ls-moved.txt" to SemanticKind.PROMPT_SELECT,
            "oc--question--single.txt" to SemanticKind.PROMPT_SELECT,
            "oc--question--single--moved.txt" to SemanticKind.PROMPT_SELECT,
            "oc--question--single--narrow.txt" to SemanticKind.PROMPT_SELECT,
            "oc--question--single--overlay.txt" to null,
        ) + OMP_SWITCH_LIFTED.associateWith { SemanticKind.PROMPT_SELECT } + OMP_SWITCH_RAW.associateWith { null }
        val directory = fixtureDirectory()
        val relevant = directory.listFiles().orEmpty().map(File::getName).filter(::isRelevantFixture).sorted()
        assertEquals("A new interactive fixture must be explicitly classified here", expected.keys.sorted(), relevant)
        val mismatches = mutableListOf<String>()
        expected.forEach { (name, kind) ->
            val agent = name.substringBefore("--").let { if (it == "oc") "opencode" else it }
            val surface = AgentSemanticParser.detect(agent, File(directory, name).readText(), 41)
            if (kind != surface?.kind) mismatches += "$name: expected $kind, actual ${surface?.kind}"
            if (name == "codex--ask-notes-focused.txt" || name.startsWith("grok--ask-multi") ||
                name in CLAUDE_FOCUSED_PLAN_FIXTURES
            ) {
                assertTrue("$name must fail closed", surface!!.interactionLocked)
            }
            if (name == "muse--ask-toppings-notes-open.txt") {
                assertTrue("Muse optional-note field owns the keyboard", surface!!.interactionLocked)
                assertTrue("No checkbox actions while the note field owns the keyboard", surface.actions.isEmpty())
            }
            if (agent == "agy") {
                val alias = AgentSemanticParser.detect("antigravity", File(directory, name).readText(), 41)
                assertEquals("Antigravity alias: $name", kind, alias?.kind)
            }
        }
        assertEquals("Native fixture classifications", emptyList<String>(), mismatches)
    }

    @Test
    fun claudeSelectAndWizardUseFixtureRecipes() {
        val select = detect("claude", """
            ☐ Color Theme
            Which color theme should the dashboard use?
            ❯ 1. Red
              2. Green
              3. Blue
              4. Type something.
            ─────────────────────────
              5. Chat about this
            Enter to select · ↑/↓ to navigate · Esc to cancel
        """)
        assertEquals(SemanticKind.PROMPT_SELECT, select?.kind)
        assertEquals(listOf("1", "Enter"), select?.actions?.first()?.keys)
        assertFalse(select!!.actions.any { it.label.startsWith("Type something") })

        val wizard = detect("claude", """
            ←  ☐ Focus area  ☐ Scope  ☐ Workflow  ✔ Submit  →
            Which focus area should we work on?
            ❯ 1. Parser
              2. UI
              3. Tests
              4. Type something.
            ─────────────────────────
              5. Chat about this
            Enter to select · Tab/Arrow keys to navigate · Esc to cancel
        """)
        assertEquals(SemanticKind.WIZARD, wizard?.kind)
        assertEquals(listOf("Left"), wizard?.actions?.first()?.keys)
        assertTrue(wizard!!.actions.any { it.label == "Parser" && it.keys == listOf("1") })
        assertEquals(listOf("Right"), wizard.actions.last().keys)
    }

    @Test
    fun ompSessionModelPickerUsesOnlyMeasuredSessionRowsAndReadBackRecipes() {
        val surface = AgentSemanticParser.detect("omp", fixture("omp--v18-4-switch.txt"), 41)!!
        assertEquals(SemanticKind.PROMPT_SELECT, surface.kind)
        assertEquals("omp-switch", surface.detail)
        assertTrue(surface.title.contains("Session-only switch — role models stay unchanged"))
        assertTrue(surface.title.contains("● current: anthropic/claude-opus-5-5"))
        val actions = surface.actions.filterNot { it.id == "cancel" }
        assertTrue(actions.isNotEmpty())
        assertFalse(actions.any { it.label == "anthropic/claude-opus-5-5" })
        assertTrue(actions.any { it.label == "openrouter/google/gemini-3.8-flash" && it.keys == listOf("Up", "Up", "Up", "Enter") })
        assertTrue(actions.any { it.label == "anthropic/claude-fable-5-1" && it.keys == listOf("Down", "Enter") })
        assertEquals("omp-model-1", actions.first().id)
        assertEquals("Close", surface.actions.last().label)

        // This capture changes only the pointer while preserving the complete visible inventory.
        // ptr-fable is a separately valid but different filtered inventory and must not share
        // authority with this picker instance.
        val moved = AgentSemanticParser.detect("omp", fixture("omp--v18-4-switch-moved.txt"), 43)!!
        assertEquals(
            NativeSemanticDeriver.identity(surface.nativeModel!!),
            NativeSemanticDeriver.identity(moved.nativeModel!!),
        )
        val overContext = AgentSemanticParser.detect("omp", fixture("omp--v18-4-switch-overcontext.txt"), 44)!!
        assertFalse(overContext.actions.any { it.label == "openrouter/openai/gpt-4" })
        val truncated = AgentSemanticParser.detect("omp", fixture("omp--v18-4-switch-truncated.txt"), 45)!!
        assertFalse(truncated.actions.any { it.label.contains("…") })

        val searched = AgentSemanticParser.detect("omp", fixture("omp--v18-4-switch-search-short.txt"), 42)!!
        assertEquals("Clear search", searched.actions.last().label)
        assertTrue(searched.title.contains("🔍 > opus-5"))
        assertFalse(NativeSemanticDeriver.identity(surface.nativeModel!!) == NativeSemanticDeriver.identity(searched.nativeModel!!))
    }

    @Test
    fun measuredOmpMuseAndOpenCodeDialogRecipesStayVersionAndShapeBound() {
        val directory = fixtureDirectory()
        fun fixture(name: String) = File(directory, name).readText()

        val ompAsk = AgentSemanticParser.detect("omp", fixture("omp--v18-4-ask-single.txt"), 51)!!
        assertEquals(SemanticKind.PROMPT_SELECT, ompAsk.kind)
        assertEquals("Pick a color", ompAsk.title)
        assertEquals(listOf("Enter"), ompAsk.actions.first().keys)
        assertEquals(listOf("Down", "Down", "Enter"), ompAsk.actions[2].keys)
        val askModel = ompAsk.nativeModel as NativeSemanticModel.Prompt
        assertEquals(listOf("Red", "Green", "Blue", "Other (type your own)"), askModel.options.map { it.label })
        assertEquals(listOf(true, false, false, false), askModel.options.map { it.pointed })
        val ompApproval = AgentSemanticParser.detect("omp", fixture("omp--v18-4-approval-bash.txt"), 52)!!
        assertEquals(listOf("Enter"), ompApproval.actions.first().keys)
        assertEquals(listOf("Down", "Enter"), ompApproval.actions[1].keys)
        val approvalModel = ompApproval.nativeModel as NativeSemanticModel.Prompt
        assertEquals(listOf(true, false), approvalModel.options.map { it.pointed })
        assertEquals(listOf("Approve", "Deny"), approvalModel.options.map { it.label })
        assertEquals("Command: echo hello-approval", approvalModel.options.first().description)
        assertTrue(ompApproval.actions.any { it.id == "cancel" && it.keys == listOf("Escape") })
        val multi = AgentSemanticParser.detect("omp", fixture("omp--v18-4-ask-multi-checked.txt"), 53)!!
        assertEquals(SemanticKind.MULTI_SELECT, multi.kind)
        assertEquals("omp-multi", multi.detail)
        val multiModel = multi.nativeModel as NativeSemanticModel.Multi
        assertEquals(listOf("Cheese", "Olives", "Basil", "Other (type your own)"), multiModel.options.map { it.label })
        assertEquals(listOf(true, false, false, false), multiModel.options.map { it.checked })
        assertEquals(1, multiModel.pointerOptionNumber)
        assertEquals(listOf("Down", "Down", "Space"), multi.actions.single { it.id == "toggle-3" }.keys)
        assertNull(multiModel.options.last().actionId)
        assertEquals(listOf("Enter"), multi.actions.single { it.id == "submit" }.keys)
        val checkedCustomAnswer = replaceRawOmpCheckbox(fixture("omp--v18-4-ask-multi.txt"), "Other (type your own)", "☑")
        assertNull(AgentSemanticParser.detect("omp", checkedCustomAnswer, 59))
        val note = AgentSemanticParser.detect("omp", fixture("omp--v18-4-ask-note-editor.txt"), 54)!!
        assertEquals("omp-note-editor", note.detail)
        assertEquals("omp-note:Green|Pick a color", (note.nativeModel as NativeSemanticModel.Prompt).identityDetails)
        val write = AgentSemanticParser.detect("omp", fixture("omp--v18-4-approval-write-long.txt"), 55)!!
        val writeBody = (write.nativeModel as NativeSemanticModel.Prompt).options.first().description!!
        assertTrue(writeBody.startsWith("Path: /tmp/omp-sandbox-approval/long.txt ↵ Content:"))
        assertTrue(writeBody.endsWith("sample line 14 for the approval dialog"))
        val malformedWrite = fixture("omp--v18-4-approval-write.txt").let { source ->
            val at = source.lastIndexOf("Content:")
            require(at >= 0) { "Captured write fixture has no Content field" }
            source.substring(0, at) + source.substring(at).replaceFirst("Content:", "Changed:")
        }
        assertTrue(TerminalPlainText.strip(malformedWrite).contains("Changed:"))
        assertNull(AgentSemanticParser.detect("omp", malformedWrite, 56))
        val deceptiveWrite = fixture("omp--v18-4-approval-write.txt").let { source ->
            val at = source.lastIndexOf("Content:")
            source.substring(0, at) + source.substring(at).replaceFirst("hello approval", "hello\u202Eapproval")
        }
        assertNull(AgentSemanticParser.detect("omp", deceptiveWrite, 57))
        val oversizedWriteRows = fixture("omp--v18-4-approval-write.txt").split('\n').toMutableList()
        val contentAt = oversizedWriteRows.indexOfLast { it.contains("Content:") }
        val firstContentAt = contentAt + 1
        require(contentAt >= 0) { "Captured write fixture has no Content field" }
        val blankAfter = (firstContentAt until oversizedWriteRows.size).first {
            TerminalPlainText.strip(oversizedWriteRows[it]).trim().let { row ->
                row.startsWith("│") && row.removePrefix("│").removeSuffix("│").isBlank()
            }
        }
        val rowWidth = TerminalPlainText.strip(oversizedWriteRows[firstContentAt]).length
        val overLimit = (1..31).map { number ->
            val value = "sample $number".padEnd(rowWidth - 3)
            "│ $value│"
        }
        oversizedWriteRows.subList(firstContentAt, blankAfter).clear()
        oversizedWriteRows.addAll(firstContentAt, overLimit)
        assertEquals(31, oversizedWriteRows.subList(firstContentAt, firstContentAt + 31).count {
            TerminalPlainText.strip(it).contains("sample ")
        })
        assertNull(AgentSemanticParser.detect("omp", oversizedWriteRows.joinToString("\n"), 58))

        val museQuestion = AgentSemanticParser.detect("muse", fixture("muse--ask-color.txt"), 54)!!
        assertEquals(SemanticKind.PROMPT_SELECT, museQuestion.kind)
        assertEquals(listOf("Down", "Enter"), museQuestion.actions[1].keys)
        val trust = AgentSemanticParser.detect("muse", fixture("muse--trust-prompt.txt"), 55)!!
        assertEquals(listOf("1"), trust.actions.first().keys)
        val approval = AgentSemanticParser.detect("muse", fixture("muse--approval-ls.txt"), 56)!!
        assertTrue(approval.regionSignature.contains("Current argv:"))
        assertEquals(listOf("1"), approval.actions.first().keys)

        val openCode = AgentSemanticParser.detect("opencode", fixture("oc--question--single.txt"), 57)!!
        assertEquals(SemanticKind.PROMPT_SELECT, openCode.kind)
        assertEquals(listOf("Down", "Enter"), openCode.actions[1].keys)
        assertNull(AgentSemanticParser.detect("opencode", fixture("oc--question--single--overlay.txt"), 58))
    }

    @Test
    fun ompMultiUsesCapturedFooterBoundaryAndRejectsNearbyMalformedShapes() {
        val captured = fixture("omp--v18-4-ask-multi.txt").let(TerminalPlainText::strip)
        val checked = fixture("omp--v18-4-ask-multi-checked.txt").let(TerminalPlainText::strip)

        val capturedSurface = AgentSemanticParser.detect("omp", captured, 601)!!
        val checkedSurface = AgentSemanticParser.detect("omp", checked, 602)!!
        assertEquals(SemanticKind.MULTI_SELECT, capturedSurface.kind)
        assertEquals(SemanticKind.MULTI_SELECT, checkedSurface.kind)
        assertEquals(listOf(false, false, false, false),
            (capturedSurface.nativeModel as NativeSemanticModel.Multi).options.map { it.checked })
        assertEquals(listOf(true, false, false, false),
            (checkedSurface.nativeModel as NativeSemanticModel.Multi).options.map { it.checked })

        assertNull(AgentSemanticParser.detect(
            "omp", captured.replace("␣ toggle · ⏎ submit", "␣ select · ⏎ submit"), 603,
        ))
        val badBottom = captured.lastIndexOf('╰')
        assertNull(AgentSemanticParser.detect(
            "omp", captured.substring(0, badBottom) + "╭" + captured.substring(badBottom + 1), 604,
        ))
        assertNull(AgentSemanticParser.detect("omp", "$captured\nunrelated output", 605))
    }

    @Test
    fun claudeConfirmFamiliesUseDigitAloneAndTypedFeedbackNeverBecomesAnAction() {
        val directory = fixtureDirectory()
        for (name in listOf(
            "claude--trust-prompt.txt",
            "claude--permission-bash.txt",
            "claude--permission-edit.txt",
            "claude--plan-approval.txt",
        )) {
            val surface = AgentSemanticParser.detect("claude", File(directory, name).readText(), 41)!!
            assertTrue(name, surface.actions.isNotEmpty())
            assertTrue(name, surface.actions.all { it.keys.size == 1 && it.keys.single().length == 1 })
        }

        val typed = AgentSemanticParser.detect(
            "claude",
            File(directory, "claude--plan-approval--three-row-typed-focused.txt").readText(),
            41,
        )!!
        assertTrue(typed.interactionLocked)
        assertFalse(typed.actions.any { it.label.contains("guard clause", ignoreCase = true) })
    }

    @Test
    fun measuredMuseCheckboxRowsExposePointerTogglesAndVerifiedSubmitLabel() {
        val value = AgentSemanticParser.detect("muse", fixture("muse--ask-toppings.txt"), 61)!!
        assertEquals(SemanticKind.MULTI_SELECT, value.kind)
        assertEquals("muse-pointer-checkbox", value.detail)
        assertEquals("Which pizza toppings do you want?", value.title)
        assertEquals(listOf("toggle-1", "toggle-2", "toggle-3", "toggle-4"), value.actions.map { it.id })
        assertEquals(listOf("2", "Enter"), value.actions[1].keys)
        val model = value.nativeModel as NativeSemanticModel.Multi
        assertEquals(listOf("Cheese (Recommended)", "Pepperoni", "Mushrooms", "None of the above"), model.options.map { it.label })
        assertEquals(listOf(false, false, false, false), model.options.map { it.checked })
        assertEquals(MultiPointer.OPTION, model.pointer)
        assertEquals(1, model.pointerOptionNumber)
        assertEquals("Submit answer (0 checked)", model.advanceLabel)

        val checked = AgentSemanticParser.detect("muse", fixture("muse--ask-toppings-checked.txt"), 62)!!
        val checkedModel = checked.nativeModel as NativeSemanticModel.Multi
        assertEquals(listOf(false, true, false, false), checkedModel.options.map { it.checked })
        assertEquals("Submit answer (1 checked)", checkedModel.advanceLabel)

        val review = AgentSemanticParser.detect("muse", fixture("muse--ask-toppings-review.txt"), 63)!!
        assertEquals("muse-checkbox-review", review.detail)
        assertEquals(listOf("Toppings: Pepperoni"), review.items)
        assertEquals(listOf("review-submit", "review-interrupt", "review-back"), review.actions.map { it.id })
        assertEquals(listOf("Enter"), review.actions.first().keys)
        assertEquals(listOf("Down", "Enter"), review.actions[1].keys)
        assertEquals(listOf("Escape"), review.actions.last().keys)
        val reviewModel = review.nativeModel as NativeSemanticModel.Multi
        assertTrue(reviewModel.review)
        assertEquals(review.items, reviewModel.reviewSummaries)
        assertEquals("review-submit", reviewModel.reviewPointerId)

        val pointedInterrupt = fixture("muse--ask-toppings-review.txt")
            .let(TerminalPlainText::strip)
            .replace("> Submit answers", "  Submit answers")
            .replace("    Interrupt turn", "  > Interrupt turn")
        val moved = AgentSemanticParser.detect("muse", pointedInterrupt, 64)!!
        assertEquals("review-interrupt", (moved.nativeModel as NativeSemanticModel.Multi).reviewPointerId)
        assertEquals(listOf("Enter"), moved.actions.single { it.id == "review-interrupt" }.keys)
    }

    @Test
    fun claudePreviewRequiresTwoGuardedTapsAndLocksDuringNoteEdit() {
        val preview = detect("claude", previewFixture())
        assertEquals(SemanticKind.PREVIEW_SELECT, preview?.kind)
        assertTrue(preview!!.actions.any { it.label == "Preview Boxy" && it.keys == listOf("1") })
        assertTrue(preview.actions.any { it.label.startsWith("Choose highlighted") && it.keys == listOf("Enter") })

        val editing = detect("claude", previewFixture().replace(
            "Enter to select · ↑/↓ to navigate · n to add notes · Esc to cancel",
            "Enter to select · ↑/↓ to navigate · n to add notes · ctrl+g to edit · Esc to cancel",
        ))
        assertTrue(editing!!.interactionLocked)
    }

    @Test
    fun claudeMultiSelectExposesToggleAndOnlyPointedAdvance() {
        val base = """
            ←  ☐ Toppings  ✔ Submit  →
            Which pizza toppings do you want?
            ❯ 1. [ ] Cheese
              2. [✔] Mushrooms
              3. [ ] Olives
              4. [ ] Type something
                 Submit
            ─────────────────────────
              5. Chat about this
            Enter to select · ↑/↓ to navigate · Esc to cancel
        """
        val multi = detect("claude", base)
        assertEquals(SemanticKind.MULTI_SELECT, multi?.kind)
        assertTrue(multi!!.actions.any { it.label == "☑ Mushrooms" && it.keys == listOf("2") })
        assertFalse(multi.actions.any { it.id == "advance" })

        val pointed = detect("claude", base.replace("   Submit", "❯  Submit"))
        assertTrue(pointed!!.actions.any { it.id == "advance" && it.keys == listOf("Enter") })
    }

    @Test
    fun claudeReviewMenuAndAutocompleteAreSeparated() {
        val review = detect("claude", """
            ←  ☒ Focus area  ☒ Scope  ☒ Workflow  ✔ Submit  →
            Review your answers
            ● Which focus area should we work on?
              → UI
            Ready to submit your answers?
            ❯ 1. Submit answers
              2. Cancel
        """)
        assertEquals(SemanticKind.WIZARD, review?.kind)
        assertEquals(listOf("1"), review?.actions?.first()?.keys)

        val menu = detect("claude", """
            ───────── Model ─────────
            ❯ Opus
              Sonnet ←/→ to change effort
            Enter to set as default · s to use this session only · Esc to cancel
        """)
        assertEquals(SemanticKind.MENU, menu?.kind)
        assertTrue(menu!!.actions.any { it.keys == listOf("Enter") })
        assertFalse(menu.actions.any { it.keys == listOf("1") })

        val autocomplete = detect("claude", """
            ─────────────────────────
              /help  Show help
              /model  Choose a model
        """)
        assertEquals(SemanticKind.AUTOCOMPLETE, autocomplete?.kind)
        assertFalse(autocomplete!!.ownsKeyboard)
        assertTrue(autocomplete.actions.isEmpty())
    }

    @Test
    fun codexTrustAskAndFocusedNotesFollowStrictFixtures() {
        val trust = detect("codex", """
            Do you trust the contents of this directory?
            › 1. Yes, continue
              2. No, quit
            Press enter to continue
        """)
        assertEquals(listOf("1"), trust?.actions?.first()?.keys)

        val ask = """
              Question 1/1 (1 unanswered)
              Pick a fruit?

              › 1. Apple (Recommended)  Choose a crisp, sweet apple.
                2. Pear                 Choose a soft, juicy pear.
                3. None of the above    Optionally, add details in notes (tab).

              tab to add notes | enter to submit answer | esc to interrupt
        """
        assertEquals(3, detect("codex", ask)?.actions?.size)
        val focused = ask.replace(
            "tab to add notes | enter to submit answer | esc to interrupt",
            "› Add notes\n\n  tab or esc to clear notes | enter to submit answer",
        )
        assertTrue(detect("codex", focused)!!.interactionLocked)
    }

    @Test
    fun grokPermissionHidesPersistentChoiceAndFocusedWriteInLocks() {
        val permission = detect("grok", """
            ┃ Run rm -rf build?
            ┃ 1 (○) Yes, allow all edits during this session
            ┃ 2 (●) Yes
            ┃ 3 (○) No, reject (type to add feedback)
            ┃ ↑/↓ navigate Enter:submit
            1/3:select Tab:next option Ctrl+o:always-approve Ctrl+c:cancel
        """)
        assertEquals(listOf("2", "3"), permission?.actions?.map { it.keys.single() })

        val ask = detect("grok", """
            ┃ Which color theme should the dashboard use?
            ┃ 1 (○) Red  Warm red palette
            ┃ 2 (○) Green  Calm green palette
            ┃ 3 (○) Blue  Cool blue palette
            ┃ z (●) ❯ custom
            ┃ ↑/↓ navigate · Enter:edit
            Tab:next answer
        """)
        assertTrue(ask!!.interactionLocked)
    }

    @Test
    fun agyAndAntigravityShareOnlyTheirExactPromptGrammar() {
        val fixture = """
            Question 1/1: Which color theme should the dashboard use?
            > 1. Red
              2. Green
              3. Blue
              4. Write-in...
            ↑/↓ Navigate · enter Select · esc Skip
            esc to cancel Gemini 3.7 Flash · low
        """
        assertEquals(listOf("1", "Enter"), detect("agy", fixture)?.actions?.first()?.keys)
        assertEquals(SemanticKind.PROMPT_SELECT, detect("antigravity", fixture)?.kind)
        assertNull(detect("agy-preview", fixture))
        assertNull(detect("omp", fixture))
    }

    @Test
    fun tornUnknownAndOversizedScreensFailClosed() {
        val valid = """
            Which color?
            ❯ 1. Red
              2. Blue
            Enter to select · Esc to cancel
        """
        assertNull(detect("claude", valid.replace("2. Blue", "3. Blue")))
        assertNull(detect("shell", valid))
        assertNull(detect("claude", valid.replace("Which color?", "Which color? " + "x".repeat(8_193))))
    }

    @Test
    fun tailBoundedDetectionPreservesFullBufferProjectionOffsets() {
        val prefix = (1..500).joinToString("\n") { "completed output row $it" }
        val dialog = """
            Which color?
            ❯ 1. Red
              2. Blue
            Enter to select · Esc to cancel
        """.trimIndent()
        val raw = "$prefix\n$dialog"
        val surface = AgentSemanticParser.detect("claude", raw, 52)!!

        val projected = SemanticTerminalProjection.project(AnsiParser().parse(raw), surface, rawMode = false).toString()
        assertTrue(projected.contains("completed output row 500"))
        assertFalse(projected.contains("Which color?"))
    }

    @Test
    fun guardRejectsRevisionSignatureActionAndModalDrift() {
        val text = """
            Which color?
            ❯ 1. Red
              2. Blue
            Enter to select · Esc to cancel
        """.trimIndent()
        val displayed = detect("claude", text, 41)!!
        val action = displayed.actions.first()
        assertEquals(action, SemanticActionGuard.verify("claude", displayed, action, text, 41))
        assertNull(SemanticActionGuard.verify("claude", displayed, action, text, 42))
        assertNull(SemanticActionGuard.verify("claude", displayed, action, text.replace("Blue", "Green"), 41))
        assertNull(SemanticActionGuard.verify("claude", displayed, action.copy(keys = listOf("2")), text, 41))
        assertNull(SemanticActionGuard.verify("codex", displayed, action, text, 41))
    }

    private fun detect(agent: String, text: String, revision: Long = 41): SemanticSurface? =
        AgentSemanticParser.detect(agent, text.trimIndent(), revision)

    private fun previewFixture() = """
        ☐ Design
        Which widget design should we use?
        ❯ 1. Boxy             ┌───────────────┐
          2. Rounded          │ rounded       │
          3. Minimal          └───────────────┘
                              Notes: press n to add notes
        ─────────────────────────
          Chat about this
        Enter to select · ↑/↓ to navigate · n to add notes · Esc to cancel
    """

    private fun fixtureDirectory(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "web/src/fixtures/panes") }
        .firstOrNull(File::isDirectory)
        ?: error("Could not locate web fixture corpus")

    private fun fixture(name: String): String = File(fixtureDirectory(), name).readText()

    /** Mutate only the raw checkbox glyph after locating the row through ANSI-stripped text. */
    private fun replaceRawOmpCheckbox(source: String, label: String, replacement: String): String {
        val ansi = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")
        var offset = 0
        for (rawLine in source.splitToSequence('\n')) {
            val visible = ansi.replace(rawLine, "")
            if (visible.contains("☐ $label")) {
                val glyphOffset = rawLine.indexOf('☐')
                check(glyphOffset >= 0) { "Visible OMP checkbox row has no raw checkbox glyph" }
                return source.replaceRange(offset + glyphOffset, offset + glyphOffset + 1, replacement)
            }
            offset += rawLine.length + 1
        }
        error("Could not locate OMP checkbox row: $label")
    }

    private fun isRelevantFixture(name: String): Boolean = name in NEW_NATIVE_FIXTURES ||
        name in OMP_SWITCH_LIFTED || name in OMP_SWITCH_RAW || Regex(
        "^(?:claude--(?:select|wizard|menu|autocomplete|permission|trust|plan-approval)|" +
            "codex--(?:trust|approval|ask)|grok--(?:ask|permission|plan)|" +
            "agy--(?:permission|select|trust|plan)|omp--(?:menu|select|switch|slash-palette|v18-4-(?:ask|approval|resume|switch))).*\\.txt$",
    ).matches(name)

    private companion object {
        val NEW_NATIVE_FIXTURES = setOf(
            "omp--v18-4-ask-single.txt", "omp--v18-4-ask-single-moved.txt",
            "omp--v18-4-approval-bash.txt", "omp--v18-4-approval-bash-moved.txt",
            "muse--ask-color.txt", "muse--ask-color-moved.txt", "muse--trust-prompt.txt",
            "muse--approval-ls.txt", "muse--approval-ls-moved.txt",
            "muse--ask-toppings.txt", "muse--ask-toppings-checked.txt",
            "muse--ask-toppings-notes-open.txt", "muse--ask-toppings-review.txt",
            "oc--question--single.txt", "oc--question--single--moved.txt",
            "oc--question--single--narrow.txt", "oc--question--single--overlay.txt",
        )
        val OMP_SWITCH_LIFTED = listOf(
            "omp--v18-4-switch-moved-up.txt", "omp--v18-4-switch-moved.txt",
            "omp--v18-4-switch-narrow-moved.txt", "omp--v18-4-switch-narrow.txt",
            "omp--v18-4-switch-overcontext-moved.txt", "omp--v18-4-switch-overcontext.txt",
            "omp--v18-4-switch-ptr-fable.txt", "omp--v18-4-switch-ptr-haiku.txt",
            "omp--v18-4-switch-ptr-opus-current.txt", "omp--v18-4-switch-ptr-sonnet.txt",
            "omp--v18-4-switch-roles-chips.txt", "omp--v18-4-switch-search-short-moved.txt",
            "omp--v18-4-switch-search-short.txt", "omp--v18-4-switch-search-son.txt",
            "omp--v18-4-switch-search.txt", "omp--v18-4-switch-short-pane-scrolled.txt",
            "omp--v18-4-switch-short-pane.txt", "omp--v18-4-switch-top-edge.txt",
            "omp--v18-4-switch-top.txt", "omp--v18-4-switch-truncated-pointed.txt",
            "omp--v18-4-switch-truncated.txt", "omp--v18-4-switch-wrapped.txt", "omp--v18-4-switch.txt",
        )
        val OMP_SWITCH_RAW = listOf(
            "omp--v18-4-switch-clipped.txt", "omp--v18-4-switch-nerd.txt",
            "omp--v18-4-switch-nomatch.txt", "omp--v18-4-switch-quick-roles.txt",
            "omp--v18-4-switch-task.txt",
        )
        val CLAUDE_FOCUSED_PLAN_FIXTURES = setOf(
            "claude--plan-approval--feedback-focused.txt",
            "claude--plan-approval--three-row-focused.txt",
            "claude--plan-approval--three-row-typed-focused.txt",
        )
    }
}
