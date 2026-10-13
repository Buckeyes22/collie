package com.lateapex.collie.ui

import com.lateapex.collie.network.PaneChatResponse
import com.lateapex.collie.network.TranscriptEntry
import com.lateapex.collie.network.TranscriptPart
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexAcceptedRepliesTest {
    private val baseline = CodexAcceptedReplies.Baseline(2, 4, 10)
    private fun turn(id: String = "new", seq: Long = 11, text: String = "hello") =
        TranscriptEntry(id, "", "user", listOf(TranscriptPart("text", text)), seq)
    private fun page(vararg turns: TranscriptEntry) = PaneChatResponse(
        "p", true, page = "live", gen = 2, rev = 5, head = 11, upserts = turns.toList(),
    )

    @Test fun aNewExactUserTurnReconcilesOnceAndCannotConsumeAnotherIdenticalSend() {
        val accepted = CodexAcceptedReplies()
        accepted.accept("hello", baseline)
        accepted.accept("hello", baseline)
        accepted.reconcile(page(turn()))
        accepted.reconcile(page(turn()))
        assertEquals(listOf("hello"), accepted.texts)
        accepted.reconcile(page(turn(id = "next", seq = 12)))
        assertEquals(emptyList<String>(), accepted.texts)
    }

    @Test fun outputQueuesOldTurnsAndNearMatchesDoNotProveAUserTurn() {
        val accepted = CodexAcceptedReplies()
        accepted.accept("hello", baseline)
        listOf(
            page(),
            page().copy(queued = listOf("hello")),
            page(turn(seq = 10)),
            page(turn().copy(role = "assistant")),
            page(turn().copy(abandoned = true)),
            page(turn(text = "hello there")),
            page(turn(text = " hello")),
            page(turn().copy(parts = listOf(TranscriptPart("text", "hello", truncated = true)))),
            page(turn()).copy(page = "older"),
            page(turn()).copy(gen = 3),
            page(turn()).copy(rev = 4),
            page(turn()).copy(available = false),
        ).forEach { accepted.reconcile(it); assertEquals(listOf("hello"), accepted.texts) }
    }

    @Test fun missingBaselineAndSlashCommandsRemainReviewableUntilExplicitDismissal() {
        val accepted = CodexAcceptedReplies()
        accepted.accept("hello", null)
        accepted.accept("/model", baseline)
        accepted.reconcile(page(turn()))
        assertEquals(listOf("hello", "/model"), accepted.texts)
        accepted.dismiss()
        assertEquals(emptyList<String>(), accepted.texts)
    }
}
