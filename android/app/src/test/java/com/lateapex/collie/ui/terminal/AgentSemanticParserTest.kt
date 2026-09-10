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
        val expected = mapOf(
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
            "omp--menu-resume-moved.txt" to null,
            "omp--menu-resume.txt" to null,
            "omp--menu-settings-moved.txt" to null,
            "omp--menu-settings.txt" to null,
            "omp--select-menu-moved.txt" to null,
            "omp--select-menu.txt" to null,
            "omp--select-multi-checked.txt" to null,
            "omp--select-multi-review.txt" to null,
            "omp--select-multi.txt" to null,
            "omp--slash-palette--filtered.txt" to null,
            "omp--slash-palette.txt" to null,
        )
        val directory = fixtureDirectory()
        val relevant = directory.listFiles().orEmpty().map(File::getName).filter(::isRelevantFixture).sorted()
        assertEquals("A new interactive fixture must be explicitly classified here", expected.keys.sorted(), relevant)
        expected.forEach { (name, kind) ->
            val agent = name.substringBefore("--")
            val surface = AgentSemanticParser.detect(agent, File(directory, name).readText(), 41)
            assertEquals(name, kind, surface?.kind)
            if (name == "codex--ask-notes-focused.txt" || name.startsWith("grok--ask-multi") ||
                name in CLAUDE_FOCUSED_PLAN_FIXTURES
            ) {
                assertTrue("$name must fail closed", surface!!.interactionLocked)
            }
            if (agent == "agy") {
                val alias = AgentSemanticParser.detect("antigravity", File(directory, name).readText(), 41)
                assertEquals("Antigravity alias: $name", kind, alias?.kind)
            }
        }
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

    private fun isRelevantFixture(name: String): Boolean = Regex(
        "^(?:claude--(?:select|wizard|menu|autocomplete|permission|trust|plan-approval)|" +
            "codex--(?:trust|approval|ask)|grok--(?:ask|permission|plan)|" +
            "agy--(?:permission|select|trust|plan)|omp--(?:menu|select|slash-palette)).*\\.txt$",
    ).matches(name)

    private companion object {
        val CLAUDE_FOCUSED_PLAN_FIXTURES = setOf(
            "claude--plan-approval--feedback-focused.txt",
            "claude--plan-approval--three-row-focused.txt",
            "claude--plan-approval--three-row-typed-focused.txt",
        )
    }
}
