package com.lateapex.collie.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.R
import com.lateapex.collie.network.TranscriptEntry

/**
 * The pane body for a journalled agent: one turn card per transcript entry, grown upwards by
 * "Load older". A re-bind only adds views for new uuids, so expansion state and scroll survive.
 */
class TranscriptBodyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    var onLoadOlder: (() -> Unit)? = null

    private val scroll = ScrollView(context)
    private val turnsContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val loadOlder = MaterialButton(context).apply {
        id = R.id.transcript_load_older
        text = context.getString(R.string.pane_transcript_load_older)
        transformationMethod = null
        isVisible = false
        setOnClickListener { onLoadOlder?.invoke() }
    }
    private val expandedTools = mutableSetOf<Pair<String, Int>>()
    private val turns = linkedMapOf<String, View>()
    private var renderer: HistoryTurnRenderer? = null
    private var boundAgent: String? = null
    private var boundEntries: List<TranscriptEntry> = emptyList()

    init {
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        scroll.addView(turnsContainer)
        turnsContainer.addView(
            loadOlder,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }

    fun bind(agent: String, entries: List<TranscriptEntry>, hasMore: Boolean) {
        val atBottom = scroll.scrollY + scroll.height >= (scroll.getChildAt(0)?.height ?: 0) - dp(8)
        // The reader's place is the first turn on screen and its offset from the top. Restoring
        // that, rather than shifting by however much the list grew, keeps an append below from
        // moving the view (S25 Ultra, 2026-09-10: every poll pushed "Load older" out of reach)
        // and keeps a prepend above from shoving the rows being read out from under the reader.
        val anchor = if (atBottom) null else firstVisibleTurn()
        loadOlder.isVisible = hasMore
        // A pane switch or a cleared journal starts the body over; a normal re-bind only appends.
        val reset = turns.isNotEmpty() && turns.keys.any { key -> entries.none { it.uuid == key } }
        if (reset) {
            turns.clear()
            turnsContainer.removeViews(1, turnsContainer.childCount - 1)
        }
        if (agent != boundAgent || renderer == null) {
            boundAgent = agent
            renderer = HistoryTurnRenderer(context, agent, expandedTools, ::toggleTool)
        }
        boundEntries = entries
        entries.forEachIndexed { index, entry ->
            if (turns[entry.uuid] != null) return@forEachIndexed
            val turn = HistoryPresentation.turn(entry, agent, resources)
            val view = renderer!!.turnView(turn, showHeader = true)
            styleUserTurn(view, entry)
            turns[entry.uuid] = view
            // The "Load older" button is child 0; each turn sits at its entry's list position.
            turnsContainer.addView(view, index + 1)
        }
        val restore = {
            if (reset || anchor == null) {
                if (reset || atBottom) scroll.scrollTo(0, (turnsContainer.height - scroll.height).coerceAtLeast(0))
            } else if (anchor.first.parent === turnsContainer) {
                scroll.scrollTo(0, anchor.first.top - anchor.second)
            }
        }
        // Posted work runs after the pending layout on a phone; if a layout is still due, wait for it.
        post { if (turnsContainer.isLayoutRequested) turnsContainer.doOnNextLayout { restore() } else restore() }
    }

    /** The first turn whose bottom is below the scroll top, with its offset from that top. */
    private fun firstVisibleTurn(): Pair<View, Int>? {
        val top = scroll.scrollY
        for (index in 1 until turnsContainer.childCount) {
            val child = turnsContainer.getChildAt(index)
            if (child.bottom > top) return child to (child.top - top)
        }
        return null
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
        val index = turns.keys.indexOf(uuid) + 1
        val rebuilt = renderer!!.turnView(HistoryPresentation.turn(entry, boundAgent.orEmpty(), resources), showHeader = true)
        turns[uuid] = rebuilt
        turnsContainer.removeViewAt(index)
        turnsContainer.addView(rebuilt, index)
    }

    @VisibleForTesting
    internal fun turnViewForTest(uuid: String): View? = turns[uuid]

    @VisibleForTesting
    internal fun turnCountForTest(): Int = turns.size

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
