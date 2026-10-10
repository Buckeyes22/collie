package com.lateapex.collie.ui

import com.lateapex.collie.network.PaneChatResponse
import com.lateapex.collie.network.TranscriptEntry
import com.lateapex.collie.network.TranscriptPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LiveChatWindowTest {
    private fun turn(id: String, seq: Long, text: String, abandoned: Boolean = false) =
        TranscriptEntry(id, "", "assistant", listOf(TranscriptPart("text", text)), seq, abandoned)
    private fun live(gen: Long, rev: Long, vararg turns: TranscriptEntry, queued: List<String> = emptyList()) =
        PaneChatResponse("p", true, page = "live", gen = gen, rev = rev, head = 10, oldest = 1,
            hasOlder = true, upserts = turns.toList(), queued = queued)

    @Test
    fun toolResultsReplaceTheTurnAndQueuedMessagesDoNotBecomePermanentTurns() {
        val window = LiveChatWindow()
        window.merge(live(1, 1, turn("a", 2, "running"), queued = listOf("next")))
        window.merge(live(1, 2, turn("a", 2, "finished"), turn("b", 3, "answer")))
        assertEquals(listOf("finished", "answer"), window.entries.map { it.parts.single().text })
        assertEquals(emptyList<String>(), window.queued)
        assertEquals(2L, window.after?.rev)
    }

    @Test
    fun aNewGenerationReplacesTheOldSessionAndRejectsItsOutstandingOlderPage() {
        val window = LiveChatWindow()
        window.merge(live(1, 1, turn("old", 2, "old session")))
        window.merge(live(2, 1, turn("new", 1, "new session")))
        assertFalse(window.merge(PaneChatResponse("p", true, page = "older", gen = 1,
            upserts = listOf(turn("earlier", 0, "old turn")))))
        assertEquals(listOf("new"), window.entries.map { it.uuid })
    }

    @Test
    fun rewoundTurnsDisappearButKeepTheirPagingCursorAndCanBeRestored() {
        val window = LiveChatWindow()
        window.merge(live(1, 1, turn("a", 2, "a"), turn("b", 3, "b")))
        window.merge(live(1, 2, turn("a", 2, "a", abandoned = true)))
        assertEquals(listOf("b"), window.entries.map { it.uuid })
        assertEquals("a", window.before?.uuid)
        window.merge(live(1, 3, turn("a", 2, "a")))
        assertEquals(listOf("a", "b"), window.entries.map { it.uuid })
    }

    @Test
    fun olderPagesSortBySequenceAndAnOutOfOrderPollCannotRollBackEdits() {
        val window = LiveChatWindow()
        window.merge(live(1, 4, turn("a", 2, "new")))
        window.merge(PaneChatResponse("p", true, page = "older", gen = 1, hasOlder = false,
            upserts = listOf(turn("first", 1, "first"))))
        assertFalse(window.merge(live(1, 3, turn("a", 2, "stale"))))
        assertEquals(listOf("first", "new"), window.entries.map { it.parts.single().text })
        assertFalse(window.hasOlder)
    }
}
