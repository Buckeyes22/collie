package com.lateapex.collie.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.ceil
import kotlin.math.max

/** Compact key rows wrap before shrinking any button below Android's touch-target floor. */
internal class CollieKeyRow(context: Context) : ViewGroup(context) {
    private val target = (48 * resources.displayMetrics.density + 0.5f).toInt()
    private var columns = 1
    private var rowHeights = emptyList<Int>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val visible = (0 until childCount).map(::getChildAt).filter { it.visibility != View.GONE }
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = max(1, width - paddingLeft - paddingRight)
        val capacity = max(1, available / target)
        val rows = max(1, ceil(visible.size.toDouble() / capacity).toInt())
        columns = max(1, ceil(visible.size.toDouble() / rows).toInt())
        val cellWidth = available / columns
        rowHeights = visible.chunked(columns).map { row ->
            row.forEach { child ->
                child.measure(
                    MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                )
            }
            max(target, row.maxOf { it.measuredHeight })
        }
        setMeasuredDimension(width, resolveSize(paddingTop + rowHeights.sum() + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val visible = (0 until childCount).map(::getChildAt).filter { it.visibility != View.GONE }
        val cellWidth = (width - paddingLeft - paddingRight) / columns
        var y = paddingTop
        visible.chunked(columns).forEachIndexed { rowIndex, row ->
            val height = rowHeights[rowIndex]
            row.forEachIndexed { column, child ->
                val x = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                    width - paddingRight - (column + 1) * cellWidth
                } else paddingLeft + column * cellWidth
                child.layout(x, y, x + cellWidth, y + height)
            }
            y += height
        }
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
}
