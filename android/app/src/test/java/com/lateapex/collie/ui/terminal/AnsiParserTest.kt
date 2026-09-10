package com.lateapex.collie.ui.terminal

import android.graphics.Color
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnsiParserTest {
    private val parser = AnsiParser()

    @Test
    fun stripsControlsAndKeepsVisibleText() {
        val parsed = parser.parse("plain \u001b[31mred\u001b[0m \u001b]0;title\u0007done")
        assertEquals("plain red done", parsed.toString())
        val spans = (parsed as Spanned).getSpans(0, parsed.length, ForegroundColorSpan::class.java)
        assertEquals(1, spans.size)
    }

    @Test
    fun supportsBoldAndTrueColour() {
        val parsed = parser.parse("\u001b[1;38;2;1;2;3mvalue\u001b[0m") as Spanned
        assertEquals("value", parsed.toString())
        assertTrue(parsed.getSpans(0, parsed.length, StyleSpan::class.java).isNotEmpty())
        assertTrue(parsed.getSpans(0, parsed.length, ForegroundColorSpan::class.java).isNotEmpty())
    }

    @Test
    fun carriageReturnKeepsLastVisualWrite() {
        val parsed = parser.parse("progress 10%\rprogress 20%\nready")
        assertEquals("progress 20%\nready", parsed.toString())
        assertFalse(parsed.toString().contains("10%"))
    }

    @Test
    fun crlfPreservesEveryVisualLine() {
        assertEquals("first\nsecond\n", parser.parse("first\r\nsecond\r\n").toString())
    }

    @Test
    fun normalAndBrightPaletteMatchesTheWebMirror() {
        val expected = listOf(
            "#000000", "#CD3131", "#0DBC79", "#E5E510",
            "#2472C8", "#BC3FBC", "#11A8CD", "#E5E5E5",
            "#666666", "#F14C4C", "#23D18B", "#F5F543",
            "#3B8EEA", "#D670D6", "#29B8DB", "#FFFFFF",
        ).map(Color::parseColor)

        expected.forEachIndexed { index, colour ->
            val code = if (index < 8) 30 + index else 90 + index - 8
            val parsed = parser.parse("\u001b[${code}mx") as Spanned
            val span = parsed.getSpans(0, parsed.length, ForegroundColorSpan::class.java).single()
            assertEquals("ANSI slot $index", colour, span.foregroundColor)
        }
    }

    @Test
    fun indexedBaseColoursUseTheSamePaletteAsBasicSgr() {
        for (index in 0..15) {
            val parsed = parser.parse("\u001b[38;5;${index}mx") as Spanned
            val indexed = parsed.getSpans(0, parsed.length, ForegroundColorSpan::class.java).single()
            val basicCode = if (index < 8) 30 + index else 90 + index - 8
            val basic = parser.parse("\u001b[${basicCode}mx") as Spanned
                val basicSpan = basic.getSpans(0, basic.length, ForegroundColorSpan::class.java).single()
            assertEquals("indexed ANSI slot $index", basicSpan.foregroundColor, indexed.foregroundColor)
        }
    }

    @Test
    fun inverseUsesTheFixedWebMirrorGround() {
        val parsed = parser.parse("\u001b[7mx") as Spanned
        val foreground = parsed.getSpans(0, parsed.length, ForegroundColorSpan::class.java).single()
        val background = parsed.getSpans(0, parsed.length, BackgroundColorSpan::class.java).single()

        assertEquals(Color.parseColor("#0A0A0A"), foreground.foregroundColor)
        assertEquals(Color.parseColor("#FAFAFA"), background.backgroundColor)
    }

    @Test
    fun dimAppliesWebOpacityToDefaultForeground() {
        val parsed = parser.parse("\u001b[2mx") as Spanned
        val foreground = parsed.getSpans(0, parsed.length, ForegroundColorSpan::class.java).single()

        assertEquals((255 * 0.6f).toInt(), Color.alpha(foreground.foregroundColor))
        assertEquals(250, Color.red(foreground.foregroundColor))
    }
}
