package com.lateapex.collie.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TerminalHorizontalScrollViewTest {
    private val longLine = (1..200).joinToString(" ") { "col$it" }

    private fun host(pan: Boolean): Pair<TerminalHorizontalScrollView, TextView> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scroll = TerminalHorizontalScrollView(context)
        val text = TextView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                if (pan) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setHorizontallyScrolling(pan)
            setText(longLine)
        }
        scroll.addView(text)
        scroll.isFillViewport = !pan
        scroll.horizontalPanEnabled = pan
        scroll.measure(
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST),
        )
        scroll.layout(0, 0, 600, scroll.measuredHeight)
        return scroll to text
    }

    @Test
    fun wrappedMirrorIsMeasuredAtTheViewportWidthSoLongLinesWrap() {
        val (_, text) = host(pan = false)
        // Robolectric's legacy text stack never breaks a line, so the wrap itself is verified on a
        // device; what this pins is the measure contract that makes wrapping possible at all.
        assertEquals(600, text.measuredWidth)
        assertEquals(600, text.layout.width)
    }

    @Test
    fun pannableMirrorKeepsItsNaturalWidth() {
        val (scroll, text) = host(pan = true)
        assertTrue(text.measuredWidth > 600)
        assertTrue(scroll.horizontalPanEnabled)
    }
}
