package com.lateapex.collie.ui

import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.MuxConfigResponse
import com.lateapex.collie.network.PaneSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaneScrollbackTest {
    @Test
    fun transcriptTakesPriorityOverTerminalScrollback() {
        val result = PaneBufferPolicy.resolve(
            pane(hasSession = true, readableLines = 4_000),
            MuxConfigResponse(capabilities = mapOf("agentSessionRef" to true, "gridScrollback" to true)),
            requestedLines = 600,
        )

        assertEquals(PaneBufferAction.SHOW_HISTORY, result.action)
        assertNull(result.muxExplanation)
        assertNull(result.missingSessionAgent)
    }

    @Test
    fun shellWithKnownDeeperGridOffersLoadOlderOnlyBelowTheClamp() {
        val mux = MuxConfigResponse(capabilities = mapOf("gridScrollback" to true))

        assertEquals(
            PaneBufferAction.LOAD_OLDER,
            PaneBufferPolicy.resolve(pane(agent = "shell", readableLines = 4_000), mux, 600).action,
        )
        assertEquals(
            PaneBufferAction.NONE,
            PaneBufferPolicy.resolve(pane(agent = "shell", readableLines = 4_000), mux, 1_000).action,
        )
        assertEquals(
            PaneBufferAction.NONE,
            PaneBufferPolicy.resolve(pane(agent = "shell", readableLines = null), mux, 600).action,
        )
    }

    @Test
    fun scrollbackCapabilityAloneDoesNotClaimUnknownTernDepth() {
        val unknownDepth = pane(agent = "shell", readableLines = null)
        val tern = MuxConfigResponse(
            name = "tern",
            capabilities = mapOf("gridScrollback" to true),
            readLimits = com.lateapex.collie.network.MuxReadLimitsResponse(
                kotlinx.serialization.json.JsonPrimitive(10_000),
            ),
        )

        assertEquals(PaneBufferAction.NONE, PaneBufferPolicy.resolve(unknownDepth, tern, 600).action)
    }

    @Test
    fun verifiedAdapterCeilingAllowsLargerBoundedWindowsButOldBridgeFallsBack() {
        val modern = MuxConfigResponse(readLimits = com.lateapex.collie.network.MuxReadLimitsResponse(kotlinx.serialization.json.JsonPrimitive(10_000)))
        assertEquals(10_000, PaneScrollbackWindow.maxLines(modern))
        assertEquals(1_000, PaneScrollbackWindow.maxLines(null))
        assertEquals(1_200, PaneScrollbackWindow.grow(600, PaneScrollbackWindow.maxLines(modern)))
        assertEquals(1_000, PaneScrollbackWindow.grow(600, PaneScrollbackWindow.maxLines(null)))
        assertEquals(1_200, PaneScrollbackWindow.growDesired(600))
        // Native desired depth stays distinct from the legacy bridge's admitted 1,000-row cap.
        assertEquals(1_000, PaneScrollbackWindow.growDesired(600).coerceAtMost(PaneScrollbackWindow.maxLines(null)))
    }

    @Test
    fun aShortNonTruncatedReadStopsGrowthEvenWhenThePreviousCensusWasLarger() {
        val staleDepth = pane(agent = "shell", readableLines = 1_400)
        val read = com.lateapex.collie.network.PaneReadResponse(
            "w1:p1", "only rows", truncated = false, revision = 1,
            requestedLines = 1_200, servedLines = 1,
        )
        assertEquals(
            PaneBufferAction.NONE,
            PaneBufferPolicy.resolve(staleDepth, MuxConfigResponse(capabilities = mapOf("gridScrollback" to true),
                readLimits = com.lateapex.collie.network.MuxReadLimitsResponse(kotlinx.serialization.json.JsonPrimitive(10_000))),
                requestedLines = 1_200, read = read).action,
        )
    }

    @Test
    fun transcriptLimitationExplainsItselfWithoutHidingIndependentScrollback() {
        val note = "This multiplexer keeps no agent session log for Collie to read."
        val result = PaneBufferPolicy.resolve(
            pane(hasSession = true, readableLines = 4_000),
            MuxConfigResponse(
                capabilities = mapOf("agentSessionRef" to false, "gridScrollback" to true),
                notes = mapOf("agentSessionRef" to note),
            ),
            requestedLines = 600,
        )

        assertEquals(PaneBufferAction.LOAD_OLDER, result.action)
        assertEquals(note, result.muxExplanation)
        assertNull(result.missingSessionAgent)
    }

    @Test
    fun journalAgentWithoutASessionGetsAnExplanationButNoDeadHistoryButton() {
        listOf("opencode", "hermes", "omp").forEach { agent ->
            val result = PaneBufferPolicy.resolve(pane(agent = agent, hasSession = false), null, 600)
            assertEquals(PaneBufferAction.NONE, result.action)
            assertEquals(agent, result.missingSessionAgent)
        }
        assertNull(PaneBufferPolicy.resolve(pane(agent = "muse", hasSession = false), null, 600).missingSessionAgent)
    }

    @Test
    fun viewportAnchorMovesByExactlyThePrependedHeight() {
        assertEquals(1_280, PaneScrollbackWindow.restoreTop(oldHeight = 2_000, oldTop = 480, newHeight = 2_800))
        assertEquals(0, PaneScrollbackWindow.restoreTop(oldHeight = 2_000, oldTop = 0, newHeight = 1_500))
    }

    private fun pane(
        agent: String = "codex",
        hasSession: Boolean? = null,
        readableLines: Int? = null,
    ) = PaneSummary(
        paneId = "w1:p1",
        workspaceId = "w1",
        workspaceLabel = "repo",
        workspaceNumber = 1,
        tabId = "w1:t1",
        agent = agent,
        status = AgentStatus.IDLE,
        cwd = "/repo",
        focused = true,
        hasSession = hasSession,
        readableLines = readableLines,
    )
}
