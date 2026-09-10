package com.lateapex.collie.ui

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.lateapex.collie.R

/**
 * Applies the app's one face (Aldrich, `@font/collie_ui`) to chrome built in code, while
 * terminal-bound text keeps its monospace. There is no picker: the face is the design, not a
 * preference.
 */
fun View.applyAppTypeface() {
    val face = ResourcesCompat.getFont(context, R.font.collie_ui) ?: return
    applyTypefaceTree(face)
    if (getTag(R.id.collie_typeface_listener) != null) return
    val listener = ViewTreeObserver.OnGlobalLayoutListener { applyTypefaceTree(face) }
    setTag(R.id.collie_typeface_listener, listener)
    viewTreeObserver.addOnGlobalLayoutListener(listener)
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit

        override fun onViewDetachedFromWindow(view: View) {
            if (view.viewTreeObserver.isAlive) {
                view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            }
            view.setTag(R.id.collie_typeface_listener, null)
            view.removeOnAttachStateChangeListener(this)
        }
    })
}

private fun View.applyTypefaceTree(face: Typeface) {
    walkText { text ->
        // Aldrich ships one real weight. Asking Android for bold synthesises a distorted face
        // that the canonical web UI explicitly disables with font-synthesis:none.
        if (text.id !in TERMINAL_BOUND_IDS) text.typeface = Typeface.create(face, Typeface.NORMAL)
    }
}

private fun View.walkText(block: (TextView) -> Unit) {
    if (this is TextView) block(this)
    if (this is ViewGroup) for (index in 0 until childCount) getChildAt(index).walkText(block)
}

private val TERMINAL_BOUND_IDS = setOf(
    R.id.terminal_text,
    R.id.reply_input,
    R.id.connection_origin_value,
)
