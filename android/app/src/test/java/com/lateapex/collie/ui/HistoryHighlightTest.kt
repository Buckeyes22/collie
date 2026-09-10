package com.lateapex.collie.ui

import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryHighlightTest {
    @Test
    fun everyExactMatchIsPaintedCurrentIsDistinctAndExistingSpansSurvive() {
        val source = SpannableString("Needle then needle").apply {
            setSpan(StyleSpan(Typeface.ITALIC), 0, length, 0)
        }
        val matches = OutputFind.matches(source, "needle")

        val rendered = HistoryHighlight.render(source, matches, 1, 0x11, 0x22)

        assertEquals(1, rendered.getSpans(0, rendered.length, StyleSpan::class.java).size)
        val backgrounds = rendered.getSpans(0, rendered.length, BackgroundColorSpan::class.java)
        assertEquals(3, backgrounds.size)
        assertEquals(2, backgrounds.count { it.backgroundColor == 0x11 })
        assertEquals(1, backgrounds.count { it.backgroundColor == 0x22 })
        val current = backgrounds.single { it.backgroundColor == 0x22 }
        assertEquals(12, rendered.getSpanStart(current))
        assertEquals(18, rendered.getSpanEnd(current))
    }
}
