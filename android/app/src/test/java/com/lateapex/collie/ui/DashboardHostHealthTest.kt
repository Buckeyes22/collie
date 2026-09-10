package com.lateapex.collie.ui

import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.ServerSummary
import com.lateapex.collie.network.SnapshotResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardHostHealthTest {
    @Test
    fun staleThresholdIsThreePollsWithAFifteenSecondCeiling() {
        assertEquals(4_500L, DashboardHostHealthModel.staleThresholdMs(1_500L))
        assertEquals(12_000L, DashboardHostHealthModel.staleThresholdMs(4_000L))
        assertEquals(15_000L, DashboardHostHealthModel.staleThresholdMs(6_000L))
        assertEquals(0L, DashboardHostHealthModel.staleThresholdMs(-1L))
    }

    @Test
    fun nominalDashboardCadenceTracksBlockedWorkingAndIdleSnapshots() {
        assertEquals(3_000L, DashboardHostHealthModel.pollMs(snapshot(AgentStatus.BLOCKED)))
        assertEquals(5_000L, DashboardHostHealthModel.pollMs(snapshot(AgentStatus.WORKING)))
        assertEquals(12_000L, DashboardHostHealthModel.pollMs(snapshot(AgentStatus.IDLE)))
    }

    @Test
    fun machineSwitcherDoesNotHideAnUnreachableMember() {
        val lead = peer(reachable = true, lastSeenAt = 100_000L).copy(id = "lead", isLead = true)
        val down = peer(reachable = false, lastSeenAt = 90_000L)

        assertTrue(DashboardHostHealthModel.shouldShowSwitcher(listOf(lead, down), selectedHost = null))
        assertFalse(DashboardHostHealthModel.shouldShowSwitcher(listOf(lead), selectedHost = null))
        assertTrue(DashboardHostHealthModel.shouldShowSwitcher(listOf(lead), selectedHost = "workshop"))
    }

    @Test
    fun freshnessAndWriteabilityRemainIndependent() {
        val recentFailure = DashboardHostHealthModel.from(
            peer(reachable = false, lastSeenAt = 98_000L),
            at = 100_000L,
            pollMs = 1_500L,
        )
        assertEquals(DashboardHostState.LIVE, recentFailure.state)
        assertFalse(recentFailure.writable)

        val oldButReachable = DashboardHostHealthModel.from(
            peer(reachable = true, lastSeenAt = 90_000L),
            at = 100_000L,
            pollMs = 1_500L,
        )
        assertEquals(DashboardHostState.STALE, oldButReachable.state)
        assertTrue(oldButReachable.writable)
    }

    @Test
    fun neverSeenAndIncompatibleMembersKeepTheirDistinctFacts() {
        val never = DashboardHostHealthModel.from(
            peer(reachable = false, lastSeenAt = 0L),
            at = 100_000L,
            pollMs = 1_500L,
        )
        assertEquals(DashboardHostState.UNKNOWN, never.state)
        assertFalse(never.incompatible)

        val detail = "pack protocol 2 (this collie speaks 1)"
        val incompatible = DashboardHostHealthModel.from(
            peer(
                reachable = true,
                lastSeenAt = 99_900L,
                protocol = "incompatible",
                protocolDetail = detail,
            ),
            at = 100_000L,
            pollMs = 1_500L,
        )
        assertEquals(DashboardHostState.STALE, incompatible.state)
        assertFalse(incompatible.writable)
        assertTrue(incompatible.incompatible)
        assertEquals(detail, incompatible.protocolDetail)
    }

    @Test
    fun leadAlwaysBelongsToPhoneConnectionHealth() {
        val lead = DashboardHostHealthModel.from(
            peer(reachable = false, lastSeenAt = 0L).copy(isLead = true),
            at = 100_000L,
            pollMs = 1_500L,
        )
        assertEquals(DashboardHostState.LIVE, lead.state)
        assertTrue(lead.writable)
    }

    private fun snapshot(status: AgentStatus) = SnapshotResponse(
        bridge = "connected",
        agents = listOf(
            PaneSummary(
                paneId = "w1:p1",
                workspaceId = "w1",
                workspaceLabel = "Collie",
                workspaceNumber = 1,
                tabId = "w1:t1",
                agent = "codex",
                status = status,
                cwd = "/src/collie",
                focused = false,
            ),
        ),
        shellPanes = emptyList(),
        workspaces = emptyList(),
        tabs = emptyList(),
        ts = 100_000L,
    )

    private fun peer(
        reachable: Boolean,
        lastSeenAt: Long,
        protocol: String = "ok",
        protocolDetail: String? = null,
    ) = ServerSummary(
        id = "workshop",
        name = "Workshop",
        isLead = false,
        reachable = reachable,
        protocol = protocol,
        protocolDetail = protocolDetail,
        lastSeenAt = lastSeenAt,
    )
}
