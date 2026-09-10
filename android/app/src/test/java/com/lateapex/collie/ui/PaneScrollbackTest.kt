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
        val result = PaneBufferPolicy.resolve(pane(agent = "opencode", hasSession = false), null, 600)

        assertEquals(PaneBufferAction.NONE, result.action)
        assertEquals("opencode", result.missingSessionAgent)
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
