package com.lateapex.collie.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalPlainTextTest {
    @Test
    fun stripsPaintAndOscControlsWithoutChangingVisibleText() {
        assertEquals(
            "red link done",
            TerminalPlainText.strip("\u001b[31mred\u001b[0m \u001b]8;;https://example.test\u0007link\u001b]8;;\u0007 done"),
        )
    }

    @Test
    fun appliesBareCarriageReturnRedraws() {
        assertEquals("first\nreplacement", TerminalPlainText.strip("first\nold row\rreplacement"))
        assertEquals("first\n", TerminalPlainText.strip("first\r\n"))
    }
}
