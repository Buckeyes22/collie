package com.lateapex.collie.ui

import com.lateapex.collie.network.PaneChatResponse
import com.lateapex.collie.network.TranscriptEntry

/** Terminal acceptance is distinct from a journalled turn. Never replay these messages. */
internal class CodexAcceptedReplies {
    data class Baseline(val gen: Long, val rev: Long, val head: Long)
    private data class Pending(val text: String, val baseline: Baseline?)
    private val pending = mutableListOf<Pending>()
    private val matchedTurns = mutableSetOf<String>()
    val texts: List<String> get() = pending.map { it.text }

    fun accept(text: String, baseline: Baseline?) {
        pending += Pending(text, baseline)
    }

    fun reconcile(page: PaneChatResponse) {
        if (!page.available || page.page != "live") return
        reconcile(page.gen, page.rev, page.upserts)
    }

    fun reconcile(gen: Long?, rev: Long?, turns: List<TranscriptEntry>) {
        for (turn in turns) {
            if (turn.role != "user" || turn.abandoned || turn.uuid in matchedTurns ||
                turn.parts.isEmpty() || turn.parts.any { it.kind != "text" || it.truncated || it.text == null }
            ) continue
            val text = turn.parts.joinToString("") { it.text.orEmpty() }.trimEnd()
            val index = pending.indexOfFirst { item ->
                item.baseline?.let { baseline ->
                    gen == baseline.gen && (rev ?: -1) > baseline.rev &&
                        (turn.seq ?: -1) > baseline.head && text == item.text
                } == true
            }
            if (index >= 0) {
                pending.removeAt(index)
                matchedTurns += turn.uuid
            }
        }
    }

    fun dismiss() {
        pending.clear()
        matchedTurns.clear()
    }
}
