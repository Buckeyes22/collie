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
    fun gridWidthIgnoresFullWidthRulesAndJoinsProseWrappedShortOfThem() {
        val rule = "─".repeat(60)
        val a = "● " + "p".repeat(54); val b = "  tail"
        assertEquals(56, SoftWrapReflow.gridWidth(listOf(rule, a, b)))
        assertEquals(listOf(rule, "$a tail"), SoftWrapReflow.reflow(listOf(rule, a, b), SoftWrapReflow.gridWidth(listOf(rule, a, b))))
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

    @Test
    fun joinsWordWrappedProseThatEndsShortOfTheWidth() {
        // Claude wraps at word boundaries, so a wrapped row ends up to one word short of the
        // width; requiring a full row left live panes almost unjoined (S25 Ultra, 2026-09-10).
        val a = "⏺ The quick brown fox jumps over the"
        val b = "  lazy dog and keeps running"
        assertEquals(listOf("$a lazy dog and keeps running"), SoftWrapReflow.reflow(listOf(a, b), 40))
    }

    @Test
    fun keepsAHardBreakWhereTheNextWordWouldHaveFit() {
        val lines = listOf("⏺ Short line.", "  Next thought")
        assertEquals(lines, SoftWrapReflow.reflow(lines, 40))
    }

    @Test
    fun judgesEachSeamByItsOwnRowNotTheJoinedParagraph() {
        val a = "x".repeat(40)
        val lines = listOf(a, "  cont", "  separate short line")
        assertEquals(listOf("$a cont", "  separate short line"), SoftWrapReflow.reflow(lines, 40))
    }
}
