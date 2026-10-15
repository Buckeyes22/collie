package com.lateapex.collie.ui

import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.DurableAsk
import com.lateapex.collie.network.DurableAskOption
import com.lateapex.collie.network.DurableOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableChatPolicyTest {
    @Test fun targetPrefersObservedTurnThenFallsBackToOldestOpenAsk() {
        val ask = ask("oldest")
        assertEquals(DurableTarget(turnId = "turn-1"), DurableChatPolicy.target("turn-1", listOf(ask)))
        assertEquals(DurableTarget(askId = "oldest"), DurableChatPolicy.target(null, listOf(ask)))
        assertNull(DurableChatPolicy.target(" ", emptyList()))
    }

    @Test fun stopAndSteerFollowTheObservedAgentState() {
        val target = DurableTarget(turnId = "turn-1")
        assertTrue(DurableChatPolicy.canSteer(AgentStatus.WORKING))
        assertFalse(DurableChatPolicy.canSteer(AgentStatus.BLOCKED))
        assertTrue(DurableChatPolicy.canStop(AgentStatus.WORKING, target))
        assertTrue(DurableChatPolicy.canStop(AgentStatus.BLOCKED, target))
        assertFalse(DurableChatPolicy.canStop(AgentStatus.DONE, target))
        assertFalse(DurableChatPolicy.canStop(AgentStatus.WORKING, null))
    }

    @Test fun onlyPermissionOptionsVisibleToThePersonCanBeAnswered() {
        val ask = ask("permission", cleaned = true, options = listOf(
            DurableAskOption("once", "Allow once", "allow"),
            DurableAskOption("always", "Always", "allow-always"),
            DurableAskOption("unknown", "Unknown", "other"),
        ))
        assertEquals(listOf("once"), DurableChatPolicy.answerable(ask).map { it.id })
        assertTrue(DurableChatPolicy.answerable(ask("question", kind = "question")).isEmpty())
    }

    @Test fun ambiguousRetryRequiresSameTargetTextAndSteerChoice() {
        val target = DurableTarget(turnId = "turn-1")
        assertTrue(DurableChatPolicy.retryStillNamesIntent(target, target, "hello", "hello", false, false))
        assertFalse(DurableChatPolicy.retryStillNamesIntent(target, target, "hello", "hello again", false, false))
        assertFalse(DurableChatPolicy.retryStillNamesIntent(target, target, "hello", "hello", false, true))
        assertFalse(DurableChatPolicy.retryStillNamesIntent(target, DurableTarget(turnId = "turn-2"), "hello", "hello", false, false))
    }

    @Test fun outcomesKeepStaleUnknownAndMaybeAppliedDistinct() {
        assertEquals(DurableReading.COMPLETE, DurableChatPolicy.reading(outcome("ok")))
        assertEquals(DurableReading.STALE, DurableChatPolicy.reading(outcome("stale")))
        assertEquals(DurableReading.UNKNOWN, DurableChatPolicy.reading(outcome("unknown")))
        assertEquals(DurableReading.MAYBE_APPLIED, DurableChatPolicy.reading(outcome("maybe-applied")))
        assertEquals(DurableReading.TOO_LATE, DurableChatPolicy.reading(outcome("too-late")))
    }

    private fun ask(
        id: String,
        kind: String = "permission",
        cleaned: Boolean = false,
        options: List<DurableAskOption> = listOf(DurableAskOption("allow", "Allow", "allow")),
    ) = DurableAsk(id, kind, "tool-1", 1, "Question", "item-1", options, cleaned = cleaned)

    private fun outcome(value: String) = DurableOutcome(ok = value in setOf("ok", "taken"), outcome = value)
}
