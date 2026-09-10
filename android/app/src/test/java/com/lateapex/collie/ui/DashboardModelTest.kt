package com.lateapex.collie.ui

import android.content.Context
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.R
import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.Launcher
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.SessionSummary
import com.lateapex.collie.network.ServerSummary
import com.lateapex.collie.network.UpdateInfo
import com.lateapex.collie.network.WorkspaceSummary
import com.lateapex.collie.domain.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class DashboardModelTest {
    @Test
    fun sectionsAlwaysFollowWebTriageAndUseTheCorrectRowTreatment() {
        val recent = pane("recent", AgentStatus.IDLE, active = 1_000, seen = 8_000)
        val working = pane("working", AgentStatus.WORKING, active = 7_000, seen = 6_000)
        val ready = pane("ready", AgentStatus.DONE, active = 9_000, seen = 2_000)
        val needs = pane("needs", AgentStatus.BLOCKED, active = 10_000, seen = 1_000)
        val seenDone = pane("done", AgentStatus.DONE, active = 3_000, seen = 5_000)
        val shell = pane("shell", AgentStatus.UNKNOWN, active = null, seen = 4_000, kind = "shell")

        val items = DashboardModel.items(
            snapshot(agents = listOf(recent, working, ready, needs, seenDone), shells = listOf(shell)),
            stale = false,
            build = "build",
        )
        val sections = items.filterIsInstance<DashboardItem.PaneSection>()

        assertEquals(
            listOf(
                TriageBucket.NEEDS_YOU,
                TriageBucket.READY_UNSEEN,
                TriageBucket.WORKING,
                TriageBucket.RECENT,
            ),
            sections.map(DashboardItem.PaneSection::bucket),
        )
        assertEquals(listOf("needs"), sections[0].panes.map { it.pane.paneId })
        assertEquals(listOf("ready"), sections[1].panes.map { it.pane.paneId })
        assertEquals(listOf("working"), sections[2].panes.map { it.pane.paneId })
        assertEquals(listOf("recent", "done"), sections[3].panes.map { it.pane.paneId })
        assertTrue(sections[0].bucket.attention)
        assertTrue(sections[1].bucket.attention)
        assertFalse(sections[2].bucket.attention)
        assertFalse(sections[3].bucket.attention)
        assertFalse(items.contains(DashboardItem.AllClear))
    }

    @Test
    fun shellPanesStayInSpacesButNeverEnterAgentTriage() {
        val shell = pane("shell", AgentStatus.UNKNOWN, seen = 4_000, kind = "shell")
        val items = DashboardModel.items(
            snapshot(shells = listOf(shell), workspaces = listOf(workspace("w1", "Shell space", 1))),
            stale = false,
            build = "build",
        )

        assertTrue(items.filterIsInstance<DashboardItem.PaneSection>().isEmpty())
        assertEquals(R.string.dashboard_no_agents, items.filterIsInstance<DashboardItem.EmptyPanes>().single().messageRes)
        assertEquals("w1", items.filterIsInstance<DashboardItem.Spaces>().single().rows.single().workspace.workspaceId)
    }

    @Test
    fun widenedTriageIncludesEverySessionButSpacesStayOnThePrimarySession() {
        val primary = pane("primary", AgentStatus.IDLE, seen = 2_000).copy(session = "default")
        val review = pane("review", AgentStatus.BLOCKED, seen = 9_000).copy(session = "review")
        val items = DashboardModel.items(
            snapshot(
                agents = listOf(primary, review),
                workspaces = listOf(workspace("w1", "Collie", 2)),
                sessions = listOf(
                    SessionSummary("default", true, true, 1, 0, 0),
                    SessionSummary("review", false, true, 1, 0, 1),
                ),
            ),
            scope = Scope(viewAll = true),
            stale = false,
            build = "build",
        )

        assertEquals(2, items.filterIsInstance<DashboardItem.PaneSection>().sumOf { it.panes.size })
        assertEquals(AgentStatus.IDLE, items.filterIsInstance<DashboardItem.Spaces>().single().rows.single().status)
    }

    @Test
    fun allClearAppearsOnlyWhenNoPaneNeedsAttention() {
        val clear = DashboardModel.items(
            snapshot(agents = listOf(pane("working", AgentStatus.WORKING))),
            stale = false,
            build = "build",
        )
        val blocked = DashboardModel.items(
            snapshot(agents = listOf(pane("blocked", AgentStatus.BLOCKED))),
            stale = false,
            build = "build",
        )

        assertTrue(clear.contains(DashboardItem.AllClear))
        assertFalse(blocked.contains(DashboardItem.AllClear))
    }

    @Test
    fun rowsSortByActivityExceptRecentWhichSortsByLastSeen() {
        val items = DashboardModel.items(
            snapshot(
                agents = listOf(
                    pane("work-old", AgentStatus.WORKING, active = 1_000),
                    pane("recent-new", AgentStatus.IDLE, seen = 8_000),
                    pane("work-new", AgentStatus.WORKING, active = 9_000),
                    pane("recent-old", AgentStatus.IDLE, seen = 2_000),
                ),
            ),
            stale = false,
            build = "build",
        )
        val sections = items.filterIsInstance<DashboardItem.PaneSection>().associateBy { it.bucket }

        assertEquals(
            listOf("work-new", "work-old"),
            sections.getValue(TriageBucket.WORKING).panes.map { it.pane.paneId },
        )
        assertEquals(
            listOf("recent-new", "recent-old"),
            sections.getValue(TriageBucket.RECENT).panes.map { it.pane.paneId },
        )
    }

    @Test
    fun recentCanCollapseAndReverseWithoutChangingOtherBucketOrder() {
        val items = DashboardModel.items(
            snapshot(
                agents = listOf(
                    pane("working-new", AgentStatus.WORKING, active = 9_000),
                    pane("working-old", AgentStatus.WORKING, active = 1_000),
                    pane("recent-new", AgentStatus.IDLE, seen = 8_000),
                    pane("recent-old", AgentStatus.IDLE, seen = 2_000),
                ),
            ),
            stale = false,
            build = "build",
            recentOpen = false,
            recentNewest = false,
        )
        val sections = items.filterIsInstance<DashboardItem.PaneSection>().associateBy { it.bucket }

        assertEquals(
            listOf("working-new", "working-old"),
            sections.getValue(TriageBucket.WORKING).panes.map { it.pane.paneId },
        )
        assertEquals(
            listOf("recent-old", "recent-new"),
            sections.getValue(TriageBucket.RECENT).panes.map { it.pane.paneId },
        )
        assertFalse(sections.getValue(TriageBucket.RECENT).open)
        assertFalse(sections.getValue(TriageBucket.RECENT).newest)
    }

    @Test
    fun paneNamingDemotesAStaleTerminalTitleBelowAnInformativeCwd() {
        val pane = pane("p", AgentStatus.IDLE).copy(
            workspaceLabel = "collie",
            tabLabel = "android",
            terminalTitle = "old command",
            terminalTitleStale = true,
            host = "attic",
            session = "review",
        )

        assertEquals("/repo/w1", DashboardModel.paneTitle(pane))
        assertEquals("collie · android", DashboardModel.paneMetadata(pane))
    }

    @Test
    fun paneNamingUsesWebLabelSessionLiveTitleAndLocationPrecedence() {
        val base = pane("p", AgentStatus.IDLE).copy(
            workspaceLabel = "Collie",
            tabLabel = "android",
            cwd = "/home/chris/git/collie",
            terminalTitle = "live title",
            sessionName = "session name",
            paneLabel = "pane label",
        )

        assertEquals("pane label", DashboardModel.paneTitle(base))
        assertEquals("Collie · android", DashboardModel.paneMetadata(base))
        assertEquals("session name", DashboardModel.paneTitle(base.copy(paneLabel = null)))
        assertEquals(
            "live title",
            DashboardModel.paneTitle(base.copy(paneLabel = null, sessionName = null)),
        )
        assertEquals(
            "android",
            DashboardModel.paneTitle(
                base.copy(paneLabel = null, sessionName = null, terminalTitle = null),
            ),
        )
    }

    @Test
    fun multiHostRowsNameTheServerAndOnlyNonPrimarySessions() {
        val lead = server("lead", reachable = true).copy(name = "Bluefin")
        val workshop = server("workshop", reachable = true).copy(name = "Workshop")
        val items = DashboardModel.items(
            snapshot(
                agents = listOf(
                    pane("primary", AgentStatus.IDLE).copy(host = "workshop", session = "default"),
                    pane("review", AgentStatus.IDLE).copy(host = "workshop", session = "review"),
                ),
                sessions = listOf(
                    SessionSummary("default", true, true, 1, 0, 0, host = "workshop"),
                    SessionSummary("review", false, true, 1, 0, 0, host = "workshop"),
                ),
                servers = listOf(lead, workshop),
            ),
            stale = false,
            build = "build",
        ).filterIsInstance<DashboardItem.PaneSection>().flatMap { it.panes }.associateBy { it.pane.paneId }

        val host = items.getValue("review").host!!
        assertEquals("Workshop", host.name)
        assertEquals(HostPalette.slot(listOf(lead, workshop), "workshop"), host.slot)
        assertEquals(DashboardPaneHostState.AVAILABLE, host.state)
        assertNull(items.getValue("primary").session)
        assertEquals("review", items.getValue("review").session)
    }

    @Test
    fun paneHostStateUsesWriteHealthAndLetsIncompatibilityWin() {
        val servers = listOf(
            server("lead", reachable = true),
            server("down", reachable = false).copy(name = "Workshop", lastSeenAt = 9_900),
            server("old", reachable = true).copy(
                name = "Attic",
                protocol = "incompatible",
                protocolDetail = "pack protocol 2",
            ),
        )
        val rows = DashboardModel.items(
            snapshot(
                agents = listOf(
                    pane("down", AgentStatus.BLOCKED).copy(host = "down"),
                    pane("old", AgentStatus.BLOCKED).copy(host = "old"),
                ),
                servers = servers,
            ),
            stale = false,
            build = "build",
        ).filterIsInstance<DashboardItem.PaneSection>().flatMap { it.panes }.associateBy { it.pane.paneId }

        assertEquals(DashboardPaneHost("Workshop", HostPalette.slot(servers, "down"), DashboardPaneHostState.UNREACHABLE), rows.getValue("down").host)
        assertEquals(DashboardPaneHost("Attic", HostPalette.slot(servers, "old"), DashboardPaneHostState.INCOMPATIBLE), rows.getValue("old").host)
    }

    @Test
    fun soloRowsDoNotGainHostChrome() {
        val items = DashboardModel.items(
            snapshot(
                agents = listOf(pane("p", AgentStatus.IDLE).copy(host = "lead")),
                servers = listOf(server("lead", reachable = true)),
            ),
            stale = false,
            build = "build",
        ).filterIsInstance<DashboardItem.PaneSection>().single().panes.single()

        assertNull(items.host)
    }

    @Test
    fun opencodeTitleDropsItsRedundantOcPrefixOnlyInPresentation() {
        listOf("OC | Codebase review", "oc|Codebase review", "  OC   |   Codebase review").forEach {
            title ->
            val openCode = pane("p", AgentStatus.IDLE, agent = "opencode").copy(
                terminalTitle = title,
            )
            assertEquals("Codebase review", DashboardModel.paneTitle(openCode))
            assertEquals("w1", DashboardModel.paneMetadata(openCode))
            assertEquals(title, openCode.terminalTitle)
        }

        val codex = pane("p", AgentStatus.IDLE).copy(terminalTitle = "OC | Keep this")
        assertEquals("OC | Keep this", DashboardModel.paneTitle(codex))
        val emptyPrefix = pane("p", AgentStatus.IDLE, agent = "opencode").copy(
            terminalTitle = "OC |",
            tabLabel = "fallback-tab",
        )
        assertEquals("fallback-tab", DashboardModel.paneTitle(emptyPrefix))
    }

    @Test
    fun spacesComeFromSnapshotInRecencyOrderWithTheirWorstStatus() {
        val workspaces = listOf(
            workspace("quiet", "Quiet", paneCount = 1),
            workspace("urgent", "Urgent", paneCount = 2),
        )
        val items = DashboardModel.items(
            snapshot(
                agents = listOf(
                    pane("quiet-pane", AgentStatus.IDLE, seen = 2_000, workspaceId = "quiet"),
                    pane("urgent-work", AgentStatus.WORKING, seen = 8_000, workspaceId = "urgent"),
                    pane("urgent-blocked", AgentStatus.BLOCKED, seen = 7_000, workspaceId = "urgent"),
                ),
                workspaces = workspaces,
            ),
            stale = false,
            build = "build",
        )
        val section = items.filterIsInstance<DashboardItem.Spaces>().single()
        val spaces = section.rows

        assertEquals(listOf("urgent", "quiet"), spaces.map { it.workspace.workspaceId })
        assertEquals(AgentStatus.BLOCKED, spaces[0].status)
        assertEquals(AgentStatus.IDLE, spaces[1].status)
        assertEquals(1, section.blockedCount)
    }

    @Test
    fun unsupportedSpaceCreationCarriesTheMuxNoteWithoutInventingCopy() {
        val note = "This multiplexer cannot create spaces from Collie."
        val spaces = DashboardModel.items(
            snapshot(workspaces = listOf(workspace("w1", "Collie", 1))),
            stale = false,
            build = "build",
            canCreateSpace = false,
            createSpaceNote = note,
        ).filterIsInstance<DashboardItem.Spaces>().single()

        assertFalse(spaces.canCreate)
        assertEquals(note, spaces.capabilityNote)
        assertEquals(
            "",
            DashboardModel.items(
                snapshot(workspaces = listOf(workspace("w1", "Collie", 1))),
                stale = false,
                build = "build",
                canCreateSpace = true,
                createSpaceNote = note,
            ).filterIsInstance<DashboardItem.Spaces>().single().capabilityNote,
        )
    }

    @Test
    fun worktreeSpacesNestUnderTheirRepositoryAtTheFreshestGroupPosition() {
        val parent = workspace("repo", "Collie", 1).copy(repoRoot = "/src/collie", isWorktree = false)
        val child = workspace("worktree", "Feature", 1).copy(repoRoot = "/src/collie", isWorktree = true)
        val other = workspace("other", "Other", 1)
        val items = DashboardModel.items(
            snapshot(
                agents = listOf(
                    pane("parent", AgentStatus.IDLE, seen = 1_000, workspaceId = "repo"),
                    pane("child", AgentStatus.IDLE, seen = 9_000, workspaceId = "worktree"),
                    pane("other", AgentStatus.IDLE, seen = 5_000, workspaceId = "other"),
                ),
                workspaces = listOf(parent, child, other),
            ),
            stale = false,
            build = "build",
        )

        val rows = items.filterIsInstance<DashboardItem.Spaces>().single().rows
        assertEquals(listOf("repo", "worktree", "other"), rows.map { it.workspace.workspaceId })
        assertEquals(listOf(0, 1, 0), rows.map(SpaceRow::depth))
    }

    @Test
    fun launchersPrecedeSpacesAndSpaceFilteringKeepsTheUnfilteredCount() {
        val items = DashboardModel.items(
            snapshot(
                workspaces = listOf(
                    workspace("native", "Native client", 3),
                    workspace("web", "Web app", 2).copy(repoRoot = "/src/collie-web"),
                ),
            ),
            stale = false,
            build = "build",
            spacesOpen = false,
            spaceQuery = "web",
            canCreateSpace = false,
            launchers = listOf(Launcher(command = "codex", label = "Codex")),
            writesEnabled = false,
            launchOpen = false,
            launching = setOf("codex"),
        )

        assertTrue(items.indexOfFirst { it is DashboardItem.Launchers } < items.indexOfFirst { it is DashboardItem.Spaces })
        val launchers = items.filterIsInstance<DashboardItem.Launchers>().single()
        val spaces = items.filterIsInstance<DashboardItem.Spaces>().single()
        assertFalse(launchers.enabled)
        assertFalse(launchers.open)
        assertEquals(setOf("codex"), launchers.launching)
        assertEquals(listOf("web"), spaces.rows.map { it.workspace.workspaceId })
        assertEquals(2, spaces.total)
        assertFalse(spaces.open)
        assertFalse(spaces.canCreate)
    }

    @Test
    fun compactAgeUsesAStableSnapshotClock() {
        assertNull(DashboardModel.compactAge(null, 100_000))
        assertEquals(CompactAge(R.string.age_now), DashboardModel.compactAge(70_000, 100_000))
        assertEquals(CompactAge(R.string.age_minutes, 5), DashboardModel.compactAge(0, 300_000))
        assertEquals(CompactAge(R.string.age_hours, 2), DashboardModel.compactAge(0, 7_200_000))
        assertEquals(CompactAge(R.string.age_days, 2), DashboardModel.compactAge(0, 172_800_000))
    }

    @Test
    fun adapterBindsTheSectionHeaderAndItsPaneRowsFromThePureModel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parent = FrameLayout(ContextThemeWrapper(context, R.style.Theme_Collie))
        val adapter = DashboardAdapter(onPane = {}, onSpace = {})
        val item = DashboardItem.PaneSection(
            bucket = TriageBucket.NEEDS_YOU,
            panes = listOf(
                PaneRow(
                    pane = pane("blocked", AgentStatus.BLOCKED).copy(hint = "Approve the command"),
                    title = "permission prompt",
                    metadata = "codex · collie",
                    age = null,
                ),
            ),
        )
        adapter.submitList(listOf(item))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
        adapter.onBindViewHolder(holder, 0)

        assertEquals("NEEDS YOU", holder.itemView.findViewById<TextView>(R.id.section_title).text)
        assertEquals("permission prompt", holder.itemView.findViewById<TextView>(R.id.pane_title).text)
        assertEquals("Approve the command", holder.itemView.findViewById<TextView>(R.id.pane_hint).text)
    }

    @Test
    fun adapterDoesNotRepeatCwdWhenThereIsNoRealHint() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parent = FrameLayout(ContextThemeWrapper(context, R.style.Theme_Collie))
        val adapter = DashboardAdapter(onPane = {}, onSpace = {})
        adapter.submitList(
            listOf(
                DashboardItem.PaneSection(
                    bucket = TriageBucket.WORKING,
                    panes = listOf(
                        PaneRow(
                            pane = pane("working", AgentStatus.WORKING).copy(cwd = "/repo/working"),
                            title = "working",
                            metadata = "codex · app-build",
                            age = CompactAge(R.string.age_minutes, 1),
                        ),
                    ),
                ),
            ),
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
        adapter.onBindViewHolder(holder, 0)

        val hint = holder.itemView.findViewById<TextView>(R.id.pane_hint)
        assertEquals("", hint.text)
        assertFalse(hint.isShown)
    }

    @Test
    fun adapterGivesHostIdentityTintToHealthyRowsAndBlockedInkToRefusals() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parent = FrameLayout(ContextThemeWrapper(context, R.style.Theme_Collie))
        fun bind(host: DashboardPaneHost): TextView {
            val adapter = DashboardAdapter(onPane = {}, onSpace = {})
            adapter.submitList(listOf(DashboardItem.PaneSection(
                bucket = TriageBucket.WORKING,
                panes = listOf(PaneRow(
                    pane = pane(host.name, AgentStatus.WORKING),
                    title = host.name,
                    metadata = "Collie",
                    age = null,
                    host = host,
                )),
            )))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            return adapter.onCreateViewHolder(parent, adapter.getItemViewType(0)).also {
                adapter.onBindViewHolder(it, 0)
            }.itemView.findViewById(R.id.pane_host)
        }

        val healthy = bind(DashboardPaneHost("Workshop", 4, DashboardPaneHostState.AVAILABLE))
        assertEquals("Workshop", healthy.text)
        assertEquals(ContextThemeWrapper(context, R.style.Theme_Collie).getColor(R.color.collie_host_4), healthy.compoundDrawableTintList?.defaultColor)

        val down = bind(DashboardPaneHost("Attic", 7, DashboardPaneHostState.UNREACHABLE))
        assertEquals("Attic · unreachable", down.text)
        assertEquals(ContextThemeWrapper(context, R.style.Theme_Collie).getColor(R.color.collie_blocked), down.compoundDrawableTintList?.defaultColor)
        assertEquals(ContextThemeWrapper(context, R.style.Theme_Collie).getColor(R.color.collie_blocked), down.currentTextColor)
    }

    @Test
    fun spaceRowClickOpensTheSelectedWorkspace() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parent = FrameLayout(ContextThemeWrapper(context, R.style.Theme_Collie))
        var opened: WorkspaceSummary? = null
        val adapter = DashboardAdapter(onPane = {}, onSpace = { opened = it })
        val workspace = workspace("w7", "Native", 3)
        adapter.submitList(listOf(DashboardItem.Spaces(listOf(SpaceRow(workspace, AgentStatus.IDLE, null)))))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
        adapter.onBindViewHolder(holder, 0)
        val title = holder.itemView.findViewById<TextView>(R.id.space_title)
        (title.parent as View).performClick()

        assertEquals(workspace, opened)
    }

    @Test
    fun spacesHeaderKeepsBlockedCountFoldedAndShowsCapabilityNoteOnlyWhenOpen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parent = FrameLayout(ContextThemeWrapper(context, R.style.Theme_Collie))
        val workspace = workspace("w7", "Native", 3)
        fun bind(open: Boolean): View {
            val adapter = DashboardAdapter(onPane = {}, onSpace = {})
            adapter.submitList(
                listOf(
                    DashboardItem.Spaces(
                        rows = listOf(SpaceRow(workspace, AgentStatus.BLOCKED, null)),
                        blockedCount = 1,
                        open = open,
                        canCreate = false,
                        capabilityNote = "This multiplexer cannot create spaces from Collie.",
                    ),
                ),
            )
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            return adapter.onCreateViewHolder(parent, adapter.getItemViewType(0)).also {
                adapter.onBindViewHolder(it, 0)
            }.itemView
        }

        val folded = bind(open = false)
        val foldedCount = folded.findViewById<TextView>(R.id.spaces_blocked_count)
        assertEquals("1", foldedCount.text)
        assertEquals("1 space needs you", foldedCount.contentDescription)
        assertEquals(View.VISIBLE, foldedCount.visibility)
        assertEquals(View.GONE, folded.findViewById<TextView>(R.id.spaces_capability_note).visibility)

        val open = bind(open = true)
        val note = open.findViewById<TextView>(R.id.spaces_capability_note)
        assertEquals("This multiplexer cannot create spaces from Collie.", note.text)
        assertEquals(View.VISIBLE, note.visibility)
    }

    @Test
    fun launcherButtonsWrapTwoPerRowAndKeepCommandOnItsOwnMonoLine() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parent = FrameLayout(ContextThemeWrapper(context, R.style.Theme_Collie))
        val adapter = DashboardAdapter(onPane = {}, onSpace = {})
        adapter.submitList(
            listOf(
                DashboardItem.Launchers(
                    rows = listOf(
                        Launcher(command = "codex", label = "Codex"),
                        Launcher(command = "claude", label = "Claude"),
                        Launcher(command = "opencode", label = "OpenCode"),
                    ),
                ),
            ),
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
        adapter.onBindViewHolder(holder, 0)
        val rows = holder.itemView.findViewById<LinearLayout>(R.id.launcher_rows)

        assertEquals(2, rows.childCount)
        assertEquals(2, (rows.getChildAt(0) as LinearLayout).childCount)
        assertEquals("Codex\ncodex", ((rows.getChildAt(0) as LinearLayout).getChildAt(0) as TextView).text.toString())
    }

    @Test
    fun footerProjectsPackCountsAndTheSharedUpdateNoticeFromSnapshotOnly() {
        val footer = DashboardModel.items(
            snapshot(
                servers = listOf(
                    server("lead", reachable = true),
                    server("peer-one", reachable = true),
                    server("peer-two", reachable = false),
                ),
                update = update(bridgeStale = true, releaseAvailable = true),
            ),
            stale = false,
            build = "native build",
        ).filterIsInstance<DashboardItem.Footer>().single()

        assertEquals(PackFooterSummary(machines = 3, reachable = 2), footer.pack)
        assertEquals(FooterUpdateNotice(FooterUpdateNoticeKind.RESTART), footer.update)
        assertEquals("native build", footer.build)
        assertNull(packFooterSummary(listOf(server("solo", reachable = true))))
    }

    @Test
    fun footerUpdateNoticeUsesSpacePrecedenceAndRequiresAVersionForRelease() {
        assertEquals(
            FooterUpdateNotice(FooterUpdateNoticeKind.RESTART),
            FooterUpdateNoticeModel.from(update(bridgeStale = true, releaseAvailable = true)),
        )
        assertEquals(
            FooterUpdateNotice(FooterUpdateNoticeKind.RELEASE, "1.6.0"),
            FooterUpdateNoticeModel.from(update(releaseAvailable = true)),
        )
        assertEquals(
            FooterUpdateNotice(FooterUpdateNoticeKind.MAJOR, "2.0.0"),
            FooterUpdateNoticeModel.from(
                update(releaseAvailable = false, latest = null, majorAvailable = "2.0.0"),
            ),
        )
        assertNull(FooterUpdateNoticeModel.from(update(releaseAvailable = true, latest = null)))
    }

    @Test
    fun footerRendersPackThenUpdateThenBuildAndRoutesBothLinks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parent = FrameLayout(ContextThemeWrapper(context, R.style.Theme_Collie))
        var packOpened = false
        var updatesOpened = false
        val adapter = DashboardAdapter(
            onPane = {},
            onSpace = {},
            onPack = { packOpened = true },
            onUpdates = { updatesOpened = true },
        )
        adapter.submitList(
            listOf(
                DashboardItem.Footer(
                    build = "Android app build test",
                    pack = PackFooterSummary(3, 2),
                    update = FooterUpdateNotice(FooterUpdateNoticeKind.RELEASE, "1.6.0"),
                ),
            ),
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
        adapter.onBindViewHolder(holder, 0)
        val root = holder.itemView as LinearLayout
        val pack = root.findViewById<View>(R.id.dashboard_pack_link)
        val notice = root.findViewById<TextView>(R.id.dashboard_footer_update_notice)
        val build = root.findViewById<TextView>(R.id.build_text)

        assertEquals("Pack · 3 machines · 2 reachable", root.findViewById<TextView>(R.id.dashboard_pack_text).text)
        assertEquals(context.getString(R.string.settings_update_available_short, "1.6.0"), notice.text)
        assertTrue(root.indexOfChild(pack) < root.indexOfChild(notice))
        assertTrue(root.indexOfChild(notice) < root.indexOfChild(build))
        pack.performClick()
        notice.performClick()
        assertTrue(packOpened)
        assertTrue(updatesOpened)
    }

    private fun snapshot(
        agents: List<PaneSummary> = emptyList(),
        shells: List<PaneSummary> = emptyList(),
        workspaces: List<WorkspaceSummary> = emptyList(),
        sessions: List<SessionSummary> = emptyList(),
        servers: List<ServerSummary>? = null,
        update: UpdateInfo? = null,
    ) = SnapshotResponse(
        bridge = "connected",
        agents = agents,
        shellPanes = shells,
        workspaces = workspaces,
        tabs = emptyList(),
        sessions = sessions,
        servers = servers,
        update = update,
        ts = 10_000,
    )

    private fun server(id: String, reachable: Boolean) = ServerSummary(
        id = id,
        name = id,
        isLead = id == "lead",
        reachable = reachable,
        protocol = "ok",
        lastSeenAt = 1,
    )

    private fun update(
        bridgeStale: Boolean = false,
        releaseAvailable: Boolean = false,
        latest: String? = "1.6.0",
        majorAvailable: String? = null,
    ) = UpdateInfo(
        current = "1.5.0",
        latest = latest,
        latestUrl = null,
        releaseAvailable = releaseAvailable,
        majorAvailable = majorAvailable,
        majorUrl = null,
        bridgeStale = bridgeStale,
        checkedAt = 1,
    )

    private fun pane(
        id: String,
        status: AgentStatus,
        active: Long? = null,
        seen: Long? = null,
        kind: String? = null,
        workspaceId: String = "w1",
        agent: String = "codex",
    ) = PaneSummary(
        paneId = id,
        workspaceId = workspaceId,
        workspaceLabel = workspaceId,
        workspaceNumber = 1,
        tabId = "$workspaceId:t1",
        agent = if (kind == "shell") "shell" else agent,
        status = status,
        cwd = "/repo/$workspaceId",
        focused = false,
        kind = kind,
        lastActiveAt = active,
        lastSeenAt = seen,
    )

    private fun workspace(id: String, label: String, paneCount: Int) = WorkspaceSummary(
        workspaceId = id,
        number = 1,
        label = label,
        focused = false,
        activeTabId = "$id:t1",
        tabCount = 1,
        paneCount = paneCount,
    )
}
