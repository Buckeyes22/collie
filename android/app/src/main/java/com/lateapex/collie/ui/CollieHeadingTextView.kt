package com.lateapex.collie.ui

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.ViewCompat

/** A semantic heading on every supported API level, including Android 8.x. */
class CollieHeadingTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {
    init {
        ViewCompat.setAccessibilityHeading(this, true)
    }
}
