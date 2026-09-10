package com.lateapex.collie.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.HorizontalScrollView

/** The full mirror pans only when the operator explicitly turns line wrapping off. */
class TerminalHorizontalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.horizontalScrollViewStyle,
) : HorizontalScrollView(context, attrs, defStyleAttr) {
    var horizontalPanEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) scrollTo(0, 0)
        }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        horizontalPanEnabled && super.onInterceptTouchEvent(event)

    override fun onTouchEvent(event: MotionEvent): Boolean =
        horizontalPanEnabled && super.onTouchEvent(event)

    override fun scrollTo(x: Int, y: Int) {
        super.scrollTo(if (horizontalPanEnabled) x else 0, y)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (!horizontalPanEnabled) info.isScrollable = false
    }
}
