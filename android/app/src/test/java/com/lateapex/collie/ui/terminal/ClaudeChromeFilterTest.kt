package com.lateapex.collie.ui.terminal

import android.text.Spanned
import android.text.style.ForegroundColorSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClaudeChromeFilterTest {
    private val parser = AnsiParser()
    private val filter = ClaudeChromeFilter()
    private val rule = "─".repeat(120)

    @Test
    fun removesCompleteComposerAndAgentFooterButResurfacesStyledStatus() {
        val parsed = parser.parse(
            listOf(
                "last real output",
                "329780 tokens",
                "",
                rule,
                "❯\u00a0",
                rule,
                "\u001b[32mchris@ed8:/home/chris/git/prometheus\u001b[0m",
                "\u001b[31mbypass permissions on\u001b[0m · 1 shell, 1 monitor · ← for agents",
                "",
                "● main",
                "◯ subagent-model-routing-claude Appending observation 9m 36s · ↓ 149.5k tokens",
            ).joinToString("\n"),
        )

        val result = filter.filter(parsed)

        assertEquals(
            "last real output\n329780 tokens\nchris@ed8:/home/chris/git/prometheus\n" +
                "bypass permissions on · 1 shell, 1 monitor · ← for agents",
            result.toString(),
        )
        assertFalse(result.toString().contains("❯"))
        assertFalse(result.toString().contains("● main"))
        assertTrue(
            (result as Spanned).getSpans(0, result.length, ForegroundColorSpan::class.java).size >= 2,
        )
    }

    @Test
    fun collapsesTheBlankPaddingClaudePinsAboveItsBox() {
        val parsed = parser.parse(
            (listOf("❯ Reply with PONG", "", "● PONG", "", "✻ Cooked for 2s") +
                List(46) { "" } +
                listOf("66083 tokens", rule, "❯\u00a0", rule, "chris@ed8:/home/chris/git/collie", "bypass permissions on")
            ).joinToString("\n"),
        )

        val result = filter.filter(parsed).toString()

        assertEquals(
            "❯ Reply with PONG\n\n● PONG\n\n✻ Cooked for 2s\n\n\n66083 tokens\nchris@ed8:/home/chris/git/collie\nbypass permissions on",
            result,
        )
    }

    @Test
    fun stripsWrappedDraftOnlyWhenTheFullBoxShapeIsPresent() {
        val parsed = parser.parse(
            listOf(
                "answer",
                rule,
                "❯ a long draft",
                "  that wrapped",
                rule,
                "status",
            ).joinToString("\n"),
        )

        assertEquals("answer\nstatus", filter.filter(parsed).toString())
    }

    @Test
    fun acceptsTheLabelledTopBorderClaudeRenders() {
        val parsed = parser.parse(
            listOf(
                "answer",
                "──── session label ──",
                "❯\u00a0",
                rule,
                "status",
            ).joinToString("\n"),
        )

        assertEquals("answer\nstatus", filter.filter(parsed).toString())
    }

    @Test
    fun leavesMenusAndIncompleteShapesUntouched() {
        val parsed = parser.parse(
            listOf(
                "Choose a model",
                "  1. Opus",
                "  2. Sonnet",
                "Enter to confirm",
                rule,
                "status",
            ).joinToString("\n"),
        )

        assertSame(parsed, filter.filter(parsed))
    }

    @Test
    fun leavesOverlongStatusRunsUntouched() {
        val parsed = parser.parse(
            buildList {
                add("answer")
                add(rule)
                add("❯\u00a0")
                add(rule)
                repeat(9) { add("status $it") }
            }.joinToString("\n"),
        )

        assertSame(parsed, filter.filter(parsed))
    }

    @Test
    fun doesNotDiscardAnUnrecognisedRunBelowTheBlankSeparator() {
        val parsed = parser.parse(
            listOf(
                "answer",
                rule,
                "❯\u00a0",
                rule,
                "status",
                "",
                "ordinary terminal output",
            ).joinToString("\n"),
        )

        assertSame(parsed, filter.filter(parsed))
    }
}
