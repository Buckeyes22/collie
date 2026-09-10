package com.lateapex.collie.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.HorizontalScrollView

/**
 * The full mirror pans only when the operator explicitly turns line wrapping off.
 *
 * A HorizontalScrollView measures its child with an UNSPECIFIED width, so a wrapping TextView
 * inside it lays out at its longest line and the viewport clips the rest: with "Wrap lines" on,
 * every column past the screen edge was unreachable. While panning is off the child is measured
 * at exactly the viewport width, which is what lets the TextView wrap at all.
 */
class TerminalHorizontalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.horizontalScrollViewStyle,
) : HorizontalScrollView(context, attrs, defStyleAttr) {
    var horizontalPanEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) scrollTo(0, 0)
            requestLayout()
        }

    override fun measureChild(child: View, parentWidthMeasureSpec: Int, parentHeightMeasureSpec: Int) {
        if (horizontalPanEnabled) return super.measureChild(child, parentWidthMeasureSpec, parentHeightMeasureSpec)
        val lp = child.layoutParams
        child.measure(
            MeasureSpec.makeMeasureSpec(viewportWidth(parentWidthMeasureSpec), MeasureSpec.EXACTLY),
            getChildMeasureSpec(parentHeightMeasureSpec, paddingTop + paddingBottom, lp.height),
        )
    }

    override fun measureChildWithMargins(
        child: View,
        parentWidthMeasureSpec: Int,
        widthUsed: Int,
        parentHeightMeasureSpec: Int,
        heightUsed: Int,
    ) {
        if (horizontalPanEnabled) {
            return super.measureChildWithMargins(child, parentWidthMeasureSpec, widthUsed, parentHeightMeasureSpec, heightUsed)
        }
        val lp = child.layoutParams as MarginLayoutParams
        val width = viewportWidth(parentWidthMeasureSpec) - lp.leftMargin - lp.rightMargin - widthUsed
        child.measure(
            MeasureSpec.makeMeasureSpec(width.coerceAtLeast(0), MeasureSpec.EXACTLY),
            getChildMeasureSpec(
                parentHeightMeasureSpec,
                paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin + heightUsed,
                lp.height,
            ),
        )
    }

    private fun viewportWidth(parentWidthMeasureSpec: Int): Int =
        (MeasureSpec.getSize(parentWidthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)

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
