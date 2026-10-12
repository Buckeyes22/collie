package com.lateapex.collie.ui

import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ScopedReadContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** One form's original actor, including all later review/force sheets. No repository lock is held
 * across a GUI acknowledgment. STOPPED is terminal for this form; resuming needs a new gesture. */
internal class WorktreeUiLease(
    private val owner: LifecycleOwner,
    private val repository: CollieRepository,
    val context: ScopedReadContext?,
    private val onWipe: () -> Unit = {},
) : DefaultLifecycleObserver, AutoCloseable {
    private val active = AtomicBoolean(true)
    private val main = Handler(Looper.getMainLooper())
    private val dialogs = mutableListOf<Pair<Dialog, View?>>() // main thread only
    private var registration: AutoCloseable? = null
    private val handoffs = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Dialog, Boolean>())
    internal val boundCopiesForTesting: Int get() = dialogs.size

    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        live.remove(owner)?.finish()
        live[owner] = this
        owner.lifecycle.addObserver(this)
        registration = context?.let { repository.onFilesActorRetired(it) { close() } }
        if (!active.get()) registration?.close()
    }

    fun current(): Boolean = active.get() && context?.let(repository::isCurrent) == true

    /** Only a current, deliberate main-thread continuation may attach copied data. */
    fun handoff(dialog: Dialog) { check(Looper.myLooper() == Looper.getMainLooper()); handoffs.add(dialog) }

    fun bind(dialog: Dialog, body: View? = null, dismissed: () -> Unit = {}): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!active.get() || context != null && !current()) { wipe(dialog, body); return false }
        if (dialogs.size >= MAX_COPIES) { close(); wipe(dialog, body); return false }
        dialogs += dialog to body
        dialog.setOnDismissListener {
            val continues = handoffs.remove(dialog)
            dialogs.removeAll { it.first === dialog }
            dismissed()
            wipe(dialog, body, dismiss = false)
            if (active.get() && !continues) finish()
        }
        if (!active.get() || context != null && !current()) { close(); return false }
        return true
    }

    override fun onStop(owner: LifecycleOwner) = close()
    override fun onDestroy(owner: LifecycleOwner) = close()

    /** End a legitimate current review without erasing its Activity-owned editable form. */
    fun finish() = release(retirement = false)
    override fun close() = release(retirement = true)

    private fun release(retirement: Boolean) {
        if (!active.compareAndSet(true, false)) return
        // Retirement wins before posting, so a queued result cannot reopen a sheet during ACK.
        registration?.close()
        val retired = Runnable {
            val copies = dialogs.toList(); dialogs.clear(); handoffs.clear()
            copies.forEach { (dialog, body) -> wipe(dialog, body) }
            if (retirement) onWipe()
            if (live[owner] === this) live.remove(owner)
            owner.lifecycle.removeObserver(this)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) retired.run() else {
            val acknowledged = CountDownLatch(1)
            check(main.post { try { retired.run() } finally { acknowledged.countDown() } }) {
                "Unable to acknowledge retired worktree UI"
            }
            var interrupted = false
            while (true) {
                try { acknowledged.await(); break } catch (_: InterruptedException) { interrupted = true }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun wipe(dialog: Dialog, body: View?, dismiss: Boolean = true) {
        fun clear(view: View) {
            view.contentDescription = null
            view.setOnClickListener(null)
            if (view is TextView) { view.text = ""; view.hint = "" }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) clear(view.getChildAt(index))
                view.removeAllViews()
            }
        }
        body?.let(::clear)
        dialog.window?.decorView?.let(::clear)
        if (dismiss) dialog.dismiss()
    }
    companion object {
        private const val MAX_COPIES = 4
        private val live = java.util.WeakHashMap<LifecycleOwner, WorktreeUiLease>() // main thread
        internal fun activeCountForTesting(owner: LifecycleOwner): Int = if (live[owner]?.active?.get() == true) 1 else 0
    }
}
