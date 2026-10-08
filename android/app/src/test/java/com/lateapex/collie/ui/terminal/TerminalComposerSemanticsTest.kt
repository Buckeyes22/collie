package com.lateapex.collie.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TerminalComposerSemanticsTest {
    @Test
    fun claudeGhostSuggestionIsNotADraftButATypedLineIs() {
        val rule = "─".repeat(40)
        val ghost = "$rule\n❯ \u001b[0m\u001b[2mfix it\u001b[0m\n$rule\n  operator@host:~ \n"
        assertNull(TerminalComposerSemantics.terminalDraft("claude", ghost))

        val typed = "$rule\n❯ fix it\n$rule\n  operator@host:~ \n"
        assertEquals("fix it", TerminalComposerSemantics.terminalDraft("claude", typed)?.text)
    }

    @Test
    fun noEchoRecognitionIsTailBoundAndNamesTheExactPrompt() {
        assertEquals(
            "[sudo] password for altan:",
            TerminalComposerSemantics.noEchoPrompt("$ sudo -v\n[sudo] password for altan:"),
        )
        assertEquals("altan@host's password:", TerminalComposerSemantics.noEchoPrompt("altan@host's password:"))
        assertEquals("Password (again):", TerminalComposerSemantics.noEchoPrompt("Sorry, try again.\nPassword (again):\n"))
        assertNull(TerminalComposerSemantics.noEchoPrompt("Password:\nok\n$ ls"))
        assertNull(TerminalComposerSemantics.noEchoPrompt("Reading the password from the environment instead."))
    }

    @Test
    fun claudeDraftRequiresACompleteTailBoxAndMarksOpaqueTokens() {
        val rule = "─".repeat(40)
        val draft = TerminalComposerSemantics.terminalDraft(
            "claude",
            "prior\n$rule\n❯ continue with the implementation\n$rule\n  Opus · /tmp",
        )
        assertEquals("continue with the implementation", draft?.text)
        assertFalse(draft!!.opaque)

        val opaque = TerminalComposerSemantics.terminalDraft(
            "claude",
            "$rule\n❯ [Pasted text #1 +3 lines]\n$rule",
        )
        assertTrue(opaque!!.opaque)
        assertNull(TerminalComposerSemantics.terminalDraft("claude", "❯ transcript without a box"))
        assertNull(TerminalComposerSemantics.terminalDraft("claude", "$rule\n❯ Press up to edit queued messages\n$rule"))
    }

    @Test
    fun composerAnalysisIgnoresLargeCompletedScrollback() {
        val prefix = (1..800).joinToString("\n") { "completed output row $it" }
        val rule = "─".repeat(40)
        val raw = "$prefix\n$rule\n❯ continue with the implementation\n$rule\n  Opus · /tmp"

        assertEquals(
            "continue with the implementation",
            TerminalComposerSemantics.terminalDraft("claude", raw)?.text,
        )
    }

    @Test
    fun stableDraftNeedsTwoObservationsAcrossTheMinimumAgeAndHandledTextStaysHidden() {
        var time = 0L
        val tracker = StableTerminalDraft { time }
        val draft = TerminalDraft("continue   here")
        assertNull(tracker.observe(draft))
        time = StableTerminalDraft.STABLE_MIN_AGE_MS - 1
        assertNull(tracker.observe(TerminalDraft(" continue here ")))
        time++
        assertEquals(" continue here ", tracker.observe(TerminalDraft(" continue here "))?.text)
        tracker.markHandled(draft)
        assertNull(tracker.observe(draft))
        time += StableTerminalDraft.STABLE_MIN_AGE_MS
        assertNull(tracker.observe(draft))

        assertNull(tracker.observe(TerminalDraft("different")))
        time += StableTerminalDraft.STABLE_MIN_AGE_MS
        assertEquals("different", tracker.observe(TerminalDraft("different"))?.text)
        assertNull(tracker.observe(null))
    }
}
