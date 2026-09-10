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
        loadOlder.isVisible = hasMore
        // A pane switch or a cleared journal starts the body over; a normal re-bind only appends.
        if (turns.isNotEmpty() && turns.keys.any { key -> entries.none { it.uuid == key } }) {
            turns.clear()
            turnsContainer.removeViews(1, turnsContainer.childCount - 1)
        }
        if (agent != boundAgent || renderer == null) {
            boundAgent = agent
            renderer = HistoryTurnRenderer(context, agent, expandedTools, ::toggleTool)
        }
        boundEntries = entries
        val atBottom = scroll.scrollY + scroll.height >= (scroll.getChildAt(0)?.height ?: 0) - dp(8)
        entries.forEach { entry ->
            if (turns[entry.uuid] != null) return@forEach
            val turn = HistoryPresentation.turn(entry, agent, resources)
            val view = renderer!!.turnView(turn, showHeader = true)
            styleUserTurn(view, entry)
            turns[entry.uuid] = view
            turnsContainer.addView(view)
        }
        if (atBottom) post {
            scroll.scrollTo(0, (turnsContainer.height - scroll.height).coerceAtLeast(0))
        }
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
