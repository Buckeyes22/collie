package com.lateapex.collie.ui.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PromptBindingTest {
    @Test
    fun bindsTheLastThreeNonBlankRenderedRows() {
        val text = "old\n\u001b[31mquestion\u001b[0m  \n\n  choice one\nchoice two   \n"

        assertEquals("question\n  choice one\nchoice two", PromptBinding.tailRegion(text))
    }

    @Test
    fun normalizesCarriageReturnsLikeTheBridge() {
        assertEquals("first\nsecond\nthird", PromptBinding.tailRegion("first\r\nsecond\rthird"))
    }

    @Test
    fun refusesBlankOrOversizedEvidence() {
        assertNull(PromptBinding.tailRegion(" \n\t\n"))
        assertNull(PromptBinding.tailRegion("x".repeat(8_193)))
    }

    @Test
    fun recognizesOnlyAnEmptyCodexComposerAnchoredByItsTailStatus() {
        val composer = "transcript\n\n› \n\n  gpt-5 · /repo · Context 50% left"
        val existingDraft = "transcript\n\n› draft text\n  wrapped words\n\n  gpt-5 · /repo · Context 50% left"
        val emptyFirstLineDraft = "transcript\n\n› \n  existing continuation\n\n  gpt-5 · /repo · Context 50% left"
        val modal = "› 1. Yes, proceed\n  2. No\n\nPress enter to confirm or esc to cancel"

        assertEquals("›", PromptBinding.composerRegion("codex", composer))
        assertNull(PromptBinding.composerRegion("codex", existingDraft))
        assertNull(PromptBinding.composerRegion("codex", emptyFirstLineDraft))
        assertNull(PromptBinding.composerRegion("codex", modal))
        val visibleTail = "transcript\n›\n  gpt-5 · /repo · Context 50% left"
        assertEquals(visibleTail, PromptBinding.composerRegion("opencode", composer))
        assertEquals(visibleTail, PromptBinding.composerRegion(null, composer))
    }

    @Test
    fun acceptsDimCodexPlaceholderButRefusesIdenticalTypedText() {
        val status = "\n\n  gpt-5 · /repo · Context 50% left"
        val dimPlaceholder = "\u001b[1m›\u001b[0m \u001b[2mAsk Codex to do anything\u001b[0m$status"
        val typedPlaceholder = "› Ask Codex to do anything$status"

        assertEquals("› Ask Codex to do anything", PromptBinding.composerRegion("codex", dimPlaceholder))
        assertNull(PromptBinding.composerRegion("codex", typedPlaceholder))
    }

    @Test
    fun acceptsCurrentPaintedCodexStatusWithRightAlignedGoalButRefusesPlainLookalike() {
        val reset = "\u001b[0m"
        val model = "\u001b[38;2;246;226;183mgpt-5.6-sol high$reset"
        val separator = "\u001b[2m · $reset"
        val cwd = "\u001b[38;2;171;223;167m~/git/collie$reset"
        val mode = "\u001b[2m · Main [default]$reset"
        val goal = "\u001b[38;5;5mGoal achieved (1h 37m)$reset"
        val prompt = "\u001b[1m›$reset \u001b[2mAsk Codex to do anything$reset"
        val painted = "transcript\n$prompt\n\n  $model$separator$cwd$mode          $goal"
        val plain = TerminalPlainText.strip(painted)

        assertEquals("› Ask Codex to do anything", PromptBinding.composerRegion("codex", painted))
        assertNull(PromptBinding.composerRegion("codex", plain))
    }

    @Test
    fun acceptsOnlyTheTwoClaudeEmptyStatesWithVisibleText() {
        val rule = "─".repeat(40)
        val placeholder = "$rule\n❯ Press up to edit queued messages\n$rule\nstatus"
        val typed = "$rule\n❯ write the tests\n$rule\nstatus"
        val dimGhost = "$rule\n❯ \u001b[2mwrite the tests\u001b[0m\n$rule\nstatus"

        assertNotNull(PromptBinding.composerRegion("claude", placeholder))
        assertNotNull(PromptBinding.composerRegion("claude", dimGhost))
        assertNull(PromptBinding.composerRegion("claude", typed))
    }

    @Test
    fun bridgeTailVerifierRejectsMovedAndNonContiguousRegions() {
        assertEquals(true, PromptBinding.verifyNearTail("old\nquestion\nanswer", "question\nanswer"))
        assertEquals(false, PromptBinding.verifyNearTail("question\nchanged\nanswer", "question\nanswer"))
        assertEquals(
            false,
            PromptBinding.verifyNearTail("question\n1\n2\n3\n4\n5\n6\nreplacement", "question"),
        )
    }

    @Test
    fun recognizesEmptyCanonicalHarnessComposersFromTheWebFixtureCorpus() {
        listOf(
            "claude" to "claude--fresh-idle.txt",
            "claude" to "claude--ghost-suggestion.txt",
            "claude" to "claude--done.txt",
            "claude" to "claude--draft-footer-empty.txt",
            "claude" to "claude--rename-resolved.txt",
            "claude" to "claude--working.txt",
            "codex" to "codex--v0150-idle.txt",
            "grok" to "grok--fresh-idle.txt",
            "grok" to "grok--done.txt",
            "grok" to "grok--startup.txt",
            "grok" to "grok--working.txt",
            "omp" to "omp--fresh-idle.txt",
            "omp" to "omp--done.txt",
            "omp" to "omp--done--tool-result.txt",
            "omp" to "omp--menu-dismissed.txt",
            "omp" to "omp--working.txt",
            "agy" to "agy--fresh-idle.txt",
            "agy" to "agy--working.txt",
            "antigravity" to "agy--done.txt",
        ).forEach { (agent, name) ->
            assertNotNull("Expected $agent composer in $name", PromptBinding.composerRegion(agent, fixture(name)))
        }
    }

    @Test
    fun refusesDraftsAndModalScreensFromEveryCanonicalHarnessFixtureFamily() {
        listOf(
            "claude" to "claude--draft-wrapped.txt",
            "claude" to "claude--draft-footer-single.txt",
            "claude" to "claude--draft-footer-wrapped.txt",
            "claude" to "claude--ghost-typed-over.txt",
            "claude" to "claude--permission-bash.txt",
            "claude" to "claude--plan-approval.txt",
            "claude" to "claude--trust-prompt.txt",
            "claude" to "claude--select-menu.txt",
            "claude" to "claude--wizard-q1.txt",
            "grok" to "grok--draft-single.txt",
            "grok" to "grok--draft-wrapped.txt",
            "grok" to "grok--permission-edit.txt",
            "grok" to "grok--plan-approval.txt",
            "grok" to "grok--plan-request-changes.txt",
            "grok" to "grok--ask-color.txt",
            "grok" to "grok--user-bubble.txt",
            "omp" to "omp--draft-single.txt",
            "omp" to "omp--draft-wrapped.txt",
            "omp" to "omp--draft-ghost-suggestion.txt",
            "omp" to "omp--slash-palette.txt",
            "omp" to "omp--menu-model.txt",
            "agy" to "agy--permission-bash.txt",
            "agy" to "agy--permission-edit.txt",
            "antigravity" to "agy--plan-approval.txt",
            "antigravity" to "agy--select-menu.txt",
            "antigravity" to "agy--trust-prompt.txt",
        ).forEach { (agent, name) ->
            assertNull("Expected $agent to refuse $name", PromptBinding.composerRegion(agent, fixture(name)))
        }
    }

    private fun fixture(name: String): String {
        val file = File("../../web/src/fixtures/panes/$name")
        check(file.isFile) { "Missing shared web fixture: ${file.absolutePath}" }
        return file.readText()
    }
}
