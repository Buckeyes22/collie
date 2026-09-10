package com.lateapex.collie.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalTableRunsTest {
    @Test
    fun markdownTableBecomesOneRunWithoutClaimingSurroundingProse() {
        val text = "before\n| Name | Value |\n| --- | ---: |\n| alpha | 123456 |\nafter"
        val run = TerminalTableRuns.find(text).single()

        assertEquals("| Name | Value |\n| --- | ---: |\n| alpha | 123456 |\n", text.substring(run.start, run.endExclusive))
    }

    @Test
    fun asciiAndCrossAnchoredBoxTablesAreRecognized() {
        assertTrue(TerminalTableRuns.find("+---+---+\n| a | b |\n+---+---+\n").isNotEmpty())
        assertTrue(TerminalTableRuns.find("┌───┬───┐\n│ a │ b │\n├───┼───┤\n│ c │ d │\n└───┴───┘").isNotEmpty())
    }

    @Test
    fun ordinaryPipeProseAndSingleColumnChromeAreNotTables() {
        assertTrue(TerminalTableRuns.find("one | incidental pipe\ntwo | another").isEmpty())
        assertTrue(TerminalTableRuns.find("╭────╮\n│ >  │\n╰────╯").isEmpty())
    }
}
