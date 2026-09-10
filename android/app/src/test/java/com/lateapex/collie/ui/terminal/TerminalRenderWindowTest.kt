package com.lateapex.collie.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalRenderWindowTest {
    private val marker = "… earlier output omitted …"

    @Test
    fun ordinaryTerminalContentIsUnchanged() {
        assertEquals("one\ntwo\nthree", TerminalRenderWindow.limit("one\ntwo\nthree", marker))
    }

    @Test
    fun hugeBuffersKeepTheNewestBoundedTailAndNameTheOmission() {
        val raw = (1..2_000).joinToString("\n") { row -> "row $row ${"x".repeat(80)}" }
        val limited = TerminalRenderWindow.limit(raw, marker)

        assertTrue(limited.startsWith(marker))
        assertTrue(limited.contains("row 2000"))
        assertTrue(limited.length <= TerminalRenderWindow.MAX_RENDER_CHARS + marker.length + 1)
    }

    @Test
    fun aSinglePathologicalRowKeepsBothUsefulEdges() {
        val raw = "BEGIN-${"x".repeat(10_000)}-END"
        val limited = TerminalRenderWindow.limit(raw, marker)

        assertTrue(limited.startsWith(marker))
        assertTrue(limited.contains("BEGIN-"))
        assertTrue(limited.endsWith("-END"))
        assertTrue(limited.length < TerminalRenderWindow.MAX_LINE_CHARS + marker.length + 4)
    }
}
