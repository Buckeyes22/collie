package com.lateapex.collie.ui

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.AuthorityRetirement
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.data.custodyOwner
import com.lateapex.collie.network.OperatorFontRow

/**
 * Applies the selected device-local face to app chrome. Agent and operator content keeps its own
 * typeface; terminal-bound text stays monospace.
 */
fun View.applyAppTypeface() {
    PreferredTypeface.registerRoot(this)
    val face = PreferredTypeface.current(context)
    applyTypefaceTree(face)
    if (getTag(R.id.collie_typeface_listener) != null) return
    val listener = ViewTreeObserver.OnGlobalLayoutListener { applyTypefaceTree(PreferredTypeface.current(context)) }
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

/** The Android client's shipped chrome choices, stored beside its other device-local preferences. */
internal object PreferredTypeface {
    private data class LoadedOperatorFont(
        val row: OperatorFontRow,
        val ownerFingerprint: String,
        val actor: ScopedReadContext,
        val typeface: Typeface,
    )

    private val roots = java.util.WeakHashMap<View, Boolean>()
    fun registerRoot(view: View) { synchronized(roots) { roots[view] = true } }
    private fun refreshRoots() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val current = synchronized(roots) { roots.keys.toList() }
            current.filter(View::isAttachedToWindow).forEach { it.applyAppTypeface() }
        }
    }
    @Volatile private var loadedOperator: LoadedOperatorFont? = null
    @Volatile private var observedRepository: com.lateapex.collie.data.CollieRepository? = null
    private var retirementObserver: AutoCloseable? = null

    fun selected(context: Context): NativePreferences.UiTypeface = NativePreferences(context).uiTypeface

    fun select(context: Context, value: NativePreferences.UiTypeface) {
        NativePreferences(context).uiTypeface = value
    }

    fun current(context: Context): Typeface {
        currentOperator(context)?.let { return it }
        return when (selected(context)) {
            NativePreferences.UiTypeface.SYSTEM -> Typeface.create("sans-serif", Typeface.NORMAL)
            NativePreferences.UiTypeface.GROTESK -> ResourcesCompat.getFont(context, R.font.space_grotesk_regular)
                ?: Typeface.DEFAULT
            NativePreferences.UiTypeface.ALDRICH -> ResourcesCompat.getFont(context, R.font.collie_ui) ?: Typeface.DEFAULT
        }
    }

    fun observe(repository: com.lateapex.collie.data.CollieRepository) {
        synchronized(this) {
            if (observedRepository === repository) return
            retirementObserver?.close()
            observedRepository = repository
            retirementObserver = repository.onReadContextRetired(::onActorRetired)
        }
    }

    fun onActorRetired(event: AuthorityRetirement) {
        val loaded = loadedOperator ?: return
        if (event.affects(loaded.actor)) { loadedOperator = null; refreshRoots() }
    }

    fun publishOperator(
        context: Context,
        row: OperatorFontRow,
        actor: ScopedReadContext,
        typeface: Typeface,
        isCurrentSelection: () -> Boolean = { true },
    ): Boolean {
        if (!row.valid() || !isCurrentSelection()) return false
        val app = context.applicationContext as? CollieApplication ?: return false
        val ownerFingerprint = actor.custodyOwner.storageIdentity
        if (!isCurrentSelection() || !app.container.repository.isCurrent(actor)) return false
        // Fence again immediately before durable selection mutation as well as publication.
        if (!isCurrentSelection()) return false
        if (!NativePreferences(context).selectOperatorTypeface(row.family, row.basename, ownerFingerprint)) return false
        loadedOperator = LoadedOperatorFont(row, ownerFingerprint, actor, typeface)
        if (!isCurrentSelection() || !app.container.repository.isCurrent(actor)) {
            if (loadedOperator?.actor === actor) loadedOperator = null
            return false
        }
        refreshRoots()
        return true
    }

    fun clearLoadedOperator() { loadedOperator = null; refreshRoots() }

    private fun currentOperator(context: Context): Typeface? {
        val selection = NativePreferences(context).operatorTypefaceSelection ?: return null
        val loaded = loadedOperator ?: return null
        val app = context.applicationContext as? CollieApplication ?: return null
        if (loaded.row.family != selection.family || loaded.row.basename != selection.basename ||
            loaded.ownerFingerprint != selection.ownerFingerprint || !app.container.repository.isCurrent(loaded.actor)
        ) {
            if (loadedOperator === loaded) loadedOperator = null
            return null
        }
        return loaded.typeface
    }
}

private fun View.applyTypefaceTree(face: Typeface) {
    walkText { text ->
        // Aldrich ships one real weight. Asking Android for bold synthesises a distorted face
        // that the canonical web UI explicitly disables with font-synthesis:none.
        if (text.id == R.id.reply_input) {
            // AppCompat may finish an inherited font callback after inflation. Reassert the
            // terminal composer's face when that callback requests layout.
            if (text.typeface != Typeface.MONOSPACE) text.typeface = Typeface.MONOSPACE
        } else if (text.id !in TERMINAL_BOUND_IDS && text.getTag(R.id.collie_content_typeface) != true && !text.isTextSelectable) {
            text.typeface = Typeface.create(face, Typeface.NORMAL)
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
