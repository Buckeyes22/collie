package com.lateapex.collie.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CodexCompatibilityTest {
    @Test
    fun currentIdleBusyHeadlessGoalAndFullscreenComposersAreWritable() {
        listOf(
            "v0156-idle", "v0156-headless-idle", "v0156-busy-streaming",
            "v0157-idle", "v0157-idle-50", "v0157-busy-streaming", "v0158-goal-notice",
            "v0154-submitted-fill",
        ).forEach { name ->
            val text = fixture(name)
            assertNotNull(name, PromptBinding.composerRegion("codex", text))
            assertNull(name, TerminalComposerSemantics.terminalDraft("codex", text))
        }
    }

    @Test
    fun currentDraftsAreRecognizedButCannotAcceptAnotherMessage() {
        listOf("v0156-draft-multiline", "v0156-draft-blank-line", "v0156-busy-draft", "v0157-draft-notice")
            .forEach { name ->
                val text = fixture(name)
                assertNotNull(name, TerminalComposerSemantics.terminalDraft("codex", text))
                assertNull(name, PromptBinding.composerRegion("codex", text))
            }
    }

    @Test
    fun newDialogsStillRefuseComposerWrites() {
        listOf("v0156-trust", "v0156-approval-exec-2opt", "v0156-approval-patch").forEach { name ->
            assertNull(name, PromptBinding.composerRegion("codex", fixture(name)))
            assertNull(name, TerminalComposerSemantics.terminalDraft("codex", fixture(name)))
        }
    }

    @Test
    fun plainLookalikesMixedSeparatorsAndTranscriptEchoesAreRefused() {
        val status = "  \u001b[31mmodel\u001b[0m\u001b[90m · \u001b[0m\u001b[32m/repo\u001b[0m"
        assertNull(PromptBinding.composerRegion("codex", "›\n\n  model · /repo"))
        assertNull(PromptBinding.composerRegion("codex", "› old message\n• agent response\n$status"))
        assertNull(PromptBinding.composerRegion("codex", "›\n$status\n\n  ? for shortcuts"))
        assertEquals(false, CodexStatusRow.matches(status + "\u001b[2m · \u001b[0m\u001b[33mbranch\u001b[0m"))
        assertEquals(false, CodexStatusRow.matches("  \u001b[90mmodel · /repo\u001b[0m"))
    }

    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/codex/codex--$name.txt"))
        .readText().replace("\\u001b", "\u001b")
}
