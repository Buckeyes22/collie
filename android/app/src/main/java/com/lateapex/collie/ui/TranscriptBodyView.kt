package com.lateapex.collie.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.R
import com.lateapex.collie.network.TranscriptEntry
import com.lateapex.collie.data.ScopedImageLoader
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.data.ScopedRequestKey

/**
 * The pane body for a journalled agent: one turn card per transcript entry, grown upwards by
 * "Load older". A re-bind only adds views for new uuids, so expansion state and scroll survive.
 */
class TranscriptBodyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    var onLoadOlder: (() -> Unit)? = null
    var onSendQueuedNow: ((QueuedSendNow) -> Unit)? = null

    /** Where a bind that added or replaced turns left the reader; wired to the diagnostics trace. */
    var onPlaced: ((Map<String, Any?>) -> Unit)? = null

    /** A jump of more than a screen the reader did not make, with the stack that caused it. */
    var onUnrequestedJump: ((Map<String, Any?>) -> Unit)? = null

    private var placing = false
    private var touching = false

    private val scroll = object : ScrollView(context) {
        override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
            touching = ev.actionMasked != android.view.MotionEvent.ACTION_UP &&
                ev.actionMasked != android.view.MotionEvent.ACTION_CANCEL
            return super.dispatchTouchEvent(ev)
        }

        override fun requestChildRectangleOnScreen(child: View, rectangle: android.graphics.Rect, immediate: Boolean): Boolean {
            if (!touching) {
                onUnrequestedJump?.invoke(
                    mapOf(
                        "via" to "requestChildRectangleOnScreen",
                        "immediate" to immediate,
                        "scrollY" to scrollY,
                        "child" to "${child.javaClass.simpleName}@${turnsContainer.indexOfChild(turnOf(child))}",
                        "stack" to stack(),
                    ),
                )
            }
            return super.requestChildRectangleOnScreen(child, rectangle, immediate)
        }

        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            if (placing || touching || kotlin.math.abs(t - oldt) <= height) return
            onUnrequestedJump?.invoke(
                mapOf(
                    "from" to oldt,
                    "to" to t,
                    "viewportHeight" to height,
                    "contentHeight" to (getChildAt(0)?.height ?: 0),
                    "focused" to findFocus()?.let { "${it.javaClass.simpleName}@${turnsContainer.indexOfChild(turnOf(it))}" },
                    "stack" to stack(),
                ),
            )
        }

        private fun stack(): String = Throwable().stackTrace.drop(2).take(STACK_FRAMES).joinToString(" < ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }
    }

    /** The turn (a direct child of the container) that [view] sits in, or null. */
    private fun turnOf(view: View): View? {
        var current: View? = view
        while (current != null && current.parent !== turnsContainer) current = current.parent as? View
        return current
    }
    private val turnsContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val loadOlder = MaterialButton(context).apply {
        id = R.id.transcript_load_older
        revealOnFocusHint = false
        text = context.getString(R.string.pane_transcript_load_older)
        transformationMethod = null
        isVisible = false
        setOnClickListener { onLoadOlder?.invoke() }
    }
    private val sendQueuedNow = MaterialButton(context).apply {
        id = R.id.transcript_send_queued_now
        revealOnFocusHint = false
        transformationMethod = null
        isVisible = false
        setOnClickListener { boundSendNow?.let { onSendQueuedNow?.invoke(it) } }
    }
    private val queuedContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var boundQueued: List<String> = emptyList()
    private var boundSendNow: QueuedSendNow? = null
    private val expandedTools = mutableSetOf<Pair<String, Int>>()
    private val turns = linkedMapOf<String, View>()
    private var renderer: HistoryTurnRenderer? = null
    private var boundAgent: String? = null
    private var boundImageActor: ImageRenderActor? = null
    private var boundEntries: List<TranscriptEntry> = emptyList()

    init {
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        scroll.addView(turnsContainer)
        turnsContainer.addView(
            loadOlder,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        turnsContainer.addView(queuedContainer)
        turnsContainer.addView(sendQueuedNow)
    }

    fun bind(
        agent: String,
        entries: List<TranscriptEntry>,
        hasMore: Boolean,
        queued: List<String> = emptyList(),
        imageLoader: ScopedImageLoader? = null,
        readContext: ScopedReadContext? = null,
        sendNow: QueuedSendNow? = null,
        sendNowEnabled: Boolean = false,
        sendNowPending: Boolean = false,
        sendNowAttempted: Boolean = false,
    ) {
        val atBottom = scroll.scrollY + scroll.height >= (scroll.getChildAt(0)?.height ?: 0) - dp(8)
        // The reader's place is the first turn on screen and its offset from the top. Restoring
        // that, rather than shifting by however much the list grew, keeps an append below from
        // moving the view (S25 Ultra, 2026-09-10: every poll pushed "Load older" out of reach)
        // and keeps a prepend above from shoving the rows being read out from under the reader.
        val anchor = if (atBottom) null else firstVisibleTurn()
        loadOlder.isVisible = hasMore
        // A pane switch or a cleared journal starts the body over; a normal re-bind only appends.
        val imageActor = readContext?.let { ImageRenderActor(it.key, it.navigationGeneration) }
        val imageAuthorityChanged = boundImageActor != imageActor
        val reset = turns.isNotEmpty() && (imageAuthorityChanged || turns.keys.any { key -> entries.none { it.uuid == key } })
        if (reset) {
            turns.values.forEach(turnsContainer::removeView)
            turns.clear()
        }
        if (agent != boundAgent || renderer == null || boundImageActor != imageActor) {
            boundAgent = agent
            boundImageActor = imageActor
            renderer = HistoryTurnRenderer(context, agent, expandedTools, ::toggleTool, imageLoader = imageLoader, readContext = readContext)
        }
        val oldViews = turns.toMap()
        val previous = boundEntries.associateBy { it.uuid }
        boundEntries = entries
        val before = turns.size
        entries.forEachIndexed { index, entry ->
            val existing = turns[entry.uuid]
            if (existing != null && previous[entry.uuid] == entry) return@forEachIndexed
            val turn = HistoryPresentation.turn(entry, agent, resources)
            val view = renderer!!.turnView(turn, showHeader = true).noRevealOnFocus()
            styleUserTurn(view, entry)
            turns[entry.uuid] = view
            // The "Load older" button is child 0; each turn sits at its entry's list position.
            if (existing != null) turnsContainer.removeView(existing)
            turnsContainer.addView(view, index + 1)
        }
        if (queued != boundQueued) {
            boundQueued = queued.toList()
            queuedContainer.removeAllViews()
            queued.forEach { text ->
                queuedContainer.addView(TextView(context).apply {
                    this.text = context.getString(R.string.pane_transcript_queued, text)
                    setTextIsSelectable(true)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    setTextColor(ContextCompat.getColor(context, R.color.collie_muted))
                    noRevealOnFocus()
                })
            }
        }
        boundSendNow = sendNow
        sendQueuedNow.isVisible = sendNow != null && queued.isNotEmpty()
        sendQueuedNow.text = context.getString(
            when {
                sendNowPending -> R.string.pane_transcript_sending_now
                sendNowAttempted -> R.string.pane_transcript_send_check_queue
                else -> R.string.pane_transcript_send_now
            },
        )
        sendQueuedNow.isEnabled = sendNow != null && sendNowEnabled && !sendNowPending && !sendNowAttempted
        val added = turns.size - before
        val restore = {
            val scrollBefore = scroll.scrollY
            placing = true
            if (reset || anchor == null) {
                if (reset || atBottom) scroll.scrollTo(0, (turnsContainer.height - scroll.height).coerceAtLeast(0))
            } else {
                val anchored = if (anchor.first.parent === turnsContainer) anchor.first else {
                    val uuid = previous.keys.firstOrNull { oldViews[it] === anchor.first }
                    uuid?.let { turns[it] }
                }
                anchored?.let { scroll.scrollTo(0, it.top - anchor.second) }
            }
            placing = false
            if (added > 0 || reset) {
                onPlaced?.invoke(
                    mapOf(
                        "turns" to turns.size,
                        "added" to added,
                        "reset" to reset,
                        "atBottom" to atBottom,
                        "anchored" to (anchor != null),
                        "viewportHeight" to scroll.height,
                        "contentHeight" to turnsContainer.height,
                        "scrollBefore" to scrollBefore,
                        "scrollAfter" to scroll.scrollY,
                        "viewLaidOut" to scroll.isLaidOut,
                    ),
                )
            }
        }
        // Place the reader at pre-draw: every layout in the frame has finished, including the
        // scroll view's own. Placing from inside that layout (the old doOnNextLayout on the
        // container) raced the ScrollView's first-layout pass, which wrote its offset back to the
        // top without a scroll callback: on a cold first open the pane landed on "Load older"
        // though the trace showed the bottom (S25 Ultra, 2026-09-18).
        turnsContainer.doOnPreDraw { restore() }
    }

    private data class ImageRenderActor(
        val key: ScopedRequestKey,
        val navigationGeneration: Long,
    )

    /** The first turn whose bottom is below the scroll top, with its offset from that top. */
    private fun firstVisibleTurn(): Pair<View, Int>? {
        val top = scroll.scrollY
        for (index in 1 until turnsContainer.childCount) {
            val child = turnsContainer.getChildAt(index)
            if (child.bottom > top) return child to (child.top - top)
        }
        return null
    }

    /**
     * Turn text is selectable, so any turn can take focus. When the body switches from the mirror,
     * Android hands focus to the first focusable turn, the oldest, and a ScrollView reveals the
     * focused view: opening a busy agent's pane landed at the top of the session instead of the
     * newest turn (S25 Ultra, 2026-09-11). Focus never moves the reader here; bind() does.
     */
    private fun View.noRevealOnFocus(): View {
        revealOnFocusHint = false
        if (this is ViewGroup) for (index in 0 until childCount) getChildAt(index).noRevealOnFocus()
        return this
    }

    /** B.2: the operator's own message reads as a card in the pane body. */
    private fun styleUserTurn(view: View, entry: TranscriptEntry) {
        if (entry.role != "user") return
        val content = (view as? ViewGroup)?.getChildAt(0) as? ViewGroup ?: return
        content.background = ContextCompat.getDrawable(context, R.drawable.bg_pane_user_turn)
        content.setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    private fun toggleTool(uuid: String, partIndex: Int) {
        val key = uuid to partIndex
        if (key in expandedTools) expandedTools -= key else expandedTools += key
        val entry = boundEntries.firstOrNull { it.uuid == uuid } ?: return
        val index = turnsContainer.indexOfChild(turns[uuid])
        val rebuilt = renderer!!.turnView(HistoryPresentation.turn(entry, boundAgent.orEmpty(), resources), showHeader = true)
            .noRevealOnFocus()
        turns[uuid] = rebuilt
        turnsContainer.removeViewAt(index)
        turnsContainer.addView(rebuilt, index)
    }

    @VisibleForTesting
    internal fun turnViewForTest(uuid: String): View? = turns[uuid]

    @VisibleForTesting
    internal fun turnCountForTest(): Int = turns.size

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val STACK_FRAMES = 14
    }
}
