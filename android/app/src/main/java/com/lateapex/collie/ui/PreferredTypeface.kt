package com.lateapex.collie.ui

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.lateapex.collie.R

/** Applies the device-local app face to chrome while preserving terminal-bound monospace text. */
fun View.applyPreferredTypeface(preferences: NativePreferences) {
    val face = when (preferences.appTypeface) {
        NativePreferences.AppTypeface.SYSTEM -> Typeface.create("sans-serif", Typeface.NORMAL)
        NativePreferences.AppTypeface.SPACE_GROTESK ->
            ResourcesCompat.getFont(context, R.font.space_grotesk_variable)
        NativePreferences.AppTypeface.ALDRICH -> ResourcesCompat.getFont(context, R.font.collie_ui)
    } ?: return
    applyTypefaceTree(face, preferences.appTypeface)
    if (getTag(R.id.collie_typeface_listener) != null) return
    val listener = ViewTreeObserver.OnGlobalLayoutListener {
        applyTypefaceTree(face, preferences.appTypeface)
    }
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

private fun View.applyTypefaceTree(face: Typeface, selected: NativePreferences.AppTypeface) {
    walkText { text ->
        if (text.id !in TERMINAL_BOUND_IDS) {
            // Aldrich ships one real weight. Asking Android for bold synthesises a distorted face
            // that the canonical web UI explicitly disables with font-synthesis:none.
            val style = if (selected == NativePreferences.AppTypeface.ALDRICH) {
                Typeface.NORMAL
            } else {
                text.typeface?.style ?: Typeface.NORMAL
            }
            text.typeface = Typeface.create(face, style)
        }
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
