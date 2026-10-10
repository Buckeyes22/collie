package com.lateapex.collie.ui

import com.lateapex.collie.network.ChatAfter
import com.lateapex.collie.network.ChatBefore
import com.lateapex.collie.network.PaneChatResponse
import com.lateapex.collie.network.TranscriptEntry

/** Generation changes replace the thread; upserts replace whole turns, including tool results. */
internal class LiveChatWindow {
    private var generation: Long? = null
    private var revision: Long? = null
    private val turns = LinkedHashMap<String, TranscriptEntry>()
    var hasOlder = false
        private set
    var queued: List<String> = emptyList()
        private set
    val after: ChatAfter? get() = generation?.let { gen -> revision?.let { ChatAfter(gen, it) } }
    val before: ChatBefore? get() = turns.values.minByOrNull { it.seq ?: Long.MAX_VALUE }?.let {
        it.seq?.let { seq -> ChatBefore(seq, it.uuid) }
    }
    val entries: List<TranscriptEntry> get() = turns.values.sortedBy { it.seq }.filterNot { it.abandoned }

    fun merge(page: PaneChatResponse): Boolean {
        if (!page.available || page.gen == null) return false
        // An older request from a discarded generation cannot put that session back on screen.
        if (page.page == "older" && page.gen != generation) return false
        if (page.page == "live") {
            if (page.gen == generation && page.rev != null && revision != null && page.rev < revision!!) return false
            if (page.gen != generation) {
                turns.clear()
                generation = page.gen
                hasOlder = page.hasOlder
            }
            if (turns.isEmpty()) hasOlder = page.hasOlder
            revision = page.rev
            queued = page.queued
        } else if (page.page == "older") {
            hasOlder = page.hasOlder
        } else return false
        for (turn in page.upserts) {
            if (page.page != "older" || !turns.containsKey(turn.uuid)) turns[turn.uuid] = turn
        }
        return true
    }

    fun clear() {
        generation = null
        revision = null
        turns.clear()
        queued = emptyList()
        hasOlder = false
    }
}
