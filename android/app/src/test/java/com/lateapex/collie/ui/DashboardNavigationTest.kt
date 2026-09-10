package com.lateapex.collie.ui

import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ServerSummary
import com.lateapex.collie.network.SessionSummary
import com.lateapex.collie.network.SnapshotResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardNavigationTest {
    @Test
    fun scopeBackStackRestoresHostAndSessionInReverseNavigationOrder() {
        val history = ScopeBackStack()
        val lead = Scope()
        val peer = Scope(host = "peer")

        history.record(lead)
        history.record(peer)

        assertEquals(peer, history.pop())
        assertEquals(lead, history.pop())
        assertNull(history.pop())
    }

    @Test
    fun scopeBackStackCoalescesConsecutiveDuplicatesAndClearsOnDisconnect() {
        val history = ScopeBackStack()
        val scope = Scope(host = "peer", session = "work")

        history.record(scope)
        history.record(scope)
        assertEquals(scope, history.pop())
        assertNull(history.pop())

        history.record(Scope())
        history.clear()
        assertNull(history.pop())
    }

    @Test
    fun spaceNavigationUsesExplicitAmbientSessionButLeavesAllSessionsPrimary() {
        assertEquals("review", DashboardNavigationModel.spaceSession(Scope(host = "peer", session = "review")))
        assertNull(DashboardNavigationModel.spaceSession(Scope(host = "peer", viewAll = true)))
        assertNull(DashboardNavigationModel.spaceSession(Scope(viewAll = true)))
    }

    @Test
    fun durableScopeKeepsOnlyHostsAndReachableSessionsFromTheFreshRoster() {
        val snapshot = SnapshotResponse(
            bridge = "connected",
            agents = emptyList(),
            shellPanes = emptyList(),
            workspaces = emptyList(),
            tabs = emptyList(),
            sessions = listOf(
                SessionSummary("default", true, true, 0, 0, 0, host = "lead"),
                SessionSummary("review", false, true, 0, 0, 0, host = "peer"),
                SessionSummary("stopped", false, false, 0, 0, 0, host = "peer"),
            ),
            servers = listOf(
                ServerSummary("lead", "Lead", true, true, "ok", lastSeenAt = 1),
                ServerSummary("peer", "Peer", false, true, "ok", lastSeenAt = 1),
            ),
            ts = 1,
        )

        assertEquals(
            Scope(host = "peer", session = "review"),
            DashboardScopePersistence.validate(Scope(host = "peer", session = "review"), snapshot),
        )
        assertEquals(
            Scope(host = "peer"),
            DashboardScopePersistence.validate(Scope(host = "peer", session = "stopped"), snapshot),
        )
        assertEquals(
            Scope(),
            DashboardScopePersistence.validate(Scope(host = "retired", session = "review"), snapshot),
        )
        assertEquals(
            Scope(host = "peer", viewAll = true),
            DashboardScopePersistence.validate(Scope(host = "peer", viewAll = true), snapshot),
        )
    }

    @Test
    fun durableScopeUsesTheUnscopedRosterAsValidationOnlyAndRereadsTheResolvedScope() {
        val snapshot = SnapshotResponse(
            bridge = "connected",
            agents = emptyList(),
            shellPanes = emptyList(),
            workspaces = emptyList(),
            tabs = emptyList(),
            sessions = listOf(
                SessionSummary("default", true, true, 0, 0, 0, host = "lead"),
                SessionSummary("review", false, true, 0, 0, 0, host = "peer"),
            ),
            servers = listOf(
                ServerSummary("lead", "Lead", true, true, "ok", lastSeenAt = 1),
                ServerSummary("peer", "Peer", false, true, "ok", lastSeenAt = 1),
            ),
            ts = 1,
        )

        val valid = DashboardScopePersistence.resolve(
            Scope(host = "peer", session = "review"),
            snapshot,
        )
        assertEquals(Scope(host = "peer", session = "review"), valid.scope)
        assertEquals(valid.scope, valid.readScope)

        val missingSession = DashboardScopePersistence.resolve(
            Scope(host = "peer", session = "gone"),
            snapshot,
        )
        assertEquals(Scope(host = "peer"), missingSession.scope)
        assertEquals(Scope(host = "peer"), missingSession.readScope)

        val missingHost = DashboardScopePersistence.resolve(
            Scope(host = "retired", session = "review"),
            snapshot,
        )
        assertEquals(Scope(), missingHost.scope)
        assertNull(missingHost.readScope)
    }
}
