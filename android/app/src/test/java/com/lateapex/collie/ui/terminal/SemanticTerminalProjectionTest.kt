package com.lateapex.collie.ui.terminal

import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.graphics.Typeface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SemanticTerminalProjectionTest {
    @Test
    fun semanticModeReplacesOnlyDetectedRegionAndKeepsPrefixSpans() {
        val parsed = SpannableString("prior output\nQuestion?\n1. Yes\n2. No\nfooter")
        parsed.setSpan(StyleSpan(Typeface.BOLD), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val surface = SemanticSurface(
            kind = SemanticKind.PROMPT_SELECT,
            title = "Question?",
            actions = listOf(SemanticAction("yes", "Yes", listOf("1"))),
            regionSignature = "Question?\n1. Yes\n2. No\nfooter",
            revision = 1,
            startLine = 1,
            endLine = 4,
        )

        val projected = SemanticTerminalProjection.project(parsed, surface, rawMode = false)

        assertEquals("prior output\n", projected.toString())
        assertTrue((projected as Spanned).getSpans(0, projected.length, StyleSpan::class.java).isNotEmpty())
    }

    @Test
    fun rawModeIsAnExactEscapeHatch() {
        val parsed = SpannableString("prior\nmenu")
        val surface = SemanticSurface(
            SemanticKind.MENU,
            "Menu",
            regionSignature = "menu",
            revision = 1,
            startLine = 1,
            endLine = 1,
        )

        assertSame(parsed, SemanticTerminalProjection.project(parsed, surface, rawMode = true))
    }
}
