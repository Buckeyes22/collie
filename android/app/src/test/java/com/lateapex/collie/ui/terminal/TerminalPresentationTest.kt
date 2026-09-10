package com.lateapex.collie.ui.terminal

import android.graphics.Color
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.URLSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TerminalPresentationTest {
    @Test
    fun linksOnlyExplicitHttpSchemesAndTrimsProsePunctuation() {
        val source = "See (https://x.dev/a_(b)), http://example.test/z. www.fake.test javascript:bad"
        val links = TerminalLinks.find(source)

        assertEquals(listOf("https://x.dev/a_(b)", "http://example.test/z"), links.map { it.href })
        val linked = TerminalLinks.apply(source) as Spanned
        assertEquals(2, linked.getSpans(0, linked.length, URLSpan::class.java).size)
    }

    @Test
    fun linkificationRetainsAnsiColourSpans() {
        val parsed = AnsiParser().parse("\u001b[31mhttps://example.test\u001b[0m")
        val linked = TerminalLinks.apply(parsed) as Spanned

        assertEquals(1, linked.getSpans(0, linked.length, URLSpan::class.java).size)
        assertEquals(1, linked.getSpans(0, linked.length, ForegroundColorSpan::class.java).size)
    }

    @Test
    fun lightMirrorInvertsGroundAndKeepsNonColourSpans() {
        assertEquals(Color.rgb(245, 245, 245), TerminalColourSpace.invertHue(Color.rgb(10, 10, 10)))
        assertEquals(Color.rgb(5, 5, 5), TerminalColourSpace.invertHue(Color.rgb(250, 250, 250)))

        val transformed = TerminalLinks.apply(
            AnsiParser().parse("\u001b[31mhttps://example.test\u001b[0m", TerminalColourSpace::invertHue),
        ) as Spanned
        assertTrue(transformed.getSpans(0, transformed.length, URLSpan::class.java).isNotEmpty())
        assertTrue(transformed.getSpans(0, transformed.length, ForegroundColorSpan::class.java).isNotEmpty())
    }
}
