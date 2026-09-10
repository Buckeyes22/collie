package com.lateapex.collie.ui

import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.MuxConfigResponse
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.ServerSummary
import com.lateapex.collie.network.TabSummary
import com.lateapex.collie.network.TranscriptEntry
import com.lateapex.collie.network.TranscriptPart
import com.lateapex.collie.network.TranscriptResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneNavigationModelsTest {
    private val current = PaneAddress(Scope("desk", "work"), "p2")

    @Test
    fun tabAndPaneRowsStayInTheCurrentScopedWorkspaceAndNavigateToFocusedPane() {
        val p1 = pane("p1", "t1", AgentStatus.WORKING)
        val p2 = pane("p2", "t2", AgentStatus.IDLE)
        val p3 = pane("p3", "t2", AgentStatus.BLOCKED).copy(focused = true)
        val peerCollision = pane("peer", "t1", AgentStatus.BLOCKED).copy(host = "peer")
        val model = PaneTabStripModel.build(
            current,
            listOf(p1, p2, p3, peerCollision),
            listOf(tab("t2", 2), tab("t1", 1), tab("peer-tab", 3).copy(host = "peer")),
            visiblePreference = true,
            canWrite = true,
            mux = MuxConfigResponse(capabilities = mapOf("createTab" to true)),
            servers = null,
        )

        assertEquals(listOf("t1", "t2"), model.tabs.map { it.tab.tabId })
        assertEquals(listOf("p1", "p3"), model.tabs.map { it.target?.paneId })
        assertEquals(listOf(false, true), model.tabs.map(PaneTabItem::active))
        assertEquals(TriageBucket.NEEDS_YOU, model.tabs.last().bucket)
        assertEquals(listOf("p2", "p3"), model.panes.map { it.pane.paneId })
        assertEquals(listOf(true, false), model.panes.map(PaneStripItem::active))
        assertTrue(model.visible)
        assertTrue(model.canCreateTab)
    }

    @Test
    fun hiddenPreferenceAndWriteGatesControlChromeWithoutRemovingNavigationData() {
        val panes = listOf(pane("p2", "t2", AgentStatus.IDLE))
        val tabs = listOf(tab("t2", 1))
        val unreachable = listOf(ServerSummary("desk", "Desk", false, false, "ok", lastSeenAt = 1))
        val model = PaneTabStripModel.build(
            current,
            panes,
            tabs,
            visiblePreference = false,
            canWrite = true,
            mux = MuxConfigResponse(capabilities = mapOf("createTab" to true)),
            servers = unreachable,
        )

        assertFalse(model.visible)
        assertFalse(model.canCreateTab)
        assertEquals(HostWriteRefusal.Unreachable("Desk"), model.writeRefusal)

        val incapable = PaneTabStripModel.build(
            current, panes, tabs, true, true,
            MuxConfigResponse(capabilities = mapOf("createTab" to false)), null,
        )
        assertFalse(incapable.canCreateTab)
    }

    @Test
    fun structuralActionsHideWhenUnsupportedAndDisableBeforeNetworkWrites() {
        val unsupported = PaneStructuralGate.action(
            "renamePane", current, true,
            MuxConfigResponse(capabilities = mapOf("renamePane" to false)), null, false,
        )
        assertFalse(unsupported.visible)

        val unreachable = PaneStructuralGate.action(
            "closePane", current, true, null,
            listOf(ServerSummary("desk", "Desk", false, false, "ok", lastSeenAt = 1)), false,
        )
        assertTrue(unreachable.visible)
        assertFalse(unreachable.enabled)
        assertEquals(HostWriteRefusal.Unreachable("Desk"), unreachable.refusal)

        val allowed = PaneStructuralGate.action("setFocus", current, true, null, null, false)
        assertTrue(allowed.enabled)
        assertNull(allowed.refusal)
    }

    @Test
    fun currentTabCloseChoosesNextThenPreviousAndFallsBackWhenNothingIsNavigable() {
        val p1 = pane("p1", "t1", AgentStatus.IDLE)
        val p3 = pane("p3", "t3", AgentStatus.IDLE)
        val tabs = listOf(
            PaneTabItem(tab("t1", 1), p1, false, TriageBucket.RECENT),
            PaneTabItem(tab("t2", 2), null, true, TriageBucket.RECENT),
            PaneTabItem(tab("t3", 3), p3, false, TriageBucket.RECENT),
        )

        assertEquals("p3", TabCloseNavigation.fallback(tabs, "t2")?.paneId)
        assertEquals("p1", TabCloseNavigation.fallback(tabs.dropLast(1), "t2")?.paneId)
        assertNull(TabCloseNavigation.fallback(tabs.filter { it.tab.tabId == "t2" }, "t2"))
    }

    @Test
    fun historySearchCoversProseToolMetadataAndResultsAndUserTurnsWrap() {
        val entries = listOf(
            entry("u1", "user", TranscriptPart(kind = "text", text = "Find needle")),
            entry(
                "a1",
                "assistant",
                TranscriptPart(
                    kind = "tool",
                    name = "Search",
                    summary = "needle input",
                    result = TranscriptResult("needle output"),
                ),
            ),
            entry("u2", "user", TranscriptPart(kind = "text", text = "done")),
        )

        assertEquals(listOf("u1", "a1", "a1"), HistoryNavigation.matches(entries, "needle").map { it.entryUuid })
        assertEquals(listOf("u1", "u2"), HistoryNavigation.userTurns(entries))
        assertEquals(0, HistoryNavigation.step(2, 1, 1))
        assertEquals(1, HistoryNavigation.step(2, 0, -1))
    }

    private fun tab(id: String, number: Int) = TabSummary(id, "w1", number, id, false, 1, "desk")

    private fun pane(id: String, tab: String, status: AgentStatus) = PaneSummary(
        paneId = id,
        workspaceId = "w1",
        workspaceLabel = "project",
        workspaceNumber = 1,
        tabId = tab,
        agent = "codex",
        status = status,
        cwd = "/home/op/project",
        focused = false,
        tabLabel = tab,
        host = "desk",
        session = "work",
    )

    private fun entry(uuid: String, role: String, part: TranscriptPart) = TranscriptEntry(
        uuid = uuid,
        ts = "2026-01-01T00:00:00Z",
        role = role,
        parts = listOf(part),
    )
}
