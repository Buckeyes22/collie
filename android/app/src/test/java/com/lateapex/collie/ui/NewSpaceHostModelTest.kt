package com.lateapex.collie.ui

import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ServerSummary
import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.WorkspaceSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewSpaceHostModelTest {
    @Test
    fun soloSnapshotsKeepTheExistingAmbientFlowWithoutAHostPicker() {
        val solo = snapshot(servers = listOf(server("lead", lead = true)))

        assertTrue(NewSpaceHostModel.options(solo).isEmpty())
        assertEquals(
            Scope(session = "review"),
            NewSpaceHostModel.validatedScope(solo, Scope(session = "review"), null),
        )
        assertEquals(Scope(), NewSpaceHostModel.validatedScope(solo, Scope(viewAll = true), null))
    }

    @Test
    fun defaultUsesAmbientThenLeadAndFallsBackToTheFirstWritableMember() {
        val options = NewSpaceHostModel.options(snapshot(servers = listOf(
            server("lead", lead = true),
            server("workshop"),
            server("attic", reachable = false),
        )))

        assertEquals("workshop", NewSpaceHostModel.defaultHostId(options, "workshop"))
        assertEquals("lead", NewSpaceHostModel.defaultHostId(options, null))
        assertEquals("lead", NewSpaceHostModel.defaultHostId(options, "attic"))

        val leaderless = NewSpaceHostModel.options(snapshot(servers = listOf(
            server("attic", reachable = false),
            server("workshop"),
        )))
        assertEquals("workshop", NewSpaceHostModel.defaultHostId(leaderless, "attic"))
    }

    @Test
    fun everyMemberStaysListedWithItsPaletteAndWriteRefusalState() {
        val snapshot = snapshot(servers = listOf(
            server("lead", lead = true),
            server("workshop", reachable = false),
            server("attic", protocol = "incompatible", detail = "pack protocol 2"),
        ))
        val options = NewSpaceHostModel.options(snapshot)

        assertEquals(listOf("lead", "workshop", "attic"), options.map { it.server.id })
        assertTrue(options.single { it.server.id == "lead" }.writable)
        assertFalse(options.single { it.server.id == "workshop" }.writable)
        assertFalse(options.single { it.server.id == "attic" }.writable)
        assertTrue(options.single { it.server.id == "attic" }.health.incompatible)
        options.forEach { option ->
            assertEquals(HostPalette.slot(snapshot.servers.orEmpty(), option.server.id), option.slot)
        }
    }

    @Test
    fun validationNormalizesLeadButPreservesTheExplicitSessionTarget() {
        val snapshot = snapshot(servers = listOf(
            server("lead", lead = true),
            server("workshop"),
        ))
        val ambient = Scope(host = "workshop", session = "review")

        assertEquals(
            Scope(host = "workshop", session = "review"),
            NewSpaceHostModel.validatedScope(snapshot, ambient, "workshop"),
        )
        assertEquals(
            Scope(host = null, session = "review"),
            NewSpaceHostModel.validatedScope(snapshot, ambient, "lead"),
        )
    }

    @Test
    fun validationRefusesMissingDownAndIncompatibleTargets() {
        val snapshot = snapshot(servers = listOf(
            server("lead", lead = true),
            server("down", reachable = false),
            server("old", protocol = "incompatible", detail = "pack protocol 2"),
        ))

        assertNull(NewSpaceHostModel.validatedScope(snapshot, Scope(), "missing"))
        assertNull(NewSpaceHostModel.validatedScope(snapshot, Scope(), "down"))
        assertNull(NewSpaceHostModel.validatedScope(snapshot, Scope(), "old"))
    }

    @Test
    fun aCapturedTargetIsRecheckedAgainstTheNewestRosterBeforeWriting() {
        val live = snapshot(servers = listOf(server("lead", lead = true), server("workshop")))
        val down = snapshot(servers = listOf(server("lead", lead = true), server("workshop", reachable = false)))
        val target = NewSpaceHostModel.validatedScope(live, Scope(session = "review"), "workshop")!!

        assertEquals(Scope(host = "workshop", session = "review"), target)
        assertNull(NewSpaceHostModel.revalidateScope(down, target))
        assertEquals(
            Scope(session = "review"),
            NewSpaceHostModel.revalidateScope(live, Scope(session = "review")),
        )
    }

    @Test
    fun worktreeRepositoriesFollowTheSelectedMachine() {
        val servers = listOf(server("lead", lead = true), server("workshop"))
        val roots = listOf(
            workspace("lead-tagged", "lead"),
            workspace("lead-legacy", null),
            workspace("peer", "workshop"),
        )

        assertEquals(
            listOf("lead-tagged", "lead-legacy"),
            NewSpaceHostModel.repositoriesFor(roots, servers, "lead").map { it.workspaceId },
        )
        assertEquals(
            listOf("peer"),
            NewSpaceHostModel.repositoriesFor(roots, servers, "workshop").map { it.workspaceId },
        )
    }

    private fun snapshot(servers: List<ServerSummary>) = SnapshotResponse(
        bridge = "connected",
        agents = emptyList(),
        shellPanes = emptyList(),
        workspaces = emptyList(),
        tabs = emptyList(),
        servers = servers,
        ts = 100_000L,
    )

    private fun server(
        id: String,
        lead: Boolean = false,
        reachable: Boolean = true,
        protocol: String = "ok",
        detail: String? = null,
    ) = ServerSummary(
        id = id,
        name = id.replaceFirstChar(Char::uppercase),
        isLead = lead,
        reachable = reachable,
        protocol = protocol,
        protocolDetail = detail,
        lastSeenAt = 99_000L,
    )

    private fun workspace(id: String, host: String?) = WorkspaceSummary(
        workspaceId = id,
        number = 1,
        label = id,
        focused = false,
        activeTabId = "$id:t1",
        tabCount = 1,
        paneCount = 1,
        repoRoot = "/src/$id",
        isWorktree = false,
        host = host,
    )
}
