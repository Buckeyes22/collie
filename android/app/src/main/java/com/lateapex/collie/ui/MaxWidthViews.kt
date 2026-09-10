package com.lateapex.collie.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.ScrollView
import com.lateapex.collie.R
import kotlin.math.min

/** Native equivalent of the web route column's `w-full max-w-screen-sm`. */
class CollieMaxWidthLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(cappedWidth(widthMeasureSpec), heightMeasureSpec)
    }

    private fun cappedWidth(spec: Int): Int {
        val cap = resources.getDimensionPixelSize(R.dimen.collie_content_max_width)
        val size = min(MeasureSpec.getSize(spec), cap)
        return MeasureSpec.makeMeasureSpec(size, MeasureSpec.getMode(spec))
    }
}

/** Native equivalent of the web pane/history route's `w-full md:max-w-screen-md`. */
class CollieWideMaxWidthLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = resources.getDimensionPixelSize(R.dimen.collie_wide_content_max_width)
        val size = min(MeasureSpec.getSize(widthMeasureSpec), cap)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(size, MeasureSpec.getMode(widthMeasureSpec)),
            heightMeasureSpec,
        )
    }
}

/** Scrollable counterpart used by the pairing route. */
class CollieMaxWidthScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ScrollView(context, attrs, defStyleAttr) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = resources.getDimensionPixelSize(R.dimen.collie_content_max_width)
        val size = min(MeasureSpec.getSize(widthMeasureSpec), cap)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(size, MeasureSpec.getMode(widthMeasureSpec)),
            heightMeasureSpec,
        )
    }
}
