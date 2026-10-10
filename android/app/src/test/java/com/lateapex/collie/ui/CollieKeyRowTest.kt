package com.lateapex.collie.ui

import android.view.View
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CollieKeyRowTest {
    @Test
    fun narrowRowsKeepEveryKeyReachableWithoutShrinkingTouchTargets() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val density = context.resources.displayMetrics.density
        val floor = (48 * density + 0.5f).toInt()
        for (widthDp in listOf(280, 300, 340, 373, 640)) {
            val row = CollieKeyRow(context)
            for (label in listOf("Esc", "Tab", "Up", "Down", "Left", "Right", "Enter")) {
                row.addView(Button(context).apply { text = label })
            }
            val width = (widthDp * density + 0.5f).toInt()
            row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            row.layout(0, 0, row.measuredWidth, row.measuredHeight)
            for (index in 0 until row.childCount) {
                val key = row.getChildAt(index)
                assertTrue("$widthDp dp key width", key.width >= floor)
                assertTrue("$widthDp dp key height", key.height >= floor)
                assertTrue("$widthDp dp key reachable", key.left >= 0 && key.right <= row.width &&
                    key.top >= 0 && key.bottom <= row.height)
                for (previous in 0 until index) {
                    val other = row.getChildAt(previous)
                    assertTrue("$widthDp dp keys must not overlap", key.left >= other.right ||
                        key.right <= other.left || key.top >= other.bottom || key.bottom <= other.top)
                }
            }
        }
    }
}
