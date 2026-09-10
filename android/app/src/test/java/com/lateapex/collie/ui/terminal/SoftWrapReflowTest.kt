package com.lateapex.collie.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class SoftWrapReflowTest {
    private val width = 40

    @Test
    fun joinsAFullWidthLineWithItsIndentedContinuation() {
        val first = "⏺ " + "x".repeat(38)
        val lines = listOf(first, "  continues here", "")
        assertEquals(listOf("$first continues here", ""), SoftWrapReflow.reflow(lines, width))
    }

    @Test
    fun doesNotJoinAShortLine() {
        val lines = listOf("⏺ short", "  next")
        assertEquals(lines, SoftWrapReflow.reflow(lines, width))
    }

    @Test
    fun neverJoinsTableRowsRulesFencesOrBullets() {
        val full = "│" + "a".repeat(38) + "│"
        val rule = "─".repeat(40)
        val fenceFull = "`".repeat(3) + "k".repeat(37)
        val bulletFull = "x".repeat(40)
        val lines = listOf(full, "│ b │", rule, "  c", fenceFull, "  code", bulletFull, "  • bullet", bulletFull, "  1. item")
        assertEquals(lines, SoftWrapReflow.reflow(lines, width))
    }

    @Test
    fun gridWidthIsTheLongestVisibleLine() {
        assertEquals(12, SoftWrapReflow.gridWidth(listOf("ab", "twelve chars", "")))
    }

    @Test
    fun joinsAcrossThreeRows() {
        val a = "y".repeat(40); val b = "  " + "z".repeat(38)
        assertEquals(listOf("$a ${b.trim()} tail"), SoftWrapReflow.reflow(listOf(a, b, "  tail"), width))
    }
}
