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
                "\u001b[32moperator@host:/home/operator/git/app\u001b[0m",
                "\u001b[31mbypass permissions on\u001b[0m · 1 shell, 1 monitor · ← for agents",
                "",
                "● main",
                "◯ subagent-model-routing-claude Appending observation 9m 36s · ↓ 149.5k tokens",
            ).joinToString("\n"),
        )

        val result = filter.split(parsed)

        assertEquals(
            "last real output\n329780 tokens",
            result.body.toString(),
        )
        assertEquals(
            listOf(
                "operator@host:/home/operator/git/app",
                "bypass permissions on · 1 shell, 1 monitor · ← for agents",
            ),
            result.statusRows.map { it.toString() },
        )
        assertFalse(result.body.toString().contains("❯"))
        assertFalse(result.body.toString().contains("● main"))
        assertTrue(
            result.statusRows.all { row ->
                (row as Spanned).getSpans(0, row.length, ForegroundColorSpan::class.java).isNotEmpty()
            },
        )
    }

    @Test
    fun collapsesTheBlankPaddingClaudePinsAboveItsBox() {
        val parsed = parser.parse(
            (listOf("❯ Reply with PONG", "", "● PONG", "", "✻ Cooked for 2s") +
                List(46) { "" } +
                listOf("66083 tokens", rule, "❯\u00a0", rule, "operator@host:/home/operator/git/collie", "bypass permissions on")
            ).joinToString("\n"),
        )

        val result = filter.split(parsed)

        assertEquals(
            "❯ Reply with PONG\n\n● PONG\n\n✻ Cooked for 2s\n\n\n66083 tokens",
            result.body.toString(),
        )
        assertEquals(
            listOf("operator@host:/home/operator/git/collie", "bypass permissions on"),
            result.statusRows.map { it.toString() },
        )
    }

    @Test
    fun collapsesPaddingUnderADialogWhenNoBoxIsAtTheTail() {
        val parsed = parser.parse(
            (listOf("❯ 1. Red", "  2. Green", "Enter to select · ↑/↓ to navigate · Esc to cancel") + List(30) { "" })
                .joinToString("\n"),
        )

        assertEquals(
            "❯ 1. Red\n  2. Green\nEnter to select · ↑/↓ to navigate · Esc to cancel\n\n",
            filter.filter(parsed).toString(),
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

        assertEquals("answer", filter.filter(parsed).toString())
        assertEquals(listOf("status"), filter.split(parsed).statusRows.map { it.toString() })
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

        assertEquals("answer", filter.filter(parsed).toString())
        assertEquals(listOf("status"), filter.split(parsed).statusRows.map { it.toString() })
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

    @Test
    fun filterReflowsBodySoftWrapsButNotStatusRows() {
        val body1 = "⏺ " + "a".repeat(28)
        val body2 = "  tail"
        val rule = "─".repeat(30)
        val status = "  ~/repo main " + "·".repeat(16)
        val input = listOf(body1, body2, rule, "❯ ", rule, status).joinToString("\n")
        val split = ClaudeChromeFilter().split(input)
        assertEquals("$body1 tail", split.body.toString().lines()[0])
        assertEquals(listOf(status.trim()), split.statusRows.map { it.toString() })
        assertFalse(split.body.toString().contains("~/repo"))
    }

    @Test
    fun filterWithReflowOffKeepsRows() {
        val body1 = "⏺ " + "a".repeat(28)
        val input = listOf(body1, "  tail").joinToString("\n")
        assertEquals(input, ClaudeChromeFilter().filter(input, reflow = false).toString())
    }

    @Test
    fun splitPeelsStatusRowsOffTheBody() {
        val body = "⏺ hello"
        val rule = "─".repeat(30)
        val box = listOf(rule, "❯ ", rule)
        val status1 = "  Opus 5 · 12% ctx"; val status2 = "  ~/repo main"
        val split = ClaudeChromeFilter().split((listOf(body) + box + listOf(status1, status2)).joinToString("\n"))
        assertEquals(body, split.body.toString())
        assertEquals(listOf(status1.trim(), status2.trim()), split.statusRows.map { it.toString() })
    }

    @Test
    fun reflowSurvivesTrailingGridPaddingOnUnjoinedRows() {
        // Herdr pads every grid row to the terminal width. Before 2026-09-10 the filter
        // re-derived the join groups by text and walked off the end of the row list.
        val width = 30
        val rows = listOf(
            "⏺ short row" + " ".repeat(19),
            "⏺ " + "a".repeat(28),
            "  tail" + " ".repeat(24),
            "plain" + " ".repeat(25),
        )
        val out = ClaudeChromeFilter().filter(rows.joinToString("\n")).toString().lines()
        assertEquals(3, out.size)
        assertEquals("⏺ " + "a".repeat(28) + " tail", out[1])
        assertEquals("plain" + " ".repeat(25), out[2])
    }
}
