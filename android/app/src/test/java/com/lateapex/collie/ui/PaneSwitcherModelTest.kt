package com.lateapex.collie.ui

import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.Launcher
import com.lateapex.collie.network.MuxConfigResponse
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.ServerSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneSwitcherModelTest {
    @Test fun changingPinAdmissionIsEvaluatedOnceAndCannotDuplicateSwitcherRows() {
        var calls = 0
        val result = PaneSwitcherModel.sections(
            panes = listOf(pane("owned", AgentStatus.IDLE)), current = PaneAddress(Scope(), "owned"),
            recentOpen = true, recentNewest = true, shellsPreference = null, launchers = emptyList(),
            launcherHome = "", launchPreference = null, writesAuthorized = true, mux = null,
            servers = null, launching = emptySet(), pinned = { ++calls == 1 },
        )
        assertEquals(1, calls)
        assertEquals(1, result.filterIsInstance<PaneSwitcherSection.Pinned>().flatMap { it.rows }.size)
        assertTrue(result.filterIsInstance<PaneSwitcherSection.Agents>().isEmpty())
        assertTrue(result.filterIsInstance<PaneSwitcherSection.Shells>().isEmpty())
    }

    @Test fun pinsLeadTheSwitcherWithoutDuplicatingRowsOrFollowingStatusTimes() {
        val first = pane("first", AgentStatus.WORKING).copy(workspaceNumber = 1)
        val second = pane("second", AgentStatus.BLOCKED).copy(workspaceNumber = 2, agent = "shell", kind = "shell")
        val other = pane("other", AgentStatus.IDLE)
        fun sections(rows: List<PaneSummary>) = PaneSwitcherModel.sections(
            panes = rows, current = PaneAddress(Scope(host = "desk", session = "work"), "first"), recentOpen = true, recentNewest = true,
            shellsPreference = null, launchers = emptyList(), launcherHome = "", launchPreference = null,
            writesAuthorized = true, mux = null, servers = null, launching = emptySet(),
            pinned = { it.paneId in setOf("first", "second") },
        )
        val original = sections(listOf(second, other, first))
        val pinned = original.first() as PaneSwitcherSection.Pinned
        assertEquals(listOf("first", "second"), pinned.rows.map { it.pane.paneId })
        assertTrue(pinned.rows.first().active)
        assertEquals(listOf("other"), original.filterIsInstance<PaneSwitcherSection.Agents>().flatMap { it.rows }.map { it.pane.paneId })
        assertTrue(original.filterIsInstance<PaneSwitcherSection.Shells>().isEmpty())
        val changed = sections(listOf(first.copy(status = AgentStatus.BLOCKED, lastActiveAt = 999),
            second.copy(status = AgentStatus.IDLE, lastSeenAt = 999), other))
        assertEquals(pinned.rows.map { it.pane.paneId }, (changed.first() as PaneSwitcherSection.Pinned).rows.map { it.pane.paneId })
    }

    @Test
    fun switcherMarksTheCurrentPane() {
        val address = PaneAddress(Scope(host = "desk", session = "work"), "b")
        val rows = PaneSwitcherModel.sections(
            panes = listOf(pane("a", AgentStatus.IDLE, seen = 8), pane("b", AgentStatus.IDLE, seen = 2)),
            current = address,
            recentOpen = true,
            recentNewest = true,
            shellsPreference = null,
            launchers = emptyList(),
            launcherHome = "/home/operator",
            launchPreference = null,
            writesAuthorized = true,
            mux = null,
            servers = null,
            launching = emptySet(),
        ).filterIsInstance<PaneSwitcherSection.Agents>().flatMap { it.rows }

        assertTrue(rows.single { it.pane.paneId == "b" }.active)
        assertFalse(rows.single { it.pane.paneId == "a" }.active)
    }

    @Test
    fun opencodeSwitcherSecondaryDropsProcessOwnedTitlePrefix() {
        val pane = pane("p1", AgentStatus.IDLE).copy(
            agent = "opencode",
            sessionName = "OC | Review this tree",
        )

        val row = PaneSwitcherModel.sections(
            panes = listOf(pane),
            current = PaneAddress(Scope(), "other"),
            recentOpen = true,
            recentNewest = true,
            shellsPreference = null,
            launchers = emptyList(),
            launcherHome = "/home/operator",
            launchPreference = null,
            writesAuthorized = true,
            mux = null,
            servers = null,
            launching = emptySet(),
        ).filterIsInstance<PaneSwitcherSection.Agents>().single().rows.single()

        assertEquals("Review this tree", row.secondary)
    }

    private val current = PaneAddress(Scope(host = "desk", session = "work"), "current")

    @Test
    fun agentsUseDashboardTriageOrderAndRecentAloneFolds() {
        val sections = sections(
            panes = listOf(
                pane("recent", AgentStatus.IDLE, seen = 8),
                pane("working", AgentStatus.WORKING, active = 7),
                pane("ready", AgentStatus.DONE, active = 9, seen = 2),
                pane("needs", AgentStatus.BLOCKED, active = 10),
                pane("current", AgentStatus.IDLE, seen = 3),
            ),
            recentOpen = false,
        ).filterIsInstance<PaneSwitcherSection.Agents>()

        assertEquals(TriageBucket.entries, sections.map { it.bucket })
        assertEquals(listOf("needs"), sections[0].rows.map { it.pane.paneId })
        assertEquals(listOf("ready"), sections[1].rows.map { it.pane.paneId })
        assertEquals(listOf("working"), sections[2].rows.map { it.pane.paneId })
        assertEquals(listOf("recent", "current"), sections[3].rows.map { it.pane.paneId })
        assertTrue(sections.take(3).all { it.open })
        assertFalse(sections.last().open)
        assertTrue(sections.last().rows.last().active)
    }

    @Test
    fun shellsAreSeparateAndCountSensitiveUntilTheOperatorChooses() {
        val many = (1..9).map { pane("shell-$it", AgentStatus.UNKNOWN, kind = "shell") }
        val collapsed = sections(many).single() as PaneSwitcherSection.Shells
        val forcedOpen = sections(many, shellsPreference = true).single() as PaneSwitcherSection.Shells

        assertFalse(collapsed.open)
        assertTrue(forcedOpen.open)
        assertEquals(9, collapsed.rows.size)
    }

    @Test
    fun recentUsesTheSharedDashboardSortPreference() {
        val recent = listOf(
            pane("older", AgentStatus.IDLE, seen = 2),
            pane("newer", AgentStatus.IDLE, seen = 8),
        )

        val oldest = sections(recent, recentNewest = false)
            .single() as PaneSwitcherSection.Agents
        assertEquals(listOf("older", "newer"), oldest.rows.map { it.pane.paneId })
    }

    @Test
    fun launchRequiresAuthorizationAndCreateTabCapabilityButKeepsHostRefusalVisible() {
        val launchers = listOf(Launcher("codex", "Codex", "/home/op/src"))
        val supported = MuxConfigResponse(capabilities = mapOf("createTab" to true))
        val unsupported = MuxConfigResponse(capabilities = mapOf("createTab" to false))
        assertTrue(sections(emptyList(), launchers = launchers, mux = supported).single() is PaneSwitcherSection.Launch)
        assertTrue(sections(emptyList(), launchers = launchers, mux = unsupported).isEmpty())
        assertTrue(sections(emptyList(), launchers = launchers, writesAuthorized = false, mux = supported).isEmpty())

        val unreachable = listOf(ServerSummary("desk", "Desk", false, false, "ok", lastSeenAt = 1))
        val launch = sections(emptyList(), launchers = launchers, mux = supported, servers = unreachable)
            .single() as PaneSwitcherSection.Launch
        assertEquals(HostWriteRefusal.Unreachable("Desk"), launch.refusal)
    }

    @Test
    fun paneSecondaryAndLauncherCwdUseTheConfigHome() {
        val pane = pane("current", AgentStatus.IDLE).copy(cwd = "/home/op/worktrees/fix")
        val sections = sections(
            listOf(pane),
            launchers = listOf(Launcher("htop", "Top", "/home/op/tools")),
            launcherHome = "/home/op",
        )
        val row = sections.filterIsInstance<PaneSwitcherSection.Agents>().single().rows.single()
        val launch = sections.filterIsInstance<PaneSwitcherSection.Launch>().single()

        assertEquals("~/worktrees/fix", row.secondary)
        assertEquals("~/tools", PaneSwitcherModel.shortenHome(launch.rows.single().cwd.orEmpty(), launch.home))
    }

    @Test
    fun duplicateLaunchLockOwnsEachCommandUntilItsAttemptSettles() {
        val lock = LaunchDuplicateLock()

        assertTrue(lock.tryStart("codex"))
        assertFalse(lock.tryStart("codex"))
        assertTrue(lock.tryStart("htop"))
        assertEquals(setOf("codex", "htop"), lock.snapshot())
        lock.finish("codex")
        assertTrue(lock.tryStart("codex"))
    }

    private fun sections(
        panes: List<PaneSummary>,
        recentOpen: Boolean = true,
        recentNewest: Boolean = true,
        shellsPreference: Boolean? = null,
        launchers: List<Launcher> = emptyList(),
        launcherHome: String = "/home/op",
        launchPreference: Boolean? = null,
        writesAuthorized: Boolean = true,
        mux: MuxConfigResponse? = null,
        servers: List<ServerSummary>? = null,
    ) = PaneSwitcherModel.sections(
        panes,
        current,
        recentOpen,
        recentNewest,
        shellsPreference,
        launchers,
        launcherHome,
        launchPreference,
        writesAuthorized,
        mux,
        servers,
        emptySet(),
    )

    private fun pane(
        id: String,
        status: AgentStatus,
        active: Long? = null,
        seen: Long? = null,
        kind: String? = null,
    ) = PaneSummary(
        paneId = id,
        workspaceId = "w1",
        workspaceLabel = "project",
        workspaceNumber = 1,
        tabId = "t1",
        agent = if (kind == "shell") "shell" else "codex",
        status = status,
        cwd = "/home/op/project",
        focused = false,
        kind = kind,
        tabLabel = "build",
        lastActiveAt = active,
        lastSeenAt = seen,
        host = "desk",
        session = "work",
    )
}
