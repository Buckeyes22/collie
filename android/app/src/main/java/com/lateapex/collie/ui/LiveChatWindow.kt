package com.lateapex.collie.ui

import com.lateapex.collie.network.ChatAfter
import com.lateapex.collie.network.ChatBefore
import com.lateapex.collie.network.PaneChatResponse
import com.lateapex.collie.network.TranscriptEntry
import com.lateapex.collie.network.isCanonicalQueuedSendIdentity

/** A one-shot action bound to the exact live queue response that rendered it. */
data class QueuedSendNow(
    val generation: Long,
    val revision: Long,
    val queuedDigest: String,
    val occupant: String,
    val queued: List<String>,
    val keys: List<String>,
) {
    /** The queue identity ignores unrelated transcript revisions, preventing a repeat send. */
    fun sameQueue(other: QueuedSendNow): Boolean =
        generation == other.generation && queuedDigest == other.queuedDigest && occupant == other.occupant && keys == other.keys
}

/** Generation changes replace the thread; upserts replace whole turns, including tool results. */
internal class LiveChatWindow {
    private var generation: Long? = null
    private var revision: Long? = null
    var head: Long? = null
        private set
    private val turns = LinkedHashMap<String, TranscriptEntry>()
    var hasOlder = false
        private set
    var queued: List<String> = emptyList()
        private set
    var sendNow: QueuedSendNow? = null
        private set
    val after: ChatAfter? get() = generation?.let { gen -> revision?.let { ChatAfter(gen, it) } }
    val before: ChatBefore? get() = turns.values.minByOrNull { it.seq ?: Long.MAX_VALUE }?.let {
        it.seq?.let { seq -> ChatBefore(seq, it.uuid) }
    }
    val entries: List<TranscriptEntry> get() = turns.values.sortedBy { it.seq }.filterNot { it.abandoned }

    fun merge(page: PaneChatResponse): Boolean {
        if (!page.available || page.gen == null || page.gen < 0L || (page.rev != null && page.rev < 0L)) {
            sendNow = null
            return false
        }
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
            head = page.head
            queued = page.queued
            sendNow = if (page.queued.isNotEmpty() && !page.sendQueuedNow.isNullOrEmpty() &&
                page.rev != null && page.rev >= 0L &&
                isCanonicalQueuedSendIdentity(page.queuedDigest) &&
                isCanonicalQueuedSendIdentity(page.queuedSendOccupant)
            ) {
                QueuedSendNow(
                    page.gen,
                    page.rev,
                    page.queuedDigest!!,
                    page.queuedSendOccupant!!,
                    page.queued.toList(),
                    page.sendQueuedNow.toList(),
                )
            } else {
                null
            }
        } else if (page.page == "older") {
            hasOlder = page.hasOlder
        } else return false
        for (turn in page.upserts) {
            if (page.page != "older" || !turns.containsKey(turn.uuid)) turns[turn.uuid] = turn
        }
        return true
    }

    /** Failed reads retire only action authority; retained content and cursors stay visible. */
    fun disarmSendNow() { sendNow = null }

    fun clear() {
        generation = null
        revision = null
        head = null
        turns.clear()
        queued = emptyList()
        sendNow = null
        hasOlder = false
    }
}
