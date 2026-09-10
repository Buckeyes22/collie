package com.lateapex.collie.ui.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexStatusRowTest {
    @Test
    fun recognizesContextTextAndNewPaintedRows() {
        assertTrue(CodexStatusRow.matches("  gpt-5 · /repo · Context 50% left"))
        assertTrue(
            CodexStatusRow.matches(
                "  \u001b[38;5;3mgpt-5.6-sol high\u001b[0m" +
                    "\u001b[2m · \u001b[0m\u001b[38;5;2m~/git/collie\u001b[0m" +
                    "\u001b[2m · Main [default]\u001b[0m      \u001b[38;5;5mGoal achieved\u001b[0m",
            ),
        )
    }

    @Test
    fun refusesUnstyledAndIncompleteNewRows() {
        assertFalse(CodexStatusRow.matches("  gpt-5.6-sol high · ~/git/collie · Main [default]"))
        assertFalse(CodexStatusRow.matches("  \u001b[38;5;3mgpt-5.6-sol high\u001b[0m\u001b[2m · Main [default]\u001b[0m"))
    }
}
