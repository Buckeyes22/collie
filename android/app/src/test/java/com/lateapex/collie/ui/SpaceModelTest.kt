package com.lateapex.collie.ui

import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.TabSummary
import com.lateapex.collie.network.WorkspaceSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceModelTest {
    @Test
    fun selectsOnlyTheRequestedHostSessionAndOrdersTabs() {
        val snapshot = SnapshotResponse(
            bridge = "connected",
            agents = listOf(
                pane("p2", "t2", host = "desk", session = "work"),
                pane("p1", "t1", host = "desk", session = "work"),
                pane("other-session", "t1", host = "desk", session = "other"),
                pane("other-host", "t1", host = "laptop", session = "work"),
            ),
            shellPanes = emptyList(),
            workspaces = listOf(workspace("desk"), workspace("laptop")),
            tabs = listOf(tab("t2", 2, "desk"), tab("t1", 1, "desk"), tab("t1", 1, "laptop")),
            ts = 1,
        )

        val content = SpaceModel.from(snapshot, "w1", Scope("desk", "work"))!!

        assertEquals(listOf("t1", "t2"), content.tabs.map { it.tab.tabId })
        assertEquals(listOf("p1", "p2"), content.tabs.flatMap { it.panes }.map { it.paneId })
        assertEquals(listOf("w1"), content.workspaces.map { it.workspaceId })
        assertEquals(listOf("p2", "p1"), content.agents.map { it.paneId })
    }

    @Test
    fun missingWorkspaceDoesNotInventAView() {
        val snapshot = SnapshotResponse(
            bridge = "connected",
            agents = emptyList(),
            shellPanes = emptyList(),
            workspaces = emptyList(),
            tabs = emptyList(),
            ts = 1,
        )

        assertNull(SpaceModel.from(snapshot, "missing", Scope()))
    }

    @Test
    fun allViewGroupsEveryTabWhileSelectedViewShowsOnlyItsPanes() {
        val first = SpaceTab(tab("t1", 1, "desk"), listOf(pane("p1", "t1", "desk", "work")))
        val second = SpaceTab(tab("t2", 2, "desk").copy(paneCount = 0), emptyList())
        val content = SpaceContent(
            workspace = workspace("desk"),
            tabs = listOf(first, second),
            workspaces = listOf(workspace("desk")),
            agents = first.panes,
        )

        val all = SpacePresentationModel.rows(content, null)
        assertEquals(
            listOf("Tab", "Pane", "Tab", "Empty"),
            all.map { it.javaClass.simpleName },
        )

        val selected = SpacePresentationModel.rows(content, "t1")
        assertEquals(listOf("Pane"), selected.map { it.javaClass.simpleName })
        assertEquals("p1", (selected.last() as SpaceListItem.Pane).pane.paneId)
    }

    @Test
    fun emptyTabsAndSpacesHaveDifferentCanonicalStates() {
        val withEmptyTab = SpaceContent(
            workspace("desk"),
            listOf(SpaceTab(tab("t1", 1, "desk").copy(paneCount = 0), emptyList())),
            listOf(workspace("desk")),
            emptyList(),
        )
        val emptySpace = withEmptyTab.copy(tabs = emptyList())

        assertEquals(
            com.lateapex.collie.R.string.space_view_empty_tab,
            (SpacePresentationModel.rows(withEmptyTab, "t1").last() as SpaceListItem.Empty).messageRes,
        )
        assertEquals(
            com.lateapex.collie.R.string.space_view_no_panes_space,
            (SpacePresentationModel.rows(emptySpace, null).last() as SpaceListItem.Empty).messageRes,
        )
    }

    @Test
    fun selectedTabFallsBackToAllWhenItDisappears() {
        val content = SpaceContent(
            workspace("desk"),
            listOf(SpaceTab(tab("t1", 1, "desk"), emptyList())),
            listOf(workspace("desk")),
            emptyList(),
        )

        assertEquals("t1", SpacePresentationModel.retainedSelection(content, "t1"))
        assertNull(SpacePresentationModel.retainedSelection(content, "closed"))
    }

    @Test
    fun stripSummariesExposeWorstStatusAndOnlyOneUnambiguousAgent() {
        val working = pane("p1", "t1", "desk", "work")
        val blocked = pane("p2", "t1", "desk", "work").copy(status = AgentStatus.BLOCKED)

        assertEquals(AgentStatus.BLOCKED, SpacePresentationModel.worstStatus(listOf(working, blocked)))
        assertEquals("codex", SpacePresentationModel.soleAgent(listOf(working, blocked)))
        assertNull(SpacePresentationModel.soleAgent(listOf(working, blocked.copy(agent = "claude"))))
        assertTrue(SpacePresentationModel.worstStatus(emptyList()) == null)
    }

    @Test
    fun paneTextInSpaceUsesCanonicalNameAndTheTabsMirrorPreview() {
        val named = pane("p1", "t1", "desk", "work").copy(
            paneLabel = "hand named",
            sessionName = "session name",
            terminalTitle = "live title",
            tabLabel = "build",
            cwd = "/home/chris/projects/a-very-long-parent-directory/collie/android",
        )

        assertEquals("hand named", spacePaneText(named).primary)
        assertEquals("build · live title", spacePaneText(named).secondary)
        assertEquals("session name", spacePaneText(named.copy(paneLabel = null)).primary)
        assertEquals(
            "live title",
            spacePaneText(named.copy(paneLabel = null, sessionName = null)).primary,
        )
    }

    @Test
    fun shortCwdCollapsesLinuxMacAndFedoraHomePrefixesByWholeSegment() {
        assertEquals("~/git/collie", shortCwd("/home/chris/git/collie"))
        assertEquals("~/git/collie", shortCwd("/Users/chris/git/collie"))
        assertEquals("~/git/collie", shortCwd("/var/home/chris/git/collie"))
        assertEquals("…/three/four", shortCwd("/one/two/three/four", max = 13))
    }

    private fun workspace(host: String) = WorkspaceSummary("w1", 1, "Collie", true, "t1", 2, 2, host = host)

    private fun tab(id: String, number: Int, host: String) =
        TabSummary(id, "w1", number, "Tab $number", number == 1, 1, host)

    @Test
    fun paneTextInSpaceUsesCanonicalNameAndSaysWhereThePaneSits() {
        // Restored from the pre-plan suite: the title precedence is unchanged; the meta line now
        // carries the tab and the live title instead of the shortened cwd (A.2).
        val named = pane("p1", "t1", "desk", "work").copy(
            tabLabel = "android",
            paneLabel = "hand named",
            sessionName = "session name",
            terminalTitle = "live title",
        )

        assertEquals("hand named", spacePaneText(named).primary)
        assertEquals("android · live title", spacePaneText(named).secondary)
        assertEquals("session name", spacePaneText(named.copy(paneLabel = null)).primary)
        assertEquals("live title", spacePaneText(named.copy(paneLabel = null, sessionName = null)).primary)
        assertEquals("android", spacePaneText(named.copy(paneLabel = null, sessionName = null)).secondary)
    }

    @Test
    fun paneTextInSpaceFallsBackToAgentOrShellAndIgnoresStaleTitles() {
        val agent = pane("p1", "t1", "desk", "work").copy(
            tabLabel = "android",
            terminalTitle = "old title",
            terminalTitleStale = true,
            cwd = "",
        )
        val shell = agent.copy(agent = "shell", kind = "shell")

        assertEquals(SpacePaneText("codex", "android"), spacePaneText(agent))
        assertEquals(SpacePaneText("shell", "android"), spacePaneText(shell))
    }

    private fun pane(id: String, tabId: String, host: String, session: String) = PaneSummary(
        paneId = id,
        workspaceId = "w1",
        workspaceLabel = "Collie",
        workspaceNumber = 1,
        tabId = tabId,
        agent = "codex",
        status = AgentStatus.WORKING,
        cwd = "/repo",
        focused = false,
        host = host,
        session = session,
    )
}
