package com.lateapex.collie.ui

import com.lateapex.collie.network.PaneChatResponse
import com.lateapex.collie.network.ChatAfter
import com.lateapex.collie.network.TranscriptEntry
import com.lateapex.collie.network.TranscriptPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuedSendNowTest {
    private fun page(
        generation: Long? = 7,
        revision: Long? = 12,
        queued: List<String> = listOf("private queued text"),
        keys: List<String>? = listOf("ctrl+Enter"),
        digest: String? = "a".repeat(64),
        occupant: String? = "b".repeat(64),
        turns: List<TranscriptEntry> = emptyList(),
    ) = PaneChatResponse(
        paneId = "w1:p1", available = true, page = "live", gen = generation, rev = revision,
        head = 1, oldest = 1, hasOlder = false, upserts = turns, queued = queued,
        sendQueuedNow = keys, queuedDigest = digest, queuedSendOccupant = occupant,
    )

    @Test
    fun emptyTranscriptStillRetainsTheExactDeclaredQueueAction() {
        val window = LiveChatWindow()
        assertTrue(window.merge(page()))
        val action = window.sendNow!!
        assertEquals(emptyList<TranscriptEntry>(), window.entries)
        assertEquals(listOf("private queued text"), action.queued)
        assertEquals(listOf("ctrl+Enter"), action.keys)
        assertEquals(7L, action.generation)
        assertEquals(12L, action.revision)
    }

    @Test
    fun absenceOrMalformedIdentityNeverSynthesizesARecipe() {
        val window = LiveChatWindow()
        assertTrue(window.merge(page(keys = null)))
        assertNull(window.sendNow)
        assertTrue(window.merge(page(revision = 13, digest = null)))
        assertNull(window.sendNow)
        assertTrue(window.merge(page(revision = 14, occupant = null)))
        assertNull(window.sendNow)
        assertTrue(window.merge(page(revision = 15, queued = emptyList())))
        assertNull(window.sendNow)
    }

    @Test
    fun malformedQueueIdentityNeverSynthesizesARecipe() {
        val malformed = listOf(
            page(digest = "x"),
            page(digest = "A".repeat(64)),
            page(occupant = "not-an-occupant"),
        )
        for (response in malformed) {
            val window = LiveChatWindow()
            assertTrue("valid display page is accepted", window.merge(page()))
            assertTrue(window.merge(response))
            assertNull(window.sendNow)
        }
    }

    @Test
    fun negativeGenerationIsRejectedAndKeepsTheLastAcceptedTranscriptAndCursor() {
        val window = LiveChatWindow()
        val retained = entry("retained")
        assertTrue(window.merge(page(turns = listOf(retained))))
        assertNotNull(window.sendNow)

        assertFalse(window.merge(page(generation = -1, revision = 13, turns = listOf(entry("rejected")))))

        assertNull(window.sendNow)
        assertEquals(listOf(retained), window.entries)
        assertEquals(ChatAfter(7, 12), window.after)
        assertEquals(listOf("private queued text"), window.queued)
    }

    @Test
    fun negativeRevisionIsRejectedBeforeStaleComparisonAndKeepsTheLastAcceptedCursor() {
        val window = LiveChatWindow()
        val retained = entry("retained")
        assertTrue(window.merge(page(turns = listOf(retained))))
        assertNotNull(window.sendNow)

        assertFalse(window.merge(page(revision = -1, turns = listOf(entry("rejected")))))

        assertNull(window.sendNow)
        assertEquals(listOf(retained), window.entries)
        assertEquals(ChatAfter(7, 12), window.after)
    }

    @Test
    fun anOlderValidRevisionIsIgnoredWithoutDisarmingTheCurrentAction() {
        val window = LiveChatWindow()
        assertTrue(window.merge(page()))
        val action = window.sendNow

        assertFalse(window.merge(page(revision = 11, turns = listOf(entry("older")))))

        assertEquals(action, window.sendNow)
        assertEquals(ChatAfter(7, 12), window.after)
        assertTrue(window.entries.isEmpty())
    }

    @Test
    fun legacyPageWithoutRevisionUpdatesDisplayWithoutCreatingAnActionOrCursor() {
        val window = LiveChatWindow()
        assertTrue(window.merge(page()))

        assertTrue(window.merge(page(revision = null, turns = listOf(entry("legacy")))))

        assertNull(window.sendNow)
        assertNull(window.after)
        assertEquals(listOf("legacy"), window.entries.map { it.uuid })
    }

    private fun entry(uuid: String) = TranscriptEntry(
        uuid = uuid,
        ts = "2026-10-10T00:00:00Z",
        role = "assistant",
        parts = listOf(TranscriptPart(kind = "text", text = uuid)),
    )

    @Test
    fun largestNonnegativeCountersRemainRepresentableWithoutOverflow() {
        val window = LiveChatWindow()
        assertTrue(window.merge(page(generation = Long.MAX_VALUE, revision = Long.MAX_VALUE)))
        assertEquals(Long.MAX_VALUE, window.sendNow?.generation)
        assertEquals(Long.MAX_VALUE, window.sendNow?.revision)
    }

    @Test
    fun theSameQueueActionStaysLatchedAcrossUnrelatedRevisionButNotChangedRecipeOrOccupant() {
        val window = LiveChatWindow()
        window.merge(page())
        val action = window.sendNow!!
        window.merge(page(revision = 13))
        assertTrue(action.sameQueue(window.sendNow!!))
        assertFalse(action.sameQueue(window.sendNow!!.copy(keys = listOf("Enter"))))
        assertFalse(action.sameQueue(window.sendNow!!.copy(occupant = "c".repeat(64))))
        window.clear()
        assertNull(window.sendNow)
        assertEquals(emptyList<String>(), window.queued)
    }
}
